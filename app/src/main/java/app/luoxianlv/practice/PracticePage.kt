package app.luoxianlv.practice

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.business.ui.ViewPage
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.wallpaper.PracticeBackdrop
import app.luoxianlv.wallpaper.PreparedWallpaper
import app.luoxianlv.wallpaper.WallpaperProjectStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 演奏和渲染属于业务代际；只有实际展示的代际能持有播放入口和窗口设置。 */
class PracticePage : ViewPage() {
    private var sampler: HarmonicaSampler? = null
    private var keyboard: PracticeKeyboard? = null
    private var backdrop: PracticeBackdrop? = null
    private var gravityLens: StageGravityLens? = null
    private var started = false
    private var resumed = false
    private var routeActive = false
    @Volatile private var foregroundSnapshot = false
    @Volatile private var canvasSnapshot: Canvas? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusChanged = ViewTreeObserver.OnWindowFocusChangeListener { updateForeground() }
    private var activated = false
    private var closing = false
    private var curtain: StageCurtain? = null
    private var exiting = false
    private var keyboardReady = false
    private var openingFinished = false
    private var reportedReady = false
    private var pageReady: NativePage.Ready? = null
    private var loadedSamples: Map<Int, app.luoxianlv.practice.HarmonicaSample>? = null
    private val ownsSession
        get() = PracticePlaybackGate.owns(this)

    private val ending
        get() = closed || closing || activity.isFinishing

    private val foreground
        get() = foregroundSnapshot

    private data class Canvas(val width: Int, val height: Int, val x: Int, val y: Int)

    private val resources
        get() = pageContext.resources

