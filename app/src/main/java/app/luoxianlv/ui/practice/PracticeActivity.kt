package app.luoxianlv.ui.practice

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.luoxianlv.audio.HarmonicaSampler
import app.luoxianlv.debug.AppLog
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.wallpaper.render.PracticeBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 演练场独占横屏系统栏；退出后恢复首页窗口。 */
class PracticeActivity : AppCompatActivity() {
    private var sampler: HarmonicaSampler? = null
    private var keyboard: PracticeKeyboard? = null
    private var backdrop: PracticeBackdrop? = null
    private var gravityLens: StageGravityLens? = null
    private var resumed = false
    private var curtain: StageCurtain? = null
    private var exiting = false
    private var keyboardReady = false
    private var openingFinished = false
    private var loadedSamples: Map<Int, app.luoxianlv.audio.HarmonicaSample>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.attributes =
            window.attributes.apply {
                rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
            }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(6, 8, 7)))
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        if (Build.VERSION.SDK_INT >= 30)
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        PracticePlaybackGate.enter()
        MusicAccessibilityService.instance?.pause()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6, 8, 7)) }
        // 移交已预加载的渲染器；真实壁纸首帧就绪前保持幕层不透明。
        backdrop =
            app.luoxianlv.wallpaper.render.PreparedWallpaper.take(this).also {
                root.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
        if (Build.VERSION.SDK_INT >= 33) {
            gravityLens = StageGravityLens(checkNotNull(backdrop))
            backdrop?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                gravityLens?.update(curtain?.openingProgress ?: .4f)
            }
        }
        val systemDark =
            resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = app.luoxianlv.data.AppearanceStore.settings.value.themeMode.isDark(systemDark)
        val veil = StageCurtain(this, intent.getBooleanExtra(StageEntry.DARK, dark))
        curtain = veil
        root.addView(veil, FrameLayout.LayoutParams(-1, -1))
        veil.isClickable = true
        setContentView(root)
        // 清单在启动时请求横屏；背景铺满刘海区，仅控件应用安全边距。
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            keyboard?.safeRight = cutout.right
            keyboard?.safeLeft = cutout.left
            keyboard?.safeTop = cutout.top
            insets
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    exitStage()
                }
            },
        )
        lifecycleScope.launch {
            try {
                val samples =
                    withContext(Dispatchers.IO) { HarmonicaSampler.load(applicationContext) }
                loadedSamples = samples
                while (!resumed && !isFinishing) delay(50)
                if (isFinishing) return@launch
                // 等待横屏尺寸稳定；为平板和多窗口限制设置等待上限。
                var lastSize = Triple(0, 0, -1)
                var stable = 0
                var attempts = 0
                while (attempts < 80 && !isFinishing && !exiting) {
                    if (!resumed) {
                        delay(50)
                        continue
                    }
                    attempts++
                    val size = Triple(root.width, root.height, root.display?.rotation ?: -1)
                    stable =
                        if (
                            size == lastSize &&
                                hasWindowFocus() &&
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
                if (isFinishing || exiting) return@launch
                if (stable < 8) {
                    Toast.makeText(this@PracticeActivity, "请使用横屏或放大窗口后进入演奏", Toast.LENGTH_LONG)
                        .show()
                    finish()
                    return@launch
                }
                while (!resumed && !isFinishing && !exiting) delay(50)
                if (isFinishing || exiting) return@launch
                // 真实壁纸就绪前保持遮罩不透明。
                while (backdrop?.prepared == false && !isFinishing && !exiting) delay(50)
                while (!resumed && !isFinishing) delay(50)
                if (isFinishing || exiting) return@launch
                veil.backgroundReady = true
                restoreAudio()
                val keys =
                    PracticeKeyboard(this@PracticeActivity).apply {
                        onNoteOn = { midi -> sampler?.noteOn(midi) ?: false }
                        onNoteOff = { sampler?.noteOff() }
                        onExit = { exitStage() }
                        onWallpaper = {
                            startActivity(
                                android.content
                                    .Intent(
                                        this@PracticeActivity,
                                        WallpaperPickerActivity::class.java,
                                    )
                                    .putExtra("returnToPractice", true)
                            )
                            finish()
                        }
                        alpha = 0f
                        onReady = {
                            keyboardReady = true
                            publishReady()
                        }
                    }
                keyboard = keys
                root.addView(keys, root.indexOfChild(veil), FrameLayout.LayoutParams(-1, -1))
                ViewCompat.requestApplyInsets(root)
                lifecycleScope.launch {
                    while (!resumed && !isFinishing && !exiting) delay(50)
                    if (!isFinishing && resumed && !exiting) {
                        veil.reveal(
                            onProgress = { p ->
                                if (Build.VERSION.SDK_INT >= 33) gravityLens?.update(p)
                                keys.alpha = StageLightRenderer.smooth(.88f, 1f, p)
                                val zoom = 1.18f - .18f * StageLightRenderer.smooth(.64f, 1f, p)
                                backdrop?.scaleX = zoom
                                backdrop?.scaleY = zoom
                            },
                            onKeys = { if (!exiting && !isFinishing) keys.open(650) },
                            onFinished = {
                                if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
                                openingFinished = true
                                publishReady()
                            },
                        )
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.e("演练场", "加载失败", error)
                Toast.makeText(this@PracticeActivity, "口琴音源加载失败，请重新进入重试", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        backdrop?.resumeRendering()
        curtain?.setAmbientActive(true)
        restoreAudio()
        publishReady()
    }

    private fun exitStage() {
        if (exiting || isFinishing) return
        exiting = true
        PracticePlaybackGate.setReady(false)
        MusicAccessibilityService.instance?.pause()
        keyboard?.close()
        sampler?.close()
        sampler = null
        val veil = curtain ?: return finish()
        // 立即请求转屏，同时执行粒子回收，不先等待动画。
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
        veil.gatherExit(
            onProgress = { p -> keyboard?.alpha = 1 - StageLightRenderer.smooth(0f, .18f, p) },
            onFinished = {
                lifecycleScope.launch {
                    var stable = 0
                    var previous = 0 to 0
                    for (attempt in 0 until 50) {
                        if (isFinishing) return@launch
                        val size = veil.width to veil.height
                        stable = if (size == previous && size.second > size.first) stable + 1 else 0
                        previous = size
                        if (stable >= 3) break
                        delay(50)
                    }
                    finish()
                }
            },
        )
    }

    private fun publishReady() {
        if (
            !keyboardReady ||
                !openingFinished ||
                exiting ||
                !resumed ||
                isFinishing ||
                PracticePlaybackGate.ready
        )
            return
        PracticePlaybackGate.setReady(true)
        AppLog.log("演练场已就绪：尺寸=${keyboard?.width}x${keyboard?.height}")
    }

    private fun stopSession() {
        if (Build.VERSION.SDK_INT >= 33) gravityLens?.clear()
        curtain?.close()
        PracticePlaybackGate.setReady(false)
        MusicAccessibilityService.instance?.pause()
        backdrop?.close()
        keyboard?.close()
        sampler?.close()
        sampler = null
    }

    override fun finish() {
        // 退出即停止播放，包括窗口退出动画尚未结束时。
        stopSession()
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun restoreAudio() {
        val samples = loadedSamples ?: return
        if (sampler != null || !resumed || exiting || isFinishing) return
        sampler =
            HarmonicaSampler(this, samples) {
                keyboard?.silence()
                MusicAccessibilityService.instance?.pause()
            }
    }

    override fun onPause() {
        resumed = false
        PracticePlaybackGate.setReady(false)
        MusicAccessibilityService.instance?.pause()
        keyboard?.silence()
        sampler?.close()
        sampler = null
        backdrop?.suspendRendering()
        curtain?.setAmbientActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        backdrop?.close()
        backdrop = null
        keyboard?.close()
        sampler?.close()
        sampler = null
        PracticePlaybackGate.leave()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
