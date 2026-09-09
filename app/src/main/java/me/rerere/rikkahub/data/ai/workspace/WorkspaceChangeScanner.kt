package me.rerere.rikkahub.data.ai.workspace

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作区文件变更后台扫描器。
 *
 * 把 workspace_shell 的「变更检测」(find) 从工具调用关键路径上抽离：
 * 前台只在命令执行前记录一个 cutoff 时间戳并 [poke] 本扫描器（非阻塞），
 * 真正耗时的全树 find 在后台协程执行，与模型的「输出回复时间」重叠。
 *
 * 协议（与用户确认的设计）：
 * - 前台每次执行命令前记录 cutoff = 当前时间（早于命令真正开始写入文件），命令成功后 [poke]。
 * - 扫描器对每个 (conversationId, root) 取**整个窗口的最小 cutoff** 作为基准（跨 burst 持久，
 *   见 [ScanWindowState.windowStart]），每次扫描都用它重扫全窗口：
 *   `find /workspace -type f -newermt "@秒"`（含边界秒，GNU find）找出该时间之后写入的文件；
 *   因为 cutoff 早于对应命令，所以该命令产生的变更必然 >= cutoff，不会漏。
 * - **快照替换语义**：find 只返回"当前存在 且 mtime >= 窗口基准"的文件，所以被删除/改名的
 *   文件会自动从下一次快照消失；每批结果整体替换而不是累积，避免展示中途生成的、
 *   后来不存在的临时文件。发布前有 [LIVE_SETTLE_MS] 稳定等待，期间又有新命令则继续扫描，
 *   只在批次稳定后一次性整体替换实时列表。
 * - 生成结束后调用 [consume] 取回最终快照，由 ChatService 回填到对话消息；
 *   consume 无论是否超时都会闭合窗口（递增 [ScanWindowState.windowEpoch] 并清残留状态），
 *   防止下一轮窗口混入上一回合的变更。
 * - **忽略过滤与剪枝**：find 结果交给 [WorkspaceChangeIgnoreFilter] 过滤，
 *   被 .gitignore / 自定义规则忽略的路径不进入实时列表与回填 metadata；过滤器同时产出
 *   「find 剪枝 glob」，让 find 在遍历阶段就跳过被忽略的目录（如 build/、node_modules/），
 *   缓解本地编译产物拖慢全树扫描的问题。剪枝为保守策略（只剪「目录级命中且无否定」的规则），
 *   宁可少剪不错剪，漏剪部分由过滤兜底。
 *
 * 追踪键为 (conversationId, root)：同一工作区被多个对话共享时，各对话只拿到自己
 * 修改的文件，互不串扰。
 */
