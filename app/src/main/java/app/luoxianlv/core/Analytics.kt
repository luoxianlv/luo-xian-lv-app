package app.luoxianlv.core

import android.content.Context
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure

/**
 * 友盟统计（U-App）启动开关。
 *
 * 合规两步走：Application.onCreate 里只做 preInit（不采集不上报）；
 * 用户同意免责协议后调用 [initialize]，此时 SDK 才真正采集并上报。
 */
object Analytics {
    /** 友盟后台「落弦律」应用的 AppKey。 */
    const val APP_KEY = "6ab2b6f174319160830e642e"

    /** 分发渠道标识，官网直链为 official。 */
    const val CHANNEL = "official"

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            // 隐私授权确认：友盟合规检查的显式授权 API，必须在 init 之前调用，
            // 否则上报被拦截（logcat 报「检测到未调用隐私授权API」）。
            UMConfigure.submitPolicyGrantResult(app, true)
            UMConfigure.init(
                app,
                APP_KEY,
                CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                null,
            )
            initialized = true
        }
    }

    /**
     * 上报自定义事件（事件 id 需与友盟后台逐字符一致）。
     *
     * 未同意免责协议（SDK 未初始化）时静默丢弃，不缓存不补报。
     */
    fun logEvent(
        context: Context,
        event: String,
    ) {
        if (!initialized) return
        MobclickAgent.onEvent(context.applicationContext, event)
    }
}
