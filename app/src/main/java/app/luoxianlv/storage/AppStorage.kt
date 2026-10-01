package app.luoxianlv.storage

import android.content.Context
import app.luoxianlv.hot.contract.AppDirectories
import java.io.File

/** 可查看的业务文件统一放入应用专属外部目录；不可用时回退内部目录，凭据不迁出。 */
object AppStorage {
    fun root(context: Context): File = AppDirectories.visibleRoot(context)

    private fun directory(context: Context, name: String) =
        File(root(context), name).apply { mkdirs() }

    fun wallpapers(context: Context) = directory(context, "wallpapers")

    fun logs(context: Context) = directory(context, "logs")

    fun diagnostics(context: Context) = directory(context, "diagnostics")

    fun updates(context: Context) = directory(context, "updates")

    fun imports(context: Context) = directory(context, "imports")

    /** 迁移完成前仍可读取旧项目；本次已打开的渲染器继续使用原路径。 */
    fun wallpaperRoots(context: Context): List<File> =
        listOf(wallpapers(context), File(context.filesDir, "wallpapers")).distinctBy {
            it.absolutePath
        }

    /** 仅在后台启动任务调用；旧壁纸副本延迟到下次启动清理，避免打断正在读取的场景。 */
    fun migrate(context: Context, report: (String) -> Unit) {
        val legacy = File(context.filesDir, "wallpapers")
        val destination = wallpapers(context)
        if (legacy.absolutePath != destination.absolutePath) {
            legacy
                .listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
                ?.forEach { source ->
                    val target = File(destination, source.name)
                    val marker = File(source, ".external-migrated")
                    runCatching {
                        if (marker.isFile && File(target, ".root").isFile) {
                            // 本进程只会选中已发布的外部项目，旧副本此时可安全回收。
                            source.deleteRecursively()
                        } else if (StorageMigration.copyDirectory(source, target)) {
                            marker.writeText(target.absolutePath, Charsets.UTF_8)
                            report("壁纸迁移完成：${source.name}")
                        }
                    }
                        .onFailure { report("壁纸迁移失败，保留旧项目：${source.name}；${it.message}") }
                }
        }
        val oldUpdates = File(context.cacheDir, "updates")
        oldUpdates
            .listFiles()
            ?.filter { it.isFile && it.extension == "apk" }
            ?.forEach { file ->
                runCatching { StorageMigration.moveFile(file, File(updates(context), file.name)) }
                    .onFailure { report("更新包迁移失败：${it.message}") }
            }
    }

    /** 日志线程在首次写入前迁移旧日志和导出包，避免与追加写入竞争。 */
    fun migrateDiagnostics(context: Context) {
        val oldLogs = File(context.filesDir, "playback-debug")
        oldLogs
            .walkTopDown()
            .filter { it.isFile }
            .forEach { source ->
                val target = File(logs(context), source.relativeTo(oldLogs).path)
                // 冲突时另存旧版本，不覆盖新日志；后续启动继续使用同一迁移名称。
                val destination =
                    if (target.exists())
                        File(
                            target.parentFile,
                            "${target.nameWithoutExtension}.legacy-${source.lastModified()}.${target.extension}",
                        )
                    else target
                StorageMigration.moveFile(source, destination)
            }
        File(context.cacheDir, "updates")
            .listFiles()
            ?.filter {
                it.isFile && it.name.startsWith("luoxianlv-debug-") && it.extension == "zip"
            }
            ?.forEach { StorageMigration.moveFile(it, File(diagnostics(context), it.name)) }
    }
}
