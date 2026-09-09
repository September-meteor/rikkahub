package me.rerere.rikkahub.data.ai.workspace

import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作区忽略规则加载器（聊天变更列表专用）。
 *
 * 匹配逻辑复用 [WorkspaceIgnoreMatcher]（原 `@` 文件补全实现，上移到本包共用），
 * 这里只负责：读工作区配置（`enableGitignore` + `customIgnorePatterns`，自定义规则同样走 gitignore 语法）、
 * 按需读取各级目录 `.gitignore`（目录级缓存，变更列表中命中 `.gitignore` 时失效重读）、
 * 以及向 [WorkspaceChangeScanner] 提供「全树 .gitignore 发现」与 find 剪枝 glob。
 *
 * 语义要点：目录被忽略即整棵子树不可见（git 不进入被排除目录）；子目录 .gitignore 覆盖父目录（last-match-wins）。
 */
class WorkspaceChangeIgnoreFilter internal constructor(
    private val loadConfig: suspend (workspaceId: String) -> WorkspaceIgnoreConfig?,
    private val readFile: suspend (workspaceId: String, relativePath: String) -> String?,
) {
    /** 变更列表忽略判定用到的工作区配置 */
    internal data class WorkspaceIgnoreConfig(
        val enableGitignore: Boolean,
        val customIgnorePatterns: String,
    )

    /** 每个工作区一组的规则缓存 */
    private class WorkspaceState {
        /** 目录（相对根，""=根）→ 该目录 .gitignore 内容；仅在确认存在时写入 */
        val dirContents = ConcurrentHashMap<String, String>()

        /** 已确认「该目录无 .gitignore」，避免反复探测 */
        val noGitignoreDirs = ConcurrentHashMap.newKeySet<String>()

        /** 内容/配置版本：任一变化都触发 matcher 重建 */
        @Volatile
        var contentVersion: Int = 0

        /** 最近生效的配置快照（供同步构建 matcher / 剪枝谓词使用） */
        @Volatile
        var enableGitignore: Boolean = true

        @Volatile
        var customPatterns: String = ""

        /** 是否已做过一次成功的全树 .gitignore 发现（剪枝前提） */
        @Volatile
        var discovered: Boolean = false

        /** 剪枝知识可能过期（如窗口关闭、规则可能被删除），下次扫描需全量刷新 */
        @Volatile
        var dirty: Boolean = false

        // matcher 缓存
        @Volatile
        var matcher: WorkspaceIgnoreMatcher? = null

        @Volatile
        var matcherFingerprint: String = ""

        fun fingerprint(): String = "$enableGitignore|$customPatterns|$contentVersion"
    }

    private val states = ConcurrentHashMap<String, WorkspaceState>()

    private fun state(workspaceId: String): WorkspaceState =
        states.computeIfAbsent(workspaceId) { WorkspaceState() }

    // ---------------------------------------------------------------- 对外 API

    /**
     * 过滤绝对路径列表（`/workspace/...`），返回应展示的路径。
     * 供 UI 展示层（历史消息 / write-edit 入参）与扫描器共用；不触发全树发现。
     */
    suspend fun filter(workspaceId: String, paths: List<String>): List<String> {
        val s = state(workspaceId)
        val config = loadConfig(workspaceId)
        val enableGitignore = config?.enableGitignore ?: true
        val custom = config?.customIgnorePatterns ?: ""
        if (s.enableGitignore != enableGitignore || s.customPatterns != custom) {
            s.enableGitignore = enableGitignore
            s.customPatterns = custom
        }

        val relPaths = paths.mapNotNull { toWorkspaceRelative(it) }
        if (enableGitignore && relPaths.isNotEmpty()) {
            ensureDirRules(workspaceId, relPaths)
        }
        val matcher = matcherOf(s)
        return paths.filter { path ->
            toWorkspaceRelative(path)?.let { !matcher.isPathIgnored(it) } != false
        }
    }

    /**
     * 记录一次扫描结果并返回「应展示的变更路径」。
     *
     * @param discoveryPaths 本次扫描是否跑了全树 .gitignore 发现；跑了传发现到的绝对路径列表，没跑传 null
     * @param changedRaw     本次 find 得到的原始变更路径（绝对路径）
     */
    suspend fun recordScan(
        workspaceId: String,
        discoveryPaths: List<String>?,
        changedRaw: List<String>,
    ): List<String> {
        val s = state(workspaceId)
        if (discoveryPaths != null) {
            // 全树发现 = 以本次结果为准整体刷新，避免删除/改动后的规则残留
            s.dirContents.clear()
            s.noGitignoreDirs.clear()
            s.contentVersion++
            discoveryPaths.forEach { path ->
                val rel = toWorkspaceRelative(path) ?: return@forEach
                val base = relDirOf(rel)
                if (s.dirContents.containsKey(base) || base in s.noGitignoreDirs) return@forEach
                val content = readFile(workspaceId, if (base.isEmpty()) ".gitignore" else "$base/.gitignore")
                if (content.isNullOrBlank()) {
                    s.noGitignoreDirs += base
                } else {
                    s.dirContents[base] = content
                    s.contentVersion++
                }
            }
            s.discovered = true
            s.dirty = false
        }
        // 本轮被修改的 .gitignore：失效其所在目录的缓存（随后的 filter 会按需重读）。
        // 注意根目录 .gitignore 的 rel 是 ".gitignore"（不含 "/"），必须与嵌套 "a/.gitignore" 一起覆盖
        changedRaw.forEach { path ->
            val rel = toWorkspaceRelative(path) ?: return@forEach
            if (rel == ".gitignore" || rel.endsWith("/.gitignore")) {
                val base = relDirOf(rel)
                if (s.dirContents.remove(base) != null || s.noGitignoreDirs.remove(base)) {
                    s.contentVersion++
                }
            }
        }
        return filter(workspaceId, changedRaw)
    }

    /** 扫描器是否需要在下次扫描前跑一次全树发现 */
    fun needsDiscovery(workspaceId: String): Boolean {
        val s = state(workspaceId)
        return !s.discovered || s.dirty
    }

    /** 当前可用的 find 剪枝 glob（发现未完成或知识过期时返回空列表 = 不剪枝，保证不过剪） */
    fun pruneGlobs(workspaceId: String): List<String> {
        val s = state(workspaceId)
        if (!s.discovered || s.dirty) return emptyList()
        return matcherOf(s).pruneGlobs()
    }

    /** 窗口关闭时调用：剪枝知识可能过期（.gitignore 可能被删除/改动），下次扫描做一次全量发现 */
    fun markDiscoveryDirty(workspaceId: String) {
        state(workspaceId).dirty = true
    }

    // ---------------------------------------------------------------- 内部

    /** 探测 [relPaths] 涉及目录（含祖先）的 .gitignore 并缓存 */
    private suspend fun ensureDirRules(workspaceId: String, relPaths: List<String>) {
        val s = state(workspaceId)
        val needed = LinkedHashSet<String>()
        relPaths.forEach { rel ->
            ancestorDirs(rel).forEach { needed += it }
        }
        needed.forEach { dir ->
            if (s.dirContents.containsKey(dir) || dir in s.noGitignoreDirs) return@forEach
            val content = readFile(workspaceId, if (dir.isEmpty()) ".gitignore" else "$dir/.gitignore")
            if (content.isNullOrBlank()) {
                s.noGitignoreDirs += dir
            } else {
                s.dirContents[dir] = content
            }
            s.contentVersion++
        }
    }

    /** 用当前内容/配置重建 matcher（有缓存指纹） */
    private fun matcherOf(s: WorkspaceState): WorkspaceIgnoreMatcher {
        val fp = s.fingerprint()
        val cached = s.matcher
        if (cached != null && s.matcherFingerprint == fp) return cached

        var matcher = WorkspaceIgnoreMatcher(includeDefaults = false)
        if (s.enableGitignore) {
            s.dirContents[""]?.takeIf { it.isNotBlank() }?.let { matcher = matcher.withGitignore("", it) }
            if (s.customPatterns.isNotBlank()) {
                matcher = matcher.withGitignore("", s.customPatterns)
            }
            s.dirContents.keys
                .filter { it.isNotEmpty() }
                .sortedWith(compareBy({ it.count { ch -> ch == '/' } }, { it }))
                .forEach { dir ->
                    s.dirContents[dir]?.takeIf { it.isNotBlank() }?.let { matcher = matcher.withGitignore(dir, it) }
                }
        } else {
            if (s.customPatterns.isNotBlank()) {
                matcher = matcher.withGitignore("", s.customPatterns)
            }
        }
        s.matcher = matcher
        s.matcherFingerprint = fp
        return matcher
    }

    companion object {
        const val WORKSPACE_ROOT = "/workspace"

        /** 生产入口：由 [WorkspaceRepository] 组装 */
        fun fromRepository(repository: WorkspaceRepository): WorkspaceChangeIgnoreFilter =
            WorkspaceChangeIgnoreFilter(
                loadConfig = { id ->
                    repository.getById(id)?.let {
                        WorkspaceIgnoreConfig(
                            enableGitignore = it.enableGitignore,
                            customIgnorePatterns = it.customIgnorePatterns,
                        )
                    }
                },
                readFile = { id, rel ->
                    runCatching { repository.readText(id, rel) }.getOrNull()
                },
            )

        /** 测试入口：注入假的配置与文件读取 */
        internal fun forTest(
            loadConfig: suspend (workspaceId: String) -> WorkspaceIgnoreConfig?,
            readFile: suspend (workspaceId: String, relativePath: String) -> String?,
        ): WorkspaceChangeIgnoreFilter = WorkspaceChangeIgnoreFilter(loadConfig, readFile)

        /** `/workspace/a/b` → `a/b`；非 /workspace 前缀返回 null（不参与忽略判定） */
        internal fun toWorkspaceRelative(absolutePath: String): String? {
            val trimmed = absolutePath.trimEnd('/')
            return when {
                trimmed == WORKSPACE_ROOT -> ""
                trimmed.startsWith("$WORKSPACE_ROOT/") -> trimmed.removePrefix("$WORKSPACE_ROOT/")
                else -> null
            }
        }

        /** `a/b/c.txt` → `a/b`；`a.txt` → `` */
        private fun relDirOf(rel: String): String =
            rel.substringBeforeLast('/', "")

        /** rel 路径的祖先目录链（含根 ""），由浅到深，不含自身 */
        private fun ancestorDirs(rel: String): List<String> {
            if (rel.isBlank()) return listOf("")
            val parts = rel.split('/').filter { it.isNotBlank() }
            if (parts.size == 1) return listOf("")
            val result = mutableListOf("")
            var prefix = ""
            for (i in 0 until parts.size - 1) {
                prefix = if (i == 0) parts[i] else "$prefix/${parts[i]}"
                result += prefix
            }
            return result
        }
    }
}
