package app.luoxianlv

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.luoxianlv.ui.practice.PracticeActivity
import app.luoxianlv.ui.practice.PracticePlaybackGate
import java.io.File

/** Exercises the actual Compose entrance through its accessibility click action. */
internal fun Instrumentation.checkStageEntry() {
    val fixture = File(targetContext.cacheDir, "preload-check.zip")
    java.util.zip.ZipOutputStream(fixture.outputStream()).use { zip ->
        mapOf(
                "project.json" to """{"title":"Preload test","type":"web","file":"index.html"}""",
                "index.html" to
                    "<html><body style='margin:0;background:#223344'>Ready</body></html>",
            )
            .forEach { (name, data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data.toByteArray())
                zip.closeEntry()
            }
    }
    if (app.luoxianlv.wallpaper.data.WallpaperProjectStore.hasBundled(targetContext)) {
        app.luoxianlv.wallpaper.data.WallpaperProjectStore.reset(targetContext)
    } else {
        app.luoxianlv.wallpaper.data.WallpaperProjectStore.import(
            targetContext,
            android.net.Uri.fromFile(fixture),
            false,
        )
    }
    val home =
        startActivitySync(
            Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
            as MainActivity
    fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 75000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        check(predicate()) { message }
    }
    fun entry(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == "演练场") return if (node.isClickable) node else node.parent
        return (0 until node.childCount).firstNotNullOfOrNull { entry(node.getChild(it)) }
    }
    var entrance: AccessibilityNodeInfo? = null
    await("Missing home stage window") {
        entrance = entry(uiAutomation.rootInActiveWindow)
        entrance != null
    }
    val directory =
        File(targetContext.getExternalFilesDir(null), "stage-entry-check").apply { mkdirs() }
    fun capture(name: String) {
        uiAutomation.takeScreenshot()?.let { image ->
            File(directory, name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
    fun background(view: android.view.View): app.luoxianlv.wallpaper.render.PracticeBackdrop? =
        when (view) {
            is app.luoxianlv.wallpaper.render.PracticeBackdrop -> view
            is android.view.ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { background(view.getChildAt(it)) }
            else -> null
        }
    var warmed: app.luoxianlv.wallpaper.render.PracticeBackdrop? = null
    await("Home did not preload actual wallpaper") {
        var ready = false
        runOnMainSync {
            warmed = background(home.window.decorView)
            ready = warmed?.renderState == "ready"
        }
        ready
    }
    fun assertRendererPaused(
        backdrop: app.luoxianlv.wallpaper.render.PracticeBackdrop,
        expected: Boolean,
    ) {
        await("Wallpaper pause state did not become $expected") {
            val result = java.util.concurrent.atomic.AtomicReference<String>()
            val done = java.util.concurrent.CountDownLatch(1)
            runOnMainSync {
                val browser =
                    (0 until backdrop.childCount)
                        .map { backdrop.getChildAt(it) }
                        .filterIsInstance<android.webkit.WebView>()
                        .single()
                browser.evaluateJavascript("window.wallpaperPaused?.()") {
                    result.set(it)
                    done.countDown()
                }
            }
            done.await(2, java.util.concurrent.TimeUnit.SECONDS) &&
                result.get() == expected.toString()
        }
    }
    assertRendererPaused(warmed!!, true)
    capture("home.png")
    val monitor = addMonitor(PracticeActivity::class.java.name, null, false)
    var originalPortrait = false
    runOnMainSync { originalPortrait = home.window.decorView.height > home.window.decorView.width }
    val originalOrientation = home.requestedOrientation
    check(entrance!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    // Repeated taps must not schedule a second activity.
    entrance!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    val stage =
        waitForMonitorWithTimeout(monitor, 20000) as? PracticeActivity
            ?: error("Entry did not launch stage")
    var portraitKeys = false
    var portraitReveal = false
    fun curtain(view: android.view.View): app.luoxianlv.ui.practice.StageCurtain? =
        when (view) {
            is app.luoxianlv.ui.practice.StageCurtain -> view
            is android.view.ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { curtain(view.getChildAt(it)) }
            else -> null
        }
    fun hasKeyboard(view: android.view.View): Boolean =
        view is app.luoxianlv.ui.practice.PracticeKeyboard ||
            (view is android.view.ViewGroup &&
                (0 until view.childCount).any { hasKeyboard(view.getChildAt(it)) })
    val frameCheck =
        android.view.ViewTreeObserver.OnPreDrawListener {
            val root = stage.window.decorView
            if (root.width <= root.height && hasKeyboard(root)) portraitKeys = true
            if (root.width <= root.height && (curtain(root)?.openingProgress ?: 0f) > .4f)
                portraitReveal = true
            true
        }
    runOnMainSync { stage.window.decorView.viewTreeObserver.addOnPreDrawListener(frameCheck) }
    await("Stage not playable") { PracticePlaybackGate.ready }
    runOnMainSync {
        stage.window.decorView.viewTreeObserver.removeOnPreDrawListener(frameCheck)
        check(!portraitKeys) { "Keyboard appeared before landscape" }
        check(!portraitReveal) { "Light burst began before landscape" }
        check(curtain(stage.window.decorView)?.visibility == android.view.View.GONE) {
            "Playback unlocked before the curtain cleared"
        }
    }
    check(monitor.hits == 1) { "Repeated tap opened multiple stages" }
    var landscape = false
    runOnMainSync { landscape = stage.window.decorView.width > stage.window.decorView.height }
    check(landscape)
    runOnMainSync {
        check(background(stage.window.decorView) === warmed) { "Prepared renderer was recreated" }
    }
    assertRendererPaused(warmed!!, false)
    capture("stage.png")
    runOnMainSync {
        stage.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    await("Playback remained enabled in background") { !PracticePlaybackGate.ready }
    assertRendererPaused(warmed!!, true)
    check(!stage.isFinishing && !stage.isDestroyed) { "Backgrounding destroyed the stage" }
    runOnMainSync {
        targetContext.startActivity(
            Intent(targetContext, PracticeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
    await("Stage did not resume") { PracticePlaybackGate.ready }
    assertRendererPaused(warmed!!, false)
    await("Original stage was not restored") {
        var restored = false
        runOnMainSync { restored = !stage.isDestroyed && stage.hasWindowFocus() }
        restored
    }

    runOnMainSync {
        stage.onBackPressedDispatcher.onBackPressed()
        check(
            stage.requestedOrientation ==
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ) {
            "Exit waited for animation before requesting portrait"
        }
        check(!PracticePlaybackGate.ready) { "Exit kept playback enabled" }
    }
    await("Home curtain remained after return") {
        var removed = false
        runOnMainSync {
            removed =
                home.window.decorView.findViewWithTag<android.view.View>(
                    "practice-entry-curtain"
                ) == null
        }
        removed && !PracticePlaybackGate.ready
    }
    check(!home.isDestroyed) { "Rotation recreated home during transition" }
    check(home.requestedOrientation == originalOrientation) { "Home orientation not restored" }
    await("Home did not restore its original shape") {
        var restored = false
        runOnMainSync {
            restored =
                (home.window.decorView.height > home.window.decorView.width) == originalPortrait
        }
        restored
    }
    capture("returned.png")
    removeMonitor(monitor)
    runOnMainSync { home.finish() }
    app.luoxianlv.wallpaper.data.WallpaperProjectStore.reset(targetContext)
}
