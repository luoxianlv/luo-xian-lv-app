package app.luoxianlv.ui.floating

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/** 悬浮球、选歌窗和点击标记共用的无障碍窗口基础参数。 */
internal object FloatingWindowLayout {
    fun create(width: Int, height: Int) =
        WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            .apply { gravity = Gravity.TOP or Gravity.LEFT }
}
