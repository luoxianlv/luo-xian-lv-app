package app.luoxianlv.playback

import app.luoxianlv.library.PlayMode
import app.luoxianlv.recognition.KeyLayout

internal object PlaybackCoordinates {
    data class Frame(val width: Int, val height: Int) {
        init {
            require(width > 0 && height > 0)
        }

        fun point(x: Float, y: Float): Pair<Float, Float> {
            require(validPoint(x, y))
            // 识别坐标以完整图像尺寸归一化，转换时使用同一尺寸作逆运算。
            return (x * width).coerceAtMost((width - 1).toFloat()) to
                (y * height).coerceAtMost((height - 1).toFloat())
        }
    }

    fun validPoint(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && x > 0f && x < 1f && y > 0f && y < 1f

    /** 两种输入方式共用截图生成的像素坐标；超出真实显示时拒绝，不重新缩放。 */
    fun fitsDisplay(x: Float, y: Float, width: Int, height: Int): Boolean =
        width > 0 &&
            height > 0 &&
            x.isFinite() &&
            y.isFinite() &&
            x >= 0f &&
            y >= 0f &&
            x <= width - 1 &&
            y <= height - 1

    fun validLayout(layout: KeyLayout): Boolean =
        layout.noteX.size == 8 &&
            layout.noteX.all { validPoint(it, layout.noteY) } &&
            layout.noteX.asList().zipWithNext().all { (a, b) -> b > a } &&
            PlayMode.values().all { mode ->
                val point = layout.modes[mode]
                point != null && point.size == 2 && validPoint(point[0], point[1])
            }

    /** 小窗琴键先从画布坐标移到真实屏幕，再交给两种输入方式。 */
    fun windowLayout(
        layout: KeyLayout,
        canvas: Frame,
        screen: Frame,
        left: Int,
        top: Int,
    ): KeyLayout? {
        if (!validLayout(layout)) return null
        fun x(value: Float) = (left + value * canvas.width) / screen.width
        fun y(value: Float) = (top + value * canvas.height) / screen.height
        return KeyLayout(
                layout.noteX.map(::x).toFloatArray(),
                y(layout.noteY),
                layout.modes.mapValues { (_, point) -> floatArrayOf(x(point[0]), y(point[1])) },
            )
            .takeIf(::validLayout)
    }
}
