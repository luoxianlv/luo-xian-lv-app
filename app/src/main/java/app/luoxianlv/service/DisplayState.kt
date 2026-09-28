package app.luoxianlv.service

/** 系统显示状态只用于拒绝过期请求，不参与截图坐标缩放。 */
data class DisplayState(val width: Int, val height: Int, val rotation: Int) {
    override fun toString(): String = "($width, $height, $rotation)"
}
