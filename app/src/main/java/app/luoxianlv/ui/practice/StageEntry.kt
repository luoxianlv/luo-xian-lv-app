package app.luoxianlv.ui.practice

import android.graphics.RectF
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 仅管理首页光幕，不在全局保留 Activity 或 WebView。 */
object StageEntry {
    const val DARK = "stageCurtainDark"
    private const val TAG = "practice-entry-curtain"

    fun open(activity: ComponentActivity, bounds: RectF, dark: Boolean, launch: () -> Unit) {
        val decor = activity.window.decorView as ViewGroup
        if (decor.findViewWithTag<android.view.View>(TAG) != null) return
        val previousOrientation = activity.requestedOrientation
        val portrait =
            activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        // 部分系统使用 LOCKED 会锁住当前横屏，因此需明确请求方向。
        activity.requestedOrientation =
            if (portrait) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val curtain =
            StageCurtain(activity, dark).apply {
                tag = TAG
                origin = bounds
                expansion = 1f
                isClickable = true
                importantForAccessibility =
                    android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        decor.addView(curtain, ViewGroup.LayoutParams(-1, -1))
        var launched = false
        var paused = false
        var returnJob: Job? = null
        lateinit var observer: DefaultLifecycleObserver
        fun remove() {
            returnJob?.cancel()
            curtain.close()
            decor.removeView(curtain)
            activity.lifecycle.removeObserver(observer)
            if (!activity.isDestroyed) activity.requestedOrientation = previousOrientation
        }
        observer =
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    curtain.setAmbientActive(false)
                    returnJob?.cancel()
                    paused = true
                    if (!launched) remove()
                }

                override fun onResume(owner: LifecycleOwner) {
                    curtain.setAmbientActive(true)
                    if (paused && launched) {
                        curtain.prepareHomeReturn()
                        returnJob =
                            activity.lifecycleScope.launch {
                                var stable = 0
                                var previous = 0 to 0
                                for (attempt in 0 until 80) {
                                    val size = decor.width to decor.height
                                    val correctShape =
                                        if (portrait) size.second > size.first
                                        else size.first > size.second
                                    stable =
                                        if (
                                            activity.hasWindowFocus() &&
                                                correctShape &&
                                                size == previous
                                        )
                                            stable + 1
                                        else 0
                                    previous = size
                                    if (stable >= 6) break
                                    delay(50)
                                }
                                if (stable >= 6) curtain.returnToEntry { remove() } else remove()
                            }
                    }
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    remove()
                }
            }
        activity.lifecycle.addObserver(observer)
        // 点击事件内立即启动，让转屏先于竖屏动画。
        launched = true
        try {
            launch()
            activity.overridePendingTransition(0, 0)
        } catch (error: Exception) {
            remove()
            throw error
        }
    }
}
