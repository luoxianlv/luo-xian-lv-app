package app.luoxianlv.playback

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import app.luoxianlv.hot.contract.SharedInput

/** 悬浮球、选歌窗和点击标记共用的系统悬浮窗参数，需独立窗口授权。 */
internal object FloatingWindowLayout {
    /** 宿主异步核对系统授权；窗口绘制只读快照，不依赖无障碍或触控输入身份。 */
    fun allowed() = runCatching {
        SharedInput.current()?.state()?.getBoolean("overlayGranted", false) == true
    }
        .getOrDefault(false)

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
