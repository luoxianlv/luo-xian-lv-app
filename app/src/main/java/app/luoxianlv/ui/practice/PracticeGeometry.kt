package app.luoxianlv.ui.practice

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
}
