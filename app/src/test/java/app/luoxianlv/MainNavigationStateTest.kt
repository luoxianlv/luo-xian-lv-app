package app.luoxianlv

import app.luoxianlv.app.MainNavigationState
import app.luoxianlv.app.Routes
import org.junit.Assert.*
import org.junit.Test

class MainNavigationStateTest {
    @Test
    fun loginOpenedFromEnsembleReturnsToRoomBeforeHome() {
        val state = MainNavigationState()
        state.subPage.value = Routes.ENSEMBLE
        state.openEnsembleSetup(Routes.LOGIN)
        assertEquals(Routes.LOGIN, state.subPage.value)
        assertFalse(state.startFloatingAfterSetup)
        state.dismissOverlay()
        assertEquals(Routes.ENSEMBLE, state.subPage.value)
        state.dismissOverlay()
        assertNull(state.subPage.value)
    }

    @Test
    fun floatingSetupReturnsToRoomAndClearsStartRequest() {
        val state = MainNavigationState()
        state.openEnsembleSetup(Routes.INPUT_MODE)
        assertTrue(state.startFloatingAfterSetup)
        state.dismissOverlay()
        assertEquals(Routes.ENSEMBLE, state.subPage.value)
        assertFalse(state.startFloatingAfterSetup)
    }

    @Test
    fun reopeningEnsembleClearsThePreviousSetupReturnDestination() {
        listOf(Routes.LOGIN, Routes.INPUT_MODE).forEach { setup ->
            val state = MainNavigationState()
            state.openEnsembleSetup(setup)
            // The floating window can reopen the room while setup is still visible.
            state.openEnsemble()
            assertEquals(Routes.ENSEMBLE, state.subPage.value)
            assertFalse(state.startFloatingAfterSetup)
            state.dismissOverlay()
            assertNull(state.subPage.value)
            // A later ordinary login or input settings visit must return to the main page.
            listOf(Routes.LOGIN, Routes.INPUT_MODE).forEach { ordinaryPage ->
                state.subPage.value = ordinaryPage
                state.dismissOverlay()
                assertNull(state.subPage.value)
                assertFalse(state.startFloatingAfterSetup)
            }
        }
    }

    @Test
    fun ordinarySettingsAndLoginKeepTheirOriginalReturnDestination() {
        val state = MainNavigationState()
        state.subPage.value = Routes.LOGIN
        state.dismissOverlay()
        assertNull(state.subPage.value)
        state.openEnsembleSetup(Routes.LOGIN)
        state.subPage.value = Routes.ABOUT
        state.dismissOverlay()
        assertNull(state.subPage.value)
        state.subPage.value = Routes.LOGIN
        state.dismissOverlay()
        assertNull(state.subPage.value)
    }
}
