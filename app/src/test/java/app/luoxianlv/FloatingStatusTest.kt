package app.luoxianlv

import app.luoxianlv.ui.library.LibraryUiState
import app.luoxianlv.ui.library.ServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗运行状态与状态胶囊文案的回归测试。
 *
 * 「我的」页点「启动」后文字不变成「运行中」、也没法用同一个按钮再点回去关掉， 根因是把持久化偏好（用户意图）当成了运行状态：服务被回收后偏好仍是 true，
 * 界面就谎报运行中/已关闭，点击分支也跟着走错。
 *
 * 这里锁住两件事：运行状态只认「服务在线 **且** 窗口可见」；四种情况各有对应文案。
 */
class FloatingStatusTest {
    private fun state(
        connected: Boolean = true,
        accessibilityEnabled: Boolean = true,
        floatingVisible: Boolean = false,
        error: String? = null,
    ) =
        LibraryUiState(
            service =
                ServiceStatus(
                    connected = connected,
                    accessibilityEnabled = accessibilityEnabled,
                    floatingVisible = floatingVisible,
                    error = error,
                )
        )

    @Test
    fun `只有服务在线且窗口可见才算运行中`() {
        assertTrue(state(floatingVisible = true).floatingRunning)
        assertFalse(state(floatingVisible = false).floatingRunning)
        // 服务掉线时窗口不可能存在，可见标记残留也不算运行中
        assertFalse(state(connected = false, floatingVisible = true).floatingRunning)
    }

    @Test
    fun `持久化偏好为开不算运行中`() {
        val stale = state().copy(floatingEnabled = true)

        assertFalse(stale.floatingRunning)
        assertEquals("悬浮窗已关闭 · 点击开启", stale.statusText)
    }

    @Test
    fun `无障碍没开时提示去开启`() {
        assertEquals(
            "无障碍未开启 · 点击去开启",
            state(connected = false, accessibilityEnabled = false).statusText,
        )
    }

    @Test
    fun `无障碍开着但服务没绑上时提示再试一次`() {
        assertEquals("无障碍服务未就绪 · 点击重试", state(connected = false).statusText)
    }

    @Test
    fun `窗口在跑时文案变成可以关闭`() {
        assertEquals("悬浮窗运行中 · 点击关闭", state(floatingVisible = true).statusText)
    }

    @Test
    fun `窗口没跑时展示上一次的播放错误`() {
        assertEquals("手势被系统取消", state(error = "手势被系统取消").statusText)
    }

    @Test
    fun `窗口在跑时错误文案不挡住关闭入口`() {
        assertEquals(
            "悬浮窗运行中 · 点击关闭",
            state(floatingVisible = true, error = "手势被系统取消").statusText,
        )
    }

    @Test
    fun `无障碍没开优先于服务未就绪`() {
        assertEquals(
            "无障碍未开启 · 点击去开启",
            state(connected = false, accessibilityEnabled = false, floatingVisible = true)
                .statusText,
        )
    }
}
