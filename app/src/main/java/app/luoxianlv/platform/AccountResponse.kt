package app.luoxianlv.platform

import app.luoxianlv.data.AccountSession
import org.json.JSONObject

internal fun parseAccountSession(root: JSONObject): AccountSession {
    val data = root.optJSONObject("data") ?: root
    val access =
        data.optString("accessToken").trim().ifBlank { data.optString("access_token").trim() }
    require(access.isNotEmpty()) { root.optString("message").ifBlank { "登录响应缺少令牌，请重试" } }
    return AccountSession(
        access,
        data.optString("refreshToken").ifBlank { data.optString("refresh_token") },
        data.optJSONObject("user")?.optString("nickname").orEmpty(),
        System.currentTimeMillis() +
            (data.optLong("expiresIn", 0L).takeIf { it > 0 }
                ?: data.optLong("expires_in", 30L * 24 * 3600)) * 1000L,
    )
}
