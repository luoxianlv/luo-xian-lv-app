package app.luoxianlv.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.luoxianlv.data.AccountSession
import app.luoxianlv.data.Kv
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/** 负责 PKCE 登录及回调校验。 */
internal class ShushuLogin(
    private val context: Context,
    private val baseUrl: String,
    private val http: BackendClient,
) {
    fun startShushuLogin(activity: Activity) {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier =
            Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val stateBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val state =
            Base64.encodeToString(
                stateBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        val challenge =
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        Kv.of(context, "oauth")
            .edit()
            .putString("state", state)
            .putString("verifier", verifier)
            .apply()
        val url =
            Uri.parse("https://shushu.fan/oauth/authorize")
                .buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", "shu_8d4eddbcb1e96ff01a8b52fb")
                .appendQueryParameter("redirect_uri", "https://luoxianlv.com/login/callback")
                .appendQueryParameter("scope", "openid profile")
                .appendQueryParameter("state", "app_$state")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
        activity.startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    fun finishShushuLogin(
        intent: Intent,
        onResult: (Result<LoginResult>) -> Unit,
    ) {
        val data = intent.data ?: return
        val code = data.getQueryParameter("code") ?: return
        val returnedState = data.getQueryParameter("state") ?: return
        val prefs = Kv.of(context, "oauth")
        if (returnedState != "app_${prefs.getString("state", null)}") return
        val verifier = prefs.getString("verifier", null) ?: return
        prefs.edit().clear().apply()
        background(onResult) {
            val root =
                JSONObject(
                    http.postJson(
                        "$baseUrl/api/auth/oauth/exchange",
                        JSONObject()
                            .put(
                                "code",
                                code,
                            )
                            .put(
                                "state",
                                returnedState,
                            )
                            .put("redirect_uri", "https://luoxianlv.com/login/callback")
                            .put("code_verifier", verifier)
                            .toString(),
                    )
                )
            val access = root.optString("accessToken").ifBlank { root.optString("access_token") }
            require(access.isNotBlank()) { root.optString("message", "鼠鼠登录失败") }
            LoginResult(
                AccountSession(
                    access,
                    root.optString("refreshToken"),
                    root.optJSONObject("user")?.optString("nickname").orEmpty(),
                    System.currentTimeMillis() +
                        (root.optLong("expiresIn", 0L).takeIf { it > 0 }
                            ?: root.optLong("expires_in", 30L * 24 * 3600)) * 1000L,
                )
            )
        }
    }
}
