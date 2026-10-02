package app.luoxianlv.ui.floating

import android.content.Context
import android.os.Handler
import android.view.View
import android.view.WindowManager
import app.luoxianlv.ui.floating.PlayerUi.dp

/** 独立管理 120ms 点击标记；复用调用方主线程，不参与播放器窗口交互。 */
internal class FloatingTouchMarker(
    private val context: Context,
    private val windows: WindowManager,
    private val handler: Handler,
    private val palette: () -> PlayerUiPalette,
) {
    private var view: View? = null
    private val remove = Runnable {
        view?.let { windows.removeView(it) }
        view = null
    }

    fun show(x: Float, y: Float) {
        handler.removeCallbacks(remove)
        if (view == null) {
            view =
                View(context).apply {
                    background = PlayerUi.background(context, 0x55007aff, 20, true, palette().line)
                }
            val params =
                FloatingWindowLayout.create(context.dp(20), context.dp(20)).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            try {
                windows.addView(view, params)
            } catch (_: WindowManager.BadTokenException) {
                view = null
                return
            } catch (_: IllegalStateException) {
                view = null
                return
            }
        }
        val params = view!!.layoutParams as WindowManager.LayoutParams
        params.x = x.toInt() - context.dp(10)
        params.y = y.toInt() - context.dp(10)
        windows.updateViewLayout(view, params)
        handler.postDelayed(remove, 120)
    }

    fun close() {
        handler.removeCallbacks(remove)
        remove.run()
    }
}
