package app.luoxianlv.shared

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StorageMigrationTest {
    @Test
    fun completeCopyPreservesOriginalAndUtf8() {
        val root = Files.createTempDirectory("migration").toFile()
        try {
            val source = File(root, "old").apply { mkdirs() }
            File(source, "中文").mkdirs()
            File(source, "中文/项目.json").writeText("{\"标题\":\"星空\"}", Charsets.UTF_8)
            val target = File(root, "new")
            assertTrue(StorageMigration.copyDirectory(source, target))
            assertArrayEquals(
                File(source, "中文/项目.json").readBytes(),
                File(target, "中文/项目.json").readBytes(),
            )
            assertTrue(source.exists())
            assertFalse(StorageMigration.copyDirectory(source, target))
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".迁移-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedPublicationNeverDeletesSource() {
        val root = Files.createTempDirectory("migration").toFile()
        try {
            val source = File(root, "old").apply { mkdirs() }
            File(source, "data").writeText("不可丢失")
            val blocker = File(root, "blocker").apply { writeText("文件不是目录") }
            assertTrue(
                runCatching { StorageMigration.copyDirectory(source, File(blocker, "new")) }
                    .isFailure
            )
            assertEquals("不可丢失", File(source, "data").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingFilesAreNeverOverwritten() {
        val root = Files.createTempDirectory("migration").toFile()
        try {
            val source = File(root, "old").apply { writeText("旧日志") }
            val target = File(root, "new").apply { writeText("新日志") }
            assertFalse(StorageMigration.moveFile(source, target))
            assertEquals("旧日志", source.readText())
            assertEquals("新日志", target.readText())
            val moved = File(root, "sub/moved")
            assertTrue(StorageMigration.moveFile(source, moved))
            assertFalse(source.exists())
            assertEquals("旧日志", moved.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
