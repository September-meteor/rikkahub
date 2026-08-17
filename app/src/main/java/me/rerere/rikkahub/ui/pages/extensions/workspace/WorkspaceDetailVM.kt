package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import me.rerere.rikkahub.data.sync.DocumentCache
import me.rerere.rikkahub.data.sync.InternalScanResult
import me.rerere.rikkahub.data.sync.SyncCheckMode
import me.rerere.rikkahub.data.sync.SyncPreviewItem
import me.rerere.rikkahub.data.sync.SyncSnapshot
import me.rerere.rikkahub.data.sync.SyncStage
import me.rerere.rikkahub.data.sync.SyncStageException
import me.rerere.rikkahub.data.sync.WorkspaceIgnoreRules
import me.rerere.rikkahub.data.sync.WorkspaceSyncEngine
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

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

    /** 「导回原处」进行中的协程（扫描阶段可取消） */
    private var syncJob: Job? = null

    /** 预览阶段构建并保留的排除规则 / DocumentFile 缓存，供确认执行时复用 */
    private var syncRules: WorkspaceIgnoreRules? = null
    private var syncDocCache: DocumentCache? = null

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
        context: Context,
        treeUri: Uri,
        enableGitignore: Boolean,
        customIgnorePatterns: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // -1 表示正在快速扫描阶段，UI 会显示不确定进度
            _state.update { it.copy(loading = true, error = null, importProgress = 0 to -1) }

            // 2.1 拿到 treeUri 后立即占住持久化权限（READ + WRITE），为「导回原处」做准备
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)

            // 2.2 记录原始目录 URI（权限失效时 UI 据此引导重新选择）
            repository.getById(id)?.let { ws ->
                repository.updateWorkspace(
                    ws.copy(
                        sourceTreeUri = treeUri.toString(),
                        sourceUriPersisted = persisted,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }

            // 排除规则：与「导回原处」共用同一实现，保证导出时不误删被排除内容
            val rules = WorkspaceIgnoreRules(enableGitignore, customIgnorePatterns)

            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("无法访问所选目录")
                val rootName = rootDoc.name ?: "imported"

                // 阶段1：快速浅扫描，只统计文件数（不加载 .gitignore，非常快）
                val subtreeCounts = mutableMapOf<Uri, Int>()
                fun quickScan(doc: DocumentFile): Int {
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

                // 阶段2：边加载 .gitignore 边导入，动态修正总数。
                // 排除规则的 key 统一采用「相对导入根目录」的路径（根为 ""，子目录为 "src"），
                // 与「导回原处」的内部/外部扫描完全一致。
                rules.loadGitignore(rootDoc, "", context.contentResolver)

                suspend fun importDoc(
                    doc: DocumentFile,
                    parentKey: String,
                ) {
                    val name = doc.name ?: return
                    val isDir = doc.isDirectory

                    if (rules.shouldIgnore(name, parentKey, isDir)) {
                        // 被忽略时从总数中扣除该子树文件数，避免进度永远到不了 100%
                        val skippedCount = subtreeCounts[doc.uri] ?: if (isDir) quickScan(doc) else 1
                        currentTotal -= skippedCount
                        if (currentTotal < processed) currentTotal = processed
                        _state.update { it.copy(importProgress = processed to currentTotal) }
                        return
                    }

                    if (isDir) {
                        val nextKey = if (parentKey.isEmpty()) name else "$parentKey/$name"
                        rules.loadGitignore(doc, nextKey, context.contentResolver)
                        doc.listFiles()?.forEach { child ->
                            importDoc(child, nextKey)
                        }
                    } else {
                        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                            val fileName = doc.name ?: "unnamed"
                            // 内部布局保留根目录名（files/<rootName>/<rel>），与「导回原处」的 syncRoot 约定一致
                            val relativeDest = if (parentKey.isEmpty()) rootName else "$rootName/$parentKey"
                            val destPath = when {
                                state.value.path.isEmpty() -> relativeDest
                                else -> "${state.value.path}/$relativeDest"
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
                    importDoc(child, "")
                }
                // 强制对齐到 100%
                if (currentTotal > 0) {
                    _state.update { it.copy(importProgress = currentTotal to currentTotal) }
                }

                // 2.3 导入完成后生成初始快照（相对导入根目录的路径 → 大小/hash + 目录指纹）。
                // 直接复用本次导入的 rules（其 .gitignore key 约定与 scanInternal 完全一致），
                // 保证被排除内容不会进入快照。
                val filesDir = repository.workspaceFilesDir(id)
                if (filesDir != null) {
                    val internal = WorkspaceSyncEngine.scanInternal(filesDir, rootName, rules)
                    repository.writeSyncSnapshot(
                        id,
                        SyncSnapshot(
                            syncRoot = rootName,
                            createdAt = System.currentTimeMillis(),
                            files = internal.files,
                            directories = internal.directories,
                        ),
                    )
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, importProgress = null) }
                loadWorkspace()
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

    /** 持久化同步检查模式（"fast" 快速 / "accurate" 完整），下次同步时读取生效 */
    fun setSyncCheckMode(mode: SyncCheckMode) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.updateWorkspace(
                workspace.copy(syncCheckMode = mode.value, updatedAt = System.currentTimeMillis())
            )
            loadWorkspace()
        }
    }

    // ---- 「导回原处」：扫描 → 预览 → 确认 → 执行 ----

    /**
     * 开始同步链路：扫描内部 + 外部 → 对比生成预览。
     *
     * 整个链路包进 runCatching（异常隔离）：扫描 / 对比 / hash 各阶段异常分别包装为
     * [SyncStageException] 并翻译成用户可读文案；单文件级异常已在引擎内跳过并记录日志，
     * 不会让整个协程崩溃。
     *
     * 若原始目录权限失效或未导入目录，置 [WorkspaceDetailState.syncSourceLost]，
     * 由 UI 层引导重新选择目录。
     */
    fun prepareSyncPreview(context: Context) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val workspace = repository.getById(id) ?: return@launch
            val treeUri = workspace.sourceTreeUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val rootDoc = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
            if (rootDoc == null || !rootDoc.canWrite()) {
                _state.update {
                    it.copy(
                        syncPhase = SyncPhase.IDLE,
                        syncPreview = null,
                        syncProgress = null,
                        syncError = null,
                        syncSourceLost = true,
                    )
                }
                return@launch
            }

            // 进入扫描阶段（UI 显示「正在扫描变更…」弹窗，可取消）
            _state.update {
                it.copy(
                    syncPhase = SyncPhase.SCANNING,
                    syncPreview = null,
                    syncProgress = null,
                    syncError = null,
                    syncSourceLost = false,
                )
            }

            // 排除规则复用：与导入完全一致（.gitignore 从外部目录加载 + 自定义模式）
            val mode = SyncCheckMode.from(workspace.syncCheckMode)
            val rules = WorkspaceIgnoreRules(workspace.enableGitignore, workspace.customIgnorePatterns)
            val docCache = DocumentCache()
            syncRules = rules
            syncDocCache = docCache

            runCatching {
                val snapshot = repository.readSyncSnapshot(id)
                val syncRoot = snapshot?.syncRoot ?: (rootDoc.name ?: "imported")

                // C：外部当前状态（目录指纹短路 + 快照复用，文件 hash 懒加载；DocumentFile 进缓存）
                val external = stageCatching(SyncStage.SCAN_EXTERNAL) {
                    WorkspaceSyncEngine.scanExternalFast(
                        context = context,
                        rootDoc = rootDoc,
                        rules = rules,
                        snapshot = snapshot,
                        docCache = docCache,
                        onProgress = { done, total ->
                            emitSyncProgress(SyncProgressStage.SCAN, done, total)
                        },
                    )
                }
                // A：内部当前状态（排除 .rikkahub 等元数据目录与被排除内容；快速模式不 eager 算 hash）
                val filesDir = repository.workspaceFilesDir(id)
                val internal = if (filesDir != null) {
                    stageCatching(SyncStage.SCAN_INTERNAL) {
                        WorkspaceSyncEngine.scanInternal(
                            filesDir = filesDir,
                            syncRoot = syncRoot,
                            rules = rules,
                            withHash = mode == SyncCheckMode.ACCURATE,
                            onProgress = { done, total ->
                                emitSyncProgress(SyncProgressStage.SCAN, done, total)
                            },
                        )
                    }
                } else {
                    InternalScanResult(emptyMap(), emptyMap())
                }
                // A vs C 直接对比生成预览（快照不参与操作类型判断；空目录参与 CREATE_DIR/DELETE_DIR）
                val preview = stageCatching(SyncStage.COMPARE) {
                    WorkspaceSyncEngine.computePreview(
                        context = context,
                        rootDoc = rootDoc,
                        internal = internal.files,
                        external = external.files,
                        internalDirs = internal.emptyDirs,
                        externalDirs = external.emptyDirs,
                        mode = mode,
                        docCache = docCache,
                        onHashProgress = { done, total ->
                            emitSyncProgress(SyncProgressStage.HASH, done, total)
                        },
                    )
                }
                SyncPreviewResult(syncRoot, preview)
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        syncRoot = result.syncRoot,
                        syncPreview = result.preview,
                        syncPhase = SyncPhase.PREVIEW,
                        syncProgress = null,
                        syncError = null,
                        syncSourceLost = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        syncPhase = SyncPhase.ERROR,
                        syncPreview = null,
                        syncProgress = null,
                        syncError = error.toSyncErrorMessage(),
                    )
                }
            }
        }
    }

    /** 用户确认后真正执行同步写入，完成后更新快照 */
    fun confirmSync(context: Context) {
        val preview = state.value.syncPreview ?: return
        if (preview.isEmpty()) {
            _state.update { it.copy(syncPhase = SyncPhase.IDLE, syncPreview = null) }
            return
        }
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val workspace = repository.getById(id) ?: return@launch
            val rootDoc = workspace.sourceTreeUri
                .takeIf { it.isNotBlank() }
                ?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
            if (rootDoc == null || !rootDoc.canWrite()) {
                _state.update {
                    it.copy(
                        syncSourceLost = true,
                        syncPhase = SyncPhase.IDLE,
                        syncPreview = null,
                        syncProgress = null,
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    syncPhase = SyncPhase.EXECUTING,
                    syncProgress = SyncProgress(SyncProgressStage.EXECUTE, 0, preview.size),
                    syncPreview = null,
                    syncError = null,
                )
            }
            runCatching {
                // 复用预览阶段的排除规则与 DocumentFile 缓存（SAF 定位降为 O(1)）；
                // 规则丢失（进程重建等）时兜底重建并加载 .gitignore
                val rules = syncRules
                    ?: WorkspaceIgnoreRules(workspace.enableGitignore, workspace.customIgnorePatterns).also {
                        WorkspaceSyncEngine.loadGitignoreTree(context, rootDoc, it, syncDocCache)
                    }
                val snapshot = repository.readSyncSnapshot(id)
                val syncRoot = snapshot?.syncRoot ?: (rootDoc.name ?: "imported")
                val filesDir = repository.workspaceFilesDir(id) ?: error("工作区文件目录不可用")

                val internal = stageCatching(SyncStage.SCAN_INTERNAL) {
                    WorkspaceSyncEngine.scanInternal(filesDir, syncRoot, rules)
                }

                // 执行写入（先删后写，复用缓存定位文件/目录，带进度回调）
                stageCatching(SyncStage.EXECUTE) {
                    WorkspaceSyncEngine.execute(
                        context = context,
                        rootDoc = rootDoc,
                        preview = preview,
                        syncRoot = syncRoot,
                        repository = repository,
                        id = id,
                        docCache = syncDocCache,
                        onProgress = { done, total ->
                            emitSyncProgress(SyncProgressStage.EXECUTE, done, total)
                        },
                    )
                }

                // 写入完成后，把当前内部状态 A 重新写入快照（含目录指纹，供下次指纹短路）
                repository.writeSyncSnapshot(
                    id,
                    SyncSnapshot(
                        syncRoot = syncRoot,
                        createdAt = System.currentTimeMillis(),
                        files = internal.files,
                        directories = internal.directories,
                    ),
                )
            }.onSuccess {
                _state.update {
                    it.copy(syncPhase = SyncPhase.IDLE, syncProgress = null, syncPreview = null, syncError = null)
                }
                refresh()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        syncPhase = SyncPhase.ERROR,
                        syncProgress = null,
                        syncError = error.toSyncErrorMessage(),
                    )
                }
            }
        }
    }

    /** 取消当前同步流程（扫描阶段弹窗的「取消」；预览阶段等同关闭弹窗），回到 IDLE */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        _state.update {
            it.copy(
                syncPhase = SyncPhase.IDLE,
                syncPreview = null,
                syncProgress = null,
                syncError = null,
            )
        }
    }

    fun dismissSyncSourceLost() {
        _state.update { it.copy(syncSourceLost = false) }
    }

    fun clearSyncError() {
        _state.update { it.copy(syncError = null, syncPhase = SyncPhase.IDLE) }
    }

    // ---- 同步链路内部辅助 ----

    /** 进度回调统一入口：写入 [WorkspaceDetailState.syncProgress] */
    private fun emitSyncProgress(stage: SyncProgressStage, done: Int, total: Int) {
        _state.update { it.copy(syncProgress = SyncProgress(stage, done, total)) }
    }

    /** 阶段隔离：非取消异常包装为 [SyncStageException]，供上层翻译成用户可读文案 */
    private suspend fun <T> stageCatching(stage: SyncStage, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SyncStageException(stage, e)
        }

    /** 把同步链路异常翻译成用户可读文案 */
    private fun Throwable.toSyncErrorMessage(): String = when (this) {
        is SyncStageException -> when (stage) {
            SyncStage.SCAN_INTERNAL -> "扫描内部文件失败：${message ?: "未知错误"}"
            SyncStage.SCAN_EXTERNAL -> "扫描外部目录失败：${message ?: "未知错误"}"
            SyncStage.COMPARE -> "对比文件时出错：${message ?: "未知错误"}"
            SyncStage.EXECUTE -> "同步失败：${message ?: "未知错误"}"
        }

        else -> message ?: "同步失败"
    }

    /** 权限失效后用户重新选择的目录：占住权限、持久化 URI，并自动生成新预览 */
    fun onSyncSourcePicked(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
            val workspace = repository.getById(id) ?: return@launch
            repository.updateWorkspace(
                workspace.copy(
                    sourceTreeUri = treeUri.toString(),
                    sourceUriPersisted = persisted,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            _state.update { it.copy(syncSourceLost = false) }
            loadWorkspace()
            prepareSyncPreview(context)
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
            // 加载同步根目录名（用于文件卡片上「同步回原目录」入口的显隐判断）
            if (workspace != null && workspace.sourceTreeUri.isNotBlank()) {
                val snapshot = repository.readSyncSnapshot(id)
                _state.update { it.copy(syncRoot = snapshot?.syncRoot) }
            }
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
    // 「导回原处」同步状态（显式状态机）
    val syncPhase: SyncPhase = SyncPhase.IDLE,
    val syncPreview: List<SyncPreviewItem>? = null,
    val syncRoot: String? = null,
    val syncProgress: SyncProgress? = null,
    val syncError: String? = null,
    val syncSourceLost: Boolean = false,
)

/** 「导回原处」显式状态机 */
enum class SyncPhase {
    /** 无事发生 */
    IDLE,

    /** 正在扫描内部 + 外部目录（或完整模式校验内容） */
    SCANNING,

    /** 扫描完成，展示结果列表，等待用户确认 */
    PREVIEW,

    /** 用户确认后正在写入外部目录 */
    EXECUTING,

    /** 任何阶段出错，[WorkspaceDetailState.syncError] 携带可读错误信息 */
    ERROR,
}

/** 同步进度（阶段 + 完成数 + 总数；total 为负表示总数未知，UI 显示不确定进度） */
data class SyncProgress(
    val stage: SyncProgressStage,
    val done: Int,
    val total: Int,
)

/** 进度阶段：扫描 / 内容校验 / 执行写入 */
enum class SyncProgressStage { SCAN, HASH, EXECUTE }

/** 预览阶段内部结果（syncRoot + 预览列表） */
data class SyncPreviewResult(
    val syncRoot: String,
    val preview: List<SyncPreviewItem>,
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