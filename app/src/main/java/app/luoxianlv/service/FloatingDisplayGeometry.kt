package app.luoxianlv.service

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