class WorkspaceChangeScanner(
    private val workspaceRepository: WorkspaceRepository,
    private val appScope: AppScope,
    private val ignoreFilter: WorkspaceChangeIgnoreFilter,
) {
    /** 追踪键：对话 + 工作区，保证同一工作区在多对话间隔离 */
    data class ScanKey(
        val conversationId: String,
        val root: String,
    )

    companion object {
        private const val TAG = "WorkspaceChangeScanner"
        private const val SCAN_OUT_FILE = "/tmp/.ws_scan_out"
        private const val GI_OUT_FILE = "/tmp/.ws_gi_out"
        // 慢设备上全树 find 可达 4~7s, 加上 executeCommand 起 proot 进程的开销单次可能 8~10s,
        // 60s 容易在极端情况下误杀, 放宽到 120s
        private const val SCAN_TIMEOUT_MS = 120_000L
        // consume 在生成 flow 的 onCompletion 里调用, 不在 UI 关键路径上,
        // 放宽到 20s 给最后一次 find 充足时间, 不影响消息完成体验
        private const val CONSUME_WAIT_TIMEOUT_MS = 20_000L
        private const val MAX_SCAN_OUT_BYTES = 8 * 1024 * 1024

        /**
         * 实时发布前的稳定等待：期间又有新命令则继续扫描，避免把中途生成、
         * 后来不存在的临时文件闪现在实时药丸里；只在批次稳定后一次性整体替换。
         */
        private const val LIVE_SETTLE_MS = 250L
    }

    /** 单窗口扫描状态（独立类以便单测注入 find 输出，见 [ScanWindowState.scanWindow]） */
    private val window = ScanWindowState()

    private val _liveChanges = MutableStateFlow<Map<ScanKey, List<String>>>(emptyMap())

    /**
     * 每个追踪键**当前已检测到**的变更路径（最新完整快照）。
     * 后台 find 每完成一个稳定批次就整体替换一次，
     * 供 UI 实时展示（生成期间即可看到药丸，无需等整轮结束）。
     */
    val liveChanges: StateFlow<Map<ScanKey, List<String>>> = _liveChanges.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        appScope.launch(Dispatchers.IO) {
            while (isActive) {
                wake.receive() // 等待 poke
                while (isActive) {
                    val key = window.pendingMin.keys.firstOrNull() ?: break
                    scanBurst(key)
                }
            }
        }
    }

    /**
     * 前台命令成功执行完毕后调用（非阻塞，不挂起调用方）。
     *
     * @param conversationId 当前对话 id
     * @param root 工作区 id
     * @param refCutoffMillis 命令执行前记录的毫秒时间戳（早于命令写入任何文件）
     */
    fun poke(conversationId: String, root: String, refCutoffMillis: Long) {
        val key = ScanKey(conversationId, root)
        window.pendingMin.compute(key) { _, existing ->
            minOf(existing ?: Long.MAX_VALUE, refCutoffMillis)
        }
        wake.trySend(Unit)
    }

    /**
     * 生成结束后调用：等待当前批次稳定（扫描完成且期间没有新命令），
     * 返回该 (conversationId, root) 本轮窗口的最终快照（自上次 consume 以来的变更，
     * 已剔除中途创建又被删除的文件）。
     *
     * 无论等待是否超时，都会**闭合窗口**：先递增 [ScanWindowState.windowEpoch] 使任何
     * 在途扫描的发布失效，再清掉残留状态，防止下一轮窗口混入上一回合的变更。
     */
    suspend fun consume(conversationId: String, root: String): List<String> {
        val key = ScanKey(conversationId, root)
        val waitStart = System.currentTimeMillis()
        val timedOut = withTimeoutOrNull(CONSUME_WAIT_TIMEOUT_MS) {
            while (window.pendingMin.containsKey(key) || key in window.scanning) {
                delay(50)
            }
            false
        } ?: true
        // 先闭合窗口再取结果：在途 scanBurst 看到 epoch 变化后不会发布过期快照
        window.windowEpoch.compute(key) { _, e -> (e ?: 0L) + 1 }
        val result = window.accumulated.remove(key)?.toList() ?: emptyList()
        window.pendingMin.remove(key)
        window.windowStart.remove(key)
        // 窗口关闭: 剪枝知识可能过期（.gitignore 可能被删除/改动），下轮窗口首扫做一次全量刷新
        ignoreFilter.markDiscoveryDirty(key.root)
        // 生成结束: 实时流让位给回填到消息 metadata 的持久展示
        _liveChanges.update { it - key }
        Log.i(
            TAG,
            "consume key=$key changes=${result.size} timedOut=$timedOut waitMs=${System.currentTimeMillis() - waitStart}"
        )
        return result
    }

    private suspend fun scanBurst(key: ScanKey) {
        val epoch = window.windowEpoch.getOrDefault(key, 0L)
        val burst = window.scanWindow(
            key = key,
            runFind = { cutoff -> runFindOnce(key, cutoff) },
            settleMillis = LIVE_SETTLE_MS,
        ) ?: return // 无新命令 / find 失败 / 窗口已闭合: 不发布, 保留上一快照
        if (window.windowEpoch.getOrDefault(key, 0L) != epoch) return
        // 快照替换: 空快照 = 全部变更文件已被删除 → 清空列表
        if (burst.isEmpty()) {
            window.accumulated.remove(key)
            _liveChanges.update { it - key }
        } else {
            window.accumulated[key] = LinkedHashSet(burst)
            _liveChanges.update { it + (key to burst) }
        }
    }

    /** 执行一次 find，返回该基准之后仍存在、且未被忽略规则过滤的文件；失败返回 null（调用方保留上一快照） */
    private suspend fun runFindOnce(key: ScanKey, cutoffMillis: Long): List<String>? {
        val start = System.currentTimeMillis()
        // 剪枝知识过期/未建立时，本轮顺带做一次全树 .gitignore 发现（此时 find 不剪枝，保证结果正确）
        val discovery = ignoreFilter.needsDiscovery(key.root)
        val pruneGlobs = ignoreFilter.pruneGlobs(key.root)
        val result = runCatching {
            workspaceRepository.executeCommand(
                id = key.root,
                command = buildScanCommand(cutoffMillis, discovery, pruneGlobs),
                timeoutMillis = SCAN_TIMEOUT_MS,
            )
        }.getOrNull()
        // 命令失败 (异常或非零退出码) 时输出文件可能缺失/是旧内容, 不能当作"无变更"
        if (result == null || result.exitCode != 0 || result.timedOut) {
            Log.w(TAG, "find failed key=$key cutoff=$cutoffMillis exit=${result?.exitCode} timedOut=${result?.timedOut}")
            return null
        }
        // 变更路径与 .gitignore 清单分别写入 rootfs /tmp 的 out 文件, Java 侧直读, 不再额外开 shell
        val rawPaths = readOutFile(key.root, SCAN_OUT_FILE)
        val giPaths = if (discovery) readOutFile(key.root, GI_OUT_FILE) else null
        // 更新忽略知识（发现结果 + 本轮被修改的 .gitignore）并过滤
        val filtered = ignoreFilter.recordScan(key.root, giPaths, rawPaths)
        Log.i(
            TAG,
            "find key=$key cutoff=$cutoffMillis raw=${rawPaths.size} kept=${filtered.size} " +
                "discovery=$discovery prune=${pruneGlobs.size} tookMs=${System.currentTimeMillis() - start}"
        )
        return filtered
    }

    /**
     * 组装一次扫描的 shell 命令。
     *
     * @param discovery true 时先做一次全树 .gitignore 发现（此时不剪枝，保证发现覆盖整树）
     * @param pruneGlobs 保守的剪枝 glob（绝对路径，如 /workspace/rikkahub/build）；为空则不剪枝
     */
    private fun buildScanCommand(cutoffMillis: Long, discovery: Boolean, pruneGlobs: List<String>): String = buildString {
        // 仅在 /tmp 缺失时才创建（避免无脑 mkdir）
        append("[ -d /tmp ] || mkdir -p /tmp\n")
        if (discovery) {
            // 全树 .gitignore 清单（不剪枝，仅文件名匹配，不 stat 全部文件）
            append("find /workspace -type f -name .gitignore -print")
            append(" > ${GI_OUT_FILE.shellQuote()} 2>/dev/null\n")
        }
        // 主扫描：-newermt "@秒" 在 GNU find 中含边界秒（同一秒内写入的文件也会命中），
        // 且 cutoff 早于命令写入时间, 因此不会漏掉同秒晚于 cutoff 的变更。
        append("find /workspace ")
        if (pruneGlobs.isNotEmpty()) {
            // 目录级忽略剪枝：整棵跳过被忽略目录（保守策略，见 WorkspaceChangeIgnoreFilter.pruneGlobs）
            append("-type d \\\\( ")
            append(pruneGlobs.joinToString(" -o ") { "-path ${it.shellQuote()}" })
            append(" \\\\) -prune -o ")
        }
        append("-type f -newermt \\\"@${cutoffMillis / 1000}\\\" -print")
        append(" > ${SCAN_OUT_FILE.shellQuote()} 2>/dev/null\n")
    }

    private suspend fun readOutFile(root: String, file: String): List<String> = runCatching {
        val size = workspaceRepository.rootfsFileSize(root, file)
        if (size <= 0L) return@runCatching emptyList()
        ByteArrayOutputStream(size.coerceAtMost(MAX_SCAN_OUT_BYTES.toLong()).toInt()).use { out ->
            workspaceRepository.exportRootfsFile(root, file, out)
            out.toString(Charsets.UTF_8.name()).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.startsWith("/workspace") }
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
}

