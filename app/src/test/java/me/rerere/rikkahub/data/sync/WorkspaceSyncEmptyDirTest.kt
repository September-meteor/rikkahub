package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.workspace.GitignoreRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 导入覆盖 / 同步的目录镜像语义回归（rsync --delete + 忽略排除）：
 * - 空目录是结构对象：源侧逻辑空目录（含仅被忽略内容的目录）缺失时也要补建；
 * - 目录是否删除由「目录存在性」决定，而不是「是否为空」；
 * - 删除只针对权威侧完全没有的野目录，且目标侧必须物理真空（否则删不掉 → 幽灵预览）。
 */
class WorkspaceSyncEmptyDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fp = mapOf("" to DirFingerprint("", 0))

    private fun internalScan(
        dirs: Set<String>,
        empty: Set<String> = emptySet(),
        phys: Set<String> = emptySet(),
    ) = InternalScanResult(
        files = emptyMap(),
        directories = fp + dirs.associateWith { DirFingerprint("", 0) },
        emptyDirs = empty,
        physicallyEmptyDirs = phys,
    )

    private fun externalScan(
        dirs: Set<String>,
        empty: Set<String> = emptySet(),
        phys: Set<String> = emptySet(),
    ) = ExternalScanResult(
        files = emptyMap(),
        directories = fp + dirs.associateWith { DirFingerprint("", 0) },
        emptyDirs = empty,
        physicallyEmptyDirs = phys,
    )

    @Test
    fun `scanInternal separates logical empty from physical empty`() = runBlocking {
        val root = tmp.newFolder("syncscan")
        // a/ 只含被忽略文件（*.log）→ 逻辑空（要镜像结构），但物理非空（删不掉）
        val dirA = tmp.newFolder("syncscan", "a")
        java.nio.file.Files.write(dirA.toPath().resolve("x.log"), ByteArray(0))
        // b/ 物理真空 → 逻辑空 + 物理空
        tmp.newFolder("syncscan", "b")
        // c/ 含可见文件 → 非空
        val dirC = tmp.newFolder("syncscan", "c")
        java.nio.file.Files.write(dirC.toPath().resolve("keep.txt"), ByteArray(0))

        val rules = GitignoreRules(enableGitignore = true, customIgnorePatterns = "*.log")
        val result = WorkspaceSyncEngine.scanInternal(
            filesDir = root.parentFile,
            syncRoot = root.name,
            rules = rules,
            withHash = false,
        )
        assertEquals(setOf("a", "b"), result.emptyDirs)
        assertEquals(setOf("b"), result.physicallyEmptyDirs)
    }

    @Test
    fun `import keeps dir present on both sides even if internal is empty`() {
        // 复现原始 bug：本地 a/ 是空目录，源侧 a/ 变为非空（!a/f 重包含）。
        // rsync 语义下目录两侧都在 → 不得出现 DELETE_DIR，只有文件层面的新增。
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("", "a"), empty = setOf("a"), phys = setOf("a")),
            externalScan = externalScan(dirs = setOf("", "a", "src")),
            invert = true,
        )
        assertEquals(emptyList<SyncPreviewItem>(), items)
    }

    @Test
    fun `import creates empty dirs that exist in source`() {
        // 原目录含空目录 e → 导入后目标侧必须补建，不能因为目录是空的就删/忽略
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("")),
            externalScan = externalScan(dirs = setOf("", "e", "sub/empty"), empty = setOf("e", "sub/empty"), phys = setOf("e", "sub/empty")),
            invert = true,
        )
        assertEquals(
            setOf("e" to SyncPreviewType.CREATE_DIR, "sub/empty" to SyncPreviewType.CREATE_DIR),
            items.map { it.path to it.type }.toSet(),
        )
    }

    @Test
    fun `import creates dir whose content is all ignored`() {
        // 仅含被忽略内容的目录（逻辑空、物理非空）也要在目标侧补结构
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("")),
            externalScan = externalScan(
                dirs = setOf("", "out"),
                empty = setOf("out"), // 逻辑空（内容全被忽略）
                phys = emptySet(),     // 物理非空
            ),
            invert = true,
        )
        assertEquals(setOf(SyncPreviewType.CREATE_DIR), items.map { it.type }.toSet())
        assertEquals("out", items.single().path)
    }

    @Test
    fun `import deletes stray physically empty dir`() {
        // 本地多出的野空目录（源完全没有）且物理真空 → 可删
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("", "stray"), empty = setOf("stray"), phys = setOf("stray")),
            externalScan = externalScan(dirs = setOf("")),
            invert = true,
        )
        assertEquals(setOf("stray" to SyncPreviewType.DELETE_DIR), items.map { it.path to it.type }.toSet())
    }

    @Test
    fun `import does not delete stray dir containing protected content`() {
        // 本地野目录含被忽略内容（物理非空）→ 删不掉，预览也不得声称删除（防幽灵）
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("", "stray"), empty = setOf("stray"), phys = emptySet()),
            externalScan = externalScan(dirs = setOf("")),
            invert = true,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `sync mirrors internal empty dirs to external`() {
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("", "d"), empty = setOf("d"), phys = setOf("d")),
            externalScan = externalScan(dirs = setOf("")),
            invert = false,
        )
        assertEquals(setOf("d" to SyncPreviewType.CREATE_DIR), items.map { it.path to it.type }.toSet())
    }

    @Test
    fun `sync deletes external stray empty dir`() {
        val items = WorkspaceSyncEngine.mirrorDirItems(
            internalScan = internalScan(dirs = setOf("")),
            externalScan = externalScan(dirs = setOf("", "z"), empty = setOf("z"), phys = setOf("z")),
            invert = false,
        )
        assertEquals(setOf("z" to SyncPreviewType.DELETE_DIR), items.map { it.path to it.type }.toSet())
    }
}
