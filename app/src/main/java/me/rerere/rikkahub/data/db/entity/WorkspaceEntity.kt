package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

/** 「导回原处」同步来源：原始 SAF tree URI + 是否已持久化权限 */
@Serializable
data class SyncSourceEntry(
    val uri: String,
    val persisted: Boolean,
)

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo("enable_gitignore", defaultValue = "1")
    val enableGitignore: Boolean = true,
    @ColumnInfo("custom_ignore_patterns", defaultValue = "")
    val customIgnorePatterns: String = "",
    // 「导回原处」：各导入目录的原始 SAF tree URI 与持久化状态
    // （JSON map: syncRoot -> SyncSourceEntry{uri, persisted}）。
    // 旧数据为单个 URI 字符串（persisted 存于 source_uri_persisted 列），读取时按旧格式兼容。
    @ColumnInfo("source_tree_uri", defaultValue = "")
    val sourceTreeUri: String = "",
    // 旧字段：仅兼容 v1 单目录数据（single Boolean），新数据统一编码进 source_tree_uri
    @ColumnInfo("source_uri_persisted", defaultValue = "0")
    val sourceUriPersisted: Boolean = false,
    // 「导回原处」同步检查模式（"fast" 快速仅比对尺寸 / "accurate" 完整校验内容）
    @ColumnInfo("sync_check_mode", defaultValue = "accurate")
    val syncCheckMode: String = "accurate",
    // 导入遇到同名文件/目录时的默认行为（"rename" 创建副本 / "overwrite" 覆盖并同步删除多余内容）
    @ColumnInfo("import_conflict_mode", defaultValue = "rename")
    val importConflictMode: String = "rename",
    // 上游: Shell 兼容模式（PROOT_NO_SECCOMP），解决部分设备上 seccomp 导致的命令异常
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    /** 各导入目录的同步来源（syncRoot -> uri/persisted）；新格式为 JSON map，旧格式单 URI 返回空 */
    fun syncSources(): Map<String, SyncSourceEntry> = runCatching {
        JsonInstant.decodeFromString<Map<String, SyncSourceEntry>>(sourceTreeUri)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
