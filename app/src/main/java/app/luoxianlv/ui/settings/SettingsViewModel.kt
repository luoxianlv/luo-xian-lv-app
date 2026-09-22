package app.luoxianlv.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.luoxianlv.core.Analytics
import app.luoxianlv.data.AccountSession
import app.luoxianlv.data.AppearanceSettings
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.data.ConfigStore
import app.luoxianlv.data.KeyLayout
import app.luoxianlv.data.SessionStore
import app.luoxianlv.data.ThemeMode
import app.luoxianlv.service.KeepAlive
import app.luoxianlv.service.KeepAliveStatus
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.update.UpdateAutoCheck
import app.luoxianlv.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val session: AccountSession? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val keepAlive: KeepAliveStatus = KeepAliveStatus(false, false, true),
    /** 「自动检查更新」开关：默认开，读自 app_updates 存储。 */
    val autoUpdate: Boolean = true,
)

class SettingsViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    private val sessionStore = SessionStore(app)
    private val updater = UpdateManager(app)
    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() =
        _state.update {
            it.copy(
                session = sessionStore.current(),
                appearance = AppearanceStore.load(app),
                keepAlive = KeepAlive.status(app),
                autoUpdate = UpdateAutoCheck.isEnabled(app),
            )
        }

    fun login(
        account: String,
        password: String,
    ) {
        if (account.isBlank() || password.isBlank()) return
        _state.update { it.copy(busy = true) }
        updater.login(account.trim(), password) { result ->
            _state.update { it.copy(busy = false) }
            result
                .onSuccess { login ->
                    sessionStore.save(login.session)
                    Analytics.logEvent(app, "login_success") // 埋点：邮箱登录成功
                    refresh()
                    _state.update { it.copy(message = "登录成功") }
                }.onFailure { e -> _state.update { it.copy(error = e.message ?: "登录失败") } }
        }
    }

    fun logout() {
        sessionStore.clear()
        refresh()
    }

    /** 开关飘雪：直接改偏好并落盘。 */
    fun setSnowEnabled(enabled: Boolean) {
        val next = _state.value.appearance.copy(snowEnabled = enabled)
        AppearanceStore.save(app, next)
        _state.update { it.copy(appearance = next) }
    }

    /**
     * 切换深浅色模式。
     *
     * 只写偏好即可：`MainActivity` 收着 `AppearanceStore.settings` 这个 Flow，
     * 存盘时发的值会直接驱动主题重组，不需要这里再通知 Activity。
     */
    fun setThemeMode(mode: ThemeMode) {
        val next = _state.value.appearance.copy(themeMode = mode)
        AppearanceStore.save(app, next)
        _state.update { it.copy(appearance = next) }
        // 悬浮窗是服务里的独立窗口，不像 Compose 那样跟着偏好流重组，单独通知一次。
        MusicAccessibilityService.instance?.refreshFloatingTheme()
    }

    /** 开关自动检查更新：直接改偏好并落盘。 */
    fun setAutoUpdate(enabled: Boolean) {
        UpdateAutoCheck.setEnabled(app, enabled)
        _state.update { it.copy(autoUpdate = enabled) }
    }

    fun loadCalibration(): KeyLayout = ConfigStore.load(app)

    /** 保存校准并热加载到服务；返回是否成功。 */
    fun saveCalibration(layout: KeyLayout): Boolean =
        runCatching {
            ConfigStore.save(app, layout)
            MusicAccessibilityService.instance?.reloadConfig()
        }.isSuccess

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
