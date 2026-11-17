package app.luoxianlv.runtime.realtime

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueryChunksTest {
    private fun part(raw: ByteArray, at: Int, count: Int) =
        JSONObject()
            .put("status", 200)
            .put("time", "1791900000")
            .put("retryAfterMillis", 0)
            .put("offset", at)
            .put("total", raw.size)
            .put("bytes", Base64.getEncoder().encodeToString(raw.copyOfRange(at, at + count)))
            .put("done", at + count == raw.size)

    @Test
    fun preservesUnicodeAcrossChunkBoundaries() {
        val text = JSONObject().put("说明", "落弦律".repeat(5000)).toString()
        val raw = text.toByteArray(Charsets.UTF_8)
        val chunks = QueryChunks()
        var result: String? = null
        while (chunks.offset < raw.size) {
            result = chunks.accept(part(raw, chunks.offset, minOf(8192, raw.size - chunks.offset)))
        }
        assertEquals(text, JSONObject(result!!).getString("body"))
    }

    @Test
    fun rejectsGapsChangedMetadataAndPrematureCompletion() {
        val raw = ByteArray(9000) { 65 }
        for (bad in
            listOf(
                part(raw, 0, 8192).put("offset", 1),
                part(raw, 0, 8192).put("done", true),
                part(raw, 0, 8192).put("total", QueryChunks.MAX_BYTES + 1),
            )) {
            assertThrows(IllegalArgumentException::class.java) { QueryChunks().accept(bad) }
        }
        val chunks = QueryChunks()
        assertNull(chunks.accept(part(raw, 0, 8192)))
        assertThrows(IllegalArgumentException::class.java) {
            chunks.accept(part(raw, 8192, 808).put("status", 403))
        }
    }

    @Test
    fun rejectsInvalidUtf8InsteadOfReplacingCharacters() {
        val raw = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            QueryChunks().accept(part(raw, 0, 2))
        }
    }
}
