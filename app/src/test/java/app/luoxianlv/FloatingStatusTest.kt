package app.luoxianlv

import app.luoxianlv.ui.library.LibraryUiState
import app.luoxianlv.ui.library.ServiceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗运行状态的回归测试。
 *
 * 「我的」页点「启动」后文字不变成「运行中」、也没法用同一个按钮再点回去关掉， 根因是把持久化偏好（用户意图）当成了运行状态：服务被回收后偏好仍是 true，
 * 界面就谎报运行中/已关闭，点击分支也跟着走错。
 *
 * 这里只锁一件事：运行状态只认「服务在线 **且** 窗口可见」，与持久化偏好、播放错误都无关。 原先的状态胶囊四态文案已随首页「演练场」入口改版删除
 * （`LibraryUiState.statusText` 不再存在），无障碍引导改由「启动」按钮的弹窗承担。
 */
class FloatingStatusTest {
    private fun state(
        connected: Boolean = true,
        accessibilityEnabled: Boolean = true,
        floatingVisible: Boolean = false,
        error: String? = null,
    ) = LibraryUiState(
        service =
            ServiceStatus(
                connected = connected,
                accessibilityEnabled = accessibilityEnabled,
                floatingVisible = floatingVisible,
                error = error,
            ),
    )

    @Test
    fun `只有服务在线且窗口可见才算运行中`() {
        assertTrue(state(floatingVisible = true).floatingRunning)
        assertFalse(state(floatingVisible = false).floatingRunning)
        // 服务掉线时窗口不可能存在，可见标记残留也不算运行中
        assertFalse(state(connected = false, floatingVisible = true).floatingRunning)
    }

    @Test
    fun `无障碍开着但服务没绑上也不算运行中`() {
        // accessibilityEnabled 只是系统开关；服务没绑上就没有窗口
        assertFalse(state(connected = false, accessibilityEnabled = true).floatingRunning)
    }

    @Test
    fun `持久化偏好为开不算运行中`() {
        val stale = state().copy(floatingEnabled = true)

        assertTrue(stale.floatingEnabled)
        assertFalse(stale.floatingRunning)
    }

    @Test
    fun `播放错误不改变运行状态`() {
        // 窗口在跑时错误只是状态行的内容，按钮仍要能把它关掉
        assertTrue(state(floatingVisible = true, error = "手势被系统取消").floatingRunning)
        // 窗口没跑时错误也不该被当成运行中
        assertFalse(state(error = "手势被系统取消").floatingRunning)
    }
}
