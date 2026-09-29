package app.luoxianlv.business

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import app.luoxianlv.BuildConfig
import app.luoxianlv.business.ui.ComposePage
import app.luoxianlv.core.Analytics
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.data.DisclaimerStore
import app.luoxianlv.data.Kv
import app.luoxianlv.data.SessionStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.platform.PlatformClient
import app.luoxianlv.service.KeepAlive
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.ui.components.DisclaimerScreen
import app.luoxianlv.ui.components.OnboardingDialog
import app.luoxianlv.ui.components.Snowfall
import app.luoxianlv.ui.navigation.AppNavHost
import app.luoxianlv.ui.navigation.MainNavigationState
import app.luoxianlv.ui.theme.LuoXianLvTheme
import app.luoxianlv.update.AppUpdateViewModel
import app.luoxianlv.update.MidiCoreFixer
import app.luoxianlv.update.UpdateAutoCheck

/** 主业务页面：拥有 Compose 和 ViewModel，系统 Activity 仅转发生命周期与平台结果。 */
class MainPage : ComposePage() {
    private val navigation = MainNavigationState()

    private lateinit var repository: SongRepository
    private val oauthUpdater by lazy { PlatformClient(pageContext) }
    private lateinit var appUpdates: AppUpdateViewModel
    private var showOnboarding by mutableStateOf(false)
    private var showBatteryPrompt by mutableStateOf(false)
    private var showAutoStartPrompt by mutableStateOf(false)
    private var disclaimerAccepted by mutableStateOf(true)
    private var updateCheckOnOpenDone = false
    private lateinit var disclaimerText: String
    private lateinit var disclaimerSha: String

    override fun prepare(state: Bundle) {
        navigation.restore(state.getBundle("navigation"))
        updateCheckOnOpenDone = state.getBoolean("updateCheckOnOpenDone", false)
        showBatteryPrompt = state.getBoolean("batteryPrompt", false)
        showAutoStartPrompt = state.getBoolean("autoStartPrompt", false)
        repository = SongRepository(pageContext)
        appUpdates = ViewModelProvider(this)[AppUpdateViewModel::class.java]
        AppearanceStore.initialize(pageContext)
        // 免责协议是启动第一道门：已同意文本的 SHA-256 与当前 assets 里的协议不一致
        // （首次使用或协议更新后）就拦截在协议页，同意前不渲染正常 App。
        // 读不到协议文件时不拦截，避免资源缺失把用户挡在门外。
        disclaimerText =
            if (BuildConfig.INTERNAL_BUILD) ""
            else runCatching { DisclaimerStore.readAsset(pageContext) }.getOrElse { "" }
        disclaimerSha = DisclaimerStore.sha256(disclaimerText)
        disclaimerAccepted =
            BuildConfig.INTERNAL_BUILD ||
                (disclaimerText.isNotEmpty() &&
                    DisclaimerStore(pageContext).agreedSha() == disclaimerSha)
        // 权限引导只在首次启动弹一次，此后不再打扰；
        // 之后的运行时检查在「我的」页悬浮窗开关处（LibraryViewModel.setFloatingEnabled）
        showOnboarding =
            !BuildConfig.INTERNAL_BUILD &&
                isFirstLaunch() &&
                !MusicAccessibilityService.isEnabled(pageContext)
    }

