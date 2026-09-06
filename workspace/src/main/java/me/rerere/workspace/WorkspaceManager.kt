package me.rerere.workspace

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream, overwrite)
    }

    /** 确保目录存在（用于导入空目录） */
    fun createDir(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry = fileSystem.ensureDirectory(areaDir(root, area), path)

    /** 目标路径是否存在（用于上传冲突检测） */
    fun fileExists(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean = fileSystem.resolve(areaDir(root, area), path).exists()

    /** 目标路径是否为目录（用于上传类型冲突检测） */
    fun isDirectory(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean = fileSystem.resolve(areaDir(root, area), path).isDirectory

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    /** bind mount 表：供 rootfs 虚拟浏览与交互终端生成 -b 参数共用，避免挂载配置漂移 */
    fun bindMounts(): List<WorkspaceBindMount> = bindMounts

    /**
     * 按沙盒内绝对路径浏览 rootfs，得到与「终端 ls」一致的视图：
     * - `/`（[path] 为空串）返回 linux/ 磁盘条目，并叠加虚拟挂载目录
     *   （/workspace、各 bind mount、存在的内核伪文件系统 /dev /proc /sys）；
     * - 其它路径经 [resolveRootfsPath] 落到真实宿主目录后枚举，
     *   返回条目的 [WorkspaceFileEntry.path] 均为沙盒内绝对路径。
     */
    fun listRootfs(root: String, path: String = ""): List<WorkspaceFileEntry> {
        val raw = path.trim().replace('\\', '/')
        // 内核伪文件系统对 App 进程不可枚举（/dev、/sys 受 SELinux 保护，
        // /proc 内容为内核特殊文件），文件浏览中一律按空目录展示
        if (isKernelMountPath(raw)) return emptyList()
        if (raw.isEmpty() || raw == "/") {
            val virtual = rootfsVirtualEntries(root)
            val virtualNames = virtual.mapTo(mutableSetOf()) { it.name }
            val disk = fileSystem.list(linuxDir(root), "")
                .filter { it.name !in virtualNames }
                .map { it.copy(path = "/${it.path}") }
            return (virtual + disk).sortedWith(ROOTFS_ENTRY_ORDER)
        }
        val location = resolveRootfsPath(root, raw)
        val prefix = rootfsPrefixOf(root, location.rootDir)
        val base = if (prefix.isEmpty()) "/" else "$prefix/"
        return fileSystem.list(location.rootDir, location.relativePath)
            .map { it.copy(path = base + it.path) }
            .sortedWith(ROOTFS_ENTRY_ORDER)
    }

    /** rootfs 根目录的虚拟挂载条目（/workspace、bind mount、内核伪文件系统） */
    private fun rootfsVirtualEntries(root: String): List<WorkspaceFileEntry> {
        val entries = mutableListOf(
            WorkspaceFileEntry(
                path = ROOTFS_WORKSPACE_DIR,
                name = ROOTFS_WORKSPACE_DIR.trimStart('/'),
                isDirectory = true,
                sizeBytes = 0L,
                updatedAt = filesDir(root).lastModified(),
                virtual = true,
            )
        )
        bindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (mount.source.isDirectory) {
                entries += WorkspaceFileEntry(
                    path = target,
                    name = target.substringAfterLast('/'),
                    isDirectory = true,
                    sizeBytes = 0L,
                    updatedAt = mount.source.lastModified(),
                    virtual = true,
                )
            }
        }
        KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                entries += WorkspaceFileEntry(
                    path = path,
                    name = path.trimStart('/'),
                    isDirectory = true,
                    sizeBytes = 0L,
                    updatedAt = 0L,
                    virtual = true,
                )
            }
        }
        return entries
    }

    /** 宿主目录对应的沙盒根前缀（files→/workspace、bind mount→目标、linux→空） */
    private fun rootfsPrefixOf(root: String, hostDir: File): String {
        if (hostDir.absolutePath == linuxDir(root).absolutePath) return ""
        if (hostDir.absolutePath == filesDir(root).absolutePath) return ROOTFS_WORKSPACE_DIR
        bindMounts.firstOrNull { it.source.absolutePath == hostDir.absolutePath }
            ?.let { return it.target.trimEnd('/') }
        return ""
    }

    /** 按沙盒内绝对路径删除（rootfs 浏览用）；内核伪文件系统与工作区根会拒绝 */
    fun deleteRootfs(root: String, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != "/" && path != ROOTFS_WORKSPACE_DIR) {
            "Refusing to delete rootfs root or workspace root"
        }
        val location = resolveRootfsPath(root, path)
        return fileSystem.delete(location.rootDir, location.relativePath, recursive)
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    /** 就地重命名（同目录改名，不跨目录移动），目录整体改名同样支持。失败返回 false。 */
    fun renameFile(
        root: String,
        path: String,
        newName: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean {
        val base = areaDir(root, area)
        val target = sameParentTarget(path, newName)
        return tryMove(base, path, target)
    }

    /** Rootfs 区就地重命名（沙盒内绝对路径，含 /workspace 挂载内的真实文件/目录）。失败返回 false。 */
    fun renameRootfs(root: String, path: String, newName: String): Boolean {
        require(path.isNotBlank() && path != "/") { "Refusing to rename rootfs root" }
        val location = resolveRootfsPath(root, path)
        require(location.relativePath.isNotBlank()) { "Refusing to rename a mount root" }
        val target = sameParentTarget(location.relativePath, newName)
        return tryMove(location.rootDir, location.relativePath, target)
    }

    private fun sameParentTarget(path: String, newName: String): String {
        val parent = path.substringBeforeLast('/', "")
        return if (parent.isBlank()) newName else "$parent/$newName"
    }

    private fun tryMove(base: File, source: String, target: String): Boolean = try {
        fileSystem.move(base, source, target, overwrite = false)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    /**
     * 清理临时文件，但**保留目录本身**：
     * - PRoot 内部临时目录（每个命令的 TMPDIR 指向这里），应用内部目录，整个删除安全；
     * - rootfs 的 /tmp 与 /var/tmp 只清**内容**不删目录——用户命令与工具
     *   （如变更扫描器的 `find > /tmp/xxx`）依赖这些目录存在，删目录会让重定向全部失败；
     *   若目录缺失则创建（fresh rootfs 可能没有）。
     *
     * 注意：rootfs 内的 /tmp、/var/tmp 只应在沙盒已安装（rootfs 存在）时才创建/清理；
     * 未安装沙盒的工作区 `linux/` 下不应凭空出现这两个目录（它们看起来像系统目录，
     * 实际由 RootfsPatcher 在安装时创建）。
     */
    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // 未安装沙盒（rootfs）的工作区不创建/清理 rootfs 内部临时目录
            if (!hasRootfs(root)) continue
            // Rootfs /tmp、/var/tmp：清内容、保目录、缺失则建
            listOf("tmp", "var/tmp").forEach { rel ->
                File(linuxDir(root), rel).let { d ->
                    if (d.isDirectory) {
                        d.listFiles()?.forEach { it.deleteRecursively() }
                    } else {
                        d.mkdirs()
                    }
                    // 标准 /tmp 权限为 1777（含 sticky bit），java.io.File 无法设置 sticky，走 chmod。
                    // - 参数数组直接 exec（不经 shell），路径不会被打断/注入；
                    // - 幂等：当前权限已是 1777 则跳过；
                    // - 显式检查退出码并记日志（runCatching 只捕获异常，捕获不了非零退出码）。
                    val dirPath = d.absolutePath
                    runCatching {
                        val stat = Runtime.getRuntime().exec(arrayOf("stat", "-c", "%a", dirPath))
                        val current = stat.inputStream.bufferedReader().readText().trim()
                        stat.waitFor()
                        if (current != "1777") {
                            val chmod = Runtime.getRuntime().exec(arrayOf("chmod", "1777", dirPath))
                            val code = chmod.waitFor()
                            if (code != 0) Log.w(TAG, "chmod 1777 failed: $dirPath exit=$code")
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "ensure tmp sticky bit failed: $dirPath", e)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "WorkspaceManager"
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        /** 路径是否命中内核伪文件系统（/dev、/proc、/sys 及其子路径，只能经终端访问） */
        fun isKernelMountPath(path: String): Boolean {
            val normalized = path.trim().trimEnd('/')
            return KERNEL_FS_MOUNTS.any { normalized == it || normalized.startsWith("$it/") }
        }

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

        /** rootfs 浏览条目排序：目录在前，同级按名称（忽略大小写） */
        private val ROOTFS_ENTRY_ORDER: Comparator<WorkspaceFileEntry> =
            compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
