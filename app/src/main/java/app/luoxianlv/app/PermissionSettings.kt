package app.luoxianlv.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.luoxianlv.business.ui.LocalPageVisible
import app.luoxianlv.hot.contract.PlatformApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 公开系统设置入口；定位和高亮由系统处理，不使用无障碍替用户点击。 */
object PermissionSettings {
    private const val ARG_KEY = ":settings:fragment_args_key"
    private const val ARGUMENTS = ":settings:show_fragment_args"

    fun overlayGranted(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(PlatformApplication.of(context))
    }
        .getOrDefault(false)

    fun overlay(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11 起权限列表忽略 package URI，改为定位本应用并请求系统高亮；部分系统会忽略高亮。
            appInfo(context, "system_alert_window")
        } else overlayList(context)

    fun overlayList(context: Context): Intent =
        highlight(
            Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${appPackage(context)}"),
                )
                .putExtra(Settings.EXTRA_APP_PACKAGE, appPackage(context)),
            appPackage(context),
        )

    fun accessibility(context: Context): Intent =
        highlight(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            ComponentName(appPackage(context), "app.luoxianlv.service.MusicAccessibilityService")
                .flattenToString(),
        )

    fun appInfo(context: Context, preference: String? = null): Intent =
        Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${appPackage(context)}"),
            )
            .putExtra(Settings.EXTRA_APP_PACKAGE, appPackage(context))
            .let { if (preference == null) it else highlight(it, preference) }

    fun highlight(intent: Intent, preference: String): Intent =
        intent
            .putExtra(ARG_KEY, preference)
            .putExtra(ARGUMENTS, Bundle().apply { putString(ARG_KEY, preference) })

    private fun appPackage(context: Context) = PlatformApplication.of(context).packageName
}

/** 授权页返回立即刷新；仅在当前页面前台可见时查询系统权限。 */
@Composable
internal fun rememberOverlayPermissionGranted(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible = LocalPageVisible.current
    var granted by remember { mutableStateOf(false) }
    LaunchedEffect(context, lifecycle, visible) {
        if (visible)
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    granted =
                        withContext(Dispatchers.IO) { PermissionSettings.overlayGranted(context) }
                    delay(1500)
                }
            }
    }
    return granted
}
