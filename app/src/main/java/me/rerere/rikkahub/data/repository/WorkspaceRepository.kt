package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.SyncSourceEntry
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.sync.SyncSnapshot
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream, overwrite)
    }

    /** 确保目录存在（用于导入空目录） */
    suspend fun createDir(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.createDir(workspace.root, path, area)
    }

    /** 目标路径是否存在（上传冲突检测） */
    suspend fun fileExists(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.fileExists(workspace.root, path, area)
    }

    /** 目标路径是否为目录（上传类型冲突检测） */
    suspend fun isDirectory(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.isDirectory(workspace.root, path, area)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun updateWorkspace(workspace: WorkspaceEntity) {
        dao.upsert(workspace)
    }

    // ---- 「导回原处」同步来源与快照（多目录：每个导入目录各自一份） ----

    /** 工作区 FILES 区根目录（内部同步扫描基目录） */
    suspend fun workspaceFilesDir(id: String): File? = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { manager.filesDir(it.root) }
    }

    /** 工作区指定区域根目录（FILES / LINUX），用于导入覆盖的目标定位 */
    suspend fun workspaceAreaDir(id: String, area: WorkspaceStorageArea): File? = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { ws ->
            when (area) {
                WorkspaceStorageArea.FILES -> manager.filesDir(ws.root)
                WorkspaceStorageArea.LINUX -> manager.linuxDir(ws.root)
            }
        }
    }

    /** 各导入目录的同步来源（syncRoot -> uri/persisted）；旧单目录数据自动兼容（syncRoot 从旧快照恢复） */
    suspend fun syncSources(id: String): Map<String, SyncSourceEntry> = withContext(Dispatchers.IO) {
        val ws = dao.getById(id) ?: return@withContext emptyMap()
        syncSourcesCompat(ws)
    }

    /** 某目录的同步来源 URI；未注册返回 null */
    suspend fun syncSourceUri(id: String, syncRoot: String): String? = withContext(Dispatchers.IO) {
        syncSources(id)[syncRoot]?.uri
    }

    /** 注册/更新某目录的同步来源（旧单值数据首次写入时自动升级为 map） */
    suspend fun registerSyncSource(id: String, syncRoot: String, uri: String, persisted: Boolean) = withContext(Dispatchers.IO) {
        val ws = dao.getById(id) ?: return@withContext
        val upgraded = syncSourcesCompat(ws) + (syncRoot to SyncSourceEntry(uri, persisted))
        dao.upsert(
            ws.copy(
                sourceTreeUri = JsonInstant.encodeToString(upgraded),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 工作区已注册同步来源的目录名集合（含旧单文件快照的 syncRoot，保证旧数据同步入口不消失） */
    suspend fun listSyncRoots(id: String): Set<String> = withContext(Dispatchers.IO) {
        val ws = dao.getById(id) ?: return@withContext emptySet()
        val roots = syncSourcesCompat(ws).keys.toMutableSet()
        legacySnapshotSyncRoot(ws.root)?.let { roots += it }
        roots
    }

    /** 读取某目录的同步快照；优先按 syncRoot 分文件，缺失时兼容旧单文件（syncRoot 匹配才用） */
    suspend fun readSyncSnapshot(id: String, syncRoot: String): SyncSnapshot? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        val file = syncSnapshotFile(workspace.root, syncRoot)
        if (file.exists()) return@withContext decodeSnapshot(file)
        val legacy = legacySyncSnapshotFile(workspace.root)
        if (legacy.exists()) {
            decodeSnapshot(legacy)?.takeIf { it.syncRoot == syncRoot }?.let { return@withContext it }
        }
        null
    }

    /** 写入某目录的同步快照（按 syncRoot 分文件，存放在工作区私有目录 .rikkahub/，不入库） */
    suspend fun writeSyncSnapshot(id: String, syncRoot: String, snapshot: SyncSnapshot): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        runCatching {
            val file = syncSnapshotFile(workspace.root, syncRoot)
            file.parentFile?.mkdirs()
            // 真正原子写入：临时文件 + Files.move(ATOMIC_MOVE, REPLACE_EXISTING)
            // 底层是 rename(2)，不存在「旧文件已删、新文件未落」的中间态；
            // 进程在写入中途被杀也不会留下截断/缺失的快照（快照损坏 → 同步入口消失）
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JsonInstant.encodeToString(snapshot))
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // 极少数文件系统不支持原子移动：退化为普通 move（同目录内基本等价）
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess
    }

    /** 新格式 map 优先；旧单值数据（单个 URI + 旧 persisted 列）用旧快照 syncRoot 拼成 map */
    private fun syncSourcesCompat(ws: WorkspaceEntity): Map<String, SyncSourceEntry> {
        val map = ws.syncSources()
        if (map.isNotEmpty()) return map
        val legacyRoot = legacySnapshotSyncRoot(ws.root)
        if (legacyRoot != null && ws.sourceTreeUri.isNotBlank()) {
            return mapOf(legacyRoot to SyncSourceEntry(ws.sourceTreeUri, ws.sourceUriPersisted))
        }
        return emptyMap()
    }

    private fun decodeSnapshot(file: File): SyncSnapshot? = runCatching {
        JsonInstant.decodeFromString<SyncSnapshot>(file.readText())
    }.getOrNull()

    private fun syncSnapshotFile(root: String, syncRoot: String): File =
        File(manager.workspaceDir(root), ".rikkahub/sync_snapshot_$syncRoot.json")

    /** 旧单文件快照（v1 单目录格式） */
    private fun legacySyncSnapshotFile(root: String): File =
        File(manager.workspaceDir(root), ".rikkahub/sync_snapshot.json")

    /** 旧单文件快照的 syncRoot（用于旧数据兼容），无旧快照返回 null */
    private fun legacySnapshotSyncRoot(root: String): String? {
        val legacy = legacySyncSnapshotFile(root)
        if (!legacy.exists()) return null
        return decodeSnapshot(legacy)?.syncRoot
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
