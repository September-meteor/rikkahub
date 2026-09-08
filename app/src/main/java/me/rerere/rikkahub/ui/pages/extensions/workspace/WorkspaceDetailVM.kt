package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateMapOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.DocumentCache
import me.rerere.rikkahub.data.sync.ImportConflictMode
import me.rerere.rikkahub.data.sync.InternalScanResult
import me.rerere.rikkahub.data.sync.SyncCheckMode
import me.rerere.rikkahub.data.sync.SyncPreviewItem
import me.rerere.rikkahub.data.sync.SyncPreviewType
import me.rerere.rikkahub.data.sync.SyncSnapshot
import me.rerere.rikkahub.data.sync.SyncStage
import me.rerere.rikkahub.data.sync.SyncStageException
import me.rerere.rikkahub.data.sync.WorkspaceSyncException
import me.rerere.rikkahub.data.sync.WorkspaceSyncFailureReason
import me.rerere.rikkahub.data.sync.WorkspaceIgnoreRules
import me.rerere.rikkahub.data.sync.WorkspaceSyncEngine
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val appContext: Context,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    /**
     * 每个目录（area+path）各保留一个独立的 LazyListState：
     * 切换目录时旧目录的滚动状态对象不被销毁，返回时直接复用，即可原样恢复位置，
     * 无需手动记录/换算偏移，不会跳动、不会逐次累积误差。
     *
     * 状态必须放在 VM（而不是 Composable 的 remember）里：打开文本文件/终端会推入新的
     * 导航条目，使 WorkspaceDetailPage 离开组合，remember 会被清空；而 VM 随本页导航条目
     * 存活，从编辑器/终端返回后仍能拿到同一批 LazyListState，滚动位置原样恢复。
     */
    val filesListStates = mutableStateMapOf<String, LazyListState>()

    /**
     * 「自定义排除模式」输入框文本状态（对齐消息输入框逻辑）：
     * 输入以 state 为准，持久化通过 debounce 单向推送，避免每键 Room 回流覆盖导致光标跳动。
     */
    val customIgnoreState = TextFieldState()
    private var customIgnoreInitialized = false

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    /** 「导回原处」进行中的协程（扫描阶段可取消） */
    private var syncJob: Job? = null

    /** 预览阶段构建并保留的排除规则 / DocumentFile 缓存，供确认执行时复用 */
    private var syncRules: WorkspaceIgnoreRules? = null
    private var syncDocCache: DocumentCache? = null

    /** 覆盖导入：预览阶段构建并保留的规则 / 缓存 / 待确认上下文 */
    private var importRules: WorkspaceIgnoreRules? = null
    private var importDocCache: DocumentCache? = null
    private var pendingImport: PendingImport? = null
    private var pendingFileImport: PendingFileImport? = null

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        // 目录型软链接解析后是真实目录（resolvedPath）；普通目录/虚拟目录无 resolvedPath，走自身路径
        _state.update { it.copy(path = entry.resolvedPath ?: entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val current = state.value
        val path = current.path
        if (path.isBlank()) return
        val parent = if (current.area == WorkspaceStorageArea.LINUX) {
            // rootfs 区为沙盒内绝对路径：/a/b -> /a，/a -> 根（空串）
            val trimmed = path.trimEnd('/')
            val index = trimmed.lastIndexOf('/')
            when {
                index <= 0 -> ""
                else -> trimmed.substring(0, index)
            }
        } else {
            path.substringBeforeLast('/', missingDelimiterValue = "")
        }
        _state.update { it.copy(path = parent, entries = emptyList(), error = null) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    // 软链接只删除链接本身，绝不递归删除其目标目录/文件
                    recursive = entry.isDirectory && !entry.isSymlink,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    /**
     * 就地重命名（同目录改名，不跨目录移动）。
     * 若改的是「顶层导入目录」（同步回原目录的根），同步来源注册与快照一并清除
     * （改名前 UI 已用对话框提示该后果）。
     */
    fun rename(entry: WorkspaceFileEntry, newName: String) {
        viewModelScope.launch {
            val current = state.value
            runCatching {
                val renamed = repository.renameFile(id, current.area, entry.path, newName)
                if (renamed) {
                    val syncRoot = topLevelSyncRootOf(entry, current.area)
                    if (syncRoot != null && syncRoot in current.syncRoots) {
                        repository.removeSyncSource(id, syncRoot)
                    }
                }
                renamed
            }.onSuccess { renamed ->
                if (renamed) {
                    loadWorkspace()
                    refresh()
                } else {
                    _state.update { it.copy(error = "重命名失败：目标已存在或路径不可写") }
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "重命名失败") }
            }
        }
    }

    /** 条目是否为「顶层导入目录」（其名字即同步根 syncRoot，改名会断开同步回原目录关联）。 */
    private fun topLevelSyncRootOf(entry: WorkspaceFileEntry, area: WorkspaceStorageArea): String? {
        if (!entry.isDirectory || entry.virtual || entry.isSymlink) return null
        return when (area) {
            WorkspaceStorageArea.FILES ->
                if ('/' !in entry.path) entry.name else null
            WorkspaceStorageArea.LINUX ->
                if (entry.path == "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/${entry.name}") entry.name else null
        }
    }

    /**
     * 导入单个文件。
     *
     * 冲突处理取决于工作区 [ImportConflictMode]：
     * - RENAME（默认）：同名时静默创建副本（name (1).ext）
     * - OVERWRITE：目标为文件 → 弹预览（更新）确认后覆盖；目标为目录 → 类型冲突弹窗（替换/取消）
     */
    fun importFile(context: Context, uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val workspace = repository.getById(id) ?: return@launch
            val mode = ImportConflictMode.from(workspace.importConflictMode)
            val area = state.value.area
            val destPath = state.value.path
            val targetPath = if (destPath.isBlank()) fileName else "$destPath/$fileName"
            Log.d(TAG, "importFile: mode=$mode area=$area dest=$destPath name=$fileName")

            val conflictExists = repository.fileExists(id, area, targetPath)
            if (!conflictExists || mode == ImportConflictMode.RENAME) {
                // 无冲突 / 创建副本：直接导入（importBytes 内部处理副本命名）
                writeSingleFile(context, uri, destPath, fileName, area, overwrite = false)
                return@launch
            }

            // OVERWRITE 且目标已存在
            if (repository.isDirectory(id, area, targetPath)) {
                // 目标为目录 → 类型冲突（替换/取消）
                pendingFileImport = PendingFileImport(
                    uri = uri,
                    fileName = fileName,
                    targetPath = targetPath,
                    area = area,
                    destPath = destPath,
                    replace = true,
                )
                _state.update {
                    it.copy(importTypeConflict = ImportTypeConflictInfo(name = fileName, importingDirectory = false))
                }
            } else {
                // 目标为文件 → 预览「更新」后覆盖
                pendingFileImport = PendingFileImport(
                    uri = uri,
                    fileName = fileName,
                    targetPath = targetPath,
                    area = area,
                    destPath = destPath,
                    replace = false,
                )
                _state.update {
                    it.copy(importPreview = listOf(SyncPreviewItem(type = SyncPreviewType.MODIFY, path = targetPath)))
                }
            }
        }
    }

    private suspend fun writeSingleFile(
        context: Context,
        uri: Uri,
        destPath: String,
        fileName: String,
        area: WorkspaceStorageArea,
        overwrite: Boolean,
    ) {
        runCatching {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error(appContext.getString(R.string.workspace_error_cannot_read_file))
            repository.importFile(
                id = id,
                area = area,
                destinationPath = destPath,
                fileName = fileName,
                inputStream = inputStream,
                overwrite = overwrite,
            )
        }.onSuccess {
            refresh()
        }.onFailure { error ->
            _state.update { it.copy(error = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_file_failed)), importError = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_file_failed))) }
            Log.e(TAG, "导入文件失败: $error", error)
        }
    }

    // 导入整个目录（流式总数 + 增强.gitignore解析）
    // 冲突处理取决于工作区 ImportConflictMode：
    // - RENAME（默认）：目标已存在同名目录时创建副本（name (1)），不注册同步来源/快照
    // - OVERWRITE：目标为目录 → 扫描差异，含破坏性操作（更新/删除）时弹预览确认后执行；
    //   目标为文件 → 类型冲突弹窗（替换/取消）
    fun importDirectory(
        context: Context,
        treeUri: Uri,
        enableGitignore: Boolean,
        customIgnorePatterns: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val workspace = repository.getById(id) ?: return@launch
            val mode = ImportConflictMode.from(workspace.importConflictMode)
            val area = state.value.area
            val destPath = state.value.path

            // -1 表示正在快速扫描阶段，UI 会显示不确定进度
            _state.update { it.copy(loading = true, error = null, importError = null, importProgress = 0 to -1) }
            Log.d(TAG, "importDirectory: mode=$mode area=$area dest=$destPath uri=$treeUri")

            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error(appContext.getString(R.string.workspace_error_cannot_access_dir))
                val rootName = rootDoc.name ?: "imported"
                val rules = WorkspaceIgnoreRules(enableGitignore, customIgnorePatterns)

                val targetPath = if (destPath.isBlank()) rootName else "$destPath/$rootName"
                val conflictExists = repository.fileExists(id, area, targetPath)

                when {
                    // 无冲突：无论哪种模式都按现有流程导入（同步来源注册 + 快照由导入完成处统一处理）
                    !conflictExists -> {
                        importTree(context, rootDoc, rootName, destPath, area, rules, registerSnapshot = true)
                    }

                    // 同名目录已存在 + 副本模式：创建副本，不注册同步来源/快照
                    mode == ImportConflictMode.RENAME -> {
                        val copyName = resolveCopyDirName(area, destPath, rootName)
                        importTree(context, rootDoc, copyName, destPath, area, rules, registerSnapshot = false)
                    }

                    // 覆盖模式 + 目标为目录：扫描差异后一律弹预览（空差异显示「没有需要导入的变更」），
                    // 确认后执行写入；确认按钮在空差异时置灰
                    repository.isDirectory(id, area, targetPath) -> {
                        val docCache = DocumentCache()
                        val preview = computeImportDiff(context, rootDoc, rootName, destPath, area, rules, docCache)
                        Log.d(TAG, "importDirectory overwrite: root=$rootName preview=${preview.size} items=${preview.map { it.type.name + ":" + it.path }}")
                        importRules = rules
                        importDocCache = docCache
                        pendingImport = PendingImport(
                            treeUri = treeUri,
                            rootName = rootName,
                            destPath = destPath,
                            area = area,
                            preview = preview,
                            replace = false,
                        )
                        _state.update { it.copy(loading = false, importProgress = null, importPreview = preview) }
                    }

                    // 覆盖模式 + 目标为文件：类型冲突 → 弹窗
                    else -> {
                        importRules = rules
                        pendingImport = PendingImport(
                            treeUri = treeUri,
                            rootName = rootName,
                            destPath = destPath,
                            area = area,
                            preview = emptyList(),
                            replace = true,
                        )
                        _state.update {
                            it.copy(
                                loading = false,
                                importProgress = null,
                                importTypeConflict = ImportTypeConflictInfo(name = rootName, importingDirectory = true),
                            )
                        }
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        importProgress = null,
                        error = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_dir_failed)),
                        importError = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_dir_failed)),
                    )
                }
                Log.e(TAG, "导入目录失败: $error", error)
            }
        }
    }

    /** 确认覆盖导入（单文件覆盖 / 目录覆盖），真正执行写入 */
    fun confirmImportPreview(context: Context) {
        val filePending = pendingFileImport
        if (filePending != null) {
            pendingFileImport = null
            _state.update { it.copy(importPreview = null) }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(filePending.uri)
                        ?: error(appContext.getString(R.string.workspace_error_cannot_read_file))
                    repository.importFile(
                        id = id,
                        area = filePending.area,
                        destinationPath = filePending.destPath,
                        fileName = filePending.fileName,
                        inputStream = stream,
                        overwrite = true,
                    )
                }.onSuccess {
                    refresh()
                }.onFailure { error ->
                    _state.update { it.copy(error = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_file_failed)), importError = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_file_failed))) }
            Log.e(TAG, "导入文件失败: $error", error)
                }
            }
            return
        }
        val pending = pendingImport ?: return
        pendingImport = null
        _state.update { it.copy(importPreview = null) }
        // 空差异（内容完全一致）：无需执行，直接关闭
        if (pending.preview.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, pending.treeUri)
                    ?: error(appContext.getString(R.string.workspace_error_cannot_access_dir))
                val rules = importRules ?: WorkspaceIgnoreRules(
                    repository.getById(id)?.enableGitignore ?: true,
                    repository.getById(id)?.customIgnorePatterns ?: "",
                )
                val docCache = importDocCache ?: DocumentCache()
                executeImportOverwrite(
                    context,
                    rootDoc,
                    pending.rootName,
                    pending.destPath,
                    pending.area,
                    rules,
                    docCache,
                    pending.preview,
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        importProgress = null,
                        error = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_dir_failed)),
                        importError = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_dir_failed)),
                    )
                }
                Log.e(TAG, "导入目录失败: $error", error)
            }
        }
    }

    /** 取消覆盖导入预览 */
    fun cancelImportPreview() {
        pendingFileImport = null
        pendingImport = null
        _state.update { it.copy(importPreview = null) }
    }

    /** 确认类型冲突替换：删除旧类型后导入 */
    fun confirmImportReplace(context: Context) {
        val filePending = pendingFileImport
        if (filePending != null) {
            pendingFileImport = null
            _state.update { it.copy(importTypeConflict = null) }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    repository.deleteFile(id, filePending.area, filePending.targetPath, recursive = true)
                    val stream = context.contentResolver.openInputStream(filePending.uri)
                        ?: error(appContext.getString(R.string.workspace_error_cannot_read_file))
                    repository.importFile(
                        id = id,
                        area = filePending.area,
                        destinationPath = filePending.destPath,
                        fileName = filePending.fileName,
                        inputStream = stream,
                        overwrite = true,
                    )
                }.onSuccess {
                    refresh()
                }.onFailure { error ->
                    _state.update { it.copy(error = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_file_failed)), importError = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_file_failed))) }
            Log.e(TAG, "导入文件失败: $error", error)
                }
            }
            return
        }
        val pending = pendingImport ?: return
        pendingImport = null
        _state.update { it.copy(importTypeConflict = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // 删除同名文件后按全新导入处理
                val targetPath = if (pending.destPath.isBlank()) {
                    pending.rootName
                } else {
                    "${pending.destPath}/${pending.rootName}"
                }
                repository.deleteFile(id, pending.area, targetPath, recursive = false)
                val rootDoc = DocumentFile.fromTreeUri(context, pending.treeUri)
                    ?: error(appContext.getString(R.string.workspace_error_cannot_access_dir))
                val rules = importRules ?: WorkspaceIgnoreRules(true, "")
                importTree(context, rootDoc, pending.rootName, pending.destPath, pending.area, rules, registerSnapshot = true)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        importProgress = null,
                        error = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_dir_failed)),
                        importError = error.toUserMessage(appContext.getString(R.string.workspace_detail_import_dir_failed)),
                    )
                }
                Log.e(TAG, "导入目录失败: $error", error)
            }
        }
    }

    /** 取消类型冲突替换 */
    fun cancelImportReplace() {
        pendingFileImport = null
        pendingImport = null
        _state.update { it.copy(importTypeConflict = null) }
    }

    /** 关闭导入失败弹窗 */
    fun dismissImportError() {
        _state.update { it.copy(importError = null) }
    }

    // ---- 导入辅助 ----

    /** 占住 SAF 持久化权限并记录原始目录 URI（「导回原处」依赖）；副本导入不调用 */
    private suspend fun registerImportSource(context: Context, treeUri: Uri, rootName: String) {
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
        repository.registerSyncSource(id, rootName, treeUri.toString(), persisted)
        // 立即刷新内存态，避免后续 setImportConflictMode 等用旧 workspace 回写库（回退同步来源）
        loadWorkspace()
    }

    /** 计算副本目录名：目标目录下不存在则用原名，否则 name (1)、name (2)… 依次递增 */
    private suspend fun resolveCopyDirName(
        area: WorkspaceStorageArea,
        destPath: String,
        rootName: String,
    ): String {
        suspend fun exists(name: String): Boolean {
            val path = if (destPath.isBlank()) name else "$destPath/$name"
            return repository.fileExists(id, area, path)
        }
        if (!exists(rootName)) return rootName
        var n = 1
        while (exists("$rootName ($n)")) n++
        return "$rootName ($n)"
    }

    /**
     * 流式导入目录树到本地（保留根目录名）。
     *
     * @param destRootName 落盘根目录名（可能为副本名 "foo (1)"）
     * @param destPath 目标父路径（当前浏览目录）
     * @param registerSnapshot true 时导入完成后写入同步快照（仅 FILES 区且位于根目录时有效，
     * 避免覆盖子目录导入时的错误快照）
     */
    private suspend fun importTree(
        context: Context,
        rootDoc: DocumentFile,
        destRootName: String,
        destPath: String,
        area: WorkspaceStorageArea,
        rules: WorkspaceIgnoreRules,
        registerSnapshot: Boolean,
    ) {
        _state.update { it.copy(importProgress = 0 to -1) }

        // 阶段1：快速浅扫描，只统计文件数（不加载 .gitignore，非常快）
        val subtreeCounts = mutableMapOf<Uri, Int>()
        fun quickScan(doc: DocumentFile): Int {
            val count = if (!doc.isDirectory) {
                1
            } else {
                doc.listFiles()?.sumOf { quickScan(it) } ?: 0
            }
            subtreeCounts[doc.uri] = count
            return count
        }
        val approxTotal = rootDoc.listFiles()?.sumOf { quickScan(it) } ?: 0
        var currentTotal = approxTotal
        var processed = 0

        // 阶段2：边加载 .gitignore 边导入，动态修正总数。
        // 排除规则的 key 统一采用「相对导入根目录」的路径（根为 ""，子目录为 "src"），
        // 与「导回原处」的内部/外部扫描完全一致。
        rules.loadGitignore(rootDoc, "", context.contentResolver)

        suspend fun importDoc(
            doc: DocumentFile,
            parentKey: String,
        ) {
            val name = doc.name ?: return
            val isDir = doc.isDirectory

            if (rules.shouldIgnore(name, parentKey, isDir)) {
                // 被忽略时从总数中扣除该子树文件数，避免进度永远到不了 100%
                val skippedCount = subtreeCounts[doc.uri] ?: if (isDir) quickScan(doc) else 1
                currentTotal -= skippedCount
                if (currentTotal < processed) currentTotal = processed
                _state.update { it.copy(importProgress = processed to currentTotal) }
                return
            }

            if (isDir) {
                val nextKey = if (parentKey.isEmpty()) name else "$parentKey/$name"
                rules.loadGitignore(doc, nextKey, context.contentResolver)
                // 空目录也要导入：目录本身非忽略时，确保目标目录树存在
                // （否则空目录 / 仅含被忽略内容的目录不会随文件写入被创建）
                val dirRel = "$destRootName/$nextKey"
                val dest = if (destPath.isEmpty()) dirRel else "$destPath/$dirRel"
                repository.createDir(id, area, dest)
                doc.listFiles()?.forEach { child ->
                    importDoc(child, nextKey)
                }
            } else {
                context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                    val fileName = doc.name ?: "unnamed"
                    // 内部布局保留根目录名（files/<rootName>/<rel>），与「导回原处」的 syncRoot 约定一致
                    val relativeDest = if (parentKey.isEmpty()) destRootName else "$destRootName/$parentKey"
                    val dest = when {
                        destPath.isEmpty() -> relativeDest
                        else -> "$destPath/$relativeDest"
                    }
                    repository.importFile(
                        id = id,
                        area = area,
                        destinationPath = dest,
                        fileName = fileName,
                        inputStream = stream,
                    )
                }
                processed++
                if (currentTotal < processed) currentTotal = processed
                _state.update { it.copy(importProgress = processed to currentTotal) }
            }
        }

        rootDoc.listFiles()?.forEach { child ->
            importDoc(child, "")
        }
        // 强制对齐到 100%
        if (currentTotal > 0) {
            _state.update { it.copy(importProgress = currentTotal to currentTotal) }
        }

        // 导入完成后注册同步来源并生成初始快照（相对导入根目录的路径 → 大小/hash + 目录指纹）。
        // 直接复用本次导入的 rules（其 .gitignore key 约定与 scanInternal 完全一致），
        // 保证被排除内容不会进入快照。仅 FILES 区、位于根目录时写入（副本导入不写）。
        if (registerSnapshot && area == WorkspaceStorageArea.FILES && destPath.isBlank()) {
            registerImportSource(context, rootDoc.uri, destRootName)
            val filesDir = repository.workspaceFilesDir(id)
            if (filesDir != null) {
                val internal = WorkspaceSyncEngine.scanInternal(filesDir, destRootName, rules)
                repository.writeSyncSnapshot(
                    id,
                    destRootName,
                    SyncSnapshot(
                        syncRoot = destRootName,
                        createdAt = System.currentTimeMillis(),
                        files = internal.files,
                        directories = internal.directories,
                    ),
                )
            }
        }

        _state.update { it.copy(loading = false, importProgress = null) }
        loadWorkspace()
        refresh()
    }

    /**
     * 扫描源目录（SAF）与本地目标，生成导入覆盖的差异预览。
     * A = 本地目标（scanInternal 预计算内容 hash），C = 源（SAF，hash 由 computePreview 现算），
     * invert=true：CREATE/MODIFY 写入本地、DELETE 删除本地。
     * 使用 ACCURATE 模式：内容相同的文件不会进入预览（跳过写入）。
     *
     * 复用同步快照（与「导回原处」的外部扫描一致）：
     * - 预估总数 = 快照文件数（环形进度条变为确定进度）
     * - 目录指纹短路：未变化的子树跳过下钻，重复覆盖上传明显加速
     * 仅当快照的 syncRoot 与本次导入根同名时使用（否则指纹不匹配且预估总数无意义）。
     */
    private suspend fun computeImportDiff(
        context: Context,
        rootDoc: DocumentFile,
        rootName: String,
        destPath: String,
        area: WorkspaceStorageArea,
        rules: WorkspaceIgnoreRules,
        docCache: DocumentCache,
    ): List<SyncPreviewItem> {
        val areaDir = repository.workspaceAreaDir(id, area) ?: error(appContext.getString(R.string.workspace_error_area_dir_unavailable))
        val baseDir = File(areaDir, destPath)
        val snapshot = repository.readSyncSnapshot(id, rootName)

        val source = WorkspaceSyncEngine.scanExternalFast(
            context = context,
            rootDoc = rootDoc,
            rules = rules,
            snapshot = snapshot,
            docCache = docCache,
            onProgress = { done, total, _ ->
                _state.update { it.copy(importProgress = done to total) }
            },
        )
        // 本地侧始终全量扫描并预计算内容 hash（保证「相同内容跳过」判定正确），
        // 进度沿用外部扫描的预估总数，避免被内部扫描的未知总数打回不确定进度
        val local = WorkspaceSyncEngine.scanInternal(
            filesDir = baseDir,
            syncRoot = rootName,
            rules = rules,
            withHash = true,
            onProgress = { done, _ ->
                _state.update { st ->
                    val total = st.importProgress?.second ?: -1
                    st.copy(importProgress = done to total)
                }
            },
        )
        return WorkspaceSyncEngine.computePreview(
            context = context,
            rootDoc = rootDoc,
            internal = local.files,
            external = source.files,
            internalDirs = local.emptyDirs,
            externalDirs = source.emptyDirs,
            mode = SyncCheckMode.ACCURATE,
            docCache = docCache,
            onHashProgress = { done, total ->
                _state.update { it.copy(importProgress = done to total) }
            },
            invert = true,
        )
    }

    /** 执行导入覆盖：把差异应用到本地，完成后刷新快照（仅 FILES 区且位于根目录时写入） */
    private suspend fun executeImportOverwrite(
        context: Context,
        rootDoc: DocumentFile,
        rootName: String,
        destPath: String,
        area: WorkspaceStorageArea,
        rules: WorkspaceIgnoreRules,
        docCache: DocumentCache,
        preview: List<SyncPreviewItem>,
    ) {
        Log.d(TAG, "executeImportOverwrite: root=$rootName area=$area dest=$destPath preview=${preview.size}")
        if (preview.isNotEmpty()) {
            val areaDir = repository.workspaceAreaDir(id, area) ?: error(appContext.getString(R.string.workspace_error_area_dir_unavailable))
            val localRoot = File(areaDir, if (destPath.isBlank()) rootName else "$destPath/$rootName")
            WorkspaceSyncEngine.executeImportToLocal(
                context = context,
                rootDoc = rootDoc,
                preview = preview,
                localRoot = localRoot,
                docCache = docCache,
                onProgress = { done, total ->
                    _state.update { it.copy(importProgress = done to total) }
                },
            )
        }

        if (area == WorkspaceStorageArea.FILES && destPath.isBlank()) {
            registerImportSource(context, rootDoc.uri, rootName)
            val filesDir = repository.workspaceFilesDir(id)
            if (filesDir != null) {
                val internal = WorkspaceSyncEngine.scanInternal(filesDir, rootName, rules)
                repository.writeSyncSnapshot(
                    id,
                    rootName,
                    SyncSnapshot(
                        syncRoot = rootName,
                        createdAt = System.currentTimeMillis(),
                        files = internal.files,
                        directories = internal.directories,
                    ),
                )
            }
        }

        _state.update { it.copy(loading = false, importProgress = null) }
        loadWorkspace()
        refresh()
    }

    fun setEnableGitignore(enabled: Boolean) {
        viewModelScope.launch {
            val workspace = repository.getById(id) ?: return@launch
            repository.updateWorkspace(
                workspace.copy(enableGitignore = enabled, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }
    
    fun setCustomIgnorePatterns(patterns: String) {
        viewModelScope.launch {
            val workspace = repository.getById(id) ?: return@launch
            // 幂等：值与库中一致时跳过（防抖推送初次携带已加载的初始值时避免无效写盘）
            if (workspace.customIgnorePatterns == patterns) return@launch
            repository.updateWorkspace(
                workspace.copy(customIgnorePatterns = patterns, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }

    /** 持久化同步检查模式（"fast" 快速 / "accurate" 完整），下次同步时读取生效 */
    fun setSyncCheckMode(mode: SyncCheckMode) {
        viewModelScope.launch {
            val workspace = repository.getById(id) ?: return@launch
            repository.updateWorkspace(
                workspace.copy(syncCheckMode = mode.value, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }

    /** 持久化导入冲突模式（"rename" 创建副本 / "overwrite" 覆盖），下次导入时读取生效 */
    fun setImportConflictMode(mode: ImportConflictMode) {
        viewModelScope.launch {
            val workspace = repository.getById(id) ?: return@launch
            repository.updateWorkspace(
                workspace.copy(importConflictMode = mode.value, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }

    // ---- 「导回原处」：扫描 → 预览 → 确认 → 执行 ----

    /**
     * 开始同步链路：扫描内部 + 外部 → 对比生成预览。
     *
     * 整个链路包进 runCatching（异常隔离）：扫描 / 对比 / hash 各阶段异常分别包装为
     * [SyncStageException] 并翻译成用户可读文案；单文件级异常已在引擎内跳过并记录日志，
     * 不会让整个协程崩溃。
     *
     * 若原始目录权限失效或未导入目录，置 [WorkspaceDetailState.syncSourceLostFor]，
     * 由 UI 层引导重新选择目录。
     */
    fun prepareSyncPreview(
        context: Context,
        syncRoot: String,
        scope: String = "",
        scopeIsFile: Boolean = false,
    ) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val workspace = repository.getById(id) ?: return@launch
            val treeUri = repository.syncSourceUri(id, syncRoot)?.let { Uri.parse(it) }
            val rootDoc = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
            if (rootDoc == null || !rootDoc.canWrite()) {
                _state.update {
                    it.copy(
                        syncPhase = SyncPhase.IDLE,
                        syncPreview = null,
                        syncProgress = null,
                        syncError = null,
                        syncSourceLostFor = syncRoot,
                    )
                }
                return@launch
            }

            // 进入扫描阶段（UI 显示「正在扫描变更…」弹窗，可取消）
            _state.update {
                it.copy(
                    syncPhase = SyncPhase.SCANNING,
                    syncPreview = null,
                    syncProgress = null,
                    syncError = null,
                    syncSourceLostFor = null,
                )
            }

            // 排除规则复用：与导入完全一致（.gitignore 从外部目录加载 + 自定义模式）
            val mode = SyncCheckMode.from(workspace.syncCheckMode)
            val rules = WorkspaceIgnoreRules(workspace.enableGitignore, workspace.customIgnorePatterns)
            val docCache = DocumentCache()
            syncRules = rules
            syncDocCache = docCache

            runCatching {
                val snapshot = repository.readSyncSnapshot(id, syncRoot)

                // ---- 外部扫描 ETA 状态：累计速率 + 每秒 ticker 刷新倒计时 ----
                val scanStartMs = SystemClock.elapsedRealtime()
                var lastDone = 0
                var scanTotal = WorkspaceSyncEngine.UNKNOWN_TOTAL
                var scanEstimated = true
                // 每秒重算 ETA 并重新上报，让「预估 x:xx」随时间流逝动态更新；
                // 生命周期与外部扫描严格一致（外部扫描结束即取消，避免覆盖后续内部扫描/HASH 的进度）
                val etaTicker = launch {
                    while (isActive) {
                        delay(1_000)
                        val eta = computeEta(scanStartMs, lastDone, scanTotal)
                        emitSyncProgress(SyncProgressStage.SCAN_EXTERNAL, lastDone, scanTotal, scanEstimated, eta)
                    }
                }
                // C：外部当前状态（目录指纹短路 + 快照复用，文件 hash 懒加载；DocumentFile 进缓存）
                val external = try {
                    stageCatching(SyncStage.SCAN_EXTERNAL) {
                        WorkspaceSyncEngine.scanExternalFast(
                            context = context,
                            rootDoc = rootDoc,
                            rules = rules,
                            snapshot = snapshot,
                            docCache = docCache,
                            scope = scope,
                            onProgress = { done, total, totalEstimated ->
                                lastDone = done
                                scanTotal = total
                                scanEstimated = totalEstimated
                                val eta = computeEta(scanStartMs, done, total)
                                emitSyncProgress(SyncProgressStage.SCAN_EXTERNAL, done, total, totalEstimated, eta)
                            },
                        )
                    }
                } finally {
                    etaTicker.cancel()
                }
                // A：内部当前状态（排除 .rikkahub 等元数据目录与被排除内容；快速模式不 eager 算 hash）
                val filesDir = repository.workspaceFilesDir(id)
                val internal = if (filesDir != null) {
                    stageCatching(SyncStage.SCAN_INTERNAL) {
                        WorkspaceSyncEngine.scanInternal(
                            filesDir = filesDir,
                            syncRoot = syncRoot,
                            rules = rules,
                            withHash = mode == SyncCheckMode.ACCURATE,
                            scope = scope,
                            onProgress = { done, total ->
                                emitSyncProgress(SyncProgressStage.SCAN_INTERNAL, done, total)
                            },
                        )
                    }
                } else {
                    InternalScanResult(emptyMap(), emptyMap())
                }
                // A vs C 直接对比生成预览（快照不参与操作类型判断；空目录参与 CREATE_DIR/DELETE_DIR）
                val preview = stageCatching(SyncStage.COMPARE) {
                    WorkspaceSyncEngine.computePreview(
                        context = context,
                        rootDoc = rootDoc,
                        internal = internal.files,
                        external = external.files,
                        internalDirs = internal.emptyDirs,
                        externalDirs = external.emptyDirs,
                        mode = mode,
                        docCache = docCache,
                        onHashProgress = { done, total ->
                            emitSyncProgress(SyncProgressStage.HASH, done, total)
                        },
                    )
                }
                // 单文件范围不做镜像删除：目标文件在本地必然存在，若因被排除规则忽略导致
                // 内部侧为空，也不应把外部同路径文件删掉（本地并非有意删除它）
                val effectivePreview = if (scopeIsFile) {
                    preview.filterNot {
                        it.type == SyncPreviewType.DELETE || it.type == SyncPreviewType.DELETE_DIR
                    }
                } else {
                    preview
                }
                SyncPreviewResult(syncRoot, effectivePreview)
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        activeSyncRoot = result.syncRoot,
                        activeSyncScope = scope,
                        syncPreview = result.preview,
                        syncPhase = SyncPhase.PREVIEW,
                        syncProgress = null,
                        syncError = null,
                        syncSourceLostFor = null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        syncPhase = SyncPhase.ERROR,
                        syncPreview = null,
                        syncProgress = null,
                        syncError = error.toSyncErrorMessage(),
                    )
                }
            }
        }
    }

    /** 用户确认后真正执行同步写入，完成后更新快照 */
    fun confirmSync(context: Context, syncRoot: String, scope: String = "") {
        val preview = state.value.syncPreview ?: return
        if (preview.isEmpty()) {
            _state.update { it.copy(syncPhase = SyncPhase.IDLE, syncPreview = null) }
            return
        }
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val workspace = repository.getById(id) ?: return@launch
            val rootDoc = repository.syncSourceUri(id, syncRoot)?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
            if (rootDoc == null || !rootDoc.canWrite()) {
                _state.update {
                    it.copy(
                        syncSourceLostFor = syncRoot,
                        syncPhase = SyncPhase.IDLE,
                        syncPreview = null,
                        syncProgress = null,
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    syncPhase = SyncPhase.EXECUTING,
                    syncProgress = SyncProgress(SyncProgressStage.EXECUTE, 0, preview.size),
                    syncPreview = null,
                    syncError = null,
                )
            }
            runCatching {
                // 复用预览阶段的排除规则与 DocumentFile 缓存（SAF 定位降为 O(1)）；
                // 规则丢失（进程重建等）时兜底重建并加载 .gitignore
                val rules = syncRules
                    ?: WorkspaceIgnoreRules(workspace.enableGitignore, workspace.customIgnorePatterns).also {
                        WorkspaceSyncEngine.loadGitignoreTree(context, rootDoc, it, syncDocCache)
                    }
                val filesDir = repository.workspaceFilesDir(id) ?: error(appContext.getString(R.string.workspace_dir_export_files_dir_unavailable))

                // 执行写入（先删后写，复用缓存定位文件/目录，带进度回调）
                stageCatching(SyncStage.EXECUTE) {
                    WorkspaceSyncEngine.execute(
                        context = context,
                        rootDoc = rootDoc,
                        preview = preview,
                        syncRoot = syncRoot,
                        repository = repository,
                        id = id,
                        docCache = syncDocCache,
                        onProgress = { done, total ->
                            emitSyncProgress(SyncProgressStage.EXECUTE, done, total)
                        },
                    )
                }

                // 写入完成后，把当前内部状态 A 重新写入快照（含目录指纹，供下次指纹短路）。
                // 子范围同步不更新快照：快照代表整棵 syncRoot 的同步状态，
                // 局部回写后仍由下一次整目录同步负责重新比对。
                if (scope.isEmpty()) {
                    val internal = stageCatching(SyncStage.SCAN_INTERNAL) {
                        WorkspaceSyncEngine.scanInternal(filesDir, syncRoot, rules)
                    }
                    repository.writeSyncSnapshot(
                        id,
                        syncRoot,
                        SyncSnapshot(
                            syncRoot = syncRoot,
                            createdAt = System.currentTimeMillis(),
                            files = internal.files,
                            directories = internal.directories,
                        ),
                    )
                }
            }.onSuccess {
                _state.update {
                    it.copy(syncPhase = SyncPhase.IDLE, syncProgress = null, syncPreview = null, syncError = null)
                }
                refresh()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        syncPhase = SyncPhase.ERROR,
                        syncProgress = null,
                        syncError = error.toSyncErrorMessage(),
                    )
                }
            }
        }
    }

    /** 取消当前同步流程（扫描阶段弹窗的「取消」；预览阶段等同关闭弹窗），回到 IDLE */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        _state.update {
            it.copy(
                syncPhase = SyncPhase.IDLE,
                syncPreview = null,
                syncProgress = null,
                syncError = null,
                activeSyncRoot = null,
                activeSyncScope = "",
            )
        }
    }

    fun dismissSyncSourceLost() {
        _state.update { it.copy(syncSourceLostFor = null) }
    }

    fun clearSyncError() {
        _state.update { it.copy(syncError = null, syncPhase = SyncPhase.IDLE) }
    }

    // ---- 同步链路内部辅助 ----

    /** 进度回调统一入口：写入 [WorkspaceDetailState.syncProgress] */
    private fun emitSyncProgress(
        stage: SyncProgressStage,
        done: Int,
        total: Int,
        totalEstimated: Boolean = false,
        etaSeconds: Long? = null,
    ) {
        _state.update { it.copy(syncProgress = SyncProgress(stage, done, total, totalEstimated, etaSeconds)) }
    }

    /**
     * 外部扫描剩余时间估计（秒）：按累计平均速率线性外推。
     * - done 过小（< [MIN_ETA_DONE]）或速率为 0 → 无法估算，返回 null（UI 隐藏 ETA）
     * - 剩余 ≤ 0（估计滞后或已扫完）→ 返回 null
     * - 返回原始秒数，由 UI 负责格式化与「>59:59」上限
     */
    private fun computeEta(startMs: Long, done: Int, total: Int): Long? {
        if (done < MIN_ETA_DONE) return null
        val remaining = total - done
        if (remaining <= 0) return null
        val elapsedSec = (SystemClock.elapsedRealtime() - startMs) / 1000.0
        if (elapsedSec <= 0.0) return null
        return (remaining * elapsedSec / done).toLong()
    }

    companion object {
        /** done 达到该值后才开始估算 ETA（刚起步速率不可靠） */
        private const val MIN_ETA_DONE = 5

        private const val TAG = "WorkspaceDetailVM"
    }

    /** 阶段隔离：非取消异常包装为 [SyncStageException]，供上层翻译成用户可读文案 */
    private suspend fun <T> stageCatching(stage: SyncStage, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SyncStageException(stage, e)
        }

    /** 展开异常链，取出底层同步引擎失败（若有） */
    private fun Throwable.findSyncEngineError(): WorkspaceSyncException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is WorkspaceSyncException) return current
            current = current.cause
        }
        return null
    }

    /** 把同步引擎底层失败（原因 + 相对路径）翻译成本地化文案 */
    private fun WorkspaceSyncException.toLocalizedMessage(): String = when (reason) {
        WorkspaceSyncFailureReason.CREATE_DIR_FAILED ->
            appContext.getString(R.string.workspace_sync_error_create_dir_failed, path)
        WorkspaceSyncFailureReason.CREATE_FILE_FAILED ->
            appContext.getString(R.string.workspace_sync_error_create_file_failed, path)
        WorkspaceSyncFailureReason.OPEN_STREAM_FAILED ->
            appContext.getString(R.string.workspace_sync_error_open_stream_failed, path)
        WorkspaceSyncFailureReason.SOURCE_MISSING ->
            appContext.getString(R.string.workspace_sync_error_source_missing, path)
        WorkspaceSyncFailureReason.READ_SOURCE_FAILED ->
            appContext.getString(R.string.workspace_sync_error_read_source_failed, path)
    }

    /** 导入等非同步链路的统一用户可读文案：底层引擎错误 > message > 场景兜底 */
    private fun Throwable.toUserMessage(fallback: String): String =
        findSyncEngineError()?.toLocalizedMessage() ?: message ?: fallback

    /** 把同步链路异常翻译成用户可读文案 */
    private fun Throwable.toSyncErrorMessage(): String {
        val detail = findSyncEngineError()?.toLocalizedMessage()
            ?: message
            ?: appContext.getString(R.string.workspace_unknown_error)
        return when (this) {
            is SyncStageException -> when (stage) {
                SyncStage.SCAN_INTERNAL ->
                    appContext.getString(R.string.workspace_sync_stage_scan_internal, detail)
                SyncStage.SCAN_EXTERNAL ->
                    appContext.getString(R.string.workspace_sync_stage_scan_external, detail)
                SyncStage.COMPARE ->
                    appContext.getString(R.string.workspace_sync_stage_compare, detail)
                SyncStage.EXECUTE ->
                    appContext.getString(R.string.workspace_sync_stage_execute, detail)
            }
            else -> findSyncEngineError()?.toLocalizedMessage()
                ?: message
                ?: appContext.getString(R.string.workspace_sync_failed_generic)
        }
    }

    /** 权限失效后用户重新选择的目录：占住权限、持久化 URI，并自动生成新预览 */
    fun onSyncSourcePicked(context: Context, treeUri: Uri, syncRoot: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
            repository.registerSyncSource(id, syncRoot, treeUri.toString(), persisted)
            _state.update { it.copy(syncSourceLostFor = null) }
            loadWorkspace()
            prepareSyncPreview(context, syncRoot)
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File = repository.resolveFile(id, area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    // ---- 目录导出到任意 SAF 位置（仅复制，不做镜像删除） ----

    /**
     * 用户选定目标文件夹后直接开始导出：无确认弹窗；复制期间以进度弹窗反馈，
     * 完成/失败以一次性 toast 提示。
     * - 同名处理：目标文件夹内无同名建原名目录，有同名递增建副本（绝不覆盖）
     * - 排除规则与导入一致：.gitignore 从源目录树加载 + 工作区自定义排除模式
     */
    fun exportDirectory(context: Context, entry: WorkspaceFileEntry, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // 弹窗只在真正复制文件时出现（只含确定进度，无“扫描”阶段）
            _state.update { it.copy(dirExportNotice = null) }
            runCatching {
                val workspace = repository.getById(id)
                    ?: error(appContext.getString(R.string.workspace_dir_export_workspace_missing))
                val filesDir = repository.workspaceFilesDir(id)
                    ?: error(appContext.getString(R.string.workspace_dir_export_files_dir_unavailable))
                val rules = WorkspaceIgnoreRules(
                    workspace.enableGitignore,
                    workspace.customIgnorePatterns,
                )
                WorkspaceSyncEngine.loadLocalGitignoreTree(filesDir, entry.path, rules)
                val internal = WorkspaceSyncEngine.scanInternal(
                    filesDir = filesDir,
                    syncRoot = entry.path,
                    rules = rules,
                    withHash = false,
                )
                val items = buildList {
                    internal.files.keys.sorted().forEach { path ->
                        add(SyncPreviewItem(type = SyncPreviewType.CREATE, path = path))
                    }
                    internal.emptyDirs.sorted().forEach { path ->
                        add(SyncPreviewItem(type = SyncPreviewType.CREATE_DIR, path = path))
                    }
                }
                val container = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error(appContext.getString(R.string.workspace_dir_export_dest_unreachable))
                val finalName = resolveSafCopyName(container, entry.name)
                val destDir = container.createDirectory(finalName)
                    ?: error(
                        appContext.getString(
                            R.string.workspace_dir_export_dest_create_failed,
                            finalName,
                        )
                    )
                val total = items.size
                if (total > 0) {
                    _state.update {
                        it.copy(
                            dirExporting = true,
                            exportProgress = ExportProgress(done = 0, total = total),
                        )
                    }
                    WorkspaceSyncEngine.execute(
                        context = context,
                        rootDoc = destDir,
                        preview = items,
                        syncRoot = entry.path,
                        repository = repository,
                        id = id,
                        onProgress = { done, doneTotal ->
                            _state.update { it.copy(exportProgress = ExportProgress(done = done, total = doneTotal)) }
                        },
                    )
                }
                finalName
            }.onSuccess { finalName ->
                _state.update {
                    it.copy(
                        dirExporting = false,
                        exportProgress = null,
                        dirExportNotice = appContext.getString(
                            R.string.workspace_dir_export_success,
                            finalName,
                        ),
                        dirExportNoticeError = false,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        dirExporting = false,
                        exportProgress = null,
                        dirExportNotice = error.toUserMessage(appContext.getString(R.string.workspace_dir_export_failed)),
                        dirExportNoticeError = true,
                    )
                }
                Log.e(TAG, "目录导出失败: $error", error)
            }
        }
    }

    /** 消费目录导出结果提示（一次性 toast） */
    fun clearDirExportNotice() {
        _state.update { it.copy(dirExportNotice = null) }
    }

    /** SAF 容器下查找不冲突的名称：原名 / name (1) / name (2)…（与导入副本语义一致） */
    private fun resolveSafCopyName(container: DocumentFile, name: String): String {
        if (container.findFile(name) == null) return name
        var n = 1
        while (container.findFile("$name ($n)") != null) n++
        return "$name ($n)"
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = repository.getById(id) ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            // 已注册同步来源的目录名集合（用于文件卡片上「同步回原目录」入口的显隐判断）
            var syncRoots = if (workspace != null) repository.listSyncRoots(id) else emptySet()
            // 极端兜底：来源 map 与旧快照均无法恢复时，从持久化的来源 URI 恢复根目录名，
            // 保证同步入口不因快照文件丢失而消失
            if (syncRoots.isEmpty() && workspace?.sourceTreeUri?.isNotBlank() == true) {
                recoverSyncRootFromSource(workspace.sourceTreeUri)?.let { syncRoots = setOf(it) }
            }
            _state.update { it.copy(workspace = workspace, syncRoots = syncRoots) }
            // 首次加载到工作区时初始化排除模式输入框；此后不再随 flow 回流覆盖（防止打断输入）
            if (!customIgnoreInitialized && workspace != null) {
                customIgnoreState.setTextAndPlaceCursorAtEnd(workspace.customIgnorePatterns)
                customIgnoreInitialized = true
            }
        }
    }

    /** 从持久化的 SAF tree URI 恢复根目录名（快照文件缺失时的兜底） */
    private fun recoverSyncRootFromSource(treeUri: String): String? = runCatching {
        DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri))?.name
    }.getOrNull()
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val importProgress: Pair<Int, Int>? = null,
    // 导入失败弹窗（区别于 error 卡片：任何导入流程异常都会弹窗，不让错误被静默吞掉）
    val importError: String? = null,
    // 覆盖导入预览（非空 = 展示预览弹窗，等待确认执行）
    val importPreview: List<SyncPreviewItem>? = null,
    // 导入类型冲突（文件 vs 目录同名，等待选择替换/取消）
    val importTypeConflict: ImportTypeConflictInfo? = null,
    // 「导回原处」同步状态（显式状态机）
    val syncPhase: SyncPhase = SyncPhase.IDLE,
    val syncPreview: List<SyncPreviewItem>? = null,
    /** 已注册同步来源的目录名集合（FILES 根目录下这些目录卡片显示「同步回原目录」入口） */
    val syncRoots: Set<String> = emptySet(),
    /** 当前同步弹窗针对的目录（SCANNING/PREVIEW/EXECUTING 期间非空） */
    val activeSyncRoot: String? = null,
    /** 当前同步弹窗针对 syncRoot 内的子范围（空 = 整棵 syncRoot 完整同步） */
    val activeSyncScope: String = "",
    val syncProgress: SyncProgress? = null,
    val syncError: String? = null,
    /** 原始目录权限失效的目录名（非空 = 显示重新选择目录弹窗） */
    val syncSourceLostFor: String? = null,
    /** 目录导出复制进行中（显示导出进度弹窗） */
    val dirExporting: Boolean = false,
    /** 目录导出复制进度（done / total） */
    val exportProgress: ExportProgress? = null,
    /** 目录导出结果提示（一次性 toast） */
    val dirExportNotice: String? = null,
    /** 目录导出结果是否为错误 */
    val dirExportNoticeError: Boolean = false,
)

