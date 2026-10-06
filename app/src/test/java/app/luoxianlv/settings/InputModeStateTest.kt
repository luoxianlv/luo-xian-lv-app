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
}
