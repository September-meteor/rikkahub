package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * 符号链接在文件浏览中的沙盒语义解析：
 * - 链接内容可能是相对路径或沙盒内绝对路径（/workspace/...、/usr/...）；
 * - FILES 区只允许跳到 /workspace（filesDir）内的目标，rootfs 内部视为不可达；
 * - LINUX/rootfs 区可跳到任意可访问位置（rootfs 内部 / /workspace / bind mount）；
 * - 删除/移动符号链接只作用于链接本身，不触碰目标。
 */
class SymlinkResolutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun createManager(): WorkspaceManager {
        val skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        return WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        ).also { it.ensureWorkspace(root) }
    }

    private fun symlink(link: File, target: String) {
        Files.createSymbolicLink(link.toPath(), File(target).toPath())
    }

    // ---- FILES 区（链接目标为沙盒绝对路径 /workspace/...） ----

    @Test
    fun filesSymlinkToWorkspaceDirResolvesToDirectory() {
        val manager = createManager()
        // files/outputs -> /workspace/rikkahub/app/build/outputs （沙盒绝对路径，宿主无法跟随）
        File(manager.filesDir(root), "rikkahub/app/build/outputs").mkdirs()
        symlink(File(manager.filesDir(root), "outputs"), "/workspace/rikkahub/app/build/outputs")

        val entries = manager.listFiles(root, "", WorkspaceStorageArea.FILES)
        val link = entries.first { it.name == "outputs" }

        assertTrue(link.isSymlink)
        assertTrue(link.isDirectory)
        assertEquals("/workspace/rikkahub/app/build/outputs", link.linkTarget)
        assertEquals("rikkahub/app/build/outputs", link.resolvedPath)
    }

    @Test
    fun filesSymlinkToWorkspaceFileResolvesToFile() {
        val manager = createManager()
        File(manager.filesDir(root), "data").mkdirs()
        File(manager.filesDir(root), "data/notes.txt").writeText("hi")
        symlink(File(manager.filesDir(root), "notes"), "/workspace/data/notes.txt")

        val link = manager.listFiles(root, "", WorkspaceStorageArea.FILES)
            .first { it.name == "notes" }

        assertTrue(link.isSymlink)
        assertFalse(link.isDirectory)
        assertEquals(2L, link.sizeBytes)
        assertEquals("data/notes.txt", link.resolvedPath)
    }

    @Test
    fun filesSymlinkPointingIntoRootfsIsUnreachable() {
        val manager = createManager()
        // FILES 区链接指向 rootfs 内部：不跨区，视为不可达
        File(manager.linuxDir(root), "etc").mkdirs()
        symlink(File(manager.filesDir(root), "to-etc"), "/etc")

        val link = manager.listFiles(root, "", WorkspaceStorageArea.FILES)
            .first { it.name == "to-etc" }

        assertTrue(link.isSymlink)
        assertFalse(link.isDirectory)
        assertNull(link.resolvedPath)
        assertEquals(0L, link.sizeBytes)
    }

    @Test
    fun danglingFilesSymlinkIsUnreachable() {
        val manager = createManager()
        symlink(File(manager.filesDir(root), "dangling"), "/workspace/does/not/exist")

        val link = manager.listFiles(root, "", WorkspaceStorageArea.FILES)
            .first { it.name == "dangling" }

        assertTrue(link.isSymlink)
        assertFalse(link.isDirectory)
        assertNull(link.resolvedPath)
    }

    // ---- LINUX/rootfs 区 ----

    @Test
    fun rootfsRelativeSymlinkToInteriorResolves() {
        val manager = createManager()
        // /bin -> usr/bin （rootfs 相对链接）
        File(manager.linuxDir(root), "usr/bin").mkdirs()
        symlink(File(manager.linuxDir(root), "bin"), "usr/bin")

        val entries = manager.listRootfs(root, "")
        val bin = entries.first { it.path == "/bin" }

        assertTrue(bin.isSymlink)
        assertTrue(bin.isDirectory)
        assertEquals("usr/bin", bin.linkTarget)
        assertEquals("/usr/bin", bin.resolvedPath)
    }

    @Test
    fun rootfsAbsoluteSymlinkToWorkspaceResolves() {
        val manager = createManager()
        // rootfs 内链接指向 /workspace 下的文件（沙盒绝对路径）
        File(manager.filesDir(root), "x.txt").writeText("hello")
        File(manager.linuxDir(root), "etc").mkdirs()
        symlink(File(manager.linuxDir(root), "etc/ws-link"), "/workspace/x.txt")

        val etc = manager.listRootfs(root, "/etc")
        val link = etc.first { it.name == "ws-link" }

        assertTrue(link.isSymlink)
        assertFalse(link.isDirectory)
        assertEquals("/workspace/x.txt", link.resolvedPath)
    }

    @Test
    fun rootfsSymlinkToKernelFsIsUnreachable() {
        val manager = createManager()
        File(manager.linuxDir(root), "etc").mkdirs()
        symlink(File(manager.linuxDir(root), "etc/proc-link"), "/proc/self")

        val link = manager.listRootfs(root, "/etc").first { it.name == "proc-link" }
        assertTrue(link.isSymlink)
        assertNull(link.resolvedPath)
        assertFalse(link.isDirectory)
    }

    // ---- 删除 / 移动只作用于链接本身 ----

    @Test
    fun deletingSymlinkToDirectoryKeepsTargetIntact() {
        val manager = createManager()
        val target = File(manager.filesDir(root), "target").apply { mkdirs() }
        File(target, "inside.txt").writeText("keep me")
        symlink(File(manager.filesDir(root), "alias"), "/workspace/target")

        assertTrue(manager.deleteFile(root, "alias", recursive = false, area = WorkspaceStorageArea.FILES))
        assertFalse(File(manager.filesDir(root), "alias").exists())
        assertTrue(target.exists())
        assertTrue(File(target, "inside.txt").exists())
    }

    @Test
    fun movingSymlinkMovesLinkNotTarget() {
        val manager = createManager()
        val target = File(manager.filesDir(root), "target").apply { mkdirs() }
        File(target, "inside.txt").writeText("keep me")
        symlink(File(manager.filesDir(root), "alias"), "/workspace/target")

        val moved = manager.renameFile(root, "alias", "alias2", WorkspaceStorageArea.FILES)
        assertTrue(moved)
        assertFalse(File(manager.filesDir(root), "alias").exists())
        // alias2 是目标写为 /workspace/... 的悬空链接（宿主无法跟随），用 isSymbolicLink 断言
        assertTrue(Files.isSymbolicLink(File(manager.filesDir(root), "alias2").toPath()))
        assertTrue(target.exists())
        assertTrue(File(target, "inside.txt").exists())
    }
}
