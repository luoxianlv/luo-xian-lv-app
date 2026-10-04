package app.luoxianlv

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.luoxianlv.practice.PracticePlaybackGate
import app.luoxianlv.ui.practice.PracticeActivity
import java.io.File

/** 通过无障碍点击实际 Compose 入口，验证完整进入流程。 */
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
    if (app.luoxianlv.wallpaper.WallpaperProjectStore.hasBundled(targetContext)) {
        app.luoxianlv.wallpaper.WallpaperProjectStore.reset(targetContext)
    } else {
        app.luoxianlv.wallpaper.WallpaperProjectStore.import(
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
        if (node.isVisibleToUser && node.text?.toString() == "演练场") {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) return target
        }
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
    fun background(view: android.view.View): app.luoxianlv.wallpaper.PracticeBackdrop? =
        when (view) {
            is app.luoxianlv.wallpaper.PracticeBackdrop -> view
            is android.view.ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { background(view.getChildAt(it)) }
            else -> null
        }
    var warmed: app.luoxianlv.wallpaper.PracticeBackdrop? = null
    await("Home did not preload actual wallpaper") {
        var ready = false
        runOnMainSync {
            warmed = background(home.window.decorView)
            ready = warmed?.renderState == "ready"
        }
        ready
    }
    fun assertRendererPaused(
        backdrop: app.luoxianlv.wallpaper.PracticeBackdrop,
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
    // 跨到最远的 Tab 再返回，首页会被回收，但预加载的壁纸不应重建。
    fun navigation(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (
            node.isVisibleToUser &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)
        ) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) return target
        }
        return (0 until node.childCount).firstNotNullOfOrNull {
            navigation(node.getChild(it), label)
        }
    }
    fun tapNavigation(label: String) {
        val node = checkNotNull(navigation(uiAutomation.rootInActiveWindow, label))
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach {
            action ->
            val event =
                android.view.MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                )
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        Thread.sleep(1200)
    }
    tapNavigation("设置")
    tapNavigation("我的")
    await("Home entrance did not return") {
        entrance = entry(uiAutomation.rootInActiveWindow)
        entrance != null
    }
    runOnMainSync {
        check(background(home.window.decorView) === warmed) {
            "Tab navigation recreated the prepared wallpaper"
        }
    }
    assertRendererPaused(warmed!!, true)
    capture("home.png")
    val monitor = addMonitor(PracticeActivity::class.java.name, null, false)
    var originalPortrait = false
    runOnMainSync { originalPortrait = home.window.decorView.height > home.window.decorView.width }
    val originalOrientation = home.requestedOrientation
    // 页面重组和截图后重新取节点，避免使用已经失效的语义节点。
    entrance = checkNotNull(entry(uiAutomation.rootInActiveWindow))
    check(entrance!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    // 重复点击不能创建第二个演练场。
    entrance!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    val stage =
        waitForMonitorWithTimeout(monitor, 20000) as? PracticeActivity
            ?: error("Entry did not launch stage")
    var portraitKeys = false
    var portraitReveal = false
    fun curtain(view: android.view.View): app.luoxianlv.practice.StageCurtain? =
        when (view) {
            is app.luoxianlv.practice.StageCurtain -> view
            is android.view.ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { curtain(view.getChildAt(it)) }
            else -> null
        }
    fun hasKeyboard(view: android.view.View): Boolean =
        view is app.luoxianlv.practice.PracticeKeyboard ||
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
    fun fullscreenGuide(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (
            node.isVisibleToUser &&
                node.packageName?.toString() == "com.android.systemui" &&
                node.text?.toString() in setOf("Got it", "知道了")
        )
            return node
        return (0 until node.childCount).firstNotNullOfOrNull { fullscreenGuide(node.getChild(it)) }
    }
    var guideSeen = 0L
    var guideDismissed = false
    await("Stage not playable") {
        check(!stage.isFinishing && !stage.isDestroyed) { "Stage exited before becoming playable" }
        if (!guideDismissed) {
            uiAutomation.clearCache()
            fullscreenGuide(uiAutomation.rootInActiveWindow)?.let { guide ->
                if (guideSeen == 0L) guideSeen = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - guideSeen >= 5000) {
                    var target = guide
                    while (!target.isClickable && target.parent != null) target = target.parent
                    check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    guideDismissed = true
                }
            }
        }
        PracticePlaybackGate.ready
    }
    if (guideDismissed) {
        sendStatus(
            1,
            android.os.Bundle().apply {
                putString("stream", "系统全屏提示停留 5 秒后继续进入演练场通过。\n")
            },
        )
    }
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
        stage.onBackPressed()
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
    app.luoxianlv.wallpaper.WallpaperProjectStore.reset(targetContext)
}
