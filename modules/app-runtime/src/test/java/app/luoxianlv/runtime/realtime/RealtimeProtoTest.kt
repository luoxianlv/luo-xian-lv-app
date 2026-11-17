package app.luoxianlv.runtime.realtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeProtoTest {
    @Test
    fun matchesRustPingAndPreservesExplicitZeroAndFalse() {
        val ping = byteArrayOf(8, 1, 18, 1, 112, 26, 1, 55, 90, 0)
        assertArrayEquals(ping, RealtimeProto.encode("7", "p", "system", "ping", JSONObject()))
        assertEquals("ping", RealtimeProto.decode(ping).getString("type"))
        val ready = JSONObject().put("uncertaintyUs", 0L).put("roundTripUs", 31_000L)
        val data = JSONObject().put("roomId", "r").put("ready", ready)
        val frame = RealtimeProto.encode("7", "p", "ensemble", "ready", data)
        assertEquals(
            0L,
            RealtimeProto.decode(frame)
                .getJSONObject("data")
                .getJSONObject("ready")
                .getLong("uncertaintyUs"),
        )
        val room =
            JSONObject()
                .put("id", "r")
                .put("members", org.json.JSONArray())
                .put("plan", JSONObject().put("committed", false))
                .put("positionUs", 4294967297L)
        val result =
            RealtimeProto.decode(RealtimeProto.encode("7", "p", "ensemble", "result", room))
                .getJSONObject("data")
        assertEquals(0, result.getJSONArray("members").length())
        assertFalse(result.getJSONObject("plan").getBoolean("committed"))
        assertEquals(4294967297L, result.getLong("positionUs"))
    }

    @Test
    fun queryChunkCarriesRawBytesAndTextFramesCannotDecode() {
        val raw = ByteArray(8192) { it.toByte() }
        val chunk = RealtimeProto.encode("7", "p", "query", "chunk", JSONObject().put("bytes", raw))
        assertTrue(chunk.size < raw.size + 32)
        assertArrayEquals(
            raw,
            RealtimeProto.decode(chunk).getJSONObject("data").get("bytes") as ByteArray,
        )
        assertThrows(Exception::class.java) { RealtimeProto.decode("{}".toByteArray()) }
        assertThrows(Exception::class.java) { RealtimeProto.decode(ByteArray(16385)) }
    }
}
