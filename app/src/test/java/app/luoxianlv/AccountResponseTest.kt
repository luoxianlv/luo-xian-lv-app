package app.luoxianlv

import app.luoxianlv.platform.parseAccountSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountResponseTest {
    @Test
    fun readsWebsiteLoginResponse() {
        val session =
            parseAccountSession(
                JSONObject("""{"accessToken":"test-token","user":{"nickname":"Admilk"}}""")
            )
        assertEquals("test-token", session.accessToken)
        assertEquals("Admilk", session.nickname)
    }

    @Test
    fun readsLegacyWrappedResponse() {
        assertEquals(
            "legacy",
            parseAccountSession(JSONObject("""{"data":{"access_token":"legacy"}}""")).accessToken,
        )
    }

    @Test
    fun rejectsMissingToken() {
        assertThrows(IllegalArgumentException::class.java) { parseAccountSession(JSONObject("{}")) }
    }
}
