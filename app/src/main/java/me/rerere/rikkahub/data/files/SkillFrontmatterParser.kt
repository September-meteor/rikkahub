package me.rerere.rikkahub.data.files

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): SkillFrontmatter = parseWithDiagnostics(content).frontmatter

    /** YAML 解析诊断信息，供上层给出精准的修复提示 */
    data class Diagnostics(
        /** YAML 是否成功解析为键值映射 */
        val parseSucceeded: Boolean = false,
        /** YAML 语法错误所在行（1-based，对应原文件行号）；无法定位时为 null */
        val errorLine: Int? = null,
        /** 解析成功但缺少有效的 name 字段 */
        val missingName: Boolean = false,
    )

    data class ParseResult(
        val frontmatter: SkillFrontmatter,
        val diagnostics: Diagnostics,
    )

    fun parseWithDiagnostics(content: String): ParseResult {
        if (!content.startsWith("---")) return ParseResult(SkillFrontmatter.Empty, Diagnostics())
        val endRange = findFrontmatterEndRange(content) ?: return ParseResult(SkillFrontmatter.Empty, Diagnostics())
        // 保留 "---" 后的前导换行，便于把 YAML 报错行号换算回原文件行号
        val yamlContent = content.substring(3, endRange.first)
        if (yamlContent.isBlank()) {
            return ParseResult(SkillFrontmatter.Empty, Diagnostics(parseSucceeded = true, missingName = true))
        }
        return try {
            val loaded = createYaml().load<Any?>(yamlContent) as? Map<*, *>
            if (loaded == null) {
                ParseResult(SkillFrontmatter.Empty, Diagnostics())
            } else {
                val values = loaded.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
                ParseResult(
                    frontmatter = SkillFrontmatter(values),
                    diagnostics = Diagnostics(
                        parseSucceeded = true,
                        missingName = (values["name"] as? String)?.isNotBlank() != true,
                    ),
                )
            }
        } catch (e: MarkedYAMLException) {
            // SnakeYAML 的行号相对 yamlContent（紧跟首个 --- 之后），故 +1 即原文件行号
            ParseResult(SkillFrontmatter.Empty, Diagnostics(errorLine = e.problemMark?.line?.plus(1)))
        } catch (e: YAMLException) {
            ParseResult(SkillFrontmatter.Empty, Diagnostics())
        } catch (e: Exception) {
            // 兜底：不因解析器异常影响保存流程
            ParseResult(SkillFrontmatter.Empty, Diagnostics())
        }
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    /** frontmatter 中冒号后缺少空格的顶层行（如 `description:"..."`、`name:x`），行号为 1-based 原文件行号 */
    data class MissingSpaceLine(
        val lineNumber: Int,
        val key: String,
    )

    /**
     * 检测 frontmatter 里所有"key:值"（冒号后缺少空格）的顶层行。
     * SnakeYAML 对这类行不会稳定报错：值以引号开头时会静默解析成垃圾键值对，
     * 因此需要独立的语法约定检查。只匹配无缩进的顶层键，避免误伤块标量内的文本。
     */
    fun findMissingSpaceLines(content: String): List<MissingSpaceLine> {
        if (!content.startsWith("---")) return emptyList()
        val end = content.indexOf("\n---", startIndex = 3)
        // 只取首个 --- 到关闭 --- 之间的内容（无关闭标记时取到文件尾，属已损坏文件，尽力提示）
        val frontmatter = content.substring(3, if (end >= 0) end else content.length)
        val lineRegex = Regex("^([A-Za-z_][A-Za-z0-9_-]*):(\\S.*)$")
        return frontmatter.lineSequence().withIndex().mapNotNull { (index, rawLine) ->
            val m = lineRegex.find(rawLine.trimEnd()) ?: return@mapNotNull null
            // index 从 0 计；frontmatter 首行是空行（紧跟开头的 ---），故真实行号 = index + 1
            MissingSpaceLine(lineNumber = index + 1, key = m.groupValues[1])
        }.toList()
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }
}

class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
