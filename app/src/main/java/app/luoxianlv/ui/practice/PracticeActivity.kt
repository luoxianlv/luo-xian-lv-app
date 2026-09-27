package app.luoxianlv.ui.practice

import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.luoxianlv.audio.HarmonicaSampler
import app.luoxianlv.debug.PlaybackDebugLog
import app.luoxianlv.service.MusicAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A separate window owns landscape/system bars; leaving restores the untouched main window. */
class PracticeActivity : AppCompatActivity() {
    private var sampler: HarmonicaSampler? = null
    private var keyboard: PracticeKeyboard? = null
    private var backdrop: PracticeBackdrop? = null
    private var resumed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(6,8,7)))
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6,8,7)) }
        // Begin the selected scene before audio loading and opening choreography.
        backdrop = PracticeBackdrop(this).also { root.addView(it, FrameLayout.LayoutParams(-1,-1)) }
        val progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(80,80,Gravity.CENTER))
        val exit = TextView(this).apply {
            text = "返回"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; textSize = 16f
            setOnClickListener { finish() }
        }
        root.addView(exit, FrameLayout.LayoutParams(160,120,Gravity.TOP or Gravity.START))
        setContentView(root)
        // Background fills the cutout area. Only controls, never the root, get safe insets.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            keyboard?.safeRight = cutout.right
            keyboard?.safeLeft = cutout.left
            keyboard?.safeTop = cutout.top
            (exit.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = cutout.left; topMargin = cutout.top; exit.layoutParams = this }
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
        lifecycleScope.launch {
            try {
                val samples = withContext(Dispatchers.IO) { HarmonicaSampler.load(applicationContext) }
                if (isFinishing) return@launch
                // Wait for a usable stable landscape area, bounded for tablets/multi-window overrides.
                var lastSize = 0 to 0
                var stable = 0
                repeat(30) {
                    val size = root.width to root.height
                    stable = if (size == lastSize && size.first > size.second && size.second >= 200 * resources.displayMetrics.density) stable + 1 else 0
                    lastSize = size
                    if (stable < 2) delay(50)
                }
                if (stable < 2) {
                    Toast.makeText(this@PracticeActivity, "请使用横屏或放大窗口后进入演奏", Toast.LENGTH_LONG).show()
                    finish(); return@launch
                }
                if (isFinishing || !resumed) return@launch
                val audio = HarmonicaSampler(this@PracticeActivity, samples) {
                    keyboard?.silence()
                    MusicAccessibilityService.instance?.pause()
                    Toast.makeText(this@PracticeActivity, "音频已中断，请重新进入演奏", Toast.LENGTH_SHORT).show()
                    finish()
                }
                sampler = audio
                val keys = PracticeKeyboard(this@PracticeActivity).apply {
                    onNoteOn = audio::noteOn
                    onNoteOff = audio::noteOff
                    onExit = { finish() }
                    onWallpaper = {
                        startActivity(android.content.Intent(this@PracticeActivity, WallpaperPickerActivity::class.java).putExtra("returnToPractice", true))
                        finish()
                    }
                    onReady = {
                        PracticePlaybackGate.setReady(true)
                        PlaybackDebugLog.log("practice ready ${width}x$height")
                    }
                }
                keyboard = keys
                root.removeView(progress)
                root.removeView(exit)
                root.addView(keys, FrameLayout.LayoutParams(-1,-1))
                ViewCompat.requestApplyInsets(root)
                keys.post { if (!isFinishing && resumed) keys.open() }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                PlaybackDebugLog.log("practice load failed ${error.message}")
                Toast.makeText(this@PracticeActivity, "口琴音源加载失败，请重新进入重试", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true }
    private fun stopSession() {
        PracticePlaybackGate.setReady(false)
        MusicAccessibilityService.instance?.pause()
        backdrop?.close()
        keyboard?.close()
        sampler?.close(); sampler = null
    }
    override fun finish() {
        // Stop immediately on exit, including while the window's exit transition is still running.
        stopSession()
        super.finish()
    }
    override fun onPause() {
        resumed = false
        stopSession()
        super.onPause()
        // Backgrounding ends the session rather than retaining hidden gestures/audio.
        if (!isChangingConfigurations) finish()
    }
    override fun onDestroy() {
        backdrop?.close(); backdrop = null
        keyboard?.close(); sampler?.close(); sampler = null
        PracticePlaybackGate.leave()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
