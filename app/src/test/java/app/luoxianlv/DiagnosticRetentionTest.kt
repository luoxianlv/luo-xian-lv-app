package app.luoxianlv

import app.luoxianlv.debug.DiagnosticRetention
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRetentionTest {
    @Test
    fun capsCombinedFilesOldestFirstWithoutDeletingApks() {
        val root = Files.createTempDirectory("diagnostic-retention").toFile()
        try {
            val logs = File(root, "logs").apply { mkdirs() }
            val exports = File(root, "updates").apply { mkdirs() }
            val old =
                File(logs, "old.jpg").apply {
                    writeBytes(ByteArray(60))
                    setLastModified(1000)
                }
            val current =
                File(logs, "play-debug.log").apply {
                    writeBytes(ByteArray(20))
                    setLastModified(2000)
                }
            val zip =
                File(exports, "luoxianlv-debug-new.zip").apply {
                    writeBytes(ByteArray(60))
                    setLastModified(3000)
                }
            val apk = File(exports, "update.apk").apply { writeBytes(ByteArray(200)) }
            DiagnosticRetention.trim(logs, exports, 100)
            assertFalse(old.exists())
            assertTrue(current.exists())
            assertTrue(zip.exists())
            assertTrue(apk.exists())
            DiagnosticRetention.trim(logs, exports, 10)
            assertFalse(current.exists())
            assertFalse(zip.exists())
            assertTrue(apk.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
