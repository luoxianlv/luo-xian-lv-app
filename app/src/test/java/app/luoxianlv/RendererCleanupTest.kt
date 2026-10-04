package app.luoxianlv

import app.luoxianlv.wallpaper.cleanupWallpaperRenderer
import org.junit.Assert.*
import org.junit.Test

class RendererCleanupTest {
    @Test
    fun brokenVendorStepDoesNotSkipRemainingCleanup() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        cleanupWallpaperRenderer(
            false,
            remove = {
                calls += "remove"
                throw IllegalArgumentException("already detached")
            },
            stop = {
                calls += "stop"
                throw IllegalStateException("renderer unavailable")
            },
            pause = {
                calls += "pause"
                throw NoSuchMethodError("vendor implementation")
            },
            destroy = { calls += "destroy" },
            onFailure = { step, _ -> failures += step },
        )
        assertEquals(listOf("remove", "stop", "pause", "destroy"), calls)
        assertEquals(listOf("remove", "stop", "pause"), failures)
    }

    @Test
    fun crashedRendererIsDestroyedWithoutFurtherRendererCalls() {
        val calls = mutableListOf<String>()
        cleanupWallpaperRenderer(
            true,
            remove = { calls += "remove" },
            stop = { fail("must not call crashed renderer") },
            pause = { fail("must not call crashed renderer") },
            destroy = { calls += "destroy" },
            onFailure = { _, error -> throw AssertionError(error) },
        )
        assertEquals(listOf("remove", "destroy"), calls)
    }

    @Test
    fun fatalMemoryFailureIsNotHidden() {
        val failure = OutOfMemoryError("test allocation failure")
        try {
            cleanupWallpaperRenderer(
                false,
                remove = { throw failure },
                stop = {},
                pause = {},
                destroy = {},
                onFailure = { _, _ -> fail("fatal failure must propagate") },
            )
            fail("expected memory failure")
        } catch (error: OutOfMemoryError) {
            assertSame(failure, error)
        }
    }
}
