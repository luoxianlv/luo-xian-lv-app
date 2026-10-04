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

    fun validLayout(layout: KeyLayout): Boolean =
        layout.noteX.size == 8 &&
            layout.noteX.all { validPoint(it, layout.noteY) } &&
            layout.noteX.asList().zipWithNext().all { (a, b) -> b > a } &&
            PlayMode.values().all { mode ->
                val point = layout.modes[mode]
                point != null && point.size == 2 && validPoint(point[0], point[1])
            }
}
