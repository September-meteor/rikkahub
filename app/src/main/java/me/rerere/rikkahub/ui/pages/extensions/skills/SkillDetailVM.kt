package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File

data class SkillFile(
    val file: File,
    val relativePath: String,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

class SkillDetailVM(
    private val context: Context,
    private val skillManager: SkillManager,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = skillManager.getSkillDir(skillName) ?: return@launch
            _tree.value = buildTree(dir, dir)
        }
    }

    private fun buildTree(root: File, dir: File): List<SkillFileNode> {
        val items = dir.listFiles()?.toList() ?: return emptyList()
        val files = items
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name }))
            .map { f -> SkillFileNode.FileNode(SkillFile(f, f.relativeTo(root).path)) }
        val dirs = items
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { d -> SkillFileNode.DirNode(d.name, d.relativeTo(root).path, buildTree(root, d)) }
        return dirs + files
    }

    suspend fun readFile(skillFile: SkillFile): String =
        withContext(Dispatchers.IO) { skillFile.file.readText() }

    // error != null：保存被拒绝（如改名），弹窗保持打开；
    // error == null：已保存（弹窗关闭）；warning != null 时表示文件格式仍不正确，需要继续修复
    fun saveFile(
        relativePath: String,
        content: String,
        onResult: (error: String?, warning: String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (relativePath == "SKILL.md") {
                val parsed = SkillFrontmatterParser.parseWithDiagnostics(content)
                val parsedName = parsed.frontmatter["name"]?.trim()
                if (!parsedName.isNullOrEmpty() && parsedName != skillName) {
                    // 仅当 frontmatter 可解析、且 name 确实被改成了其他值时，才判定为改名
                    withContext(Dispatchers.Main) {
                        onResult(context.getString(R.string.skill_detail_page_rename_forbidden, skillName), null)
                    }
                    return@launch
                }
                val success = skillManager.saveSkillFile(skillName, relativePath, content)
                if (!success) {
                    withContext(Dispatchers.Main) {
                        onResult(context.getString(R.string.skill_detail_page_save_failed), null)
                    }
                    return@launch
                }
                loadFiles()
                // 保存成功但内容仍非规范格式（缺空格 / YAML 报错 / 缺 name / 缺 description）时，
                // 弹窗保持打开并给出最短的定位提示，让用户持续迭代到格式正确
                val warning = buildSkillFormatWarning(content, parsed, parsedName)
                withContext(Dispatchers.Main) { onResult(null, warning) }
                return@launch
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            withContext(Dispatchers.Main) {
                onResult(if (success) null else context.getString(R.string.skill_detail_page_save_failed), null)
            }
        }
    }

    /**
     * 保存后校验 SKILL.md：仍不合规时返回最短定位提示（不罗列原因、不截断内容），合规返回 null。
     * 校验口径与 SkillManager 的 broken 判定一致，避免"列表标红但保存无提示"的缺口。
     */
    private fun buildSkillFormatWarning(
        content: String,
        parsed: SkillFrontmatterParser.ParseResult,
        parsedName: String?,
    ): String? {
        // 1) 冒号后缺空格：SnakeYAML 可能静默吞掉（值以引号开头时解析成垃圾键），需逐行检出
        val colonLines = SkillFrontmatterParser.findMissingSpaceLines(content)
        if (colonLines.isNotEmpty()) {
            return colonLines.joinToString("\n") { line ->
                context.getString(
                    R.string.skill_detail_page_invalid_colon_hint,
                    line.lineNumber,
                    line.key,
                )
            }
        }
        val diagnostics = parsed.diagnostics
        // 2) YAML 解析器给出的报错行号
        if (diagnostics.errorLine != null) {
            return context.getString(R.string.skill_detail_page_invalid_yaml_line, diagnostics.errorLine)
        }
        // 3) 解析成功（能拿到字段）时按缺失字段提示
        if (diagnostics.parseSucceeded) {
            if (parsedName.isNullOrEmpty()) {
                return context.getString(R.string.skill_detail_page_invalid_missing_name)
            }
            val parsedDescription = parsed.frontmatter["description"]?.trim()
            if (parsedDescription.isNullOrEmpty()) {
                return context.getString(R.string.skill_detail_page_invalid_missing_description)
            }
            return null
        }
        // 4) 解析失败且无定位信息（无 frontmatter / 整体被当成标量等）时的兜底提示
        return context.getString(R.string.skill_detail_page_invalid_generic)
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }
}
