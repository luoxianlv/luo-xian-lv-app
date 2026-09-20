package app.luoxianlv.update

import android.content.Context
import app.luoxianlv.data.Kv

/**
 * 自动检查当前固定开启。保留旧开关的存储代码，但不再读取其关闭状态。
 * 启动和回前台检查沿用原有时机与节流，手动检查不受节流影响。
 */
internal object UpdateAutoCheck {
    private const val STORE = "app_updates"
    private const val KEY = "auto_check"

    // 强制开启检查，忽略旧版本保存的关闭状态；保留原读取逻辑供恢复。
    @Suppress("UNUSED_PARAMETER")
    fun isEnabled(context: Context): Boolean = true
    // fun isEnabled(context: Context): Boolean = Kv.of(context, STORE).getBoolean(KEY, true)

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
