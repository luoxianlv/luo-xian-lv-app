package app.luoxianlv

import android.app.Application
import app.luoxianlv.core.Analytics
import com.umeng.commonsdk.UMConfigure

/**
 * 友盟统计接入（U-App）：
 * - preInit 在 Application.onCreate 主线程执行，不采集设备信息、不上报，合规要求；
 * - 正式 init 必须等用户同意免责协议后由 [app.luoxianlv.core.Analytics] 触发，见 MainActivity。
 */
class LuoXianLvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UMConfigure.preInit(this, Analytics.APP_KEY, Analytics.CHANNEL)
    }
}
