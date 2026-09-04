package me.rerere.rikkahub.data.sync

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 目录导入 / 同步共用的排除规则。
 *
 * 保证「导入时的 shouldIgnore 逻辑」与「同步回原目录时的保护逻辑」完全一致：
 * 被 .gitignore 或自定义模式排除的内容，外部目录中保持不动。
 *
 * 规则来源：
 * 1. 用户自定义模式（逗号 / 中文逗号 / 换行分隔，支持 * ? 与 / 后缀）
 * 2. 遍历外部目录时递归加载的 .gitignore
 *
 * key 约定（导入与同步完全一致）：相对导入/同步根目录的路径，根目录为 ""，
 * 子目录为 "src"、"src/sub" 等。规则缓存与 shouldIgnore 的 parentDir 使用同一约定。
 *
 * .gitignore 匹配语义（与 git 对齐）：
 * - 无斜杠模式（build）：匹配任意层级的同名条目（所在目录及其子树）
 * - 含斜杠模式（app/build）：相对 .gitignore 所在目录的路径匹配，
 *   目录命中后其下所有内容一并忽略（由遍历剪枝天然保证）
 * - 以 / 开头（/build）：锚定 .gitignore 所在目录
 * - 以 / 结尾（build/）：只匹配目录
 * - `*` / `?` 不跨 `/`；`**` 跨目录匹配（前导、尾部、中间位置）
 * - `[abc]` / `[!abc]` 字符类；`\X` 转义字面量；尾随空格默认忽略（\ 转义则保留）
 */
