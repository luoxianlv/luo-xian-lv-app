package app.luoxianlv

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.data.DisclaimerStore
import app.luoxianlv.data.Kv
import app.luoxianlv.data.SessionStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.service.KeepAlive
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.ui.components.DisclaimerScreen
import app.luoxianlv.ui.components.OnboardingDialog
import app.luoxianlv.ui.components.Snowfall
import app.luoxianlv.ui.navigation.AppNavHost
import app.luoxianlv.ui.theme.LuoXianLvTheme
import app.luoxianlv.update.AppUpdateViewModel
import app.luoxianlv.update.UpdateAutoCheck
import app.luoxianlv.update.UpdateManager

/** Compose 单 Activity 入口：只负责挂 UI 树与生命周期级的服务/热更新对齐。 */
class MainActivity : AppCompatActivity() {
    private lateinit var repository: SongRepository
    private val oauthUpdater by lazy { UpdateManager(this) }
    private lateinit var appUpdates: AppUpdateViewModel
    private var showOnboarding by mutableStateOf(false)
    private var showBatteryPrompt by mutableStateOf(false)
    private var disclaimerAccepted by mutableStateOf(true)
    private var updateCheckOnOpenDone = false
    private lateinit var disclaimerText: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        repository = SongRepository(this)
        appUpdates = ViewModelProvider(this)[AppUpdateViewModel::class.java]
        AppearanceStore.initialize(this)
        // 免责协议是启动第一道门：已同意文本的 SHA-256 与当前 assets 里的协议不一致
        // （首次使用或协议更新后）就拦截在协议页，同意前不渲染正常 App。
        // 读不到协议文件时不拦截，避免资源缺失把用户挡在门外。
        disclaimerText = runCatching { DisclaimerStore.readAsset(this) }.getOrElse { "" }
        val disclaimerSha = DisclaimerStore.sha256(disclaimerText)
        disclaimerAccepted =
            disclaimerText.isNotEmpty() && DisclaimerStore(this).agreedSha() == disclaimerSha
        // 权限引导只在首次启动弹一次，此后不再打扰；
        // 之后的运行时检查在「我的」页悬浮窗开关处（LibraryViewModel.setFloatingEnabled）
        showOnboarding = isFirstLaunch() && !MusicAccessibilityService.isEnabled(this)
        setContent {
            val appearance by AppearanceStore.settings.collectAsState()
            // 主题不再吃外观设置：容器半透明固定（见 Theme.kt），
            // appearance 这里只驱动飘雪动效。
            LuoXianLvTheme {
                if (!disclaimerAccepted) {
                    DisclaimerScreen(
                        text = disclaimerText,
                        onAgree = {
                            DisclaimerStore(this).markAgreed(disclaimerSha)
                            disclaimerAccepted = true
                            checkUpdatesAfterDisclaimer()
                        },
                        onDecline = ::finishAffinity,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 页面底色由各页自己铺的渐变底承担（见 ui/theme/Backdrop.kt）。
                        AppNavHost(appUpdates)
                        // 飘雪盖在最上层：Canvas 不消费触摸，不会挡住底下的按钮与列表。
                        Snowfall(enabled = appearance.snowEnabled)
                        if (showOnboarding) {
                            OnboardingDialog(
                                onEnable = {
                                    markOnboardingDone()
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onLater = ::markOnboardingDone,
                            )
                        }
                        if (showBatteryPrompt) {
                            BatteryExemptionDialog(
                                onAllow = {
                                    markBatteryAsked()
                                    KeepAlive.requestBatteryExemption(this@MainActivity)
                                    showBatteryPrompt = false
                                },
                                onLater = {
                                    markBatteryAsked()
                                    showBatteryPrompt = false
                                },
                            )
                        }
                    }
                }
            }
        }
        finishShushuIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        finishShushuIntent(intent)
    }

    private fun finishShushuIntent(intent: Intent) {
        if (intent.data?.scheme != "luoxianlv") return
        oauthUpdater.finishShushuLogin(intent) { result ->
            result.onSuccess { SessionStore(this).save(it.session) }
        }
    }

    private val appPrefs by lazy { Kv.of(this, "app_state") }

    private fun isFirstLaunch(): Boolean = !appPrefs.getBoolean("onboarding_done", false)

    private fun markOnboardingDone() {
        appPrefs.edit().putBoolean("onboarding_done", true).apply()
        showOnboarding = false
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33 && repository.floatingEnabled &&
            MusicAccessibilityService.isEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !appPrefs.getBoolean("notification_permission_asked", false)
        ) {
            appPrefs.edit().putBoolean("notification_permission_asked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1201)
        }
        // 无障碍服务可能在本应用暂停期间被启用；回到前台时按持久化偏好重新对齐悬浮窗
        MusicAccessibilityService.instance?.showFloating(repository.floatingEnabled)
        if (disclaimerAccepted) {
            if (!updateCheckOnOpenDone) {
                checkUpdatesAfterDisclaimer()
            } else {
                appUpdates.onResume(this)
            }
        }
        requestBatteryExemptionOnce()
    }

    /** 每次打开应用且已读完免责声明后立即检查一次，后续前台恢复走节流检查。 */
    private fun checkUpdatesAfterDisclaimer() {
        if (updateCheckOnOpenDone || !disclaimerAccepted) return
        // 用户关了「自动检查更新」：启动不再请求更新服务，关于页仍可手动检查。
        if (!UpdateAutoCheck.isEnabled(this)) return
        updateCheckOnOpenDone = true
        appUpdates.check(manual = true)
    }

    /** 无障碍开启后，引导一次「忽略电池优化」：防 Doze/OEM 后台清理把服务和悬浮窗杀掉。
     * 先弹应用内说明再跳系统授权页，只问一次；入口常驻在设置 → 后台运行保护。 */
    private fun requestBatteryExemptionOnce() {
        if (!MusicAccessibilityService.isEnabled(this)) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (appPrefs.getBoolean("battery_exemption_asked", false)) return
        showBatteryPrompt = true
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
            Text(
                "自动演奏依赖无障碍服务和悬浮窗长期在后台运行。" +
                    "系统省电策略可能会在切后台或息屏后杀掉它们，导致权限反复丢失、悬浮窗消失。" +
                    "请把落弦律加入电池优化白名单；" +
                    "红魔、小米等手机还需在「设置 → 后台运行保护」里允许自启动。",
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("去允许") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("以后再说") }
        },
    )
}
