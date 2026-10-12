package app.luoxianlv.settings

import org.junit.Assert.*
import org.junit.Test

class InputModeStateTest {
    private val shizuku =
        InputModeState(
            supported = true,
            overlayGranted = true,
            mode = "shizuku",
            installed = true,
            binderAlive = true,
        )

    @Test
    fun binderHandshakeIsNotShownAsPermissionDenied() {
        val waiting = shizuku.copy(binderReady = false)
        assertEquals("正在等待 Shizuku", waiting.statusTitle)
        assertEquals("refresh", waiting.primaryAction?.command)
    }

    @Test
    fun unreadablePermissionDoesNotRequestAnotherGrant() {
        val unreadable = shizuku.copy(binderReady = true, permissionState = "unavailable")
        assertEquals("授权状态暂未读到", unreadable.statusTitle)
        assertEquals("refresh", unreadable.primaryAction?.command)
    }

    @Test
    fun deniedPermissionUsesOfficialRequest() {
        val denied = shizuku.copy(binderReady = true, permissionState = "denied")
        assertEquals("需要授权", denied.statusTitle)
        assertEquals("authorize", denied.primaryAction?.command)
    }

    @Test
    fun authorizedConnectionWithFailedTouchProbeKeepsAuthorization() {
        val failure = shizuku.copy(binderReady = true, permissionGranted = true, connected = true)
        assertEquals("触控检查未通过", failure.statusTitle)
        assertEquals("connect", failure.primaryAction?.command)
    }

    @Test
    fun standardFallbackRemainsUsableAndStatesItsTouchLimitation() {
        val ready = shizuku.copy(connected = true, touchReady = true, coexistenceReady = false)
        assertTrue(ready.usable)
        assertEquals("可以开始演奏", ready.statusTitle)
        assertTrue(ready.statusSummary.contains("标准点击"))
        assertTrue(ready.statusSummary.contains("中断"))
        assertNull(ready.primaryAction)
        assertEquals("已连接，可边操作边演奏", ready.copy(coexistenceReady = true).statusSummary)
    }
}
