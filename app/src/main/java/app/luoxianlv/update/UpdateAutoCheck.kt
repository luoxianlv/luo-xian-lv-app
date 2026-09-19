package app.luoxianlv.update

import android.content.Context
import app.luoxianlv.data.Kv

/**
 * 「自动检查更新」开关：存在 `app_updates` 存储里，与更新检查的节流
 * 记录（`last_check`）同一个文件，默认开。
 *
 * 只约束**自动**检查（启动、回前台）；关于页的手动「检查新版本」
 * 和 DEBUG 广播触发不受它管 —— 那是用户点出来的动作。
 */
internal object UpdateAutoCheck {
    private const val STORE = "app_updates"
    private const val KEY = "auto_check"

    fun isEnabled(context: Context): Boolean = Kv.of(context, STORE).getBoolean(KEY, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        Kv
            .of(context, STORE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
