package app.luoxianlv

import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.luoxianlv.ui.practice.*
import app.luoxianlv.ui.wallpaper.WallpaperImportModel
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import app.luoxianlv.wallpaper.render.PracticeBackdrop
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.json.JSONObject

class WallpaperImportInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var current: android.app.Activity? = null
        val result = Bundle()
        try {
            fun archive(type: String): File {
                val folder = File(targetContext.cacheDir, "updates").apply { mkdirs() }
                val file = File(folder, "wallpaper-$type.zip")
                val entry =
                    when (type) {
                        "web" -> "index.html"
                        "video" -> "video.mp4"
                        else -> "picture.png"
                    }
                ZipOutputStream(file.outputStream()).use { zip ->
                    fun put(name: String, data: ByteArray) {
                        zip.putNextEntry(ZipEntry("3113554287/$name"))
                        zip.write(data)
                        zip.closeEntry()
                    }
                    put(
                        "project.json",
                        JSONObject()
                            .put("title", "Fixture-$type")
                            .put("type", type)
                            .put("file", entry)
                            .toString()
                            .toByteArray(),
                    )
                    if (type == "web") {
                        put(
                            entry,
                            "<html><head><link rel='stylesheet' href='styles/main.css'><script src='app.js' defer></script></head><body></body></html>"
                                .toByteArray(),
                        )
                        put(
                            "styles/main.CSS",
                            "html,body{margin:0;width:100%;height:100%;background:red}"
                                .toByteArray(),
                        )
                        put("color.json", "{\"color\":\"#1655cc\"}".toByteArray())
                        put(
                            "app.js",
                            "let isolated=false;try{parent.document.body}catch(e){isolated=true}fetch('color.json').then(r=>r.json()).then(c=>{document.body.style.background=isolated?c.color:'red'})"
                                .toByteArray(),
                        )
                        put(
                            "lib/project.json",
                            "{}".toByteArray(),
                        ) // nested unrelated metadata must not override project root
                    } else if (type == "video")
                        put(entry, context.assets.open("wallpaper-test.mp4").use { it.readBytes() })
                    else {
                        val image =
                            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(Color.GREEN)
                            }
                        val out = ByteArrayOutputStream()
                        image.compress(Bitmap.CompressFormat.PNG, 100, out)
                        image.recycle()
                        put(entry, out.toByteArray())
                    }
                }
                return file
            }
            fun importFromSystem(file: File, action: String, mime: String) {
                val uri =
                    FileProvider.getUriForFile(
                        targetContext,
                        targetContext.packageName + ".updates",
                        file,
                    )
                val intent =
                    Intent(action)
                        .setPackage(targetContext.packageName)
                        .addCategory(Intent.CATEGORY_DEFAULT)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                if (action == Intent.ACTION_VIEW) intent.setDataAndType(uri, mime)
                else {
                    intent.type = mime
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.clipData = ClipData.newRawUri("wallpaper", uri)
                }
                val resolved =
                    targetContext.packageManager.resolveActivity(
                        intent,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                    )
                check(resolved?.activityInfo?.name == WallpaperImportActivity::class.java.name) {
                    "Missing system ZIP association: $action $mime"
                }
                var activity = startActivitySync(intent) as WallpaperImportActivity
                current = activity
                lateinit var model: WallpaperImportModel
                runOnMainSync {
                    model =
                        ViewModelProvider(activity.businessModels())[
                            WallpaperImportModel::class.java]
                }
                lateinit var retainedJob: Job
                runOnMainSync { retainedJob = model.viewModelScope.launch { awaitCancellation() } }
                val monitor = addMonitor(WallpaperImportActivity::class.java.name, null, false)
                val original = activity
                runOnMainSync { original.recreate() }
                activity =
                    waitForMonitorWithTimeout(monitor, 15000) as? WallpaperImportActivity
                        ?: error("壁纸导入窗口未重建")
                removeMonitor(monitor)
                current = activity
                runOnMainSync {
                    check(
                        ViewModelProvider(activity.businessModels())[
                            WallpaperImportModel::class.java] === model
                    ) {
                        "壁纸导入模型在重建后被替换"
                    }
                    check(retainedJob.isActive) { "窗口重建取消了导入任务所属作用域" }
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 20000
                var done = false
                while (!done && android.os.SystemClock.uptimeMillis() < deadline) {
                    runOnMainSync { done = !model.busy }
                    if (!done) Thread.sleep(100)
                }
                runOnMainSync {
                    check(model.success) { model.message }
                    activity.finish()
                }
                val closeDeadline = android.os.SystemClock.uptimeMillis() + 5000
                while (
                    !retainedJob.isCancelled &&
                        android.os.SystemClock.uptimeMillis() < closeDeadline
                ) Thread.sleep(50)
                check(retainedJob.isCancelled) { "真正关闭导入窗口后仍保留业务任务" }
            }
            fun all(v: View): List<View> =
                listOf(v) +
                    if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) }
                    else emptyList()
            for ((index, type) in listOf("image", "video", "web").withIndex()) {
                importFromSystem(
                    archive(type),
                    if (index == 1) Intent.ACTION_SEND else Intent.ACTION_VIEW,
                    if (index == 2) "application/octet-stream" else "application/zip",
                )
                val activity =
                    startActivitySync(
                        Intent(targetContext, PracticeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                        as PracticeActivity
                current = activity
                var ready = false
                val deadline = android.os.SystemClock.uptimeMillis() + 30000
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    runOnMainSync {
                        ready =
                            all(activity.window.decorView)
                                .filterIsInstance<PracticeBackdrop>()
                                .firstOrNull()
                                ?.renderState == "ready" &&
                                app.luoxianlv.ui.practice.PracticePlaybackGate.ready
                    }
                    if (!ready) Thread.sleep(200)
                }
                check(ready) { "$type failed to render" }
                Thread.sleep(1000)
                fun capture(): Bitmap {
                    val frame =
                        Bitmap.createBitmap(
                            activity.window.decorView.width,
                            activity.window.decorView.height,
                            Bitmap.Config.ARGB_8888,
                        )
                    val latch = CountDownLatch(1)
                    var code = -1
                    runOnMainSync {
                        PixelCopy.request(
                            activity.window,
                            frame,
                            {
                                code = it
                                latch.countDown()
                            },
                            Handler(Looper.getMainLooper()),
                        )
                    }
                    check(latch.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS)
                    return frame
                }
                val first = capture()
                val color = first.getPixel(first.width / 2, first.height / 5)
                if (type == "image")
                    check(Color.green(color) > Color.red(color) + 30) { "Image did not display" }
                if (type == "web")
                    check(Color.blue(color) > Color.red(color) + 30) {
                        "Web assets/fetch/sandbox failed: $color"
                    }
                if (type == "video") {
                    Thread.sleep(900)
                    val second = capture()
                    check(!first.sameAs(second))
                    second.recycle()
                }
                File(targetContext.getExternalFilesDir(null), "import-$type.png")
                    .outputStream()
                    .use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
                first.recycle()
                runOnMainSync { activity.finish() }
            }
            val previous = WallpaperProjectStore.current(targetContext)
            val invalid = File(targetContext.cacheDir, "bad.zip").apply { writeText("not a ZIP") }
            check(
                runCatching {
                    WallpaperProjectStore.import(
                        targetContext,
                        android.net.Uri.fromFile(invalid),
                        false,
                    )
                }
                    .isFailure
            )
            check(WallpaperProjectStore.current(targetContext) == previous)
            val original = File(targetContext.getExternalFilesDir(null), "elaina-original.zip")
            WallpaperProjectStore.import(targetContext, android.net.Uri.fromFile(original), false)
            check(
                WallpaperProjectStore.scenePackage(WallpaperProjectStore.root(targetContext)!!)!!
                    .length() == 84230588L
            )
            // 测试结束会立即终止进程，须先同步写入样本清理结果。
            check(
                targetContext
                    .getSharedPreferences(
                        "practice_wallpaper",
                        android.content.Context.MODE_PRIVATE,
                    )
                    .edit()
                    .remove("project")
                    .commit()
            )
            result.putString(
                "stream",
                "System VIEW/SEND ZIP and octet-stream associations, content URI import, image/video/web rendering, sandboxed web fetch, invalid rollback and original folder ZIP passed.\n",
            )
            finish(-1, result)
        } catch (error: Throwable) {
            current?.let { runOnMainSync { it.finish() } }
            result.putString("stream", error.stackTraceToString())
            finish(0, result)
        }
    }
}
