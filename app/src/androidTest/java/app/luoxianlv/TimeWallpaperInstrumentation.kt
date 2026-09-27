package app.luoxianlv

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import app.luoxianlv.ui.practice.*
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import app.luoxianlv.wallpaper.render.PracticeBackdrop
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the bundled original project, original ZIP import and all four supplied clock periods.
 */
class TimeWallpaperInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var activity: PracticeActivity? = null
        val result = Bundle()
        try {
            WallpaperProjectStore.current(
                targetContext
            ) // Apply bundled-default migration before explicit selection.
            WallpaperProjectStore.reset(targetContext)
            WallpaperProjectStore.setMinute(targetContext, 480)
            activity =
                startActivitySync(
                    Intent(targetContext, PracticeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as PracticeActivity
            val stage = activity
            fun all(view: View): List<View> =
                listOf(view) +
                    if (view is ViewGroup)
                        (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
                    else emptyList()
            var backdrop: PracticeBackdrop? = null
            repeat(250) {
                runOnMainSync {
                    backdrop =
                        all(stage.window.decorView)
                            .filterIsInstance<PracticeBackdrop>()
                            .firstOrNull()
                }
                if (backdrop?.renderState != "ready") Thread.sleep(200)
            }
            check(backdrop?.renderState == "ready") {
                "Bundled project failed: ${backdrop?.renderState}"
            }
            lateinit var browser: WebView
            runOnMainSync {
                browser = all(stage.window.decorView).filterIsInstance<WebView>().single()
            }
            fun capture(): Bitmap {
                val image =
                    Bitmap.createBitmap(
                        stage.window.decorView.width,
                        stage.window.decorView.height,
                        Bitmap.Config.ARGB_8888,
                    )
                val latch = CountDownLatch(1)
                var code = -1
                runOnMainSync {
                    PixelCopy.request(
                        stage.window,
                        image,
                        {
                            code = it
                            latch.countDown()
                        },
                        Handler(Looper.getMainLooper()),
                    )
                }
                check(latch.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS)
                return image
            }
            var previous: Bitmap? = null
            for (minute in listOf(480, 720, 1080, 1320)) {
                runOnMainSync {
                    browser.evaluateJavascript("window.setWallpaperTime($minute)", null)
                }
                Thread.sleep(500)
                var videoReady = false
                val deadline = android.os.SystemClock.uptimeMillis() + 30000
                while (!videoReady && android.os.SystemClock.uptimeMillis() < deadline) {
                    val latch = CountDownLatch(1)
                    runOnMainSync {
                        browser.evaluateJavascript(
                            "(()=>{const v=[...document.querySelectorAll('video')].filter(v=>v.hasAttribute('src'));return v.length===1 && !v[0].paused && v[0].readyState>=2 && v[0].currentTime>0})()"
                        ) {
                            videoReady = it == "true"
                            latch.countDown()
                        }
                    }
                    check(latch.await(5, TimeUnit.SECONDS))
                    if (!videoReady) Thread.sleep(250)
                }
                check(videoReady) { "Time $minute video failed to decode/play" }
                var frame = capture()
                val frameDeadline = android.os.SystemClock.uptimeMillis() + 25000
                fun hasColor(image: Bitmap): Boolean {
                    var count = 0
                    for (y in 0 until image.height / 3 step 12) for (x in
                        0 until image.width step 12) {
                        val color = image.getPixel(x, y)
                        if (
                            kotlin.math.abs(
                                android.graphics.Color.red(color) -
                                    android.graphics.Color.blue(color)
                            ) > 18
                        )
                            count++
                    }
                    return count > 100
                }
                while (!hasColor(frame) && android.os.SystemClock.uptimeMillis() < frameDeadline) {
                    frame.recycle()
                    Thread.sleep(500)
                    frame = capture()
                }
                check(hasColor(frame)) { "Time $minute decoded but texture remained blank" }
                File(targetContext.getExternalFilesDir(null), "elaina-$minute.png")
                    .outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                previous?.let { old ->
                    var largeChanges = 0
                    for (y in 0 until frame.height step 12) for (x in 0 until frame.width step 12) {
                        val a = old.getPixel(x, y)
                        val b = frame.getPixel(x, y)
                        if (
                            kotlin.math.abs(
                                android.graphics.Color.red(a) - android.graphics.Color.red(b)
                            ) +
                                kotlin.math.abs(
                                    android.graphics.Color.blue(a) - android.graphics.Color.blue(b)
                                ) > 30
                        )
                            largeChanges++
                    }
                    check(largeChanges > 100) { "Time $minute did not change scene" }
                    old.recycle()
                }
                previous = frame
            }
            previous?.recycle()
            fun videoTime(): Double {
                val latch = CountDownLatch(1)
                var time = -1.0
                runOnMainSync {
                    browser.evaluateJavascript(
                        "(()=>{const v=[...document.querySelectorAll('video')].find(v=>v.hasAttribute('src'));return v?v.currentTime:-1})()"
                    ) { value ->
                        time = value.toDoubleOrNull() ?: -1.0
                        latch.countDown()
                    }
                }
                check(latch.await(5, TimeUnit.SECONDS))
                return time
            }
            // SwiftShader and 4K software decode need not produce a new frame within 1.2s.
            // Require both media-clock progress and a genuinely changed rendered frame.
            val startTime = videoTime()
            val first = capture()
            val animationDeadline = android.os.SystemClock.uptimeMillis() + 10000
            var advanced = false
            var changed = false
            var lastTime = startTime
            while (
                !(advanced && changed) && android.os.SystemClock.uptimeMillis() < animationDeadline
            ) {
                Thread.sleep(250)
                lastTime = videoTime()
                advanced =
                    advanced || (lastTime >= 0 && kotlin.math.abs(lastTime - startTime) > .05)
                val frame = capture()
                changed = changed || !first.sameAs(frame)
                frame.recycle()
            }
            first.recycle()
            check(advanced && changed) {
                "Video layers did not animate at fixed time: media=$startTime->$lastTime changed=$changed"
            }
            runOnMainSync { stage.finish() }
            check(!PracticePlaybackGate.ready)
            val source = File(targetContext.getExternalFilesDir(null), "elaina-original.zip")
            WallpaperProjectStore.import(targetContext, android.net.Uri.fromFile(source), false)
            check(
                WallpaperProjectStore.root(targetContext)?.resolve("scene.pkg")?.length() ==
                    84230588L
            )
            WallpaperProjectStore.reset(targetContext)
            WallpaperProjectStore.setMinute(targetContext, null)
            result.putString(
                "stream",
                "Elaina: bundled scene, four time periods, animation at fixed time, original ZIP import and immediate exit passed.\n",
            )
            finish(-1, result)
        } catch (error: Throwable) {
            activity?.let { runOnMainSync { it.finish() } }
            result.putString("stream", error.stackTraceToString())
            finish(0, result)
        }
    }
}