/**
 * 单个 (conversationId, root) 的扫描窗口状态。
 * 与 [WorkspaceChangeScanner] 分离，使 [scanWindow] 的窗口逻辑可以脱离仓库/协程作用域做单测。
 */
internal class ScanWindowState {
    /** 自上次 consume 以来待处理 poke 的最小 cutoff（毫秒）。compute 原子合并 min，保证并发 poke 不丢 */
    val pendingMin = ConcurrentHashMap<WorkspaceChangeScanner.ScanKey, Long>()

    /** 当前窗口的最早 cutoff（跨 burst 持久，consume 时清除）；每次 find 都以它为基准重扫全窗口 */
    val windowStart = ConcurrentHashMap<WorkspaceChangeScanner.ScanKey, Long>()

    /** 自上次 consume 以来的变更路径快照（最新完整快照，不是累积） */
    val accumulated = ConcurrentHashMap<WorkspaceChangeScanner.ScanKey, LinkedHashSet<String>>()

    /** 正在扫描中的追踪键 */
    val scanning = ConcurrentHashMap.newKeySet<WorkspaceChangeScanner.ScanKey>()

    /** 窗口代际：每次 consume 递增，使在途扫描的发布失效，防止窗口污染 */
    val windowEpoch = ConcurrentHashMap<WorkspaceChangeScanner.ScanKey, Long>()
}

