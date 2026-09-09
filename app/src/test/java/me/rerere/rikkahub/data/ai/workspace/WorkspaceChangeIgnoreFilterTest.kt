package me.rerere.rikkahub.data.ai.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkspaceChangeIgnoreFilter] 的加载/缓存/发现逻辑单测：
 * 通过 [WorkspaceChangeIgnoreFilter.forTest] 注入假的配置读取与 .gitignore 文件读取，不依赖 Android。
 */
class WorkspaceChangeIgnoreFilterTest {

    private class FakeFiles {
        val contents = mutableMapOf<String, String?>()

        suspend fun read(workspaceId: String, relativePath: String): String? = contents[relativePath]
    }

    private fun config(
        enableGitignore: Boolean = true,
        custom: String = "",
    ) = WorkspaceChangeIgnoreFilter.WorkspaceIgnoreConfig(enableGitignore, custom)

    private fun newFilter(
        files: FakeFiles,
        enableGitignore: Boolean = true,
        custom: String = "",
    ) = WorkspaceChangeIgnoreFilter.forTest(
        loadConfig = { config(enableGitignore, custom) },
        readFile = files::read,
    )

    @Test
    fun `filter probes ancestor gitignore without discovery`() = runBlocking {
        val files = FakeFiles().apply {
            contents["rikkahub/.gitignore"] = "build/\n*.log"
        }
        val filter = newFilter(files)
        val kept = filter.filter(
            "ws-1",
            listOf(
                "/workspace/rikkahub/build/a.txt",
                "/workspace/rikkahub/src/Main.kt",
                "/workspace/rikkahub/app.log",
                "/workspace/other/x.txt",
            ),
        )
        assertEquals(listOf("/workspace/rikkahub/src/Main.kt", "/workspace/other/x.txt"), kept)
    }

