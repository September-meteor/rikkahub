package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
    /** true = 非磁盘真实文件，是挂载/内核等虚拟目录（仅展示/导航用，如 /workspace、/proc） */
    val virtual: Boolean = false,
    /** true = 符号链接（[linkTarget] 为其 readlink 原文；真实目标见 [resolvedPath]） */
    val isSymlink: Boolean = false,
    /** 符号链接原文（沙盒视角，可能为相对或绝对路径），非链接条目为 null */
    val linkTarget: String? = null,
    /**
     * 链接解析后的真实目标路径（与 [path] 同一坐标系）：
     * - FILES 区：相对文件区根目录的路径；
     * - LINUX 区：沙盒内绝对路径。
     * null = 目标不可达（悬空/越界/指向内核伪文件系统或另一存储区），
     * 此时按普通 0 字节文件展示，点击给出提示而不是尝试打开。
     */
    val resolvedPath: String? = null,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
