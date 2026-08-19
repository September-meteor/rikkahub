package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32

/** 内部扫描结果：文件状态 + 目录指纹 + 空目录列表 */
data class InternalScanResult(
    val files: Map<String, SyncFileState>,
    val directories: Map<String, DirFingerprint>,
    val emptyDirs: Set<String> = emptySet(),
)

/** 外部扫描结果：文件状态（hash 懒加载，未计算为 ""）+ 目录指纹 + 空目录列表 */
data class ExternalScanResult(
    val files: Map<String, SyncFileState>,
    val directories: Map<String, DirFingerprint>,
    val emptyDirs: Set<String> = emptySet(),
)

/**
 * DocumentFile 缓存：扫描外部目录时按路径（相对根目录）填充，
 * 对比（hash 解析）与执行（定位/写入）阶段直接取用，避免 SAF 逐层 findFile。
 * - [dirs]：目录路径 → 目录 DocumentFile（含根目录 ""）
 * - [files]：文件路径 → 文件 DocumentFile（仅在遍历到真实子项时填充）
 */
class DocumentCache {
    val dirs = linkedMapOf<String, DocumentFile>()
    val files = linkedMapOf<String, DocumentFile>()
}

/** 同步链路阶段标识，用于把异常翻译成用户可读文案 */
enum class SyncStage { SCAN_INTERNAL, SCAN_EXTERNAL, COMPARE, EXECUTE }

/** 携带阶段的同步异常；[stage] 供上层决定展示文案 */
class SyncStageException(val stage: SyncStage, cause: Throwable?) :
    Exception(cause?.message ?: stage.name, cause)

/**
 * 工作区「导回原处」核心逻辑：扫描 → 直接对比 → 预览 → 执行写入 → 快照更新。
 *
 * 路径约定（内部与外部完全一致，均不含 syncRoot 前缀）：
 * - 内部：files/<syncRoot>/<rel> 中的 <rel>（相对导入根目录）
 * - 外部：<treeRoot>/<rel> 中的 <rel>（相对 SAF tree 根目录）
 *
 * 对比规则（严格 rsync 语义，快照 B 不再参与操作类型判断）：
 * - CREATE：A 有，C 无 → 写入外部
 * - DELETE：A 无，C 有 → 从外部删除
 * - MODIFY：A 有，C 有，size 不同 → 覆盖外部；size 相同但内容 hash 不同（完整模式）→ 覆盖外部
 * - 相同：A 有，C 有，size 相同（快速模式），或 size 与 hash 均相同（完整模式）→ 无操作
 * - IGNORE：匹配排除规则 → 扫描阶段即剪枝，后续逻辑完全不可见
 *
 * 容错（单文件熔断）：扫描 / 对比 / 删除阶段单个文件打不开、读不了、SAF 抛异常时，
 * 只跳过该文件并记录警告日志，绝不让整个协程崩溃；写入阶段失败保持致命（数据完整性）。
 *
 * 快照 B 仅用于两处：
 * 1. 外部扫描的目录指纹短路（指纹一致 → 子树整体复用快照文件记录，但 hash 强制置空，
 *    由比对阶段重新验算内容，保证同尺寸变更不漏检）
 * 2. 同步完成后记录新的 A 状态（供下次指纹短路）
 */
object WorkspaceSyncEngine {

    private const val TAG = "WorkspaceSyncEngine"

    /** 进度回调中「总数未知」的哨兵值（扫描阶段无法预知总文件数，UI 显示不确定进度） */
    const val UNKNOWN_TOTAL = -1

    /**
     * 完整模式并发校验内容时，同时开启的协程数（批量分块，每块回调一次进度）。
     * 保持 8~12：Android 进程 fd 上限通常 1024，应用自身（数据库 / 网络 / SAF 等）已占用不少，
     * 并发开流过多会触发 "Too many open files" 崩溃；12 路并发对 2000 文件约 167 批，足够快。
     */
    private const val HASH_CONCURRENCY = 16

