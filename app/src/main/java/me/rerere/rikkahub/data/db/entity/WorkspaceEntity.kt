package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

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
    // 「导回原处」：导入目录的原始 SAF tree URI
    @ColumnInfo("source_tree_uri", defaultValue = "")
    val sourceTreeUri: String = "",
    // 是否成功持久化 takePersistableUriPermission（卸载重装 / 用户撤销后失效，需重新选择）
    @ColumnInfo("source_uri_persisted", defaultValue = "0")
    val sourceUriPersisted: Boolean = false,
    // 「导回原处」同步检查模式（"fast" 快速仅比对尺寸 / "accurate" 完整校验内容）
    @ColumnInfo("sync_check_mode", defaultValue = "accurate")
    val syncCheckMode: String = "accurate",
    // 导入遇到同名文件/目录时的默认行为（"rename" 创建副本 / "overwrite" 覆盖并同步删除多余内容）
    @ColumnInfo("import_conflict_mode", defaultValue = "rename")
    val importConflictMode: String = "rename",
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
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
