package app.luoxianlv.wallpaper.data

import android.content.Context
import app.luoxianlv.storage.AppStorage
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** 内置 ZIP 只作安装源；完整解压后一次发布到外部目录，渲染不再读取 APK 内项目。 */
object BundledWallpaper {
    const val ID = "34518073-0800-4000-8000-000000000001"
    const val TITLE = "世界很温柔 · 上杉绘梨衣"
    private const val ARCHIVE = "default-wallpaper.zip"

    fun available(context: Context) = context.assets.list("")?.contains(ARCHIVE) == true

    fun folder(context: Context) = File(AppStorage.wallpapers(context), ID)

    /** 仅在 IO 线程调用；并发预热共用同一安装结果，失败不发布半成品、不覆盖用户选择。 */
    @Synchronized
    fun ensureInstalled(context: Context) {
        val destination = folder(context)
        if (File(destination, ".root").isFile || !available(context)) return
        val staging = File(destination.parentFile, ".install-$ID")
        // 上次安装若被进程终止，只清理本安装器专用的未发布目录。
        if (staging.exists()) check(staging.deleteRecursively()) { "无法清理未完成的默认壁纸" }
        check(staging.mkdirs()) { "无法准备默认壁纸目录" }
        try {
            val budget = WallpaperArchive.Budget(staging)
            // 直接从 APK 解压，不落地第二份 ZIP；ZipInputStream 同时校验条目 CRC。
            ZipInputStream(context.assets.open(ARCHIVE).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = budget.target(entry.name, entry.isDirectory)
                    if (entry.isDirectory) target.mkdirs() else budget.copy(zip, target)
                    zip.closeEntry()
                }
            }
            val metadata = JSONObject(File(staging, "project.json").readText(Charsets.UTF_8))
            require(WallpaperArchive.resolve(staging, metadata.getString("file")) != null) {
                "默认壁纸缺少入口文件"
            }
            File(staging, ".root").writeText("", Charsets.UTF_8)
            check(!destination.exists() && staging.renameTo(destination)) { "无法保存默认壁纸" }
            app.luoxianlv.debug.AppLog.i("壁纸", "默认壁纸已安装：${destination.absolutePath}")
        } finally {
            // 仅清理本次创建的临时目录；APK 内安装资源不可单独删除。
            staging.deleteRecursively()
        }
    }
}