    /**
     * 扫描内部文件区（本地文件）。
     * 排除 `.rikkahub` / `.l2s.` 等元数据与命中排除规则的内容；
     * 同时收集空目录（整棵子树无可见文件，根目录除外）以同步目录结构。
     *
     * 单文件熔断：单个文件打不开 / 读不了 / 算 hash 失败 → 跳过该文件并记录警告，
     * 不影响其余文件与整个协程。
     *
     * @param withHash 是否 eager 计算内容 hash；快速模式传 false（仅收集尺寸，省去本地读文件）
     * @param onProgress 每处理一个可见文件回调一次 (done, total)；total 为 [UNKNOWN_TOTAL]
     */
    suspend fun scanInternal(
        filesDir: File,
        syncRoot: String,
        rules: WorkspaceIgnoreRules,
        withHash: Boolean = true,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): InternalScanResult = withContext(Dispatchers.IO) {
        val root = File(filesDir, syncRoot)
        if (!root.isDirectory) return@withContext InternalScanResult(emptyMap(), emptyMap())
        val files = linkedMapOf<String, SyncFileState>()
        val directories = linkedMapOf<String, DirFingerprint>()
        val emptyDirs = linkedSetOf<String>()

        /** 返回该目录子树内的可见文件总数（用于判定空目录） */
        suspend fun walk(dir: File, parentKey: String): Int {
            val entries = mutableListOf<String>()
            var directFileCount = 0
            var subtreeFileCount = 0
            val children = runCatching { dir.listFiles() }.getOrElse { e ->
                Log.w(TAG, "scanInternal: 无法列出目录「${parentKey.ifBlank { "/" } }」，已跳过: $e")
                emptyArray()
            } ?: emptyArray()
            for (f in children) {
                currentCoroutineContext().ensureActive()
                val name = runCatching { f.name }.getOrNull() ?: continue
                if (name.startsWith(".l2s.") || name == ".rikkahub") continue
                val isDir = runCatching { f.isDirectory }.getOrDefault(false)
                if (rules.shouldIgnore(name, parentKey, isDir)) continue
                val key = if (parentKey.isEmpty()) name else "$parentKey/$name"
                if (isDir) {
                    subtreeFileCount += walk(f, key)
                    directories[key]?.let { entries += "$name:d:${it.childHash}" }
                } else {
                    val state = runCatching { fileState(f, withHash) }.getOrElse { e ->
                        Log.w(TAG, "scanInternal: 文件「$key」无法读取，已跳过: $e")
                        null
                    }
                    if (state != null) {
                        files[key] = state
                        entries += "$name:f:${state.size}"
                        directFileCount++
                        onProgress(files.size, UNKNOWN_TOTAL)
                    }
                }
            }
            subtreeFileCount += directFileCount
            directories[parentKey] = dirFingerprint(entries, directFileCount)
            if (subtreeFileCount == 0 && parentKey.isNotEmpty()) {
                emptyDirs += parentKey
            }
            return subtreeFileCount
        }

        walk(root, "")
        InternalScanResult(files, directories, emptyDirs)
    }

    /**
     * 扫描外部目录（SAF）。与内部扫描共用同一个 [rules] 实例，排除行为完全一致。
     *
     * 单文件熔断：单个子项 length() / listFiles() / 加载 .gitignore 抛异常 → 跳过该项并记录警告。
     *
     * 性能优化（指纹短路 + DocumentFile 缓存）：
     * - 每个目录 1 次 listFiles()，只取 size 元数据，不读取文件内容。
     * - 目录指纹与快照同路径指纹一致 → 该子树整体复用快照文件记录，
     *   但 [SyncFileState.hash] 强制置空，由比对阶段懒计算当前内容 CRC32——
     *   因为指纹的文件子项只代理 size，同尺寸内容变更无法被指纹感知，
     *   只有重新验算内容才能保证 100% 正确。
     * - 指纹不一致 → 继续递归下钻；变更子树内的文件 hash 同样留空，按需懒计算。
     * - 遍历到的所有目录 / 文件 DocumentFile 按路径写入 [docCache]，供对比与执行阶段直接取用。
     *
     * 进度总数（预估 → 修正）：
     * 初始 total = 快照文件数（[SyncSnapshot.files] 大小，立即可得、零成本）；
     * 扫描中每完成一个目录，用「实际子树文件数 − 快照子树文件数」修正 total，
     * 使总数逐渐收敛到真实外部文件数（自底向上累加，逐层去重，避免嵌套目录重复修正）。
     * 无快照时 total 保持 [UNKNOWN_TOTAL]（UI 显示不确定进度）。
     *
     * @param onProgress 每处理一个可见文件或完成一个目录回调一次
     *  (done, total, totalEstimated)：
     *  - done：已扫描文件数（不超过 total，避免估计滞后时显示超 100%）
     *  - total：当前估计总数（无快照时为 [UNKNOWN_TOTAL]）
     *  - totalEstimated：true=仍用快照估计数（UI 显示「预估」）；false=已发生偏差修正（UI 显示「更新」）
     */
    suspend fun scanExternalFast(
        context: Context,
        rootDoc: DocumentFile,
        rules: WorkspaceIgnoreRules,
        snapshot: SyncSnapshot?,
        docCache: DocumentCache? = null,
        onProgress: suspend (done: Int, total: Int, totalEstimated: Boolean) -> Unit = { _, _, _ -> },
    ): ExternalScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val snapshotFiles = snapshot?.files.orEmpty()
        val snapshotDirs = snapshot?.directories.orEmpty()
        val files = linkedMapOf<String, SyncFileState>()
        val directories = linkedMapOf<String, DirFingerprint>()
        val emptyDirs = linkedSetOf<String>()

