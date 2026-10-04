package app.luoxianlv

import app.luoxianlv.shared.dispatchWindowTouch
import org.junit.Assert.*
import org.junit.Test

class WindowTouchGuardTest {
    @Test
    fun ignoresOnlyKnownSystemCaptionFailure() {
        val error = IllegalStateException("This activity is currently not freeform-enabled")
        error.stackTrace =
            arrayOf(
                StackTraceElement(
                    "com.android.internal.widget.DecorCaptionView",
                    "toggleFreeformWindowingMode",
                    "DecorCaptionView.java",
                    544,
                )
            )
        assertFalse(dispatchWindowTouch { throw error })
        assertTrue(dispatchWindowTouch { true })
    }

    @Test
    fun applicationFailuresStillPropagate() {
        val error = IllegalStateException("This activity is currently not freeform-enabled")
        try {
            dispatchWindowTouch { throw error }
            fail("应用异常不应被吞掉")
        } catch (actual: IllegalStateException) {
            assertSame(error, actual)
        }
    }
}
