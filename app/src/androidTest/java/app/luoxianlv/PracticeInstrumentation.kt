package app.luoxianlv

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.luoxianlv.profile.ScreenRecognizer
import app.luoxianlv.ui.practice.*
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import app.luoxianlv.wallpaper.render.PracticeBackdrop
import java.io.File

/**
 * Framework-only integration runner: real Android drawing, audio preparation and touch dispatch.
 */
class PracticeInstrumentation : Instrumentation() {
    private var testWallpaper = false
    private var argumentsEntryOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testWallpaper = arguments?.getString("wallpaper") == "true"
        argumentsEntryOnly = arguments?.getString("entryOnly") == "true"
        start()
    }

    override fun onStart() {
        var stage: PracticeActivity? = null
        val result = Bundle()
        try {
            checkStageEntry()
            if (argumentsEntryOnly) {
                finish(
                    -1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Stage entry: home window, double click, landscape reveal and portrait return passed.\n",
                        )
                    },
                )
                return
            }
            if (testWallpaper) {
                val source = File(targetContext.getExternalFilesDir(null), "blackhole-original.zip")
                check(source.isFile) { "Missing original-project fixture" }
                WallpaperProjectStore.import(targetContext, android.net.Uri.fromFile(source), false)
                val selected = WallpaperProjectStore.current(targetContext)
                check(
                    WallpaperProjectStore.root(targetContext)?.resolve("scene.pkg")?.length() ==
                        33659165L
                )
                val invalid = File(targetContext.cacheDir, "invalid-wallpaper.zip")
                java.util.zip.ZipOutputStream(invalid.outputStream()).use {
                    it.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
                    it.write(byteArrayOf(1))
                    it.closeEntry()
                }
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
                check(WallpaperProjectStore.current(targetContext) == selected) {
                    "Failed import replaced selected wallpaper"
                }
                check(!File(targetContext.filesDir, "wallpapers/escape.txt").exists())
            } else WallpaperProjectStore.reset(targetContext)
            stage =
                startActivitySync(
                    Intent(targetContext, PracticeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as PracticeActivity
            val activity = stage
            repeat(150) { if (!PracticePlaybackGate.ready) Thread.sleep(100) }
            check(PracticePlaybackGate.ready) { "Stage never became ready" }
            fun find(view: View): PracticeKeyboard? =
                when (view) {
                    is PracticeKeyboard -> view
                    is ViewGroup ->
                        (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                    else -> null
                }
            lateinit var keyboard: PracticeKeyboard
            runOnMainSync { keyboard = checkNotNull(find(activity.window.decorView)) }
            fun capture(): Bitmap {
                Thread.sleep(100)
                val bitmap =
                    Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
                val latch = java.util.concurrent.CountDownLatch(1)
                var code = -1
                runOnMainSync {
                    android.view.PixelCopy.request(
                        activity.window,
                        bitmap,
                        {
                            code = it
                            latch.countDown()
                        },
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                }
                check(
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS) &&
                        code == android.view.PixelCopy.SUCCESS
                )
                return bitmap
            }
            if (testWallpaper) {
                fun background(view: View): PracticeBackdrop? =
                    when (view) {
                        is PracticeBackdrop -> view
                        is ViewGroup ->
                            (0 until view.childCount).firstNotNullOfOrNull {
                                background(view.getChildAt(it))
                            }
                        else -> null
                    }
                lateinit var backdrop: PracticeBackdrop
                runOnMainSync { backdrop = checkNotNull(background(activity.window.decorView)) }
                val deadline = SystemClock.uptimeMillis() + 35000
                while (SystemClock.uptimeMillis() < deadline) {
                    var state = ""
                    runOnMainSync { state = backdrop.renderState }
                    if (state != "loading") {
                        check(state == "ready") { "Scene renderer state: $state" }
                        break
                    }
                    Thread.sleep(200)
                }
                runOnMainSync { check(backdrop.renderState == "ready") { "Scene did not render" } }
                val first = capture()
                Thread.sleep(1200)
                val second = capture()
                var changed = 0
                for (y in 0 until first.height step 8) for (x in 0 until first.width step 8) {
                    if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
                }
                check(changed > 100) { "Scene is static: changed pixels=$changed" }
                File(targetContext.getExternalFilesDir(null), "practice-blackhole.png")
                    .outputStream()
                    .use { second.compress(Bitmap.CompressFormat.PNG, 100, it) }
                first.recycle()
                second.recycle()
            }
            val fit = PracticeGeometry.fit(keyboard.width.toFloat(), keyboard.height.toFloat())
            fun touch(key: PracticeGeometry.Key, action: Int) {
                runOnMainSync {
                    val now = SystemClock.uptimeMillis()
                    val event =
                        MotionEvent.obtain(
                            now,
                            now,
                            action,
                            fit.left + key.x * fit.scale,
                            fit.top + key.y * fit.scale,
                            0,
                        )
                    keyboard.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            for (modeIndex in listOf(2, 1, 3)) for (half in listOf(false, true)) {
                touch(PracticeGeometry.modes[modeIndex], MotionEvent.ACTION_DOWN)
                // Assert before UP: modifiers are synchronous on DOWN.
                check(
                    keyboard.session.mode ==
                        listOf(
                            PracticeSession.Mode.NATURAL,
                            PracticeSession.Mode.RAISE,
                            PracticeSession.Mode.NATURAL,
                            PracticeSession.Mode.LOWER,
                        )[modeIndex]
                )
                touch(PracticeGeometry.modes[modeIndex], MotionEvent.ACTION_UP)
                if (keyboard.session.half != half) {
                    touch(PracticeGeometry.modes[0], MotionEvent.ACTION_DOWN)
                    check(keyboard.session.half == half)
                    touch(PracticeGeometry.modes[0], MotionEvent.ACTION_UP)
                }
                lateinit var bitmap: Bitmap
                bitmap = capture()
                File(targetContext.getExternalFilesDir(null), "practice-$modeIndex-$half.png")
                    .outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val recognized = ScreenRecognizer.fromBitmap(bitmap)
                bitmap.recycle()
                checkNotNull(recognized) { "Recognition failed mode=$modeIndex half=$half" }
                for (i in 0..7) check(
                    kotlin.math.abs(
                        recognized.layout.noteX[i] * keyboard.width -
                            (fit.left + PracticeGeometry.notes[i].x * fit.scale)
                    ) < 8
                ) {
                    "Wrong key $i"
                }
                check(recognized.halfTone == half) {
                    "Wrong half-tone state: ${recognized.halfTone} expected $half"
                }
                check(recognized.mode?.name == keyboard.session.mode.name) {
                    "Wrong mode: ${recognized.mode}"
                }
                touch(PracticeGeometry.notes[0], MotionEvent.ACTION_DOWN)
                check(
                    keyboard.session.active?.midi ==
                        PracticeSession.pitch(0, keyboard.session.mode, half)
                )
                touch(PracticeGeometry.notes[0], MotionEvent.ACTION_UP)
                check(keyboard.session.active == null)
            }
            // Stage toolbar opens a portrait library; GIF preview runs before returning to
            // landscape.
            val pickerMonitor = addMonitor(WallpaperPickerActivity::class.java.name, null, false)
            runOnMainSync {
                val density = keyboard.resources.displayMetrics.density
                val x = keyboard.safeLeft + 99 * density
                val y = keyboard.safeTop + 39 * density
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).let {
                    keyboard.dispatchTouchEvent(it)
                    it.recycle()
                }
                MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0).let {
                    keyboard.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            val picker =
                waitForMonitorWithTimeout(pickerMonitor, 10000) as? WallpaperPickerActivity
                    ?: error("Wallpaper toolbar failed to open picker")
            fun descendants(view: View): List<View> =
                listOf(view) +
                    if (view is ViewGroup)
                        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                    else emptyList()
            var previewReady = false
            repeat(100) {
                runOnMainSync {
                    check(
                        picker.resources.configuration.orientation ==
                            android.content.res.Configuration.ORIENTATION_PORTRAIT
                    )
                    previewReady =
                        descendants(picker.window.decorView)
                            .filterIsInstance<android.widget.ImageView>()
                            .any {
                                if (
                                    testWallpaper || WallpaperProjectStore.hasBundled(targetContext)
                                )
                                    (it.drawable as? android.graphics.drawable.Animatable)
                                        ?.isRunning == true
                                else it.drawable != null
                            }
                }
                if (!previewReady) Thread.sleep(100)
            }
            check(previewReady) { "Project preview did not load" }
            val pickerFrame =
                Bitmap.createBitmap(
                    picker.window.decorView.width,
                    picker.window.decorView.height,
                    Bitmap.Config.ARGB_8888,
                )
            val pickerLatch = java.util.concurrent.CountDownLatch(1)
            var copyCode = -1
            runOnMainSync {
                android.view.PixelCopy.request(
                    picker.window,
                    pickerFrame,
                    {
                        copyCode = it
                        pickerLatch.countDown()
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
            check(
                pickerLatch.await(5, java.util.concurrent.TimeUnit.SECONDS) &&
                    copyCode == android.view.PixelCopy.SUCCESS
            )
            File(targetContext.getExternalFilesDir(null), "wallpaper-picker.png")
                .outputStream()
                .use { pickerFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pickerFrame.recycle()
            check(!PracticePlaybackGate.ready) { "Stage kept playing behind picker" }
            val stageMonitor = addMonitor(PracticeActivity::class.java.name, null, false)
            runOnMainSync { picker.onBackPressedDispatcher.onBackPressed() }
            val returned =
                waitForMonitorWithTimeout(stageMonitor, 10000) as? PracticeActivity
                    ?: error("Picker did not return to stage")
            runOnMainSync {
                check(descendants(returned.window.decorView).any { it is PracticeBackdrop }) {
                    "Wallpaper loading waits for audio"
                }
                returned.finish()
            }
            removeMonitor(pickerMonitor)
            removeMonitor(stageMonitor)
            runOnMainSync { activity.finish() }
            waitForIdleSync()
            check(!PracticePlaybackGate.ready)
            // A new stage interrupted while loading/animating must not publish a delayed ready
            // callback.
            stage =
                startActivitySync(
                    Intent(targetContext, PracticeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as PracticeActivity
            val interrupted = stage
            runOnMainSync { interrupted.finish() }
            Thread.sleep(1400)
            check(!PracticePlaybackGate.active && !PracticePlaybackGate.ready) {
                "Stale stage callback after exit"
            }
            result.putString(
                "stream",
                "Practice: six mode states recognized, synchronous modifiers, real touches, exit, portrait preview picker, early backdrop and interrupted entry passed.\n",
            )
            finish(-1, result)
        } catch (failure: Throwable) {
            stage?.let { runOnMainSync { it.finish() } }
            result.putString("stream", failure.stackTraceToString())
            finish(0, result)
        }
    }
}
