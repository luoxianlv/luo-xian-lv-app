package app.luoxianlv.ui.practice

import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** 导航宿主恢复后延迟预热；暂停时挂起，销毁时清理，不随 Tab 切换重建。 */
@Composable
fun StagePrewarmEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.activity()
        val prepare = Runnable {
            if (
                activity
                    ?.lifecycle
                    ?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
            ) {
                app.luoxianlv.wallpaper.render.PreparedWallpaper.prepare(activity)
            }
        }
        val observer =
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    activity?.window?.decorView?.postDelayed(prepare, 500)
                }

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                    activity?.window?.decorView?.removeCallbacks(prepare)
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.pause()
                }

                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
                }
            }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.window?.decorView?.removeCallbacks(prepare)
            activity?.lifecycle?.removeObserver(observer)
            app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
        }
    }
}

/** 带开幕动画进入演练场，幕布从 [origin]（入口按钮的窗口坐标）展开；拿不到 Activity 时退化为直接跳转。 */
fun openPracticeStage(
    context: Context,
    origin: RectF,
    dark: Boolean,
    onPractice: (Boolean) -> Unit,
) {
    val activity = context.activity()
    if (activity == null) onPractice(dark)
    else StageEntry.open(activity, origin, dark) { onPractice(dark) }
}

private tailrec fun Context.activity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
