package app.luoxianlv.data

import android.content.Context

data class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val nickname: String,
    val expiresAt: Long = 0L,
)

class SessionStore(
    context: Context,
) {
    private val prefs = Kv.of(context, "account_session")

    fun current(): AccountSession? =
        prefs.getString("access", null)?.takeIf { it.isNotBlank() }?.let {
            AccountSession(
                it,
                prefs.getString("refresh", "").orEmpty(),
                prefs.getString("nickname", "").orEmpty(),
                prefs.getLong("expires_at", 0L),
            )
        }

    fun save(session: AccountSession) =
        prefs
            .edit()
            .putString(
                "access",
                session.accessToken,
            ).putString("refresh", session.refreshToken)
            .putString("nickname", session.nickname)
            .putLong("expires_at", session.expiresAt)
            .apply()

    fun clear() = prefs.edit().clear().apply()
}