        // 快照子树文件数（每个目录路径 → 该子树内可见文件总数），用于逐目录修正估计总数
        val snapshotSubtreeCounts = subtreeFileCounts(snapshot)

        // 估计总数：初始 = 快照文件数；扫描中逐目录修正；无快照则保持未知
        var currentTotal = snapshot?.files?.size ?: UNKNOWN_TOTAL
        // 是否已发生偏差修正（true 后 UI 文案从「预估」切到「更新」）
        var corrected = false
        // 每个目录已应用的修正量（自底向上去重：祖先目录的修正要扣除子孙已修正的部分）
        val appliedDelta = mutableMapOf<String, Int>()

        /** 上报进度：done 不超过 total，避免估计滞后时进度/文案超界 */
        suspend fun reportProgress() {
            val reportTotal = if (currentTotal >= 0) currentTotal else UNKNOWN_TOTAL
            val reportDone = if (reportTotal >= 0) minOf(files.size, reportTotal) else files.size
            onProgress(reportDone, reportTotal, !corrected)
        }

        /** 返回该目录子树内的可见文件总数（用于判定空目录） */
        suspend fun walk(doc: DocumentFile, parentKey: String): Int {
            currentCoroutineContext().ensureActive()
            runCatching { rules.loadGitignore(doc, parentKey, resolver) }
                .onFailure { e ->
                    Log.w(TAG, "scanExternalFast: 加载「${parentKey.ifBlank { "/" } }/.gitignore」失败，已跳过: $e")
                }
            docCache?.dirs?.put(parentKey, doc)
            val prefix = if (parentKey.isEmpty()) "" else "$parentKey/"
            val entries = mutableListOf<String>()
            var directFileCount = 0
            var subtreeFileCount = 0

            val children = runCatching { doc.listFiles() }.getOrElse { e ->
                Log.w(TAG, "scanExternalFast: 无法列出目录「${parentKey.ifBlank { "/" } }」，已跳过: $e")
                emptyArray()
            } ?: emptyArray()

            for (child in children) {
                currentCoroutineContext().ensureActive()
                val name = child.name ?: continue
                val isDir = runCatching { child.isDirectory }.getOrDefault(false)
                if (rules.shouldIgnore(name, parentKey, isDir)) continue
                val key = "$prefix$name"
                if (isDir) {
                    subtreeFileCount += walk(child, key)
                    directories[key]?.let { entries += "$name:d:${it.childHash}" }
                } else {
                    val size = runCatching { child.length() }.getOrElse { e ->
                        Log.w(TAG, "scanExternalFast: 文件「$key」无法读取，已跳过: $e")
                        -1L
                    }
                    if (size >= 0) {
                        docCache?.files?.put(key, child)
                        files[key] = SyncFileState(size = size, hash = "")
                        entries += "$name:f:$size"
                        directFileCount++
                        reportProgress()
                    }
                }
            }
            subtreeFileCount += directFileCount

            val fp = dirFingerprint(entries, directFileCount)
            directories[parentKey] = fp

            // 指纹短路：与快照同路径指纹一致 → 子树整体复用快照记录。
            // 注意：hash 强制置空（不信任快照 hash），比对阶段重新验算内容，避免同尺寸变更漏检。
            // docCache 中已缓存的真实 DocumentFile 保留（指纹一致说明子项未变，文档仍有效）。
            if (snapshotDirs[parentKey] == fp) {
                files.keys.retainAll { !it.startsWith(prefix) }
                snapshotFiles.forEach { (path, state) ->
                    if (path.startsWith(prefix)) files[path] = state.copy(hash = "")
                }
            }

            // 目录完成：用「实际子树数 − 快照子树数」修正估计总数。
            // 由于子树嵌套，祖先目录的修正需扣除子孙目录已修正的部分（appliedDelta 自底向上累积）。
            if (snapshot != null) {
                val snapshotCount = snapshotSubtreeCounts[parentKey]
                val ownDelta = if (snapshotCount == null) subtreeFileCount else subtreeFileCount - snapshotCount
                val childrenApplied = appliedDelta.entries
                    .filter { it.key.startsWith(prefix) }
                    .sumOf { it.value }
                val applied = ownDelta - childrenApplied
                if (applied != 0) {
                    currentTotal += applied
                    corrected = true
                    appliedDelta[parentKey] = applied
                }
            }

            // 目录完成：上报修正后的总数（UI 据此更新「xxx」与「预估/更新」）
            reportProgress()

            if (subtreeFileCount == 0 && parentKey.isNotEmpty()) {
                emptyDirs += parentKey
            }
            return subtreeFileCount
        }

