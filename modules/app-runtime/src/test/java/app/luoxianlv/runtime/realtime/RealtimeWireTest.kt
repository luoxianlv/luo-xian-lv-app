package app.luoxianlv.runtime.realtime

import org.junit.Assert.*
import org.junit.Test

class RealtimeWireTest {
    @Test
    fun limitsUtf8BytesAndDepthBeforeJsonParser() {
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeWire.objectValue("{\"text\":\"${"中".repeat(6000)}\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeWire.objectValue("{\"x\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")
        }
        assertEquals("{[]}", RealtimeWire.objectValue("{\"x\":\"{[]}\"}").getString("x"))
        assertThrows(IllegalArgumentException::class.java) { RealtimeWire.objectValue("{} {}") }
        assertThrows(IllegalArgumentException::class.java) { RealtimeWire.objectValue("[]") }
    }

    @Test
    fun identifiersAndLongValuesHaveExplicitBounds() {
        assertTrue(RealtimeWire.validId("command-123:1"))
        assertFalse(RealtimeWire.validId("secret?token="))
        assertEquals(Long.MAX_VALUE, RealtimeWire.positive(Long.MAX_VALUE.toString()))
        assertThrows(IllegalArgumentException::class.java) { RealtimeWire.positive("1e3") }
        assertThrows(IllegalArgumentException::class.java) { RealtimeWire.positive("-1") }
    }
}
