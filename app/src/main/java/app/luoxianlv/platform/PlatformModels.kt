package app.luoxianlv.platform

import app.luoxianlv.data.AccountSession
import org.json.JSONObject

data class HotUpdate(
    val contentVersion: Int,
    val payload: JSONObject,
)

/**
 * A score returned by the platform library endpoint. The optional fields keep the client compatible
 * with both the current demo API and the signed content records used by production (`contentUrl`,
 * `updatedAt`).
 */
data class SyncedScore(
    val id: String,
    val title: String,
    val contentVersion: Long,
    val notationText: String,
    val bpm: Int,
    val keySignature: String?,
    val contentUrl: String? = null,
    val updatedAt: String? = null,
)

data class PlatformScore(
    val id: String,
    val title: String,
    val author: String,
    val bpm: Int,
    val keySignature: String?,
    val notationText: String,
)

data class LoginResult(val session: AccountSession)
