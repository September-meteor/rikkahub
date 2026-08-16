package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea
import kotlinx.coroutines.Dispatchers

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    // 导入整个目录（方案3：流式总数 + 增强.gitignore解析）
    fun importDirectory(
        context: android.content.Context,
        treeUri: android.net.Uri,
        enableGitignore: Boolean,
        customIgnorePatterns: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // -1 表示正在快速扫描阶段，UI 会显示不确定进度
            _state.update { it.copy(loading = true, error = null, importProgress = 0 to -1) }

            runCatching {
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("无法访问所选目录")
                val rootName = rootDoc.name ?: "imported"

                // 用户自定义排除模式
                val userPatterns = customIgnorePatterns
                    .split(',', '，', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

                // 增强的 .gitignore 规则结构
                data class IgnoreRule(
                    val regex: Regex,
                    val isNegation: Boolean,
                    val isAnchored: Boolean,      // 以 / 开头，只匹配直接子项
                    val isDirectoryOnly: Boolean, // 以 / 结尾，只匹配目录
                    val rawPattern: String,
                )

                fun shouldIgnore(
                    name: String,
                    parentDir: String,
                    isDirectory: Boolean,
                    gitignoreRules: Map<String, List<IgnoreRule>>,
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

                fun loadGitignore(
                    dir: androidx.documentfile.provider.DocumentFile,
                    key: String,
                    gitignoreRules: MutableMap<String, MutableList<IgnoreRule>>,
                ) {
                    if (!enableGitignore) return
                    val gitignoreDoc = dir.findFile(".gitignore")
                    if (gitignoreDoc == null || !gitignoreDoc.isFile) return
                    val rules = mutableListOf<IgnoreRule>()
                    context.contentResolver.openInputStream(gitignoreDoc.uri)
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
                                        rawPattern = patternText
                                    )
                                )
                            }
                        }
                    if (rules.isNotEmpty()) {
                        gitignoreRules[key] = (gitignoreRules[key] ?: mutableListOf()).apply { addAll(rules) }
                    }
                }

                // 阶段1：快速浅扫描，只统计文件数（不加载 .gitignore，非常快）
                val subtreeCounts = mutableMapOf<android.net.Uri, Int>()
                fun quickScan(doc: androidx.documentfile.provider.DocumentFile): Int {
                    val count = if (!doc.isDirectory) {
                        1
                    } else {
                        doc.listFiles()?.sumOf { quickScan(it) } ?: 0
                    }
                    subtreeCounts[doc.uri] = count
                    return count
                }
                val approxTotal = rootDoc.listFiles()?.sumOf { quickScan(it) } ?: 0
                var currentTotal = approxTotal
                var processed = 0

                // 阶段2：边加载 .gitignore 边导入，动态修正总数
                val gitignoreRules = mutableMapOf<String, MutableList<IgnoreRule>>()
                loadGitignore(rootDoc, rootName, gitignoreRules)

                suspend fun importDoc(
                    doc: androidx.documentfile.provider.DocumentFile,
                    parentDir: String,
                ) {
                    val name = doc.name ?: return
                    val isDir = doc.isDirectory

                    if (shouldIgnore(name, parentDir, isDir, gitignoreRules)) {
                        // 被忽略时从总数中扣除该子树文件数，避免进度永远到不了 100%
                        val skippedCount = subtreeCounts[doc.uri] ?: if (isDir) quickScan(doc) else 1
                        currentTotal -= skippedCount
                        if (currentTotal < processed) currentTotal = processed
                        _state.update { it.copy(importProgress = processed to currentTotal) }
                        return
                    }

                    if (isDir) {
                        val nextDir = if (parentDir.isEmpty()) name else "$parentDir/$name"
                        loadGitignore(doc, nextDir, gitignoreRules)
                        doc.listFiles()?.forEach { child ->
                            importDoc(child, nextDir)
                        }
                    } else {
                        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                            val fileName = doc.name ?: "unnamed"
                            val destPath = when {
                                state.value.path.isEmpty() -> parentDir
                                parentDir.isEmpty() -> state.value.path
                                else -> "${state.value.path}/$parentDir"
                            }
                            repository.importFile(
                                id = id,
                                area = state.value.area,
                                destinationPath = destPath,
                                fileName = fileName,
                                inputStream = stream,
                            )
                        }
                        processed++
                        if (currentTotal < processed) currentTotal = processed
                        _state.update { it.copy(importProgress = processed to currentTotal) }
                    }
                }

                rootDoc.listFiles()?.forEach { child ->
                    importDoc(child, rootName)
                }
                // 强制对齐到 100%
                if (currentTotal > 0) {
                    _state.update { it.copy(importProgress = currentTotal to currentTotal) }
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, importProgress = null) }
                refresh()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        importProgress = null,
                        error = error.message ?: "导入目录失败",
                    )
                }
            }
        }
    }

    fun setEnableGitignore(enabled: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.updateWorkspace(
                workspace.copy(enableGitignore = enabled, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }
    
    fun setCustomIgnorePatterns(patterns: String) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.updateWorkspace(
                workspace.copy(customIgnorePatterns = patterns, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val importProgress: Pair<Int, Int>? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}