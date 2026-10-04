package app.luoxianlv.playback

/** 仅保留会影响悬浮窗位置或内容尺寸的显示信息，不含亮度和刷新率。 */
internal data class FloatingDisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val densityDpi: Int,
    val fontScale: Float,
) {
    fun needsNewContent(previous: FloatingDisplayGeometry?): Boolean =
        previous == null || densityDpi != previous.densityDpi || fontScale != previous.fontScale

    fun windowWidth(expanded: Boolean, bubbleWidth: Int, panelWidth: Int, margin: Int): Int =
        if (expanded) minOf(panelWidth, (width - margin).coerceAtLeast(1)) else bubbleWidth

    fun position(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        margin: Int,
        expanded: Boolean,
        dock: FloatingDock,
    ): FloatingWindowPosition =
        FloatingWindowPosition(
            (if (expanded) FloatingDock.NONE else dock).position(
                if (dock == FloatingDock.RIGHT) width - windowWidth else x,
                width,
                windowWidth,
            ),
            y.coerceIn(margin, (height - windowHeight - margin).coerceAtLeast(margin)),
        )
}

internal data class FloatingWindowPosition(val x: Int, val y: Int)

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
