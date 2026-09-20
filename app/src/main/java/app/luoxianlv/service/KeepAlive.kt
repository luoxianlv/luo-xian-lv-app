package app.luoxianlv.service

import android.content.Context
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 悬浮窗与无障碍服务都依赖进程存活；国产 ROM 在电池优化之外还叠加
 * 自启动、后台管理等限制（红魔/小米尤其明显）。这里统一做状态检测与系统页跳转。 */
data class KeepAliveStatus(
    val accessibilityEnabled: Boolean,
    val batteryExempt: Boolean,
    val notificationsGranted: Boolean,
)

object KeepAlive {
    fun status(context: Context): KeepAliveStatus {
        val pm = context.getSystemService(PowerManager::class.java)
        return KeepAliveStatus(
            accessibilityEnabled = MusicAccessibilityService.isEnabled(context),
            batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
            notificationsGranted =
                (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) &&
                    context.getSystemService(NotificationManager::class.java).let { manager ->
                        manager.areNotificationsEnabled() &&
                            manager.getNotificationChannel(PlaybackForegroundService.CHANNEL)?.importance !=
                            NotificationManager.IMPORTANCE_NONE
                    },
        )
    }

    /** 系统「忽略电池优化」授权弹窗；个别 ROM 不支持精确弹窗时回退到电池优化列表页。 */
    fun requestBatteryExemption(context: Context) {
        val direct =
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        if (direct.resolveActivity(context.packageManager) != null) {
            launch(context, direct)
        } else {
            openBatteryOptimizationList(context)
        }
    }

    fun openBatteryOptimizationList(context: Context) {
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openNotificationSettings(context: Context) {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(PlaybackForegroundService.CHANNEL)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
            launch(context, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, PlaybackForegroundService.CHANNEL))
            return
        }
        launch(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    /** 各 ROM 的「自启动 / 后台运行」页入口按厂商适配，找不到可解析的时
     * 回退到应用详情页，由用户手动允许。 */
    fun openAutoStartSettings(context: Context) {
        val candidates =
            listOf(
                // 小米 / MIUI / HyperOS
                Intent()
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                // 华为 / 荣耀（EMUI、老版本 HarmonyOS）
                Intent()
                    .setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                Intent()
                    .setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                // OPPO / 一加 / realme（ColorOS）
                Intent()
                    .setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                Intent()
                    .setClassName("com.coloros.phonemanager", "com.coloros.phonemanager.settings.BaseActivity"),
                // vivo / iQOO（FuntouchOS、OriginOS）
                Intent()
                    .setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                Intent()
                    .setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                // 魅族（Flyme）
                Intent()
                    .setClassName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
                // 努比亚 / 红魔（手机管家，入口随版本变化，尽力而为）
                Intent()
                    .setClassName("cn.nubia.security2", "cn.nubia.security.MainActivity"),
            )
        // 直接尝试启动，避免 Android 包可见性限制使 resolveActivity 误判。
        for (candidate in candidates) {
            if (runCatching { context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        val target = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
        launch(context, target)
    }

    private fun launch(context: Context, intent: Intent) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
