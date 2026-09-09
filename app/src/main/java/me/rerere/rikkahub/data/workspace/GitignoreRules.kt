package me.rerere.rikkahub.data.workspace

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 统一的 .gitignore 解析 / 匹配引擎（原 data.sync.WorkspaceIgnoreRules 与
 * data.ai.workspace.WorkspaceIgnoreMatcher 合并而来，导入 / 导出 / 同步、聊天变更
 * 列表与 find 剪枝、`@` 文件补全共用同一套解析与求值，避免各处各自维护一份语义）。
 *
 * 规则来源（按求值优先级从低到高）：
 * 1. [includeDefaults] 内置默认规则（仅 `@` 补全需要）
 * 2. 各级目录 .gitignore（根目录 → 子目录，子目录覆盖父目录）
 * 3. [customIgnorePatterns] 自定义规则：解析为**根级**规则并最后求值，
 *    可覆盖任意层级 .gitignore（忽略与 `!` 保留都压过 .gitignore）；
 *    多条自定义规则之间按书写顺序 last-match-wins。
 *
 * key 约定：目录相对 workspace / 导入 / 同步根目录的路径，根目录为 ""，
 * 子目录为 "src"、"src/sub" 等。
 *
 * .gitignore 匹配语义（与 git 对齐）：
 * - 无斜杠模式（build）：匹配任意层级的同名条目
 * - 含斜杠模式（app/build）：相对 .gitignore 所在目录的路径匹配，
 *   目录命中后其下所有内容一并忽略（由遍历剪枝天然保证）
 * - 以 / 开头（/build）：锚定 .gitignore 所在目录
 * - 以 / 结尾（build/）：只匹配目录
 * - `*` / `?` 不跨 `/`；`**` 跨目录匹配（前导、尾部、中间位置）
 * - `[abc]` / `[!abc]` 字符类；`\X` 转义字面量；尾随空格默认忽略（\ 转义则保留）
 *
 * 使用形态：
 * - 导入 / 同步 / 导出：可变加载（[loadGitignore]），边遍历边挂规则，[shouldIgnore] 逐条目判定
 * - 变更列表 / 补全：不可变叠加（[withGitignore] 返回副本），[isIgnored] / [isPathIgnored] 判定
 */