        walk(rootDoc, "")
        ExternalScanResult(files, directories, emptyDirs)
    }

    /**
     * 从快照目录指纹预计算每棵子树的可见文件总数（key 为目录相对路径，根目录为 ""）。
     * 用于外部扫描的逐目录总数修正；快照缺失或为空返回空 Map。
     * 递归深度与目录树深度一致（实际项目一般 < 50 层，安全）。
     */
    private fun subtreeFileCounts(snapshot: SyncSnapshot?): Map<String, Int> {
        val dirs = snapshot?.directories ?: return emptyMap()
        // 注意：根目录 key 为 ""，其 substringBeforeLast 结果仍是自身，若参与分组会形成自环导致无限递归，
        // 因此必须排除空串（根目录没有父目录，只作为其他目录的父分组键存在）。
        val childrenOf = dirs.keys
            .filter { it.isNotEmpty() }
            .groupBy { it.substringBeforeLast('/', "") }
        val memo = mutableMapOf<String, Int>()
        fun count(path: String): Int = memo.getOrPut(path) {
            val fp = dirs[path] ?: return@getOrPut 0
            fp.fileCount + (childrenOf[path]?.sumOf { count(it) } ?: 0)
        }
        dirs.keys.forEach { count(it) }
        return memo
    }

    /**
     * 仅遍历外部目录树加载 .gitignore 规则（不收集文件/指纹/空目录），
     * 供同步执行前复用与导入/预览完全一致的排除逻辑。
     * 若已持有 [docCache]（预览阶段扫描填充），直接遍历缓存，不再走 SAF 逐层 findFile。
     */
    fun loadGitignoreTree(
        context: Context,
        rootDoc: DocumentFile,
        rules: WorkspaceIgnoreRules,
        docCache: DocumentCache? = null,
    ) {
        val resolver = context.contentResolver
        if (docCache != null) {
            docCache.dirs.forEach { (key, doc) ->
                runCatching { rules.loadGitignore(doc, key, resolver) }
                    .onFailure { e ->
                        Log.w(TAG, "loadGitignoreTree: 加载「${key.ifBlank { "/" } }/.gitignore」失败，已跳过: $e")
                    }
            }
            return
        }
        rules.loadGitignore(rootDoc, "", resolver)

        fun walk(doc: DocumentFile, parentKey: String) {
            doc.listFiles().orEmpty().forEach { child ->
                val name = child.name ?: return@forEach
                if (!child.isDirectory) return@forEach
                if (rules.shouldIgnore(name, parentKey, true)) return@forEach
                val key = if (parentKey.isEmpty()) name else "$parentKey/$name"
                rules.loadGitignore(child, key, resolver)
                walk(child, key)
            }
        }

        walk(rootDoc, "")
    }

    /**
     * 直接对比 A（内部当前）与 C（外部当前），生成预览项（不执行写入）。
     * 快照不参与操作类型判断。
     *
     * 双模式：
     * - [SyncCheckMode.FAST]（轻量）：只比对存在性与文件尺寸。
     *   一边有一边没有 → CREATE / DELETE；两边都有但尺寸不同 → MODIFY；
     *   两边都有且尺寸相同 → 直接视为相同，无操作。
     * - [SyncCheckMode.ACCURATE]（准确）：尺寸相同且 C 侧 hash 未计算的文件，
     *   并发批量计算 CRC32（[HASH_CONCURRENCY] 个协程一批），每算完一批回调一次 [onHashProgress]；
     *   任一文件内容校验失败 → 跳过该文件（视为无变更），不中断整体。
     *
     * 空目录（[internalDirs] / [externalDirs]，整棵子树无可见文件）：
     * A 有 C 无 → CREATE_DIR；C 有 A 无 → DELETE_DIR。
     */
    suspend fun computePreview(
        context: Context,
        rootDoc: DocumentFile,
        internal: Map<String, SyncFileState>,
        external: Map<String, SyncFileState>,
        internalDirs: Set<String> = emptySet(),
        externalDirs: Set<String> = emptySet(),
        mode: SyncCheckMode = SyncCheckMode.ACCURATE,
        docCache: DocumentCache? = null,
        onHashProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncPreviewItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SyncPreviewItem>()
        // 存在性 + 尺寸差异（两种模式都走这一遍）
        val sameSizePaths = mutableListOf<String>()
        for (path in internal.keys + external.keys) {
            currentCoroutineContext().ensureActive()
            val a = internal[path]
            val c = external[path]
            when {
                a == null -> items += SyncPreviewItem(type = SyncPreviewType.DELETE, path = path)

                c == null -> items += SyncPreviewItem(
                    type = SyncPreviewType.CREATE,
                    path = path,
                    sizeHint = a.size.fileSizeToString(),
                )

                a.size != c.size -> items += SyncPreviewItem(
                    type = SyncPreviewType.MODIFY,
                    path = path,
                    sizeHint = a.size.fileSizeToString(),
                )

                // 尺寸相同：快速模式直接视为相同；准确模式走内容 hash
                else -> sameSizePaths += path
            }
        }
        if (mode == SyncCheckMode.ACCURATE && sameSizePaths.isNotEmpty()) {
            val cHashes = hashExternalFiles(context, rootDoc, sameSizePaths, docCache, onHashProgress)
            for ((index, path) in sameSizePaths.withIndex()) {
                currentCoroutineContext().ensureActive()
                val a = internal[path] ?: continue
                val cHash = cHashes[index]
                if (cHash == null) {
                    // 内容校验失败（无法读取等）→ 跳过该文件，已在上层记录警告
                    continue
                }
                if (a.hash != cHash) {
                    items += SyncPreviewItem(
                        type = SyncPreviewType.MODIFY,
                        path = path,
                        sizeHint = a.size.fileSizeToString(),
                    )
                }
            }
        }
        // 空目录：A 有 C 无 → 创建；C 有 A 无 → 删除
        for (dir in internalDirs - externalDirs) {
            items += SyncPreviewItem(type = SyncPreviewType.CREATE_DIR, path = dir)
        }
        for (dir in externalDirs - internalDirs) {
            items += SyncPreviewItem(type = SyncPreviewType.DELETE_DIR, path = dir)
        }
        items.sortedWith(compareBy({ it.type.ordinal }, { it.path }))
    }

    /**
     * 执行同步写入。先删后写：
     * 1. 所有 DELETE（文件先删，目录自下而上清理空目录，含两遍兜底重试）
     * 2. 所有 CREATE/MODIFY（目录自上而下创建，文件通过 ContentResolver 写入）
     *
     * 复用 [docCache]（预览阶段扫描填充）定位文件与目录，避免 SAF 逐层 findFile 重复查询；
     * 缓存缺失时回退 [resolveDocument]。
     *
     * 删除阶段单文件容错：删除失败只跳过并记录警告（空目录删除失败最后统一再扫一遍）；
     * 写入阶段失败保持致命（数据完整性，失败后不更新快照，下次同步重试）。
     */
    suspend fun execute(
        context: Context,
        rootDoc: DocumentFile,
        preview: List<SyncPreviewItem>,
        syncRoot: String,
        repository: WorkspaceRepository,
        id: String,
        docCache: DocumentCache? = null,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val total = preview.size
        var done = 0
        fun tick() {
            done++
            onProgress(done, total)
        }

        // ---- 阶段 1：DELETE（文件后目录，自下而上）----
        val fileDeletes = preview.filter { it.type == SyncPreviewType.DELETE }
        val dirDeletes = preview.filter { it.type == SyncPreviewType.DELETE_DIR }
        val touchedDirs = mutableSetOf<String>()
        for (item in fileDeletes.sortedByDescending { it.path.count { c -> c == '/' } }) {
            currentCoroutineContext().ensureActive()
            val doc = docCache?.files?.get(item.path) ?: resolveDocument(rootDoc, item.path)
            if (doc != null && doc.isFile) {
                runCatching { doc.delete() }
                    .onSuccess { touchedDirs += item.path.substringBeforeLast('/', "") }
                    .onFailure { e ->
                        Log.w(TAG, "execute: 删除文件「${item.path}」失败，已跳过: $e")
                    }
            }
            tick()
        }
        // 删除后清理空目录（自下而上，避免目录非空删不掉；含被忽略内容的目录不会被删）
        // 注意：顶层文件（如 README.md）的 parent 是空字符串，必须过滤掉，
        // 否则 resolveDocument(rootDoc, "") 会返回 rootDoc 本身，误删整个外部根目录。
        // SAF 上 listFiles() 可能有缓存延迟：第一遍删不掉的（报非空）先跳过，
        // 最后统一再扫一遍残余空目录兜底。
        cleanupEmptyDirs(
            rootDoc = rootDoc,
            candidates = touchedDirs.filter { it.isNotBlank() } + dirDeletes.map { it.path },
            docCache = docCache,
        )
        for (item in dirDeletes.sortedByDescending { it.path.count { c -> c == '/' } }) {
            currentCoroutineContext().ensureActive()
            tick()
        }

        // ---- 阶段 2：CREATE / MODIFY（目录自上而下，文件写入）----
        val writes = preview
            .filter { it.type == SyncPreviewType.CREATE || it.type == SyncPreviewType.MODIFY }
            .sortedBy { it.path }
        for (item in writes) {
            currentCoroutineContext().ensureActive()
            val segments = item.path.split('/')
            val name = segments.last()
            val parent = ensureDocumentDir(rootDoc, segments.dropLast(1), docCache)
                ?: error("无法创建目录: ${item.path}")
            val existing = docCache?.files?.get(item.path) ?: parent.findFile(name)
            val fileDoc = if (existing != null && existing.isFile) {
                existing
            } else {
                parent.createFile(mimeFor(name), name) ?: error("无法创建文件: ${item.path}")
            }
            if (existing == null) {
                docCache?.files?.put(item.path, fileDoc)
            }
            val out = resolver.openOutputStream(fileDoc.uri, "wt")
                ?: error("无法打开输出流: ${item.path}")
            // 内部文件位于 files/<syncRoot>/<rel>，读取时补回 syncRoot 前缀
            repository.exportFile(id, WorkspaceStorageArea.FILES, "$syncRoot/${item.path}", out)
            tick()
        }
        // 显式 CREATE_DIR（先建浅层目录，自上而下创建目录链；已由写入阶段建好的直接命中缓存）
        val dirCreates = preview
            .filter { it.type == SyncPreviewType.CREATE_DIR }
            .sortedBy { it.path.count { c -> c == '/' } }
        for (item in dirCreates) {
            currentCoroutineContext().ensureActive()
            ensureDocumentDir(rootDoc, item.path.split('/'), docCache)
                ?: error("无法创建目录: ${item.path}")
            tick()
        }
    }

    // ---- 内部辅助 ----

    private fun fileState(f: File, withHash: Boolean): SyncFileState = SyncFileState(
        size = f.length(),
        hash = if (withHash) f.inputStream().use { crc32Hex(it) } else "",
    )

    private fun dirFingerprint(entries: List<String>, fileCount: Int): DirFingerprint =
        DirFingerprint(
            childHash = crc32Hex(entries.sorted().joinToString("\n").byteInputStream()),
            fileCount = fileCount,
        )

    /**
     * 并发批量计算外部文件 CRC32（[HASH_CONCURRENCY] 个协程一批，每批回调一次进度）。
     * 单个文件失败返回 null（上层跳过该文件），不中断整批。
     */
    private suspend fun hashExternalFiles(
        context: Context,
        rootDoc: DocumentFile,
        paths: List<String>,
        docCache: DocumentCache?,
        onHashProgress: suspend (done: Int, total: Int) -> Unit,
    ): List<String?> {
        val results = arrayOfNulls<String>(paths.size)
        var emitted = 0
        for (chunk in paths.chunked(HASH_CONCURRENCY)) {
            currentCoroutineContext().ensureActive()
            val base = emitted
            val deferred = coroutineScope {
                chunk.mapIndexed { _, path ->
                    async {
                        runCatching { resolveExternalHash(context, rootDoc, path, docCache) }
                            .getOrElse { e ->
                                Log.w(TAG, "computePreview: 文件「$path」内容校验失败，已跳过: $e")
                                null
                            }
                    }
                }
            }
            for (indexInChunk in deferred.indices) {
                results[base + indexInChunk] = deferred[indexInChunk].await()
            }
            emitted += chunk.size
            onHashProgress(emitted, paths.size)
        }
        return results.toList()
    }

    /** 懒加载外部文件 hash（仅在 size 相同且需比对内容时调用）；失败返回 null */
    private fun resolveExternalHash(
        context: Context,
        rootDoc: DocumentFile,
        path: String,
        docCache: DocumentCache? = null,
    ): String? {
        val doc = docCache?.files?.get(path) ?: resolveDocument(rootDoc, path)
        if (doc == null || !doc.isFile) return null
        val stream = context.contentResolver.openInputStream(doc.uri) ?: return null
        return stream.use { crc32Hex(it) }
    }

    private fun crc32Hex(input: InputStream): String {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
        }
        return crc.value.toString(16)
    }

    private fun resolveDocument(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (seg in path.split('/')) {
            if (seg.isEmpty()) continue
            current = current.findFile(seg) ?: return null
        }
        return current
    }

    /** 定位（并可按需创建）目录链；优先命中 [docCache]，新建目录同步写入缓存 */
    private fun ensureDocumentDir(
        root: DocumentFile,
        segments: List<String>,
        docCache: DocumentCache?,
    ): DocumentFile? {
        var current = root
        var path = ""
        for (seg in segments) {
            if (seg.isEmpty()) continue
            path = if (path.isEmpty()) seg else "$path/$seg"
            val cached = docCache?.dirs?.get(path)
            current = cached
                ?: current.findFile(seg)
                ?: current.createDirectory(seg)
                ?: return null
            if (cached == null) {
                docCache?.dirs?.put(path, current)
            }
        }
        return current
    }

    /**
     * 清理空目录（自下而上）。SAF 上 listFiles() 可能有缓存延迟，
     * 第一遍删不掉的先跳过，最后统一再扫一遍残余空目录兜底。
     */
    private fun cleanupEmptyDirs(
        rootDoc: DocumentFile,
        candidates: Collection<String>,
        docCache: DocumentCache?,
    ) {
        val ordered = candidates
            .filter { it.isNotBlank() }
            .sortedByDescending { it.count { c -> c == '/' } }
        for (pass in 0 until 2) {
            var changed = false
            for (dirPath in ordered) {
                val dir = docCache?.dirs?.get(dirPath) ?: resolveDocument(rootDoc, dirPath) ?: continue
                if (dir.uri == rootDoc.uri || !dir.isDirectory) continue
                val empty = runCatching { dir.listFiles()?.isEmpty() != false }.getOrDefault(false)
                if (empty && runCatching { dir.delete() }.getOrDefault(false)) {
                    docCache?.dirs?.remove(dirPath)
                    changed = true
                }
            }
            if (!changed) break
        }
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
