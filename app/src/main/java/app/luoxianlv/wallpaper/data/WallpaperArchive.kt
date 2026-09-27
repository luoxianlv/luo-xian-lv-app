package app.luoxianlv.wallpaper.data

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** ZIP container policy shared by system-open and in-app imports. No Android dependencies. */
object WallpaperArchive {
    const val LIMIT = 1024L * 1024 * 1024

    class Budget(val root: File, private val checkpoint: () -> Unit = {}) {
        private var bytes = 0L
        private var entries = 0
        private val files = HashSet<String>()

        fun target(rawPath: String, directory: Boolean = false): File {
            checkpoint()
            require(++entries <= 16384) { "项目文件过多（最多 16384 项）" }
            val path = normalize(rawPath)
            require(path.split('/').size <= 32) { "项目目录层级过深" }
            val file = File(root, path)
            require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                "项目包含越界路径"
            }
            if (!directory) {
                require(files.add(path.lowercase(java.util.Locale.ROOT)) && !file.exists()) {
                    "项目包含重复文件：$path"
                }
                require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "项目文件与目录冲突" }
            }
            return file
        }

        fun copy(input: InputStream, target: File, expectedCrc: Long = -1) {
            val crc = CRC32()
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= LIMIT) { "项目解压后超过 1 GB" }
                    require(root.usableSpace > count + 16L * 1024 * 1024) { "存储空间不足" }
                    output.write(buffer, 0, count)
                    crc.update(buffer, 0, count)
                }
            }
            require(expectedCrc < 0 || crc.value == expectedCrc) { "ZIP 文件损坏，请重新下载" }
        }
    }

    fun normalize(raw: String): String {
        val path = raw.replace('\\', '/').removeSuffix("/")
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                '\u0000' !in path &&
                path.split('/').none { it.isEmpty() || it == ".." || it == "." || ':' in it }
        ) {
            "项目包含无效路径"
        }
        return path
    }

    fun extract(archive: File, root: File, checkpoint: () -> Unit = {}) {
        val zip =
            try {
                ZipFile(archive, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                ZipFile(archive, Charset.forName("GB18030"))
            } catch (error: java.util.zip.ZipException) {
                // Legacy Windows ZIPs often use GBK names without the UTF-8 flag.
                if (error.message?.contains("entry name", ignoreCase = true) == true)
                    ZipFile(archive, Charset.forName("GB18030"))
                else throw error
            }
        zip.use {
            val budget = Budget(root, checkpoint)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = budget.target(entry.name, entry.isDirectory)
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "项目目录冲突" }
                } else
                    zip.getInputStream(entry).use { input -> budget.copy(input, target, entry.crc) }
            }
        }
    }

    /** Resolve Windows-authored asset paths on Android without allowing directory traversal. */
    fun resolve(root: File, raw: String): File? {
        val path = runCatching { normalize(raw) }.getOrNull() ?: return null
        var file = root
        for (part in path.split('/')) {
            val exact = File(file, part)
            file =
                if (exact.exists()) exact
                else file.listFiles()?.singleOrNull { it.name.equals(part, true) } ?: return null
        }
        return file.canonicalFile.takeIf {
            it.path.startsWith(root.canonicalPath + File.separator) && it.isFile
        }
    }
}
