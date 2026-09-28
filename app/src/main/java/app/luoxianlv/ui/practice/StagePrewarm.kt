package app.luoxianlv.ui.practice

import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 演练场入口的公共后勤。入口在首页的状态胶囊位（见 HomeOverview 的 HomeStagePill），
 * 这里放两件与入口形态无关的事：首页存活期间预热壁纸渲染器，以及带开幕动画的跳转。
 */

/**
 * 壁纸预热：resume 后延迟 500ms 准备（避开首页自身的进场动画）， pause 挂起渲染省电，销毁时彻底清理。
 * 挂在首页根部组合里，只要「我的」页还在就持续有效。
 */
@Composable
fun StagePrewarmEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.activity()
        val observer =
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    activity
                        ?.window
                        ?.decorView
                        ?.postDelayed(
                            {
                                if (
                                    owner.lifecycle.currentState.isAtLeast(
                                        androidx.lifecycle.Lifecycle.State.RESUMED
                                    )
                                )
                                    app.luoxianlv.wallpaper.render.PreparedWallpaper.prepare(
                                        activity
                                    )
                            },
                            500,
                        )
                }

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.pause()
                }

                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
                }
            }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
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
