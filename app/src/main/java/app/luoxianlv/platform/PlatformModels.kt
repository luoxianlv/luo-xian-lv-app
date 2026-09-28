package app.luoxianlv.platform

import app.luoxianlv.data.AccountSession
import org.json.JSONObject

data class HotUpdate(
    val contentVersion: Int,
    val payload: JSONObject,
)

/** 平台曲目记录；可选的 contentUrl、updatedAt 兼容演示接口与正式签名内容。 */
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
