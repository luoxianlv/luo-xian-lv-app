package app.luoxianlv.platform

import android.content.Context
import app.luoxianlv.shared.Kv

data class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val nickname: String,
    val expiresAt: Long = 0L,
)

data class SessionSnapshot(val epoch: Long, val session: AccountSession?)

interface SessionPersistence {
    fun snapshot(): SessionSnapshot

    fun replace(expected: SessionSnapshot, session: AccountSession?): Boolean

    fun accepts(snapshot: SessionSnapshot, token: String?): Boolean =
        snapshot.session?.accessToken == token
}

class SessionStore(context: Context) : SessionPersistence {
    private val prefs = Kv.of(context, "account_session")

    fun current(): AccountSession? = synchronized(lock) { read() }

    fun publishRealtime() =
        synchronized(lock) {
            app.luoxianlv.hot.contract.SharedRealtime.current()
                ?.account(read()?.accessToken.orEmpty())
        }

    private fun read(): AccountSession? =
        prefs
            .getString("access", null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                AccountSession(
                    it,
                    prefs.getString("refresh", "").orEmpty(),
                    prefs.getString("nickname", "").orEmpty(),
                    prefs.getLong("expires_at", 0L),
                )
            }

    override fun snapshot(): SessionSnapshot =
        synchronized(lock) {
            SessionSnapshot(prefs.getLong("session_epoch", 0L), read())
        }

    fun save(session: AccountSession) =
        synchronized(lock) {
            // 显式登录/换号先撤销旧合奏身份；令牌刷新走 replace，不中断当前演奏。
            app.luoxianlv.hot.contract.SharedRealtime.current()?.account("")
            val epoch = prefs.getLong("session_epoch", 0L) + 1
            previousTokens.clear()
            write(session, epoch)
            publishRealtime()
        }

    private fun write(session: AccountSession, epoch: Long = prefs.getLong("session_epoch", 0L)) =
        prefs
            .edit()
            .putString(
                "access",
                session.accessToken,
            )
            .putString("refresh", session.refreshToken)
            .putString("nickname", session.nickname)
            .putLong("expires_at", session.expiresAt)
            .putLong("session_epoch", epoch)
            .apply()

    fun clear() =
        synchronized(lock) {
            val epoch = prefs.getLong("session_epoch", 0L) + 1
            previousTokens.clear()
            prefs.edit().clear().putLong("session_epoch", epoch).apply()
            publishRealtime()
        }

    override fun replace(expected: SessionSnapshot, session: AccountSession?): Boolean =
        synchronized(lock) {
            if (snapshot() != expected) return@synchronized false
            if (session == null) clear()
            else {
                expected.session?.accessToken?.let { previousTokens.addLast(it) }
                while (previousTokens.size > 4) previousTokens.removeFirst()
                write(session)
                publishRealtime()
            }
            true
        }

    override fun accepts(snapshot: SessionSnapshot, token: String?): Boolean =
        synchronized(lock) {
            this.snapshot() == snapshot &&
                (snapshot.session?.accessToken == token || token in previousTokens)
        }

    private companion object {
        val lock = Any()
        val previousTokens = java.util.ArrayDeque<String>()
    }
}
