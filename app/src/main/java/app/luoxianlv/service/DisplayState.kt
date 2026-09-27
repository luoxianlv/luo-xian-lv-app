package app.luoxianlv.service

/**
 * System display observation used to reject stale requests, not to scale screenshot coordinates.
 */
data class DisplayState(val width: Int, val height: Int, val rotation: Int) {
    override fun toString(): String = "($width, $height, $rotation)"
}
