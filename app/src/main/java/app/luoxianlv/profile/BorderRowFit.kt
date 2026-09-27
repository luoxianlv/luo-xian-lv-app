package app.luoxianlv.profile

import kotlin.math.abs

internal data class BorderRow(
    val xs: FloatArray,
    val y: Float,
    val radius: Float,
    val observed: BooleanArray,
) {
    val count
        get() = observed.count { it }
}

/**
 * Robust affine row fit: the image determines offset AND scale. Reject isolated scene circles by
 * their row height, radius and grid residual.
 */
internal fun fitBorderRow(
    circles: List<ButtonBorderDetector.Circle?>,
    units: FloatArray,
    spacing: Float,
): BorderRow? {
    var best: BorderRow? = null
    var bestError = Float.MAX_VALUE
    for (a in circles.indices) for (b in a + 1 until circles.size) {
        val first = circles[a] ?: continue
        val second = circles[b] ?: continue
        val scale = (second.x - first.x) / (units[b] - units[a])
        if (scale !in .80f * spacing..1.20f * spacing || abs(first.y - second.y) > .12f * spacing)
            continue
        val origin = first.x - units[a] * scale
        val cy = (first.y + second.y) / 2f
        val radius = (first.radius + second.radius) / 2f
        val kept =
            circles.indices.filter { i ->
                circles[i]?.let {
                    abs(it.x - origin - units[i] * scale) <= .10f * spacing &&
                        abs(it.y - cy) <= .10f * spacing &&
                        abs(it.radius - radius) <= .18f * radius
                } == true
            }
        if (kept.size < 3) continue
        val meanUnit = kept.map { units[it] }.average().toFloat()
        val meanX = kept.map { circles[it]!!.x }.average().toFloat()
        val fittedScale =
            kept
                .sumOf { ((units[it] - meanUnit) * (circles[it]!!.x - meanX)).toDouble() }
                .toFloat() /
                kept
                    .sumOf { ((units[it] - meanUnit) * (units[it] - meanUnit)).toDouble() }
                    .toFloat()
        val xs = FloatArray(units.size) { meanX + (units[it] - meanUnit) * fittedScale }
        val error = kept.sumOf { abs(circles[it]!!.x - xs[it]).toDouble() }.toFloat()
        if (kept.size > (best?.count ?: 0) || kept.size == best?.count && error < bestError) {
            best =
                BorderRow(
                    xs,
                    kept.map { circles[it]!!.y }.average().toFloat(),
                    kept.map { circles[it]!!.radius }.average().toFloat(),
                    BooleanArray(units.size) { it in kept },
                )
            bestError = error
        }
    }
    return best
}
