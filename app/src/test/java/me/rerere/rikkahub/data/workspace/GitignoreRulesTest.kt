package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一引擎 [GitignoreRules] 语义单测（纯 JVM，不依赖 Android）：
 * 导入/同步/导出（shouldIgnore）、变更列表/补全（isIgnored/isPathIgnored/pruneGlobs）
 * 共用同一套解析与求值；自定义规则最后求值、可覆盖任意层级 .gitignore。
 */
class GitignoreRulesTest {

    private fun rules(custom: String = ""): GitignoreRules =
        GitignoreRules(enableGitignore = true, customIgnorePatterns = custom)

    @Test
    fun `directory only rule matches dir not file`() {
        val r = rules().withGitignore("", "build/")
        assertTrue(r.shouldIgnore("build", "", true))
        assertFalse(r.shouldIgnore("build", "", false))
    }

    @Test
    fun `basename rule applies at any depth`() {
        val r = rules().withGitignore("", "*.log")
        assertTrue(r.shouldIgnore("x.log", "a/b", false))
        assertTrue(r.isIgnored("a/b/x.log", false))
        assertFalse(r.isIgnored("a/b/x.log.txt", false))
    }

    @Test
    fun `anchored and path rules are relative to their gitignore dir`() {
        // 锚定：/top 只匹配根下条目
        val anchored = rules().withGitignore("", "/top")
        assertTrue(anchored.shouldIgnore("top", "", true))
        assertFalse(anchored.shouldIgnore("top", "sub", true))

        // 路径模式：app/build 相对规则所在目录
        val path = rules().withGitignore("", "app/build")
        assertTrue(path.shouldIgnore("build", "app", true))
        assertFalse(path.shouldIgnore("build", "", true))
    }

    @Test
    fun `subdir gitignore overrides parent only within its subtree`() {
        val r = rules()
            .withGitignore("", "*.log")
            .withGitignore("a", "!special.log")
        assertFalse(r.shouldIgnore("special.log", "a", false)) // a/ 下被父规则忽略后重包含
        assertTrue(r.shouldIgnore("other.log", "a", false))
        assertTrue(r.shouldIgnore("special.log", "", false))   // a/ 的规则不作用于子树外
    }

    @Test
    fun `negation inside excluded dir cannot resurrect subtree`() {
        val r = rules().withGitignore("", "build/\n!build/keep.txt")
        assertTrue(r.isPathIgnored("build/keep.txt"))
    }

    @Test
    fun `custom ignore overrides subdir re-inclusion`() {
        val r = rules(custom = "keep.log").withGitignore("a", "!keep.log")
        assertTrue(r.shouldIgnore("keep.log", "a", false))
    }

    @Test
    fun `custom negation re-includes whole ignored dir`() {
        val r = rules(custom = "!dist/").withGitignore("", "dist/")
        assertFalse(r.isPathIgnored("dist/x.txt"))
    }

    @Test
    fun `custom patterns split by comma chinese comma and newline`() {
        val r = rules(custom = "a/,，*.tmp\nb")
        assertTrue(r.shouldIgnore("a", "", true))
        assertTrue(r.shouldIgnore("x.tmp", "sub", false))
        assertTrue(r.shouldIgnore("b", "", false))
        assertFalse(r.shouldIgnore("c", "", false))
    }

    @Test
    fun `full syntax char class escape comment`() {
        val r = rules().withGitignore(
            "",
            """
            a[0-9].txt
            \#keep
            # comment
            file\ name.txt
            """.trimIndent(),
        )
        assertTrue(r.isIgnored("a5.txt", false))
        assertFalse(r.isIgnored("ax.txt", false))
        assertTrue(r.isIgnored("#keep", false))        // \# 转义的字面 # 文件名
        assertTrue(r.isIgnored("file name.txt", false)) // \ 转义的字面空格
    }

    @Test
    fun `isPathIgnored isolates projects`() {
        val r = rules().withGitignore("proj-a", "gen/")
        assertTrue(r.isPathIgnored("proj-a/gen/x.txt"))
        assertFalse(r.isPathIgnored("proj-b/gen/x.txt"))
    }

    @Test
    fun `includeDefaults only for completion`() {
        val plain = rules()
        assertFalse(plain.isPathIgnored("node_modules/x/index.js"))
        val completion = GitignoreRules(true, "", includeDefaults = true)
        assertTrue(completion.isPathIgnored("node_modules/x/index.js"))
    }

    @Test
    fun `prune globs conservative and disabled by negation or meta`() {
        val r = rules().withGitignore("", "node_modules/\nsub/build").withGitignore("proj", "gen/")
        val globs = r.pruneGlobs()
        assertTrue("/workspace/node_modules" in globs)
        assertTrue("/workspace/*/node_modules" in globs)
        assertTrue("/workspace/sub/build" in globs)
        assertTrue("/workspace/proj/gen" in globs)

        // 含 glob 元字符的模式跳过（避免与 find 通配差异导致过剪）
        val meta = rules().withGitignore("", "temp.*")
        assertTrue(meta.pruneGlobs().isEmpty())

        // 任何否定规则存在 → 不剪枝
        val neg = rules(custom = "!keep/").withGitignore("", "node_modules/")
        assertEquals(emptyList<String>(), neg.pruneGlobs())
    }
}