/** 目录导出执行进度 */
data class ExportProgress(
    val done: Int,
    val total: Int,
)

/** 「导回原处」显式状态机 */
enum class SyncPhase {
    /** 无事发生 */
    IDLE,

    /** 正在扫描内部 + 外部目录（或完整模式校验内容） */
    SCANNING,

    /** 扫描完成，展示结果列表，等待用户确认 */
    PREVIEW,

    /** 用户确认后正在写入外部目录 */
    EXECUTING,

    /** 任何阶段出错，[WorkspaceDetailState.syncError] 携带可读错误信息 */
    ERROR,
}

/**
 * 同步进度（阶段 + 完成数 + 总数；total 为负表示总数未知，UI 显示不确定进度）。
 * - [totalEstimated]：仅外部扫描阶段有意义。true=总数仍用快照估计值（UI 文案「预估」）；
 *   false=已发生偏差修正（UI 文案「更新」）
 * - [etaSeconds]：外部扫描阶段的剩余时间估计（秒）；null=无法估算/不显示
 */
data class SyncProgress(
    val stage: SyncProgressStage,
    val done: Int,
    val total: Int,
    val totalEstimated: Boolean = false,
    val etaSeconds: Long? = null,
)

/** 进度阶段：外部扫描 / 内部扫描 / 内容校验 / 执行写入 */
enum class SyncProgressStage { SCAN_EXTERNAL, SCAN_INTERNAL, HASH, EXECUTE }

/** 预览阶段内部结果（syncRoot + 预览列表） */
data class SyncPreviewResult(
    val syncRoot: String,
    val preview: List<SyncPreviewItem>,
)

/** 待确认的目录导入（覆盖预览 / 类型冲突替换） */
data class PendingImport(
    val treeUri: Uri,
    val rootName: String,
    val destPath: String,
    val area: WorkspaceStorageArea,
    val preview: List<SyncPreviewItem>,
    /** true = 类型冲突替换（目标为同名文件），确认后删除旧文件再导入 */
    val replace: Boolean,
)

/** 待确认的单文件导入（覆盖预览 / 类型冲突替换） */
data class PendingFileImport(
    val uri: Uri,
    val fileName: String,
    val targetPath: String,
    val area: WorkspaceStorageArea,
    val destPath: String,
    /** true = 类型冲突替换（目标为同名目录），确认后删除旧目录再写入 */
    val replace: Boolean,
)

/** 导入类型冲突信息（用于替换/取消弹窗展示） */
data class ImportTypeConflictInfo(
    val name: String,
    /** true = 导入目录遇到同名文件；false = 导入文件遇到同名目录 */
    val importingDirectory: Boolean,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}