    @Composable
    override fun Content() {
        val appearance by AppearanceStore.settings.collectAsState()
        // 深浅色：偏好（跟随系统 / 浅色 / 深色）叠加系统设置算出最终结果。
        // 主题只吃这一个入参；容器半透明固定，见 Theme.kt。
        LuoXianLvTheme(
            darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme()),
            applySystemBars = isActive,
        ) {
            if (!disclaimerAccepted) {
                DisclaimerScreen(
                    text = disclaimerText,
                    onAgree = {
                        DisclaimerStore(pageContext).markAgreed(disclaimerSha)
                        disclaimerAccepted = true
                        Analytics.initialize(pageContext)
                        Analytics.logEvent(pageContext, "disclaimer_agree") // 埋点：同意免责协议
                        checkUpdatesAfterDisclaimer()
                        requestBackgroundPermissionsOnce()
                    },
                    onDecline = { host.finish() },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 页面底色由各页自己铺的渐变底承担（见 ui/theme/Backdrop.kt）。
                    AppNavHost(appUpdates, navigation)
                    // 飘雪盖在最上层：Canvas 不消费触摸，不会挡住底下的按钮与列表。
                    Snowfall(enabled = appearance.snowEnabled)
                    if (showOnboarding) {
                        OnboardingDialog(
                            onEnable = {
                                markOnboardingDone()
                                activity.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            },
                            onLater = ::markOnboardingDone,
                        )
                    }
                    if (showBatteryPrompt) {
                        BatteryExemptionDialog(
                            onAllow = {
                                markBatteryAsked()
                                KeepAlive.requestBatteryExemption(activity)
                                showBatteryPrompt = false
                            },
                            onLater = {
                                markBatteryAsked()
                                showBatteryPrompt = false
                                requestBackgroundPermissionsOnce()
                            },
                        )
                    }
                    if (showAutoStartPrompt) {
                        AutoStartDialog(
                            onAllow = {
                                markAutoStartAsked()
                                KeepAlive.openAutoStartSettings(activity)
                            },
                            onLater = ::markAutoStartAsked,
                        )
                    }
                }
            }
        }
    }

    override fun newIntent(intent: Intent) {
        finishShushuIntent(intent)
        Analytics.recordScheme(intent.dataString)
    }

    override fun savePageState() =
        Bundle().apply {
            putBundle("navigation", navigation.save())
            putBoolean("updateCheckOnOpenDone", updateCheckOnOpenDone)
            putBoolean("batteryPrompt", showBatteryPrompt)
            putBoolean("autoStartPrompt", showAutoStartPrompt)
        }

    override fun canReplace() = super.canReplace() && !navigation.moving

    override fun result(key: String, resultCode: Int, data: Intent?): Boolean =
        key == "main.notifications" || super.result(key, resultCode, data)

    private fun finishShushuIntent(intent: Intent) {
        if (intent.data?.scheme != "luoxianlv") return
        oauthUpdater.finishShushuLogin(intent) { result ->
            result.onSuccess {
                SessionStore(pageContext).save(it.session)
                Analytics.logEvent(pageContext, "oauth_login_success") // 埋点：鼠鼠 OAuth 登录成功
            }
        }
    }

    override fun hostWarning(code: String, error: Throwable) {
        if (code == "stale_freeform")
            app.luoxianlv.debug.AppLog.w("窗口交互", "系统小窗状态已变化，忽略失效的标题栏点击", error)
        else
            app.luoxianlv.debug.AppLog.w(
                "原生宿主",
                when (code) {
                    "page_state_failed" -> "页面状态暂时无法保存"
                    "invalid_system_result" -> "系统选择结果无效，已取消本次操作"
                    else -> "页面运行失败"
                },
                error,
            )
    }

    private val appPrefs by lazy { Kv.of(pageContext, "app_state") }

    private fun isFirstLaunch(): Boolean = !appPrefs.getBoolean("onboarding_done", false)

    private fun markOnboardingDone() {
        appPrefs.edit().putBoolean("onboarding_done", true).apply()
        showOnboarding = false
    }

    override fun lifecycleChanged(state: Int) {
        if (state != NativePage.RESUMED) return
        // 候选页后台预绘制不启动统计；只有真正展示且已同意协议的页面执行初始化。
        if (disclaimerAccepted) Analytics.initialize(pageContext)
        // MIDI 编译核心版本检查 + remoteId 回填 + 批量重编：内部有 5 分钟节流，
        // 启动和每次回前台都调用即可，离线时静默失败。
        MidiCoreFixer.kick(pageContext)
        if (
            !BuildConfig.INTERNAL_BUILD &&
                android.os.Build.VERSION.SDK_INT >= 33 &&
                repository.floatingEnabled &&
                appPrefs.getBoolean("auto_start_asked", false) &&
                MusicAccessibilityService.isEnabled(pageContext) &&
                pageContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !appPrefs.getBoolean("notification_permission_asked", false)
        ) {
            appPrefs.edit().putBoolean("notification_permission_asked", true).apply()
            host.permissions(
                "main.notifications",
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        // 无障碍服务可能在本应用暂停期间被启用；回到前台时按持久化偏好重新对齐悬浮窗
        MusicAccessibilityService.instance?.showFloating(repository.floatingEnabled)
        if (!BuildConfig.INTERNAL_BUILD && disclaimerAccepted) {
            if (!updateCheckOnOpenDone) {
                checkUpdatesAfterDisclaimer()
            } else {
                appUpdates.onResume(activity)
            }
        }
        requestBackgroundPermissionsOnce()
    }

    /** 每次打开应用且已读完免责声明后立即检查一次，后续前台恢复走节流检查。 */
    private fun checkUpdatesAfterDisclaimer() {
        if (updateCheckOnOpenDone || !disclaimerAccepted) return
        // 启动绕过六小时节流，但不显示手动检查的结果提示。
        if (!UpdateAutoCheck.isEnabled(pageContext)) return
        updateCheckOnOpenDone = true
        // Debug 包不触发启动检测更新；回前台节流检查与手动「检查新版本」不受影响。
        if (BuildConfig.INTERNAL_BUILD) return
        appUpdates.check(force = true)
    }

    /** 每项引导仅显示一次；展示前保存记录，返回设置、重开无障碍或应用都不重复。 */
    private fun requestBackgroundPermissionsOnce() {
        if (
            BuildConfig.INTERNAL_BUILD ||
                !disclaimerAccepted ||
                showOnboarding ||
                showBatteryPrompt ||
                showAutoStartPrompt
        )
            return
        if (!MusicAccessibilityService.isEnabled(pageContext)) return
        val pm = pageContext.getSystemService(PowerManager::class.java) ?: return
        if (
            !pm.isIgnoringBatteryOptimizations(pageContext.packageName) &&
                !appPrefs.getBoolean("battery_exemption_asked", false)
        ) {
            appPrefs.edit().putBoolean("battery_exemption_asked", true).commit()
            showBatteryPrompt = true
        } else if (!appPrefs.getBoolean("auto_start_asked", false)) {
            appPrefs.edit().putBoolean("auto_start_asked", true).commit()
            showAutoStartPrompt = true
        }
    }

    private fun markAutoStartAsked() {
        // 只记录首次引导已处理，不代表用户已授予权限。
        appPrefs.edit().putBoolean("auto_start_asked", true).apply()
        showAutoStartPrompt = false
    }

    private fun markBatteryAsked() {
        appPrefs.edit().putBoolean("battery_exemption_asked", true).apply()
    }
}

/** 电池优化白名单引导：先讲清楚为什么要开，用户才不容易在系统弹窗里点「不允许」。 */
@Composable
private fun BatteryExemptionDialog(
    onAllow: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("防止后台被清理") },
        text = {
            Text("请允许忽略电池优化，减少后台播放中断和悬浮窗消失。")
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("去允许") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("以后再说") }
        },
    )
}

@Composable
private fun AutoStartDialog(
    onAllow: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("自启动设置") },
        text = { Text("请在系统设置中允许落弦律自启动，已开启可跳过。此提示只显示一次。") },
        confirmButton = { TextButton(onClick = onAllow) { Text("去设置") } },
        dismissButton = { TextButton(onClick = onLater) { Text("不再提醒") } },
    )
}
