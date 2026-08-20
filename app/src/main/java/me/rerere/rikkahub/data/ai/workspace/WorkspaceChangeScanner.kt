package me.rerere.rikkahub.data.ai.workspace

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
 * - 扫描器对每个 (conversationId, root) 取**所有待处理 poke 的最小 cutoff** 作为基准，
 *   `find /workspace -type f -newermt "@秒"`（含边界秒，GNU find）找出该时间之后写入的文件；
 *   因为 cutoff 早于对应命令，所以该命令产生的变更必然 >= cutoff，不会漏。
 * - 若扫描期间又有新命令执行（产生了新的 pending poke）→ 继续扫描；
 *   直到某次扫描期间没有新命令 → 该批变更累计到 [accumulated]。
 * - 生成结束后调用 [consume] 取回并清空累计变更，由 ChatService 回填到对话消息。
 *
 * 追踪键为 (conversationId, root)：同一工作区被多个对话共享时，各对话只拿到自己
 * 修改的文件，互不串扰。
 */
class WorkspaceChangeScanner(
    private val workspaceRepository: WorkspaceRepository,
    private val appScope: AppScope,
) {
    /** 追踪键：对话 + 工作区，保证同一工作区在多对话间隔离 */
    data class ScanKey(
        val conversationId: String,
        val root: String,
    )

    companion object {
        private const val SCAN_OUT_FILE = "/tmp/.ws_scan_out"
        private const val SCAN_TIMEOUT_MS = 60_000L
        private const val CONSUME_WAIT_TIMEOUT_MS = 10_000L
        private const val MAX_SCAN_OUT_BYTES = 8 * 1024 * 1024
    }

    /**
     * 每个追踪键自上次扫描以来待处理 poke 的最小 cutoff（毫秒）。
     * compute 原子合并 min，保证并发 poke 不丢。
     */
    private val pendingMin = ConcurrentHashMap<ScanKey, Long>()

    /** 正在扫描中的追踪键 */
    private val scanning = ConcurrentHashMap.newKeySet<ScanKey>()

    /** 每个追踪键自上次 consume 以来累计的变更路径（保留插入序去重） */
    private val accumulated = ConcurrentHashMap<ScanKey, LinkedHashSet<String>>()

    private val _liveChanges = MutableStateFlow<Map<ScanKey, List<String>>>(emptyMap())

    /**
     * 每个追踪键**当前已检测到**的变更路径。后台 find 每完成一批就更新一次，
     * 供 UI 实时展示（生成期间即可看到药丸，无需等整轮结束）。
     */
    val liveChanges: StateFlow<Map<ScanKey, List<String>>> = _liveChanges.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        appScope.launch(Dispatchers.IO) {
            while (isActive) {
                wake.receive() // 等待 poke
                while (isActive) {
                    val key = pendingMin.keys.firstOrNull() ?: break
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
        pendingMin.compute(key) { _, existing ->
            minOf(existing ?: Long.MAX_VALUE, refCutoffMillis)
        }
        wake.trySend(Unit)
    }

    /**
     * 生成结束后调用：等待当前批次稳定（扫描完成且期间没有新命令），
     * 返回并清空该 (conversationId, root) 自上次 consume 以来的累计变更。
     * 最多等待 [CONSUME_WAIT_TIMEOUT_MS]，超时返回已有部分结果。
     */
    suspend fun consume(conversationId: String, root: String): List<String> {
        val key = ScanKey(conversationId, root)
        withTimeoutOrNull(CONSUME_WAIT_TIMEOUT_MS) {
            while (pendingMin.containsKey(key) || key in scanning) {
                delay(50)
            }
        }
        val result = accumulated.remove(key)?.toList() ?: emptyList()
        // 生成结束: 实时流让位给回填到消息 metadata 的持久展示
        _liveChanges.update { it - key }
        return result
    }

    private suspend fun scanBurst(key: ScanKey) {
        scanning += key
        try {
            val burst = LinkedHashSet<String>()
            var dirty: Boolean
            do {
                dirty = false
                // 取出本次扫描前所有待处理 poke 合并后的最小 cutoff（原子 remove）
                val cutoffMillis = pendingMin.remove(key) ?: break
                val result = runCatching {
                    workspaceRepository.executeCommand(
                        id = key.root,
                        command = buildScanCommand(cutoffMillis),
                        timeoutMillis = SCAN_TIMEOUT_MS,
                    )
                }.getOrNull()
                if (result == null) break
                // 变更路径写入 rootfs /tmp 的 out 文件, Java 侧直读, 不再额外开 shell
                burst += readScanOutput(key.root)
                // 扫描期间又有新命令（产生新的 pending poke）→ 需要继续扫描
                dirty = pendingMin.containsKey(key)
            } while (dirty)
            if (burst.isNotEmpty()) {
                val set = accumulated.getOrPut(key) { LinkedHashSet() }
                set += burst
                // 每完成一批就推送一次实时变更
                _liveChanges.update { it + (key to set.toList()) }
            }
        } finally {
            scanning -= key
        }
    }

    private fun buildScanCommand(cutoffMillis: Long): String = buildString {
        // -newermt "@秒" 在 GNU find 中含边界秒（同一秒内写入的文件也会命中），
        // 且 cutoff 早于命令写入时间, 因此不会漏掉同秒晚于 cutoff 的变更。
        append("find /workspace -type f -newermt \"@${cutoffMillis / 1000}\" > ${SCAN_OUT_FILE.shellQuote()} 2>/dev/null\n")
    }

    private suspend fun readScanOutput(root: String): List<String> = runCatching {
        val size = workspaceRepository.rootfsFileSize(root, SCAN_OUT_FILE)
        if (size <= 0L) return@runCatching emptyList()
        ByteArrayOutputStream(size.coerceAtMost(MAX_SCAN_OUT_BYTES.toLong()).toInt()).use { out ->
            workspaceRepository.exportRootfsFile(root, SCAN_OUT_FILE, out)
            out.toString(Charsets.UTF_8.name()).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.startsWith("/workspace") }
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
}
