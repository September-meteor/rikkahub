package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable

/**
 * 工作区目录同步（导回原处）相关的数据模型。
 *
 * 快照文件约定存放在工作区私有目录的 `.rikkahub/sync_snapshot.json`，
 * 只记录文件元信息（相对路径 → 大小/hash）与目录指纹，不存文件内容，避免数据库膨胀。
 */
@Serializable
data class SyncSnapshot(
    /** 内部同步根目录名（导入时的外部目录名，内部文件位于 files/<syncRoot>/ 下） */
    val syncRoot: String = "",
    /** 快照生成时间戳 */
    val createdAt: Long = 0,
    /** 相对导入根目录的路径（内部与外部一致，不含 syncRoot 前缀）→ 文件状态 */
    val files: Map<String, SyncFileState> = emptyMap(),
    /** 目录指纹（key 为目录相对路径，根目录为 ""），用于外部扫描的指纹短路 */
    val directories: Map<String, DirFingerprint> = emptyMap(),
)

@Serializable
data class SyncFileState(
    val size: Long = 0,
    /** 全量 CRC32 十六进制串；空串表示尚未计算（懒加载），仅在 size 相同且需比对内容时计算 */
    val hash: String = "",
)

/**
 * 目录指纹：由「直接子项 (name:type:fingerprint) 排序后的哈希」构成。
 * - 文件子项：`name:f:<size>`（size 代理，不读取内容，保证扫描廉价）
 * - 目录子项：`name:d:<childHash>`（递归）
 *
 * 说明：指纹使用 size 而非内容 hash，因此同尺寸内容变更不会被指纹感知；
 * 该场景由比对阶段的懒 hash 兜底（外部未变子树的快照 hash 与内部 A 侧 hash 对比）。
 */
@Serializable
data class DirFingerprint(
    /** 直接子项 (name:type:fingerprint) 排序后的哈希 */
    val childHash: String,
    /** 直接文件数，辅助校验 */
    val fileCount: Int,
)

@Serializable
enum class SyncPreviewType {
    CREATE,
    MODIFY,
    DELETE,
    /** 空目录：A 有 C 无 → 在外部创建目录结构 */
    CREATE_DIR,
    /** 空目录：C 有 A 无 → 从外部删除空目录 */
    DELETE_DIR,
}

/** 同步变更预览项，供确认对话框展示，不直接执行写入 */
@Serializable
data class SyncPreviewItem(
    val type: SyncPreviewType,
    /** 相对导入根目录的路径（内部与外部一致，不含 syncRoot 前缀） */
    val path: String,
    /** 展示用大小提示，如 "12.5 KB" */
    val sizeHint: String = "",
)

/** 同步检查模式：快速（仅存在性 + 尺寸） / 完整（尺寸相同再并发校验内容 CRC32） */
@Serializable
enum class SyncCheckMode(val value: String) {
    FAST("fast"),
    ACCURATE("accurate");

    companion object {
        /** 未知值（含 null / 旧数据）默认走完整检查，保证不漏检 */
        fun from(value: String?): SyncCheckMode =
            entries.firstOrNull { it.value == value } ?: ACCURATE
    }
}

/** 导入遇到同名文件/目录时的默认行为（存于 workspaces.import_conflict_mode） */
enum class ImportConflictMode(val value: String) {
    /** 创建副本（name (1).ext / name (1) 递增），不改动已有内容 */
    RENAME("rename"),

    /** 覆盖（完整 rsync --delete 语义：删除目标中源没有的内容，排除规则无视） */
    OVERWRITE("overwrite");

    companion object {
        /** 未知值（含 null / 旧数据）默认创建副本，保证不破坏已有文件 */
        fun from(value: String?): ImportConflictMode =
            entries.firstOrNull { it.value == value } ?: RENAME
    }
}
