package app.luoxianlv

import app.luoxianlv.playback.FloatingDisplayGeometry
import app.luoxianlv.playback.FloatingDock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDisplayGeometryTest {
    private val portrait = FloatingDisplayGeometry(720, 1600, 0, 280, 1f)
    private val landscape = portrait.copy(width = 1600, height = 720, rotation = 1)

    @Test
    fun `转屏和窗口缩放复用内容 密度或字体缩放变化重新构建`() {
        assertFalse(landscape.needsNewContent(portrait))
        assertFalse(portrait.copy(width = 500).needsNewContent(portrait))
        assertFalse(portrait.copy(rotation = 2).needsNewContent(portrait))
        assertTrue(portrait.copy(densityDpi = 320).needsNewContent(portrait))
        assertTrue(portrait.copy(fontScale = 1.3f).needsNewContent(portrait))
        assertTrue(portrait.needsNewContent(null))
    }

    @Test
    fun `横屏转竖屏把面板限制在新窗口内`() {
        val position = portrait.position(1400, 1500, 413, 180, 42, true, FloatingDock.NONE)
        assertEquals(307, position.x)
        assertEquals(1378, position.y)
    }

    @Test
    fun `旋转保持气泡左右半隐藏方向`() {
        val right = landscape.position(681, 300, 77, 77, 42, false, FloatingDock.RIGHT)
        val left = landscape.position(-38, 300, 77, 77, 42, false, FloatingDock.LEFT)
        assertEquals(1562, right.x)
        assertEquals(-38, left.x)
        assertEquals(300, right.y)
    }

    @Test
    fun `右侧气泡展开后整个面板可见`() {
        val position = portrait.position(681, 300, 413, 180, 42, true, FloatingDock.RIGHT)
        assertEquals(307, position.x)
        assertEquals(300, position.y)
    }

    @Test
    fun `缩窗时面板宽度有效且不越过左右边缘`() {
        val small = portrait.copy(width = 250, height = 300)
        val width = small.windowWidth(true, 77, 413, 28)
        assertEquals(222, width)
        val position = small.position(-100, 800, width, 180, 42, true, FloatingDock.NONE)
        assertEquals(0, position.x)
        assertEquals(78, position.y)
        assertEquals(1, small.copy(width = 10).windowWidth(true, 77, 413, 28))
    }
}
