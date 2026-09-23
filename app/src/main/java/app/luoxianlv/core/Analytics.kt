package app.luoxianlv.core

import android.content.Context
import android.os.Bundle
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure
import com.umeng.umcrash.UMCrash

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
            // U-APM 性能监控配置：必须在 UMConfigure.init 之前调用。
            // 放在这里（而非 Application.onCreate）是为了和统计一样等用户同意协议后再开启。
            UMCrash.initConfig(
                Bundle().apply {
                    putBoolean(UMCrash.KEY_ENABLE_CRASH_JAVA, true)
                    putBoolean(UMCrash.KEY_ENABLE_CRASH_NATIVE, true)
                    putBoolean(UMCrash.KEY_ENABLE_ANR, true)
                    putBoolean(UMCrash.KEY_ENABLE_LAUNCH, true)
                    putBoolean(UMCrash.KEY_ENABLE_NET, true)
                    putBoolean(UMCrash.KEY_ENABLE_MEM, true)
                    // 页面分析与卡顿（PA）都要显式打开：不写这两个开关，插桩注入的
                    // PageManger/PA 调用不会产生上报（默认不开）。
                    putBoolean(UMCrash.KEY_ENABLE_PAGE, true)
                    putBoolean(UMCrash.KEY_ENABLE_PA, true)
                },
            )
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
            // 页面统计改走手动模式：本应用是单 Activity + Compose，页面由 AppNavHost
            // 按路由成对调用 [pageStart]/[pageEnd]；关掉自动采集就不会多出一个没意义的
            // MainActivity 页面。（U-APM 的页面分析是另一套，与此开关无关。）
            MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.MANUAL)
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

    /**
     * U-App 页面统计（页面访问次数/停留时长/访问路径）。
     *
     * 本应用是单 Activity + Compose，而 U-APM 的页面维度是 Activity（PageManger 的
     * 第二个参数是阶段名不是页面名），所以按路由分页面只能走 U-App 这套 [pageStart]/[pageEnd]。
     * 必须成对调用，未同意免责协议时静默丢弃。
     */
    fun pageStart(page: String) {
        if (!initialized) return
        MobclickAgent.onPageStart(page)
    }

    fun pageEnd(page: String) {
        if (!initialized) return
        MobclickAgent.onPageEnd(page)
    }
}
