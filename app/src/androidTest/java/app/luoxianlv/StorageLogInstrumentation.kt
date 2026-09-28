package app.luoxianlv

import android.app.Instrumentation
import android.os.Bundle
import androidx.core.content.FileProvider
import app.luoxianlv.debug.AppLog
import app.luoxianlv.debug.DebugExport
import app.luoxianlv.storage.AppStorage
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking

/** 验证真实外部目录、旧项目迁移、中文日志和 FileProvider 导出。 */
class StorageLogInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var success = false
        val id = UUID.randomUUID().toString()
        val legacy = File(targetContext.filesDir, "wallpapers/$id")
        val destination = File(AppStorage.wallpapers(targetContext), id)
        val prefs = targetContext.getSharedPreferences("practice_wallpaper", 0)
        val previous = prefs.getString("project", null)
        val oldLog = File(targetContext.filesDir, "playback-debug/play-debug-迁移测试-$id.log")
        try {
            check(
                AppStorage.root(targetContext).canonicalFile ==
                    targetContext.getExternalFilesDir(null)!!.canonicalFile
            )
            val project = File(legacy, "中文项目").apply { mkdirs() }
            File(project, "project.json")
                .writeText(
                    """{"title":"迁移测试","type":"image","file":"preview.png"}""",
                    Charsets.UTF_8,
                )
            File(project, "preview.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(legacy, ".root").writeText("中文项目", Charsets.UTF_8)
            prefs.edit().putString("project", id).commit()
            check(WallpaperProjectStore.root(targetContext) == project.canonicalFile)
            AppStorage.migrate(targetContext) { AppLog.i("迁移测试", it) }
            check(
                WallpaperProjectStore.root(targetContext) == File(destination, "中文项目").canonicalFile
            )
            check(
                File(destination, "中文项目/preview.png")
                    .readBytes()
                    .contentEquals(byteArrayOf(1, 2, 3, 4))
            )
            check(legacy.exists())
            check(WallpaperProjectStore.entries(targetContext).count { it.id == id } == 1)
            AppStorage.migrate(targetContext) {}
            check(!legacy.exists())
            oldLog.parentFile!!.mkdirs()
            oldLog.writeText("旧版中文日志\n", Charsets.UTF_8)
            AppLog.flush()
            AppStorage.migrateDiagnostics(targetContext)
            check(
                File(AppStorage.logs(targetContext), oldLog.name).readText(Charsets.UTF_8) ==
                    "旧版中文日志\n"
            )
            AppLog.i("存储测试", "中文编码验证：壁纸、识别、暂停与恢复；标识=$id")
            AppLog.w("存储测试", "异常原文保留", java.io.IOException("测试错误详情"))
            AppLog.flush()
            val log = File(AppStorage.logs(targetContext), "play-debug.log").readBytes()
            check(!log.take(3).toByteArray().contentEquals(byteArrayOf(-17, -69, -65)))
            val text = log.toString(Charsets.UTF_8)
            check(
                text.contains("中文编码验证") && text.contains(id) && text.contains("java.io.IOException")
            )
            check(!text.contains('\uFFFD'))
            val archive = runBlocking { DebugExport.create(targetContext) }
            check(archive.parentFile == AppStorage.diagnostics(targetContext))
            ZipFile(archive).use { zip ->
                check(zip.getEntry("说明.txt") != null)
                check(zip.getEntry("logs/play-debug.log") != null)
                check(zip.getEntry("device.json") != null)
            }
            val uri =
                FileProvider.getUriForFile(
                    targetContext,
                    targetContext.packageName + ".updates",
                    archive,
                )
            targetContext.contentResolver.openInputStream(uri)!!.use {
                check(it.read() == 0x50 && it.read() == 0x4b)
            }
            result.putString("stream", "外部目录、旧壁纸迁移、延迟清理、UTF-8 中文日志、原始异常和诊断 ZIP 分享读取验证通过。\n")
            success = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            if (previous == null) prefs.edit().remove("project").commit()
            else prefs.edit().putString("project", previous).commit()
            legacy.deleteRecursively()
            destination.deleteRecursively()
            oldLog.delete()
        }
        finish(if (success) -1 else 0, result)
    }
}
