package app.luoxianlv

import app.luoxianlv.wallpaper.data.WallpaperArchive
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WallpaperArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(names: List<String>, charset: Charset = Charsets.UTF_8): File =
        temp.newFile().also { file ->
            ZipOutputStream(file.outputStream(), charset).use { output ->
                names.forEach { name ->
                    output.putNextEntry(ZipEntry(name))
                    output.write("fixture".toByteArray())
                    output.closeEntry()
                }
            }
        }

    @Test
    fun wholeFolderZipAndWindowsSeparatorsResolveCaseInsensitively() {
        val root = temp.newFolder()
        WallpaperArchive.extract(
            zip(listOf("3113554287\\project.json", "3113554287\\Assets\\星空.JPG")),
            root,
        )
        assertEquals(
            "fixture",
            WallpaperArchive.resolve(root, "3113554287/assets/星空.jpg")!!.readText(),
        )
    }

    @Test
    fun legacyChineseZipNamesAreDecoded() {
        val root = temp.newFolder()
        WallpaperArchive.extract(zip(listOf("壁纸/背景.jpg"), Charset.forName("GB18030")), root)
        assertTrue(File(root, "壁纸/背景.jpg").isFile)
    }

    @Test
    fun traversalAbsoluteDriveAndDuplicateCaseAreRejected() {
        for (name in listOf("../escape", "C:\\escape", "/escape", "a/../../escape", "a//escape")) {
            assertThrows(IllegalArgumentException::class.java) {
                WallpaperArchive.extract(zip(listOf(name)), temp.newFolder())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WallpaperArchive.extract(zip(listOf("File.png", "file.PNG")), temp.newFolder())
        }
    }

    @Test
    fun directoryCountAndDepthAreBoundedAndCancelStopsCopy() {
        val root = temp.newFolder()
        val budget = WallpaperArchive.Budget(root)
        repeat(16384) { budget.target("dir$it", true) }
        assertThrows(IllegalArgumentException::class.java) { budget.target("last", true) }
        assertThrows(IllegalArgumentException::class.java) {
            WallpaperArchive.Budget(root).target("a/".repeat(32) + "file")
        }
        assertThrows(InterruptedException::class.java) {
            WallpaperArchive.extract(zip(listOf("file")), root) {
                throw InterruptedException("cancelled")
            }
        }
    }

    @Test
    fun resourcesCannotEscapeRoot() {
        assertNull(WallpaperArchive.resolve(temp.newFolder(), "../other/file"))
        assertNull(WallpaperArchive.resolve(temp.newFolder(), "https://example.com/a"))
    }
}
