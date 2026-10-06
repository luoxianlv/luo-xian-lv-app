package app.luoxianlv.playback

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/** 悬浮球、选歌窗和点击标记共用的系统悬浮窗参数，需独立窗口授权。 */
internal object FloatingWindowLayout {
    fun create(width: Int, height: Int) =
        WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            .apply { gravity = Gravity.TOP or Gravity.LEFT }
}
