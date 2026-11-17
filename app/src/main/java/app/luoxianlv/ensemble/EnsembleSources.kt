package app.luoxianlv.ensemble

import android.content.Context
import app.luoxianlv.shared.AppStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 新导入 MIDI 的原始源；按曲目删除，旧谱没有源文件时保持同谱模式。 */
internal class EnsembleSources(context: Context) {
    private val root = File(AppStorage.imports(context), "midi-sources")

    private fun path(id: String): File {
        require(id.isNotBlank() && id.length <= 256)
        return File(root, "${digest(id.toByteArray(Charsets.UTF_8))}.mid")
    }

    fun save(id: String, bytes: ByteArray) {
        require(
            bytes.size in 14..4 * 1024 * 1024 && String(bytes, 0, 4, Charsets.US_ASCII) == "MThd"
        )
        check(root.isDirectory || root.mkdirs())
        val target = path(id)
        val temporary = File.createTempFile("source-", ".part", root)
        try {
            temporary.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    fun read(id: String): ByteArray? = runCatching {
        val file = path(id)
        if (
            !file.isFile ||
                file.length() !in 14..4 * 1024 * 1024 ||
                Files.isSymbolicLink(file.toPath())
        )
            null
        else
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
    }
        .getOrNull()

    fun remove(id: String) {
        runCatching { path(id).delete() }
    }
}
