package app.luoxianlv

import app.luoxianlv.platform.PlatformResponseParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformResponseParserTest {
    private val parser = PlatformResponseParser("https://example.test")

    @Test
    fun legacyLibraryAliasesKeepTheirDefaultsAndResolveRelativeContent() {
        val score =
            parser.parseSyncedScore(
                JSONObject(
                    """{
            "score_id":" 42 ", "name":"Legacy", "version":"7", "bpm":0,
            "content_url":"/scores/42", "key_signature":"C"
        }"""
                )
            )!!
        assertEquals("42", score.id)
        assertEquals("Legacy", score.title)
        assertEquals(7L, score.contentVersion)
        assertEquals(1, score.bpm)
        assertEquals("https://example.test/scores/42", score.contentUrl)
        assertNull(parser.parseSyncedScore(JSONObject("{}")))
        assertNull(parser.parseSyncedScore(null))
    }

    @Test
    fun librarySupportsBareArraysAndBothCursorEnvelopes() {
        assertEquals(1, parser.parseLibraryPage("[{\"id\":\"1\"}]").first.length())
        assertNull(parser.parseLibraryPage("[]").second)
        val (items, cursor) =
            parser.parseLibraryPage("""{"scores":[{"id":"2"}],"next_cursor":"next"}""")
        assertEquals(1, items.length())
        assertEquals("next", cursor)
        assertEquals("n", parser.parseLibraryPage("""{"items":[],"nextCursor":"n"}""").second)
    }

    @Test
    fun publicListingSkipsMissingIdsAndClampsTempo() {
        val scores =
            parser.parsePlatformScores(JSONObject("""{"items":[null,{}, {"id":"a","bpm":2000}]}"""))
        assertEquals(1, scores.size)
        assertEquals("a", scores.single().id)
        assertEquals(999, scores.single().bpm)
        assertEquals("未命名谱子", scores.single().title)
    }
}
