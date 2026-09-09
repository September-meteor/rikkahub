package me.rerere.rikkahub.data.ai.workspace

/**
 * rootfs 路径型 .gitignore matcher（原为 @ 文件补全实现，从 ui.components.ai.completion 上移共用）。
 *
 * - [withGitignore] 按目录累加规则：目录 key 为相对 workspace 根（""=根），先加父目录、后加子目录（后者覆盖前者，last-match-wins）；
 * - [isIgnored] 判定单条目（路径需在该规则 base 之下才命中，天然隔离多项目）；
 * - [isPathIgnored] 判定「文件或其任一祖先目录被忽略」（目录被排除 = 子树整体不可见，git 不进入被排除目录）；
 * - [pruneGlobs] 生成保守的 find 剪枝 glob（保守策略：宁可少剪不错剪）。
 *
 * [includeDefaults] 仅 `@` 文件补全场景需要内置默认规则（build/ 等）；聊天变更列表传 false，完全以 .gitignore + 自定义规则为准。
 */
internal class WorkspaceIgnoreMatcher private constructor(
    private val rules: List<Rule>,
) {
    constructor(includeDefaults: Boolean = true) : this(
        if (includeDefaults) DEFAULT_RULES else emptyList()
    )

    fun withGitignore(directory: String, content: String): WorkspaceIgnoreMatcher {
        val basePath = directory.normalizeWorkspacePath()
        val parsed = content
            .lineSequence()
            .mapNotNull { line -> Rule.parse(basePath, line) }
            .toList()
        return if (parsed.isEmpty()) this else WorkspaceIgnoreMatcher(rules + parsed)
    }

    fun isIgnored(path: String, isDirectory: Boolean): Boolean {
        val normalized = path.normalizeWorkspacePath()
        var ignored = false
        rules.forEach { rule ->
            if (rule.matches(normalized, isDirectory)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    /** 判定路径自身或其任一祖先目录被忽略（目录级忽略 = 整棵子树不可见） */
    fun isPathIgnored(path: String): Boolean {
        val normalized = path.normalizeWorkspacePath()
        if (normalized.isBlank()) return false
        val parts = normalized.split('/').filter { it.isNotBlank() }
        var prefix = ""
        for (i in parts.indices) {
            prefix = if (i == 0) parts[i] else "$prefix/${parts[i]}"
            val isDir = i < parts.lastIndex
            if (isIgnored(prefix, isDir)) return true
        }
        return false
    }

    /**
     * 保守剪枝 glob（绝对路径，相对 [workspaceRoot]）：
     * - 规则集合含任何否定（!）→ 不剪（只靠过滤兜底）；
     * - 含 glob 元字符（* ? [ \）→ 跳过（避免与 find 通配语义差异导致过剪）；
     * - 锚定/路径模式 → 精确单路径；无斜杠名字模式 → 直接子目录 + `*` 深度形式（恒为被忽略目录集合的子集，安全）。
     */
    fun pruneGlobs(workspaceRoot: String = "/workspace"): List<String> {
        if (rules.any { it.negated }) return emptyList()
        val globs = LinkedHashSet<String>()
        for (rule in rules) {
            val pattern = rule.pattern
            if (pattern.isEmpty()) continue
            if (pattern.any { it == '*' || it == '?' || it == '[' || it == '\\' }) continue
            val base = if (rule.basePath.isEmpty()) workspaceRoot else "$workspaceRoot/${rule.basePath}"
            if (rule.anchored || rule.hasSlash) {
                globs += "$base/$pattern"
            } else {
                globs += "$base/$pattern"
                globs += "$base/*/$pattern"
            }
        }
        return globs.toList()
    }

    private data class Rule(
        val basePath: String,
        val pattern: String,
        val regex: Regex,
        val negated: Boolean,
        val directoryOnly: Boolean,
        val anchored: Boolean,
        val hasSlash: Boolean,
    ) {
        fun matches(path: String, isDirectory: Boolean): Boolean {
            if (directoryOnly && !isDirectory) return false
            val relative = path.relativeToBaseOrNull(basePath) ?: return false
            if (relative.isBlank()) return false
            val target = if (anchored || hasSlash) relative else relative.substringAfterLast('/')
            return regex.matches(target)
        }

        companion object {
            fun parse(basePath: String, rawLine: String): Rule? {
                var line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return null
                if (line.startsWith("\\#") || line.startsWith("\\!")) {
                    line = line.drop(1)
                }

                val negated = line.startsWith("!")
                if (negated) line = line.drop(1).trim()
                if (line.isBlank()) return null

                val directoryOnly = line.endsWith("/")
                val anchored = line.startsWith("/")
                val pattern = line
                    .trim('/')
                    .takeIf { it.isNotBlank() }
                    ?: return null
                val hasSlash = pattern.contains('/')
                return Rule(
                    basePath = basePath,
                    pattern = pattern,
                    regex = pattern.toGitignoreRegex(),
                    negated = negated,
                    directoryOnly = directoryOnly,
                    anchored = anchored,
                    hasSlash = hasSlash,
                )
            }
        }
    }

    companion object {
        private val DEFAULT_RULES = listOf(
            "build/",
            ".gradle/",
            ".git/",
            "node_modules/",
            "dist/",
            "out/",
        ).mapNotNull { Rule.parse(basePath = "", rawLine = it) }
    }
}

private fun String.normalizeWorkspacePath(): String =
    replace('\\', '/').trim().trim('/')

private fun String.relativeToBaseOrNull(basePath: String): String? {
    if (basePath.isBlank()) return this
    if (this == basePath) return ""
    val prefix = "$basePath/"
    return if (startsWith(prefix)) removePrefix(prefix) else null
}

private fun String.toGitignoreRegex(): Regex {
    val result = StringBuilder()
    var index = 0
    while (index < length) {
        val char = this[index]
        when (char) {
            '*' -> {
                val nextIsStar = getOrNull(index + 1) == '*'
                if (nextIsStar) {
                    val followedBySlash = getOrNull(index + 2) == '/'
                    if (followedBySlash) {
                        result.append("(?:.*/)?")
                        index += 3
                    } else {
                        result.append(".*")
                        index += 2
                    }
                } else {
                    result.append("[^/]*")
                    index++
                }
            }

            '?' -> {
                result.append("[^/]")
                index++
            }

            else -> {
                if (char in REGEX_SPECIAL_CHARS) result.append('\\')
                result.append(char)
                index++
            }
        }
    }
    return Regex("^$result$")
}

private const val REGEX_SPECIAL_CHARS = "\\.[]{}()+-^$|"
