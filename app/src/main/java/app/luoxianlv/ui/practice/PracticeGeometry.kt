package app.luoxianlv.ui.practice

import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.KeyLayout
import kotlin.math.min

/** 按完整游戏截图中约占屏宽 70% 的键盘拟合参考裁图。 */
object PracticeGeometry {
    const val WIDTH = 1970f
    const val HEIGHT = 512f

    data class Key(val x: Float, val y: Float, val radius: Float)

    val notes =
        listOf(96, 347, 598, 848, 1098, 1349, 1600, 1852).map { Key(it.toFloat(), 355f, 86f) }
    val modes = listOf(588, 882, 1116, 1350).map { Key(it.toFloat(), 126f, 74f) }

    data class Fit(val scale: Float, val left: Float, val top: Float) {
        fun hit(x: Float, y: Float): Int? {
            val px = (x - left) / scale
            val py = (y - top) / scale
            return (notes + modes)
                .indexOfFirst {
                    (px - it.x) * (px - it.x) + (py - it.y) * (py - it.y) <= it.radius * it.radius
                }
                .takeIf { it >= 0 }
        }
    }

    fun fit(width: Float, height: Float): Fit {
        val scale = min(width * .70f / WIDTH, height * .64f / HEIGHT)
        return Fit(scale, (width - WIDTH * scale) / 2f, height * .53f - HEIGHT * scale / 2f)
    }

    /** 与实际绘制共用比例；传入完整手势显示尺寸，不依赖截图或历史识别结果。 */
    fun keyLayout(width: Int, height: Int): KeyLayout {
        require(width > 0 && height > 0)
        val fit = fit(width.toFloat(), height.toFloat())
        fun x(key: Key) = (fit.left + key.x * fit.scale) / width
        fun y(key: Key) = (fit.top + key.y * fit.scale) / height
        return KeyLayout(
            notes.map(::x).toFloatArray(),
            y(notes.first()),
            listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER)
                .mapIndexed { index, mode ->
                    mode to floatArrayOf(x(modes[index]), y(modes[index]))
                }
                .toMap(),
        )
    }
}
