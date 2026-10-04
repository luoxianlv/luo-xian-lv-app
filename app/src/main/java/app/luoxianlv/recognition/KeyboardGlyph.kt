package app.luoxianlv.recognition

import kotlin.math.max
import kotlin.math.min

internal class Glyph(
    var x0: Int,
    var x1: Int,
    var y0: Int,
    var y1: Int,
    val pixels: Int,
) {
    val cx
        get() = (x0 + x1) / 2f

    val cy
        get() = (y0 + y1) / 2f

    val w
        get() = x1 - x0

    val h
        get() = y1 - y0

    fun merge(other: Glyph) {
        x0 = min(x0, other.x0)
        x1 = max(x1, other.x1)
        y0 = min(y0, other.y0)
        y1 = max(y1, other.y1)
    }
}

internal class Label(
    val cx: Float,
    val cy: Float,
    val h: Float,
)