/**
 * 扫描一个窗口：以窗口最早 cutoff 为基准反复重扫全窗口并取**最新快照**，
 * 直到扫描期间没有新命令（含 [settleMillis] 稳定等待）为止。
 *
 * 快照替换语义：
 * - find 只返回"当前存在 且 mtime >= 窗口基准"的文件 → 被删除/改名的文件自动从快照消失；
 * - 每批结果整体替换而不是累积 → 中途生成的临时文件只要在最终快照里不存在就不会展示。
 *
 * @param key 追踪键
 * @param runFind 执行一次 find，返回该基准之后的现存文件；返回 null 表示本次 find 失败
 * @param settleMillis 每批之后的稳定等待；期间又有新命令（pendingMin 非空）则继续扫描
 * @return 最新完整快照；null 表示"无新命令 / find 失败 / 窗口已闭合"，调用方不应发布
 */
internal suspend fun ScanWindowState.scanWindow(
    key: WorkspaceChangeScanner.ScanKey,
    runFind: suspend (cutoffMillis: Long) -> List<String>?,
    settleMillis: Long,
): List<String>? {
    scanning += key
    val epoch = windowEpoch.getOrDefault(key, 0L)
    try {
        var burst: List<String>? = null
        var dirty = true
        while (dirty) {
            dirty = false
            if (windowEpoch.getOrDefault(key, 0L) != epoch) return null // 窗口已闭合(consume), 丢弃
            val cutoffMillis = pendingMin.remove(key) ?: break // 无新命令
            // 窗口基准 = 本窗口内所有 cutoff 的最小值, 跨 burst 持久
            windowStart.compute(key) { _, existing -> minOf(existing ?: Long.MAX_VALUE, cutoffMillis) }
            val start = windowStart[key] ?: break
            val next = runFind(start) ?: return null // find 失败: 不发布, 保留上一快照
            burst = next // 整体替换: 整个窗口的当前快照
            if (windowEpoch.getOrDefault(key, 0L) != epoch) return null
            if (settleMillis > 0) {
                delay(settleMillis) // 稳定等待: 期间又有新命令则继续扫描
                dirty = pendingMin.containsKey(key)
            }
        }
        if (windowEpoch.getOrDefault(key, 0L) != epoch) return null // 收尾前再校验一次
        return burst
    } finally {
        scanning -= key
    }
}
