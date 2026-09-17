package app.luoxianlv

import app.luoxianlv.data.AppearanceSettings
import app.luoxianlv.ui.components.decodeSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置现在只有一条透明度轴：默认值、派生关系、非法值回退。
 * 末尾一并覆盖 hero 插画的解码降采样计算。
 *
 * 全局背景（背景色 / 本地图片 / 遮罩）已移除，相关用例随之删除。
 */
class AppearanceSettingsTest {
    @Test
    fun `默认完全不透明`() {
        val settings = AppearanceSettings()
        assertEquals(0f, settings.transparency, 0f)
        assertEquals(1f, settings.controlAlpha, 0f)
    }

    @Test
    fun `透明度为零时控件完全不透`() {
        assertEquals(1f, AppearanceSettings(transparency = 0f).controlAlpha, 0.0001f)
    }

    @Test
    fun `透明度拉满时控件透到下限`() {
        assertEquals(
            AppearanceSettings.MIN_CONTROL_ALPHA,
            AppearanceSettings(transparency = 1f).controlAlpha,
            0.0001f,
        )
    }

    @Test
    fun `控件应随透明度升高而单调变透`() {
        // 用整数步进而不是浮点累加，避免 0.05f 累加 20 次略大于 1.0
        var previous = Float.MAX_VALUE
        (0..20).forEach { step ->
            val alpha = AppearanceSettings(transparency = step / 20f).controlAlpha
            assertTrue("控件应随透明度升高而变透", alpha < previous)
            previous = alpha
        }
    }

    @Test
    fun `派生出的控件透明度始终在合法区间内`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
            val alpha = AppearanceSettings(transparency = t).controlAlpha
            assertTrue(alpha in AppearanceSettings.MIN_CONTROL_ALPHA..1f)
        }
    }

    @Test
    fun `构造越界透明度会立即失败而不是静默通过`() {
        listOf(-0.01f, 1.01f, 5f).forEach { t ->
            val failed = runCatching { AppearanceSettings(transparency = t) }.exceptionOrNull()
            assertTrue("transparency=$t 应当被拒绝", failed is IllegalArgumentException)
        }
    }

    @Test
    fun `NaN 透明度归一化为完全不透明而不是抛异常`() {
        assertEquals(0f, AppearanceSettings.normalizeTransparency(Float.NaN), 0f)
        assertEquals(1f, AppearanceSettings.normalizeTransparency(9f), 0f)
        assertEquals(0f, AppearanceSettings.normalizeTransparency(-3f), 0f)
        assertEquals(0.6f, AppearanceSettings.normalizeTransparency(0.6f), 0.0001f)
    }

    @Test
    fun `归一化后的值始终可用于构造设置对象`() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, -1f, 0f, 0.45f, 1f, 5f).forEach { raw ->
            val settings =
                AppearanceSettings(transparency = AppearanceSettings.normalizeTransparency(raw))
            assertTrue(settings.transparency in 0f..1f)
        }
    }

    @Test
    fun `飘雪默认开启`() {
        assertTrue(AppearanceSettings().snowEnabled)
        assertFalse(AppearanceSettings(snowEnabled = false).snowEnabled)
    }

    @Test
    fun `外观对话框不会覆盖不归它管的字段`() {
        // 透明度对话框只改透明度，必须用 copy —— 否则会把飘雪开关重置成默认值
        val current = AppearanceSettings(transparency = 0.3f, snowEnabled = false)
        val saved = current.copy(transparency = 0.8f)
        assertEquals(0.8f, saved.transparency, 0.0001f)
        assertFalse(saved.snowEnabled)
    }

    @Test
    fun `飘雪开关不影响透明度派生值`() {
        // 它是纯装饰，不应该被谁顺手接进主题的容器透明度里
        val withSnow = AppearanceSettings(transparency = 0.5f, snowEnabled = true)
        val withoutSnow = AppearanceSettings(transparency = 0.5f, snowEnabled = false)
        assertEquals(withoutSnow.controlAlpha, withSnow.controlAlpha, 0f)
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
