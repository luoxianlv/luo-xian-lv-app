package app.luoxianlv.ui.practice

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.luoxianlv.business.ui.findActivity

/** 导航宿主恢复后延迟预热；暂停时挂起，销毁时清理，不随 Tab 切换重建。 */
@Composable
fun StagePrewarmEffect() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val lease = Any()
        val activity = context.findActivity()
        val prepare = Runnable {
            if (
                activity != null &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED
                    )
            ) {
                app.luoxianlv.wallpaper.render.PreparedWallpaper.prepare(activity, context, lease)
            }
        }
        val observer =
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.claim(lease)
                    activity?.window?.decorView?.postDelayed(prepare, 500)
                }

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                    activity?.window?.decorView?.removeCallbacks(prepare)
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.pause(lease)
                }

                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.clear(lease)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            activity?.window?.decorView?.removeCallbacks(prepare)
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.luoxianlv.wallpaper.render.PreparedWallpaper.clear(lease)
        }
    }
}

/** 带开幕动画进入演练场，幕布从 [origin]（入口按钮的窗口坐标）展开；拿不到 Activity 时退化为直接跳转。 */
fun openPracticeStage(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    origin: RectF,
    dark: Boolean,
    onPractice: (Boolean) -> Unit,
) {
    val activity = context.findActivity()
    if (activity == null) onPractice(dark)
    else StageEntry.open(activity, lifecycleOwner, origin, dark) { onPractice(dark) }
}
