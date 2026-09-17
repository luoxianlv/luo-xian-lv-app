package app.luoxianlv.data

import android.content.Context
import java.security.MessageDigest

/** 免责协议同意状态：记录已同意的协议文本 SHA-256。
 * 协议文本更新后哈希变化，与存储值不一致即视为未同意，需重新确认。 */
class DisclaimerStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("app_state", Context.MODE_PRIVATE)

    /** 已同意的协议哈希；从未同意过返回 null。 */
    fun agreedSha(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun markAgreed(sha: String) = prefs.edit().putString(KEY, sha).apply()

    companion object {
        private const val KEY = "disclaimer_agreed_sha"
        const val ASSET_PATH = "disclaimer.txt"

        fun currentSha(context: Context): String = sha256(readAsset(context))

        fun readAsset(context: Context): String =
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