class GitignoreRules(
    private val enableGitignore: Boolean,
    customIgnorePatterns: String,
    private val includeDefaults: Boolean = false,
) {
    private data class Rule(
        val regex: Regex,
        val negated: Boolean,
        val directoryOnly: Boolean, // 以 / 结尾，只匹配目录
        val anchored: Boolean,      // 以 / 开头，锚定规则所在目录
        val pattern: String,        // 清理后的模式文本（无 !、前导/尾随 /）
        val baseKey: String,        // 规则所在 .gitignore 目录（相对根，根为 ""）
        val hasSlash: Boolean,      // 模式含 /：按相对路径匹配
    ) {
        companion object {
            /**
             * 解析单行 .gitignore。注释 / 空行 / 无法编译的正则返回 null。
             * 与 git 对齐：去 CR、尾随空格默认忽略（\ 转义保留）、`#` 注释、
             * `!` 否定（`\!` 转义为字面量）、`/` 结尾目录专用、`/` 开头锚定。
             */
            fun parse(baseKey: String, rawLine: String): Rule? {
                // 去掉 CR（兼容 CRLF），空行跳过
                var text = rawLine.trimEnd('\r')
                if (text.isBlank()) return null
                // 尾随空格默认忽略；以 \ 转义（\ ）时保留一个字面空格
                val noTrailingSpaces = text.trimEnd(' ')
                text = if (noTrailingSpaces.endsWith("\\") && noTrailingSpaces.length < text.length) {
                    noTrailingSpaces.dropLast(1) + " "
                } else {
                    noTrailingSpaces
                }
                // 注释（\# 转义后视为字面量，不以 # 开头，自然跳过此处）
                if (text.startsWith("#")) return null

                // 否定：! 开头（\! 转义则视为字面量）
                val isNegation = text.startsWith("!") && !text.startsWith("\\!")
                val rawPattern = if (isNegation) text.substring(1) else text

                // 目录专用：/ 结尾（\/ 转义则视为字面量）
                val isDirectoryOnly = rawPattern.endsWith("/") && !rawPattern.endsWith("\\/")
                val cleanPattern = if (isDirectoryOnly) rawPattern.dropLast(1) else rawPattern

                // 锚定：/ 开头（\/ 转义则视为字面量）
                val isAnchored = cleanPattern.startsWith("/") && !cleanPattern.startsWith("\\/")
                val patternText = if (isAnchored) cleanPattern.substring(1) else cleanPattern
                if (patternText.isBlank()) return null

                val regex = patternText.toGitignoreRegex() ?: return null
                return Rule(
                    regex = regex,
                    negated = isNegation,
                    directoryOnly = isDirectoryOnly,
                    anchored = isAnchored,
                    pattern = patternText,
                    baseKey = baseKey,
                    hasSlash = patternText.hasUnescapedSlash(),
                )
            }
        }
    }

    // 内置默认规则（仅补全场景）：可被 .gitignore / 自定义规则覆盖
    private val defaults: MutableList<Rule> =
        if (includeDefaults) DEFAULT_RULES.mapNotNull { Rule.parse("", it) }.toMutableList() else mutableListOf()

    // 根目录 .gitignore 的规则（key = ""）
    private val rootFileRules = mutableListOf<Rule>()

    // 各子目录 .gitignore 的规则（key 相对根）
    private val dirRules = mutableMapOf<String, MutableList<Rule>>()

    // 自定义规则：最后求值，优先级最高（忽略与 ! 保留都压过任何 .gitignore）
    private val customRules: MutableList<Rule> = customIgnorePatterns
        .split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { Rule.parse("", it) }
        .toMutableList()

    // ---------------------------------------------------------------- 加载

    /** 加载 [dir] 目录下的 .gitignore（SAF 侧），规则以 [key] 为基准目录（可变，供遍历流使用） */
    fun loadGitignore(
        dir: DocumentFile,
        key: String,
        resolver: ContentResolver,
    ) {
        if (!enableGitignore) return
        val gitignoreDoc = dir.findFile(".gitignore")
        if (gitignoreDoc == null || !gitignoreDoc.isFile) return
        resolver.openInputStream(gitignoreDoc.uri)
            ?.bufferedReader()?.useLines { lines -> store(key, lines) }
    }

    /** 加载 [dir] 目录下的 .gitignore（本地磁盘侧），用于导出/复制前的排除 */
    fun loadGitignore(
        dir: File,
        key: String,
    ) {
        if (!enableGitignore) return
        val gitignoreFile = File(dir, ".gitignore")
        if (!gitignoreFile.isFile) return
        gitignoreFile.useLines { lines -> store(key, lines) }
    }

    /** 追加 [content]（[directory] 目录的 .gitignore 内容）并返回新实例（不可变，供过滤/补全缓存使用） */
    fun withGitignore(directory: String, content: String): GitignoreRules {
        if (content.isBlank()) return this
        return clone().apply { store(directory, content.lineSequence()) }
    }

    private fun store(key: String, lines: Sequence<String>) {
        val target = if (key.isEmpty()) rootFileRules else dirRules.getOrPut(key) { mutableListOf() }
        lines.forEach { line ->
            Rule.parse(key, line)?.let { target += it }
        }
    }

    // ---------------------------------------------------------------- 求值

    /**
     * 判断 [name]（位于 parentDir 目录下）是否应被排除。
     * 供导入 / 同步 / 导出的树遍历使用：逐条调用，目录命中即剪枝（与旧实现约定一致）。
     */
    fun shouldIgnore(
        name: String,
        parentDir: String,
        isDirectory: Boolean,
    ): Boolean {
        val relPath = if (parentDir.isEmpty()) name else "$parentDir/$name"
        return isEntryIgnored(relPath, isDirectory)
    }

    /** 判断路径（相对 workspace 根）自身是否被忽略（不含祖先；供 `@` 补全逐条目判定） */
    fun isIgnored(path: String, isDirectory: Boolean): Boolean =
        isEntryIgnored(path.normalizeRel(), isDirectory)

    /** 判断路径自身或其任一祖先目录被忽略（目录级忽略 = 整棵子树不可见） */
    fun isPathIgnored(path: String): Boolean {
        val normalized = path.normalizeRel()
        if (normalized.isBlank()) return false
        val parts = normalized.split('/').filter { it.isNotBlank() }
        var prefix = ""
        for (i in parts.indices) {
            prefix = if (i == 0) parts[i] else "$prefix/${parts[i]}"
            if (isIgnored(prefix, i < parts.lastIndex)) return true
        }
        return false
    }

    /**
     * 单条目求值：按「根默认 → 根 .gitignore → 祖先链上的子目录 .gitignore →
     * 自定义规则（最后，最高优先级）」的顺序应用，last-match-wins。
     */
    private fun isEntryIgnored(relPath: String, isDirectory: Boolean): Boolean {
        if (relPath.isBlank()) return false
        val parentDir = relPath.substringBeforeLast('/', "")
        var ignored = false

        fun apply(rules: List<Rule>) {
            for (rule in rules) {
                val matches = when {
                    // 锚定 / 路径模式：以规则所在目录为基准的相对路径匹配
                    rule.anchored || rule.hasSlash -> {
                        val relative = if (rule.baseKey.isEmpty()) {
                            relPath
                        } else if (relPath.startsWith("${rule.baseKey}/")) {
                            relPath.removePrefix("${rule.baseKey}/")
                        } else {
                            null
                        }
                        relative != null && relative.matches(rule.regex)
                    }
                    // 无斜杠模式：匹配任意层级的同名条目
                    else -> relPath.substringAfterLast('/').matches(rule.regex)
                }
                if (!matches) continue
                // 目录专用规则（x/）不匹配同名文件
                if (rule.directoryOnly && !isDirectory) continue
                ignored = !rule.negated
            }
        }

        // 祖先链（含父目录）由根到近；父目录规则先应用、子目录规则后应用（覆盖）
        val chain = mutableListOf<String>()
        var ancestor = parentDir
        while (true) {
            chain += ancestor
            if (ancestor.isEmpty()) break
            ancestor = ancestor.substringBeforeLast('/', "")
        }
        for (key in chain.asReversed()) {
            if (key.isEmpty()) {
                apply(defaults)
                apply(rootFileRules)
            } else {
                dirRules[key]?.let { apply(it) }
            }
        }
        apply(customRules)
        return ignored
    }

    // ---------------------------------------------------------------- find 剪枝

    /**
     * 保守剪枝 glob（绝对路径，相对 [workspaceRoot]），供变更列表 find 使用：
     * - 规则集合含任何否定（!）→ 不剪（只靠过滤兜底）
     * - 含 glob 元字符（* ? [ \）→ 跳过（避免与 find 通配语义差异导致过剪）
     * - 锚定/路径模式 → 精确单路径
     * - 无斜杠名字模式 → 直接子级 glob 加通配展开的深度变体；find -path 的通配可跨 `/`，
     *   实际覆盖 base 下任意深度的同名目录（正是 no-slash 的 git 语义），
     *   命令端有 `-type d` 守卫，只剪目录、不剪同名文件，恒为被忽略目录集合的子集，安全
     */
    fun pruneGlobs(workspaceRoot: String = "/workspace"): List<String> {
        val allRules = buildList {
            addAll(defaults)
            addAll(rootFileRules)
            dirRules.values.forEach { addAll(it) }
            addAll(customRules)
        }
        if (allRules.any { it.negated }) return emptyList()
        val globs = LinkedHashSet<String>()
        fun collect(rules: List<Rule>) {
            for (rule in rules) {
                val pattern = rule.pattern
                if (pattern.isEmpty()) continue
                if (pattern.any { it == '*' || it == '?' || it == '[' || it == '\\' }) continue
                val base = if (rule.baseKey.isEmpty()) workspaceRoot else "$workspaceRoot/${rule.baseKey}"
                if (rule.anchored || rule.hasSlash) {
                    globs += "$base/$pattern"
                } else {
                    globs += "$base/$pattern"
                    globs += "$base/*/$pattern"
                }
            }
        }
        collect(defaults)
        collect(rootFileRules)
        dirRules.values.forEach { collect(it) }
        collect(customRules)
        return globs.toList()
    }

    // ---------------------------------------------------------------- 内部

    private fun clone(): GitignoreRules {
        val copy = GitignoreRules(enableGitignore, "", includeDefaults)
        copy.defaults.clear()
        copy.defaults.addAll(defaults)
        copy.customRules.clear()
        copy.customRules.addAll(customRules)
        copy.rootFileRules.addAll(rootFileRules)
        for ((key, rules) in dirRules) {
            copy.dirRules.getOrPut(key) { mutableListOf() }.addAll(rules)
        }
        return copy
    }

    companion object {
        private val DEFAULT_RULES = listOf(
            "build/",
            ".gradle/",
            ".git/",
            "node_modules/",
            "dist/",
            "out/",
        )
    }
}