class WorkspaceIgnoreRules(
    private val enableGitignore: Boolean,
    customIgnorePatterns: String,
) {
    private data class IgnoreRule(
        val regex: Regex,
        val isNegation: Boolean,
        val isAnchored: Boolean,      // 以 / 开头，锚定 .gitignore 所在目录
        val isDirectoryOnly: Boolean, // 以 / 结尾，只匹配目录
        val rawPattern: String,
        val baseKey: String,          // 规则来源 .gitignore 所在目录（相对根，根为 ""）
        val isPathPattern: Boolean,   // 模式含 /：按相对路径匹配（如 app/build）
    )

    private val userPatterns: Set<String> = customIgnorePatterns
        .split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val gitignoreRules = mutableMapOf<String, MutableList<IgnoreRule>>()

    /** 加载 [dir] 目录下的 .gitignore（SAF 侧），规则以 [key]（该目录在遍历中的标识）为索引 */
    fun loadGitignore(
        dir: DocumentFile,
        key: String,
        resolver: ContentResolver,
    ) {
        if (!enableGitignore) return
        val gitignoreDoc = dir.findFile(".gitignore")
        if (gitignoreDoc == null || !gitignoreDoc.isFile) return
        resolver.openInputStream(gitignoreDoc.uri)
            ?.bufferedReader()?.useLines { lines -> parseGitignore(lines, key) }
    }

    /** 加载 [dir] 目录下的 .gitignore（本地磁盘侧），用于工作区内容导出/复制前的排除剪枝 */
    fun loadGitignore(
        dir: File,
        key: String,
    ) {
        if (!enableGitignore) return
        val gitignoreFile = File(dir, ".gitignore")
        if (!gitignoreFile.isFile) return
        gitignoreFile.useLines { lines -> parseGitignore(lines, key) }
    }

    /** 解析 .gitignore 内容并挂到 [key] 目录索引下（与 git 对齐的语义见文件头注释） */
    private fun parseGitignore(lines: Sequence<String>, key: String) {
        val rules = mutableListOf<IgnoreRule>()
        lines.forEach { line ->
            // 去掉 CR（兼容 CRLF），空行跳过
            var text = line.trimEnd('\r')
            if (text.isBlank()) return@forEach
            // 尾随空格默认忽略；以 \ 转义（\ ）时保留一个字面空格
            val noTrailingSpaces = text.trimEnd(' ')
            text = if (noTrailingSpaces.endsWith("\\") && noTrailingSpaces.length < text.length) {
                noTrailingSpaces.dropLast(1) + " "
            } else {
                noTrailingSpaces
            }
            // 注释（\# 转义后视为字面量，不以 # 开头，自然跳过此处）
            if (text.startsWith("#")) return@forEach

            // 否定：! 开头（\! 转义则视为字面量）
            val isNegation = text.startsWith("!") && !text.startsWith("\\!")
            val rawPattern = if (isNegation) text.substring(1) else text

            // 目录专用：/ 结尾（\/ 转义则视为字面量）
            val isDirectoryOnly = rawPattern.endsWith("/") && !rawPattern.endsWith("\\/")
            val cleanPattern = if (isDirectoryOnly) rawPattern.dropLast(1) else rawPattern

            // 锚定：/ 开头（\/ 转义则视为字面量）
            val isAnchored = cleanPattern.startsWith("/") && !cleanPattern.startsWith("\\/")
            val patternText = if (isAnchored) cleanPattern.substring(1) else cleanPattern

            val regex = globToRegex(patternText) ?: return@forEach
            rules.add(
                IgnoreRule(
                    regex = regex,
                    isNegation = isNegation,
                    isAnchored = isAnchored,
                    isDirectoryOnly = isDirectoryOnly,
                    rawPattern = patternText,
                    baseKey = key,
                    isPathPattern = hasUnescapedSlash(patternText),
                )
            )
        }
        if (rules.isNotEmpty()) {
            gitignoreRules.getOrPut(key) { mutableListOf() }.addAll(rules)
        }
    }

    /**
     * glob → 正则（对齐 git 语义）：
     * - `*` / `?` 不跨 `/`
     * - 双星号跨目录：前导「双星号+斜杠」匹配零或多个目录段；尾部「斜杠+双星号」匹配目录下所有内容；
     *   其他连续星号按普通 `*` 处理
     * - `[...]` 字符类，`[!...]` 否定字符类；未闭合 `[` 按字面量
     * - `\X` 转义为字面 X
     * 无法编译时返回 null（该条规则被跳过）。
     */
    private fun globToRegex(pattern: String): Regex? {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                // \X → 字面 X
                c == '\\' && i + 1 < pattern.length -> {
                    sb.append(Regex.escape(pattern[i + 1].toString()))
                    i += 2
                }
                // **
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        // **/ → 零或多个目录段
                        sb.append("(?:[^/]+/)*")
                        i += 3
                    } else if (i + 2 == pattern.length && i > 0 && pattern[i - 1] == '/') {
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
                    val end = pattern.indexOf(']', i + 1)
                    if (end == -1) {
                        // 未闭合 [：按字面量
                        sb.append("\\[")
                        i++
                    } else {
                        var content = pattern.substring(i + 1, end)
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
    private fun hasUnescapedSlash(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '/') return true
            i++
        }
        return false
    }

    /** 判断 name（位于 parentDir 目录下）是否应被排除 */
    fun shouldIgnore(
        name: String,
        parentDir: String,
        isDirectory: Boolean,
    ): Boolean {
        // 1. 用户自定义模式（支持 * ? 和 / 后缀）
        if (userPatterns.any { pattern ->
                when {
                    pattern.endsWith("/") -> isDirectory && name == pattern.dropLast(1)
                    pattern.contains("*") || pattern.contains("?") -> name.matches(
                        pattern.replace(".", "\\.")
                            .replace("*", ".*")
                            .replace("?", ".")
                            .toRegex()
                    )
                    else -> name == pattern
                }
            }) return true

        // 2. .gitignore 规则
        if (!enableGitignore) return false
        // 当前条目相对同步/导入根目录的完整路径（如 app/build）
        val relPath = if (parentDir.isEmpty()) name else "$parentDir/$name"

        var ignored = false
        // 只应用「祖先链」上的 .gitignore：父目录规则先应用、子目录规则后应用（覆盖），
        // 与 git 的「父目录规则对子孙可见、子目录规则优先级更高」语义一致。
        val chain = mutableListOf<String>()
        var ancestor = parentDir
        while (true) {
            chain += ancestor
            if (ancestor.isEmpty()) break
            ancestor = ancestor.substringBeforeLast('/', "")
        }
        for (key in chain.asReversed()) {
            gitignoreRules[key]?.let { rules ->
                for (rule in rules) {
                    val matches = when {
                        // 锚定（/build）与路径模式（app/build）：以 .gitignore 所在目录为基准的
                        // 相对路径匹配；目录命中后由遍历剪枝保证其下内容一并忽略
                        rule.isAnchored || rule.isPathPattern -> {
                            val relative = if (rule.baseKey.isEmpty()) {
                                relPath
                            } else if (relPath.startsWith("${rule.baseKey}/")) {
                                relPath.removePrefix("${rule.baseKey}/")
                            } else {
                                null
                            }
                            relative != null && relative.matches(rule.regex)
                        }
                        // 无斜杠模式（build）：匹配任意层级的同名条目
                        else -> name.matches(rule.regex)
                    }
                    if (!matches) continue
                    // build/ 只忽略目录，不忽略同名文件
                    if (rule.isDirectoryOnly && !isDirectory) continue
                    ignored = if (rule.isNegation) false else true
                }
            }
        }
        return ignored
    }
}
