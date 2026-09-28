package app.luoxianlv.service

/** 只在手动拖动结束时判断贴边；窗口尺寸变化仅恢复已有停靠方向。 */
internal enum class FloatingDock {
    NONE,
    LEFT,
    RIGHT;

    fun position(x: Int, screenWidth: Int, bubbleWidth: Int): Int =
        when (this) {
            LEFT -> -bubbleWidth / 2
            RIGHT -> screenWidth - bubbleWidth / 2
            NONE -> x.coerceIn(0, (screenWidth - bubbleWidth).coerceAtLeast(0))
        }

    companion object {
        fun afterDrag(x: Int, screenWidth: Int, bubbleWidth: Int, threshold: Int): FloatingDock =
            when {
                x <= threshold -> LEFT
                x + bubbleWidth >= screenWidth - threshold -> RIGHT
                else -> NONE
            }
    }
}
