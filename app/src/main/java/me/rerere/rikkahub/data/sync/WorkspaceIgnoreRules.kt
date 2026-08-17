package me.rerere.rikkahub.data.sync

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

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
 */
class WorkspaceIgnoreRules(
    private val enableGitignore: Boolean,
    customIgnorePatterns: String,
) {
    private data class IgnoreRule(
        val regex: Regex,
        val isNegation: Boolean,
        val isAnchored: Boolean,      // 以 / 开头，只匹配直接子项
        val isDirectoryOnly: Boolean, // 以 / 结尾，只匹配目录
        val rawPattern: String,
    )

    private val userPatterns: Set<String> = customIgnorePatterns
        .split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val gitignoreRules = mutableMapOf<String, MutableList<IgnoreRule>>()

    /** 加载 [dir] 目录下的 .gitignore，规则以 [key]（该目录在遍历中的标识）为索引 */
    fun loadGitignore(
        dir: DocumentFile,
        key: String,
        resolver: ContentResolver,
    ) {
        if (!enableGitignore) return
        val gitignoreDoc = dir.findFile(".gitignore")
        if (gitignoreDoc == null || !gitignoreDoc.isFile) return
        val rules = mutableListOf<IgnoreRule>()
        resolver.openInputStream(gitignoreDoc.uri)
            ?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                    val isNegation = trimmed.startsWith("!")
                    val rawPattern = if (isNegation) trimmed.substring(1) else trimmed

                    val isDirectoryOnly = rawPattern.endsWith("/")
                    val cleanPattern = if (isDirectoryOnly) rawPattern.dropLast(1) else rawPattern

                    val isAnchored = cleanPattern.startsWith("/")
                    val patternText = if (isAnchored) cleanPattern.substring(1) else cleanPattern

                    // 简单 glob → regex：支持 * 和 ?
                    val regexText = patternText
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")

                    val regex = try {
                        regexText.toRegex()
                    } catch (e: Exception) {
                        return@forEach
                    }
                    rules.add(
                        IgnoreRule(
                            regex = regex,
                            isNegation = isNegation,
                            isAnchored = isAnchored,
                            isDirectoryOnly = isDirectoryOnly,
                            rawPattern = patternText,
                        )
                    )
                }
            }
        if (rules.isNotEmpty()) {
            gitignoreRules.getOrPut(key) { mutableListOf() }.addAll(rules)
        }
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
        val rules = gitignoreRules[parentDir] ?: return false

        var ignored = false
        for (rule in rules) {
            val matches = when {
                // /build 只匹配当前目录下的直接子项 build
                rule.isAnchored -> name == rule.rawPattern || name.matches(rule.regex)
                // build 匹配任何层级的 build
                else -> name.matches(rule.regex)
            }
            if (!matches) continue
            // build/ 只忽略目录，不忽略同名文件
            if (rule.isDirectoryOnly && !isDirectory) continue
            ignored = if (rule.isNegation) false else true
        }
        return ignored
    }
}
