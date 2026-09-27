package app.luoxianlv

import app.luoxianlv.data.AppearanceSettings
import app.luoxianlv.ui.components.decodeSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置现在只剩飘雪开关：默认值、可关闭。 末尾一并覆盖 hero 插画的解码降采样计算。
 *
 * 全局背景（背景色 / 本地图片 / 遮罩）与控件透明度轴已移除，相关用例随之删除。
 */
class AppearanceSettingsTest {
    @Test
    fun `飘雪默认开启`() {
        assertTrue(AppearanceSettings().snowEnabled)
        assertFalse(AppearanceSettings(snowEnabled = false).snowEnabled)
    }

    // ---- hero 插画的解码降采样 ----

    @Test
    fun `尺寸在上限内时不降采样`() {
        assertEquals(1, decodeSampleSize(1080, 1440, maxEdge = 1440))
        assertEquals(1, decodeSampleSize(1440, 1080, maxEdge = 1440))
        assertEquals(1, decodeSampleSize(100, 100, maxEdge = 1440))
    }

    @Test
    fun `超出上限的图按最长边降采样`() {
        // 1920 是最长边，超过 1440，需要 2 倍降采样
        assertEquals(2, decodeSampleSize(1080, 1920, maxEdge = 1440))
        // 4000x3000：4 倍后为 1000x750，刚好落在上限内
        assertEquals(4, decodeSampleSize(4000, 3000, maxEdge = 1440))
        assertEquals(8, decodeSampleSize(8000, 6000, maxEdge = 1440))
    }

    @Test
    fun `降采样后最长边不超过上限，且是满足条件的最小倍数`() {
        val cases = listOf(200 to 100, 1440 to 1080, 1441 to 100, 6000 to 8000, 12000 to 12000)
        cases.forEach { (width, height) ->
            val sample = decodeSampleSize(width, height, maxEdge = 1440)
            val longest = maxOf(width / sample, height / sample)
            assertTrue("$width x $height 降采样 $sample 后仍为 $longest", longest <= 1440)
            if (sample > 1) {
                val coarser = maxOf(width / (sample / 2), height / (sample / 2))
                assertTrue("$width x $height 可以用更小的 $sample", coarser > 1440)
            }
        }
    }

    @Test
    fun `非法尺寸不会导致死循环`() {
        assertEquals(1, decodeSampleSize(0, 0))
        assertEquals(1, decodeSampleSize(-10, 20))
    }
}
