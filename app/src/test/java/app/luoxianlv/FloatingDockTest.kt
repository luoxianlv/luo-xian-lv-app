package app.luoxianlv

import app.luoxianlv.playback.FloatingDock
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingDockTest {
    @Test
    fun `只在边缘阈值内停靠`() {
        assertEquals(FloatingDock.NONE, FloatingDock.afterDrag(120, 720, 80, 20))
        assertEquals(FloatingDock.LEFT, FloatingDock.afterDrag(20, 720, 80, 20))
        assertEquals(FloatingDock.RIGHT, FloatingDock.afterDrag(620, 720, 80, 20))
        assertEquals(FloatingDock.NONE, FloatingDock.afterDrag(619, 720, 80, 20))
    }

    @Test
    fun `贴边半隐藏并随窗口宽度恢复方向`() {
        assertEquals(-40, FloatingDock.LEFT.position(0, 720, 80))
        assertEquals(680, FloatingDock.RIGHT.position(0, 720, 80))
        assertEquals(1560, FloatingDock.RIGHT.position(680, 1600, 80))
        assertEquals(0, FloatingDock.NONE.position(-40, 720, 80))
        assertEquals(640, FloatingDock.NONE.position(1560, 720, 80))
        assertEquals(0, FloatingDock.NONE.position(30, 40, 80))
    }
}
