package app.luoxianlv.data

import android.content.Context

data class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val nickname: String,
)

class SessionStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("account_session", Context.MODE_PRIVATE)

    fun current(): AccountSession? =
        prefs.getString("access", null)?.takeIf { it.isNotBlank() }?.let {
            AccountSession(it, prefs.getString("refresh", "").orEmpty(), prefs.getString("nickname", "").orEmpty())
        }

    fun save(session: AccountSession) =
        prefs
            .edit()
            .putString(
                "access",
                session.accessToken,
            ).putString("refresh", session.refreshToken)
            .putString("nickname", session.nickname)
            .apply()

    fun clear() = prefs.edit().clear().apply()
}
