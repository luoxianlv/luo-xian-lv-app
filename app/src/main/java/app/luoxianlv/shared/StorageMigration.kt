package app.luoxianlv.shared

import java.io.File
import java.util.UUID

/** 先复制到同盘临时文件，再重命名发布；失败时保留旧数据，已存在的目标绝不覆盖。 */
internal object StorageMigration {
    fun copyDirectory(source: File, target: File): Boolean {
        if (!source.isDirectory || target.exists()) return false
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, ".迁移-${UUID.randomUUID()}")
        try {
            check(staging.mkdirs()) { "无法创建迁移临时目录" }
            val root = source.canonicalFile
            source.walkTopDown().forEach { file ->
                check(
                    file.canonicalPath == root.path ||
                        file.canonicalPath.startsWith(root.path + File.separator)
                ) {
                    "迁移目录包含越界链接"
                }
                val destination = File(staging, file.relativeTo(source).path)
                if (file.isDirectory) destination.mkdirs()
                else {
                    val size = file.length()
                    val modified = file.lastModified()
                    file.copyTo(destination)
                    check(
                        destination.length() == size &&
                            file.length() == size &&
                            file.lastModified() == modified
                    ) {
                        "迁移时源文件发生变化"
                    }
                    destination.setLastModified(modified)
                }
            }
            check(staging.renameTo(target)) { "无法发布迁移目录" }
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    fun moveFile(source: File, target: File): Boolean {
        if (!source.isFile || target.exists()) return false
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".迁移-${UUID.randomUUID()}")
        try {
            source.copyTo(temporary)
            check(temporary.length() == source.length()) { "迁移文件不完整" }
            temporary.setLastModified(source.lastModified())
            check(temporary.renameTo(target)) { "无法发布迁移文件" }
            source.delete()
            return true
        } finally {
            temporary.delete()
        }
    }
}
