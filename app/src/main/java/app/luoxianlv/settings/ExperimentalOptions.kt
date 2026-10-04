package app.luoxianlv.settings

import android.content.Context
import app.luoxianlv.shared.Kv

/** 实验性能力独立存储，默认关闭，不覆盖自动识别保存的布局。 */
object ExperimentalOptions {
    fun fixedHarmonicaKeys(context: Context): Boolean =
        Kv.of(context, "experimental_options").getBoolean("fixed_harmonica_keys", false)

    fun setFixedHarmonicaKeys(context: Context, enabled: Boolean) {
        Kv.of(context, "experimental_options")
            .edit()
            .putBoolean("fixed_harmonica_keys", enabled)
            .apply()
    }
}
