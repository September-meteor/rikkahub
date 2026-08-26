package me.rerere.rikkahub.data.ai.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测窗口状态机 [ScanWindowState.scanWindow] 的快照替换语义，
 * 不依赖 WorkspaceRepository / AppScope（两者在沙箱无 mock 框架时难以构造）。
 */
class WorkspaceChangeScannerTest {

    private val key = WorkspaceChangeScanner.ScanKey("conv-1", "ws-1")

    /** 用脚本化的 find 输出驱动一次 scanWindow */
    private suspend fun ScanWindowState.scanScripted(
        responses: List<List<String>?>,
        settleMillis: Long = 0,
        findCalls: MutableList<Long> = mutableListOf(),
        onFind: ((Long) -> Unit)? = null,
    ): List<String>? {
        var i = 0
        return scanWindow(
            key = key,
            runFind = { cutoff ->
                findCalls += cutoff
                onFind?.invoke(cutoff)
                responses[i++]
            },
            settleMillis = settleMillis,
        )
    }

    @Test
    fun `no pending command returns null`() = runBlocking {
        val state = ScanWindowState()
        assertNull(state.scanScripted(emptyList()))
    }

    @Test
    fun `bursts replace snapshot and keep the earliest window start`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        val findCalls = mutableListOf<Long>()
        // 第一批: A、B 变化
        val first = state.scanScripted(
            responses = listOf(listOf("/workspace/A", "/workspace/B")),
            findCalls = findCalls,
        )
        assertEquals(listOf("/workspace/A", "/workspace/B"), first)

        // 第二批: B 被删除, C 新增 → 结果覆盖列表为 [A, C], 而不是累积 [A, B, C]
        state.pendingMin[key] = 5_000L
        val second = state.scanScripted(
            responses = listOf(listOf("/workspace/A", "/workspace/C")),
            findCalls = findCalls,
        )
        assertEquals(listOf("/workspace/A", "/workspace/C"), second)

        // 窗口基准跨 burst 保持最早 cutoff: 第二次也是从 1000 重扫整个窗口
        assertEquals(listOf(1_000L, 1_000L), findCalls)
    }

    @Test
    fun `early changed file not re-touched is kept`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        val first = state.scanScripted(listOf(listOf("/workspace/A", "/workspace/B")))
        assertEquals(listOf("/workspace/A", "/workspace/B"), first)

        // 第二批 find 只报告新文件 C: A 虽未再被修改, 但窗口重扫仍包含它
        state.pendingMin[key] = 2_000L
        val second = state.scanScripted(listOf(listOf("/workspace/A", "/workspace/C")))
        assertEquals(listOf("/workspace/A", "/workspace/C"), second)
    }

    @Test
    fun `deleted file disappears from snapshot`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        state.scanScripted(listOf(listOf("/workspace/A", "/workspace/B")))
        // B 被删除: 第二次 find 不再包含它
        state.pendingMin[key] = 2_000L
        val second = state.scanScripted(listOf(listOf("/workspace/A")))
        assertEquals(listOf("/workspace/A"), second)
    }

    @Test
    fun `all files deleted yields empty snapshot`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        state.scanScripted(listOf(listOf("/workspace/A", "/workspace/B")))
        state.pendingMin[key] = 2_000L
        val second = state.scanScripted(listOf(listOf<String>()))
        assertEquals(emptyList<String>(), second)
    }

    @Test
    fun `find failure returns null and caller keeps previous snapshot`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        assertEquals(listOf("/workspace/A"), state.scanScripted(listOf(listOf("/workspace/A"))))
        // 第二次 find 失败 → 返回 null, 不覆盖已有快照
        state.pendingMin[key] = 2_000L
        assertNull(state.scanScripted(listOf(null)))
    }

    @Test
    fun `closed window discards in-flight burst`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        // 模拟 consume 在扫描中途闭合窗口 (递增 epoch)
        val result = state.scanWindow(
            key = key,
            runFind = {
                state.windowEpoch.compute(key) { _, e -> (e ?: 0L) + 1 }
                listOf("/workspace/A")
            },
            settleMillis = 0,
        )
        assertNull(result)
    }

    @Test
    fun `poke during settle keeps scanning with same window start`() = runBlocking {
        val state = ScanWindowState()
        state.pendingMin[key] = 1_000L
        val findCalls = mutableListOf<Long>()
        val result = state.scanScripted(
            responses = listOf(listOf("/workspace/A"), listOf("/workspace/A")),
            settleMillis = 50,
            findCalls = findCalls,
            onFind = { if (findCalls.size == 1) state.pendingMin[key] = 9_000L },
        )
        assertEquals(listOf("/workspace/A"), result)
        // 稳定等待期间来了新命令 → 继续扫了一轮, 且仍以最早 cutoff 为基准
        assertEquals(listOf(1_000L, 1_000L), findCalls)
    }
}