    @Test
    fun `recordScan discovery enables prune and filters`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "node_modules/\n"
        }
        val filter = newFilter(files)

        assertTrue(filter.needsDiscovery("ws-1"))
        assertEquals(emptyList<String>(), filter.pruneGlobs("ws-1"))

        val kept = filter.recordScan(
            "ws-1",
            discoveryPaths = listOf("/workspace/.gitignore"),
            changedRaw = listOf(
                "/workspace/node_modules/x/index.js",
                "/workspace/app/src/Main.kt",
            ),
        )
        assertEquals(listOf("/workspace/app/src/Main.kt"), kept)

        assertFalse(filter.needsDiscovery("ws-1"))
        val globs = filter.pruneGlobs("ws-1")
        assertTrue("/workspace/node_modules" in globs)
        assertTrue("/workspace/*/node_modules" in globs)
    }

    @Test
    fun `recordScan discovery refresh drops removed rules`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/\n"
        }
        val filter = newFilter(files)
        filter.recordScan("ws-1", listOf("/workspace/.gitignore"), emptyList())
        assertTrue(filter.pruneGlobs("ws-1").isNotEmpty())

        // .gitignore 被删除后，下次发现结果为空 → 剪枝知识清空
        files.contents[".gitignore"] = null
        filter.recordScan("ws-1", listOf<String>(), emptyList())
        assertEquals(emptyList<String>(), filter.pruneGlobs("ws-1"))
    }

    @Test
    fun `markDiscoveryDirty forces refresh and disables prune temporarily`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/\n"
        }
        val filter = newFilter(files)
        filter.recordScan("ws-1", listOf("/workspace/.gitignore"), emptyList())
        assertTrue(filter.pruneGlobs("ws-1").isNotEmpty())

        filter.markDiscoveryDirty("ws-1")
        assertTrue(filter.needsDiscovery("ws-1"))
        // 知识可能过期：不剪枝，保证不过剪
        assertEquals(emptyList<String>(), filter.pruneGlobs("ws-1"))

        filter.recordScan("ws-1", listOf("/workspace/.gitignore"), emptyList())
        assertTrue(filter.pruneGlobs("ws-1").isNotEmpty())
    }

    @Test
    fun `custom rules full syntax applied and gitignore toggle`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/\n"
        }
        // gitignore 关闭时 build 保留，但自定义规则仍生效（根级）
        val filter = newFilter(files, enableGitignore = false, custom = "**/gen/*")
        val kept = filter.filter(
            "ws-1",
            listOf(
                "/workspace/build/a.txt",
                "/workspace/app/gen/a.java",
                "/workspace/src/Main.kt",
            ),
        )
        assertEquals(listOf("/workspace/build/a.txt", "/workspace/src/Main.kt"), kept)
    }

    @Test
    fun `config change invalidates nothing stale`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/\n"
        }
        var custom = ""
        val filter = WorkspaceChangeIgnoreFilter.forTest(
            loadConfig = { config(true, custom) },
            readFile = files::read,
        )
        filter.filter("ws-1", listOf("/workspace/a/build/x.txt"))
        custom = "a/build/"
        // 自定义规则优先级最高（最后求值）；此处 a/build/ 直接命中目录
        val kept = filter.filter("ws-1", listOf("/workspace/a/build/x.txt"))
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun `dir excluded subtree cannot be re-included by deeper negation`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/\n!build/keep.txt"
        }
        val filter = newFilter(files)
        val kept = filter.filter("ws-1", listOf("/workspace/build/keep.txt", "/workspace/src/Main.kt"))
        // build/ 排除目录后 git 不会进入，!build/keep.txt 无法重包含
        assertEquals(listOf("/workspace/src/Main.kt"), kept)
    }

    @Test
    fun `file re-inclusion with star pattern works`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/*\n!build/keep.txt"
        }
        val filter = newFilter(files)
        val kept = filter.filter(
            "ws-1",
            listOf("/workspace/build/drop.txt", "/workspace/build/keep.txt"),
        )
        assertEquals(listOf("/workspace/build/keep.txt"), kept)
    }

    @Test
    fun `subdir gitignore overrides parent`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "*.log"
            contents["app/.gitignore"] = "!special.log"
        }
        val filter = newFilter(files)
        val kept = filter.filter(
            "ws-1",
            listOf("/workspace/app/special.log", "/workspace/app/root.log", "/workspace/special.log"),
        )
        // app/.gitignore 的 ! 只作用于 app 子树
        assertEquals(listOf("/workspace/app/special.log"), kept)
    }

    @Test
    fun `root gitignore change invalidates cache within window`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "old.log\n"
        }
        val filter = newFilter(files)
        filter.recordScan("ws-1", listOf("/workspace/.gitignore"), emptyList())
        // 规则生效：old.log 被过滤
        assertEquals(emptyList<String>(), filter.filter("ws-1", listOf("/workspace/old.log")))

        // 窗口内根 .gitignore 被改写为空：缓存应立即失效并重读，old.log 恢复可见
        files.contents[".gitignore"] = ""
        val kept = filter.recordScan(
            "ws-1",
            discoveryPaths = null,
            changedRaw = listOf("/workspace/.gitignore", "/workspace/old.log"),
        )
        assertEquals(listOf("/workspace/.gitignore", "/workspace/old.log"), kept)
    }

    @Test
    fun `custom comma separated rules and top priority override gitignore`() = runBlocking {
        val files = FakeFiles().apply {
            contents[".gitignore"] = "build/"
            contents["app/.gitignore"] = "!special.log"
        }
        // 自定义：!build/ 重包含整个 build 目录；special.log 压过 app/.gitignore 的重包含；*.tmp 任意层级排除
        val filter = newFilter(files, custom = "!build/, special.log, *.tmp")
        val kept = filter.filter(
            "ws-1",
            listOf(
                "/workspace/build/keep.txt",          // 被自定义 !build/ 重包含 → 保留
                "/workspace/app/special.log",         // 自定义 special.log 压过子目录 !special.log → 排除
                "/workspace/x.tmp",                   // 自定义 *.tmp → 排除
                "/workspace/src/Main.kt",
            ),
        )
        assertEquals(listOf("/workspace/build/keep.txt", "/workspace/src/Main.kt"), kept)
    }
}