    override fun createView(state: Bundle, ready: NativePage.Ready): View {
        pageReady = ready
        val restored = state.getBoolean("playable", false)
        val mode =
            PracticeSession.Mode.entries.firstOrNull { it.name == state.getString("mode") }
                ?: PracticeSession.Mode.NATURAL
        val half = state.getBoolean("half", false)
        val root = FrameLayout(pageContext).apply { setBackgroundColor(Color.rgb(6, 8, 7)) }
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    focusTree =
                        view.viewTreeObserver.also {
                            it.addOnWindowFocusChangeListener(focusChanged)
                        }
                    updateForeground()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    removeFocusListener()
                    deactivateRoute()
                }
            }
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCanvasSnapshot() }
        // 同窗口候选自行准备无声画面，不从仍在使用的旧页夺走渲染器。
        backdrop =
            (if (host.isCurrent()) PreparedWallpaper.take(activity, pageContext)
                else PracticeBackdrop(pageContext))
                .also {
                    it.onOfficialFailure = { error -> if (!ending) ready.failed(error) }
                    it.setSoundEnabled(false)
                    root.addView(it, FrameLayout.LayoutParams(-1, -1))
                }
        if (Build.VERSION.SDK_INT >= 33) {
            gravityLens = StageGravityLens(checkNotNull(backdrop))
            backdrop?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                gravityLens?.update(if (openingFinished) 1f else curtain?.openingProgress ?: .4f)
            }
        }
        val systemDark =
            resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark =
            app.luoxianlv.settings.AppearanceStore.settings.value.themeMode.isDark(systemDark)
        val veil = StageCurtain(pageContext, activity.intent.getBooleanExtra(StageEntry.DARK, dark))
        curtain = veil
        root.addView(veil, FrameLayout.LayoutParams(-1, -1))
        veil.isClickable = true
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            keyboard?.safeRight = cutout.right
            keyboard?.safeLeft = cutout.left
            keyboard?.safeTop = cutout.top
            insets
        }
        lifecycleScope.launch {
            try {
                loadedSamples = BusinessJobs.io { HarmonicaSampler.load(pageContext) }
                while (!started && !ending) delay(50)
                if (ending) return@launch
                var lastSize = Triple(0, 0, -1)
                var stable = 0
                var attempts = 0
                while (attempts < 80 && !ending && !exiting) {
                    // 系统面板暂时抢走焦点时，不消耗横屏等待预算。
                    if (!started || !activity.hasWindowFocus()) {
                        stable = 0
                        delay(50)
                        continue
                    }
                    attempts++
                    val size = Triple(root.width, root.height, root.display?.rotation ?: -1)
                    stable =
                        if (
                            size == lastSize &&
                                resources.configuration.orientation ==
                                    android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                                size.first > size.second &&
                                size.second >= 200 * resources.displayMetrics.density
                        )
                            stable + 1
                        else 0
                    lastSize = size
                    if (stable >= 8) break
                    delay(50)
                }
                if (ending || exiting) return@launch
                if (stable < 8) {
                    AppLog.w(
                        "演练场",
                        "等待横屏超时：尺寸=$lastSize，稳定次数=$stable，焦点=${activity.hasWindowFocus()}，方向=${resources.configuration.orientation}",
                    )
                    if (host.isCurrent()) {
                        Toast.makeText(pageContext, "请使用横屏或放大窗口后进入演奏", Toast.LENGTH_LONG).show()
                        finishPage()
                    } else ready.failed(IllegalStateException("候选演奏窗口无法稳定"))
                    return@launch
                }
                while ((!started || backdrop?.prepared == false) && !ending && !exiting) delay(50)
                if (ending || exiting) return@launch
                veil.backgroundReady = true
                restoreAudio()
                val keys =
                    PracticeKeyboard(pageContext).apply {
                        session.select(mode)
                        if (half) session.toggleHalf()
                        onNoteOn = { midi ->
                            if (ownsSession && this@PracticePage.foreground)
                                sampler?.noteOn(midi) ?: false
                            else false
                        }
                        onNoteOff = { sampler?.noteOff() }
                        onExit = ::exitStage
                        wallpaperSoundEnabled = WallpaperProjectStore.soundEnabled(pageContext)
                        onWallpaperSound = {
                            if (ownsSession) {
                                val enabled = !WallpaperProjectStore.soundEnabled(pageContext)
                                WallpaperProjectStore.setSoundEnabled(pageContext, enabled)
                                wallpaperSoundEnabled = enabled
                                backdrop?.setSoundEnabled(
                                    enabled && openingFinished && resumed && !exiting
                                )
                                announceForAccessibility(if (enabled) "壁纸声音已开启" else "壁纸声音已关闭")
                            }
                        }
                        onWallpaper = {
                            host.open(
                                Intent()
                                    .setClassName(
                                        pageContext.packageName,
                                        "app.luoxianlv.ui.practice.WallpaperPickerActivity",
                                    )
                                    .putExtra("returnToPractice", true),
                                true,
                            )
                            activity.overridePendingTransition(0, 0)
                        }
                        alpha = if (restored) 1f else 0f
                        onReady = {
                            keyboardReady = true
                            publishReady()
                        }
                    }
                keyboard = keys
                PracticePlaybackGate.bindSession(this@PracticePage, keys.session)
                keys.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updateCanvasSnapshot()
                }
                root.addView(keys, root.indexOfChild(veil), FrameLayout.LayoutParams(-1, -1))
                updateCanvasSnapshot()
                ViewCompat.requestApplyInsets(root)
                if (restored) {
                    // 重建或即时替换保持当前演奏界面，不重播入场、也不恢复旧的按住状态。
                    openingFinished = true
                    veil.visibility = View.GONE
                    if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
                    keys.open(0)
                } else {
                    while (!resumed && !ending && !exiting) delay(50)
                    if (!ending && !exiting)
                        veil.reveal(
                            onProgress = { p ->
                                if (Build.VERSION.SDK_INT >= 33) gravityLens?.update(p)
                                keys.alpha = StageLightRenderer.smooth(.88f, 1f, p)
                                val zoom = 1.18f - .18f * StageLightRenderer.smooth(.64f, 1f, p)
                                backdrop?.scaleX = zoom
                                backdrop?.scaleY = zoom
                            },
                            onKeys = { if (!exiting && !ending) keys.open(650) },
                            onFinished = {
                                if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
                                openingFinished = true
                                publishReady()
                            },
                        )
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.e("演练场", "加载失败", error)
                if (host.isCurrent()) {
                    Toast.makeText(pageContext, "口琴音源加载失败，请重新进入重试", Toast.LENGTH_LONG).show()
                    finishPage()
                } else ready.failed(error)
            }
        }
        return root
    }

    private fun configureWindow() {
        val window = activity.window
        window.attributes =
            window.attributes.apply {
                rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
                if (Build.VERSION.SDK_INT >= 28)
                    layoutInDisplayCutoutMode =
                        if (Build.VERSION.SDK_INT >= 30)
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(6, 8, 7)))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun lifecycleChanged(state: Int) {
        started = state >= NativePage.STARTED
        val wasResumed = resumed
        resumed = state == NativePage.RESUMED
        if (resumed && !ending) {
            activated = true
            configureWindow()
            updateForeground()
        } else {
            if (wasResumed || foregroundSnapshot) deactivateRoute()
            keyboard?.silence()
            sampler?.close()
            sampler = null
            backdrop?.setSoundEnabled(false)
            // STARTED 的未展示候选需要预绘制；已经展示过的页面在暂停时立即停渲染。
            if (started && !activated) backdrop?.resumeRendering() else backdrop?.suspendRendering()
            curtain?.setAmbientActive(false)
        }
    }

    private fun updateForeground() {
        // 宿主和窗口只能在主线程读取；共享桥接接口在任意线程读取此快照。
        foregroundSnapshot =
            resumed && !ending && !exiting && host.isCurrent() && activity.hasWindowFocus()
        if (!foreground) {
            canvasSnapshot = null
            if (routeActive) deactivateRoute()
            return
        }
        if (!ownsSession) {
            PracticePlaybackGate.enter(this, { foreground }, ::keyboardGeometry)
            keyboard?.let { PracticePlaybackGate.bindSession(this, it.session) }
        }
        if (!routeActive) {
            routeActive = true
            PlaybackConnection.instance?.pause()
        }
        backdrop?.resumeRendering()
        if (openingFinished && keyboardReady) keyboard?.open(0)
        keyboard?.wallpaperSoundEnabled = WallpaperProjectStore.soundEnabled(pageContext)
        backdrop?.setSoundEnabled(
            openingFinished && WallpaperProjectStore.soundEnabled(pageContext)
        )
        curtain?.setAmbientActive(true)
        updateCanvasSnapshot()
        restoreAudio()
        publishReady()
    }

    private fun deactivateRoute() {
        foregroundSnapshot = false
        canvasSnapshot = null
        val token = PracticePlaybackGate.token(this)
        routeActive = false
        if (ownsSession) PracticePlaybackGate.setReady(this, false)
        PlaybackConnection.instance?.pausePractice(token)
        keyboard?.silence()
        sampler?.close()
        sampler = null
        backdrop?.setSoundEnabled(false)
        if (activated) backdrop?.suspendRendering()
        curtain?.setAmbientActive(false)
    }

    private fun updateCanvasSnapshot() {
        val keys = keyboard
        if (
            !foreground ||
                keys == null ||
                !keys.isAttachedToWindow ||
                !keys.isShown ||
                keys.width <= 0 ||
                keys.height <= 0
        ) {
            canvasSnapshot = null
            return
        }
        val origin = IntArray(2)
        keys.getLocationOnScreen(origin)
        canvasSnapshot = Canvas(keys.width, keys.height, origin[0], origin[1])
    }

    private fun keyboardGeometry(): Bundle? {
        if (!foreground) return null
        val canvas = canvasSnapshot ?: return null
        return Bundle().apply {
            putInt("width", canvas.width)
            putInt("height", canvas.height)
            putInt("screenX", canvas.x)
            putInt("screenY", canvas.y)
        }
    }

    private fun removeFocusListener() {
        focusTree?.let { if (it.isAlive) it.removeOnWindowFocusChangeListener(focusChanged) }
        focusTree = null
    }

    override fun back(): Boolean {
        exitStage()
        return true
    }

    private fun exitStage() {
        if (exiting || ending || !host.isCurrent()) return
        exiting = true
        foregroundSnapshot = false
        canvasSnapshot = null
        backdrop?.setSoundEnabled(false)
        PracticePlaybackGate.setReady(this, false)
        PlaybackConnection.instance?.pausePractice(PracticePlaybackGate.token(this))
        keyboard?.close()
        sampler?.close()
        sampler = null
        val veil = curtain ?: return finishPage()
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
        veil.gatherExit(
            onProgress = { p -> keyboard?.alpha = 1 - StageLightRenderer.smooth(0f, .18f, p) },
            onFinished = {
                lifecycleScope.launch {
                    var stable = 0
                    var previous = 0 to 0
                    repeat(50) {
                        if (ending) return@launch
                        val size = veil.width to veil.height
                        stable = if (size == previous && size.second > size.first) stable + 1 else 0
                        previous = size
                        if (stable >= 3) {
                            finishPage()
                            return@launch
                        }
                        delay(50)
                    }
                    finishPage()
                }
            },
        )
    }

    private fun publishReady() {
        if (!keyboardReady || !openingFinished || exiting || ending) return
        updateCanvasSnapshot()
        if (!reportedReady) {
            reportedReady = true
            pageReady?.ready()
        }
        if (!foreground || !ownsSession || PracticePlaybackGate.ready) return
        PracticePlaybackGate.setReady(this, true)
        backdrop?.setSoundEnabled(WallpaperProjectStore.soundEnabled(pageContext))
        AppLog.log("演练场已就绪：尺寸=${keyboard?.width}x${keyboard?.height}")
    }

    private fun stopSession() {
        foregroundSnapshot = false
        canvasSnapshot = null
        if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
        curtain?.close()
        if (ownsSession) {
            PracticePlaybackGate.setReady(this, false)
            PlaybackConnection.instance?.pausePractice(PracticePlaybackGate.token(this))
        }
        backdrop?.close()
        keyboard?.close()
        sampler?.close()
        sampler = null
    }

    override fun finishing() {
        if (closing) return
        closing = true
        stopSession()
        if (ownsSession) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            PracticePlaybackGate.leave(this)
        }
    }

    private fun finishPage() {
        if (!host.isCurrent()) return
        host.closePage()
        activity.overridePendingTransition(0, 0)
    }

    private fun restoreAudio() {
        val samples = loadedSamples ?: return
        if (sampler != null || !foreground || !ownsSession) return
        sampler =
            HarmonicaSampler(
                samples,
                app.luoxianlv.business.ui.OfficialRuntimeConfig.read(pageContext).harmonicaGain,
            ) {
                if (ownsSession) {
                    keyboard?.silence()
                    PlaybackConnection.instance?.pausePractice(PracticePlaybackGate.token(this))
                }
            }
    }

    override fun save() =
        Bundle().apply {
            putBoolean("playable", openingFinished && keyboardReady && !exiting)
            putString("mode", keyboard?.session?.mode?.name ?: PracticeSession.Mode.NATURAL.name)
            putBoolean("half", keyboard?.session?.half ?: false)
        }

    override fun canReplace(): Boolean {
        val service = PlaybackConnection.instance
        return openingFinished &&
            keyboardReady &&
            !exiting &&
            !ending &&
            keyboard?.session?.active == null &&
            !host.hasPendingResults() &&
            service?.let { it.playing || it.preparing || it.waitingToPlay } != true
    }

    override fun dispose() {
        removeFocusListener()
        stopSession()
        backdrop = null
        keyboard = null
        loadedSamples = null
        pageReady = null
        if (ownsSession) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            PracticePlaybackGate.leave(this)
        }
    }
}