/** 路径规范化：反斜杠转正斜杠、去掉首尾 / */
private fun String.normalizeRel(): String =
    replace('\\', '/').trim('/')

/**
 * glob → 正则（对齐 git 语义）：
 * - `*` / `?` 不跨 `/`
 * - 双星号跨目录：前导「双星号+斜杠」匹配零或多个目录段；尾部「斜杠+双星号」匹配目录下所有内容；
 *   其他连续星号按普通 `*` 处理
 * - `[...]` 字符类，`[!...]` 否定字符类；未闭合 `[` 按字面量
 * - `\X` 转义为字面 X
 * 无法编译时返回 null（该条规则被跳过）。
 */
private fun String.toGitignoreRegex(): Regex? {
    val sb = StringBuilder("^")
    var i = 0
    while (i < this.length) {
        val c = this[i]
        when {
            // \X → 字面 X
            c == '\\' && i + 1 < this.length -> {
                sb.append(Regex.escape(this[i + 1].toString()))
                i += 2
            }
            // **
            c == '*' && i + 1 < this.length && this[i + 1] == '*' -> {
                if (i + 2 < this.length && this[i + 2] == '/') {
                    // **/ → 零或多个目录段
                    sb.append("(?:[^/]+/)*")
                    i += 3
                } else if (i + 2 == this.length && i > 0 && this[i - 1] == '/') {
                    // 尾部 /**（前面的 / 已作为普通字符输出）：匹配目录下所有内容（跨 /）
                    sb.append(".*")
                    i += 2
                } else {
                    // 其他连续星号按普通 *（git: consecutive asterisks are regular）
                    sb.append("[^/]*")
                    i += 2
                }
            }
            c == '*' -> {
                sb.append("[^/]*")
                i++
            }
            c == '?' -> {
                sb.append("[^/]")
                i++
            }
            c == '[' -> {
                val end = this.indexOf(']', i + 1)
                if (end == -1) {
                    // 未闭合 [：按字面量
                    sb.append("\\[")
                    i++
                } else {
                    var content = this.substring(i + 1, end)
                    val negated = content.startsWith("!")
                    if (negated) content = content.substring(1)
                    val safe = buildString {
                        for (ch in content) {
                            when (ch) {
                                '\\', ']', '^' -> append('\\').append(ch)
                                else -> append(ch) // '-' 保留用于范围
                            }
                        }
                    }
                    sb.append('[')
                    if (negated) sb.append('^')
                    sb.append(safe).append(']')
                    i = end + 1
                }
            }
            // 正则特殊字符转义
            c == '.' || c == '+' || c == '(' || c == ')' || c == '|' ||
                c == '^' || c == '$' || c == '{' || c == '}' || c == '\\' -> {
                sb.append('\\').append(c)
                i++
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    sb.append('$')
    return try {
        sb.toString().toRegex()
    } catch (e: Exception) {
        null
    }
}

/** 模式中是否存在未转义的 /（决定按路径模式匹配） */
private fun String.hasUnescapedSlash(): Boolean {
    var i = 0
    while (i < this.length) {
        val c = this[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == '/') return true
        i++
    }
    return false
}
