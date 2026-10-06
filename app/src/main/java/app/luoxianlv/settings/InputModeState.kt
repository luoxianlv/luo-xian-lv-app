package app.luoxianlv.settings

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.luoxianlv.app.rememberOverlayPermissionGranted
import app.luoxianlv.business.ui.LocalPageVisible
import app.luoxianlv.hot.contract.SharedInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class InputModeState(
    val supported: Boolean = false,
    val overlayGranted: Boolean = false,
    val mode: String = "accessibility",
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val binderReady: Boolean = false,
    val permissionGranted: Boolean = false,
    val permissionState: String = "",
    val connected: Boolean = false,
    val touchReady: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val wirelessSupported: Boolean = false,
    val notificationGranted: Boolean = false,
    val localNetworkGranted: Boolean = false,
    val wifiConnected: Boolean = false,
    val wirelessEnabled: Boolean = false,
    val pairingPort: Int = 0,
    val connectionPort: Int = 0,
    val busy: Boolean = false,
    val pairing: Boolean = false,
    val paired: Boolean = false,
    val message: String = "当前宿主暂不支持输入模式设置",
) {
    val label: String
        get() =
            when (mode) {
                "shizuku" -> "Shizuku"
                "wireless" -> "无线调试（内置）"
                else -> "无障碍"
            }

    val usable: Boolean
        get() = if (mode == "accessibility") accessibilityEnabled else connected && touchReady

    val displayMessage: String
        get() = message.replace("输入助手", "连接").replace("触控助手", "触控连接")

    val statusTitle: String
        get() =
            when {
                !supported -> "暂不可用"
                !overlayGranted -> "需要允许悬浮窗"
                usable -> "可以开始演奏"
                pairing -> "正在配对"
                busy -> "正在连接"
                mode == "shizuku" && !installed -> "需要安装 Shizuku"
                mode == "shizuku" && !binderAlive -> "需要启动 Shizuku"
                mode == "shizuku" && !binderReady -> "正在等待 Shizuku"
                mode == "shizuku" && permissionState == "unavailable" -> "授权状态暂未读到"
                mode == "shizuku" && !permissionGranted -> "需要授权"
                mode == "wireless" && !wirelessSupported -> "系统暂不支持"
                mode == "wireless" && !paired -> "首次使用需要配对"
                connected -> "触控检查未通过"
                mode == "accessibility" -> "需要开启无障碍"
                else -> "尚未连接"
            }

    val statusSummary: String
        get() =
            when {
                !supported -> message
                !overlayGranted -> "先允许显示在其他应用上方，再选择输入模式。"
                usable -> if (mode == "accessibility") "使用无障碍自动演奏" else "已连接，支持的手机可边操作边演奏"
                mode == "shizuku" && !busy && !installed -> "安装后，按 Shizuku 内的指引启动。"
                mode == "shizuku" && !busy && !binderAlive -> "打开 Shizuku，按其中的指引启动后再回来。"
                mode == "shizuku" && !busy && !binderReady -> "正在等待 Shizuku 完成连接，稍后会自动检查授权。"
                mode == "shizuku" && !busy && permissionState == "unavailable" ->
                    "暂时无法读取授权状态，请确认 Shizuku 正常运行后重新检查。"
                mode == "shizuku" && !busy && !permissionGranted -> "允许落弦律使用 Shizuku 后即可连接。"
                mode == "wireless" && !wirelessSupported -> "内置无线调试需要 Android 11 或更新版本。"
                mode == "wireless" && !busy && !paired ->
                    displayMessage.ifBlank { "按使用指引完成配对，无需安装其他应用。" }
                mode == "wireless" && !busy && !connected && !wifiConnected -> "先连接 Wi-Fi，再点连接。"
                mode == "wireless" && !busy && !connected && !wirelessEnabled ->
                    "开启无线调试后会自动连接，无需重新配对。"
                else -> displayMessage
            }

    val primaryAction: InputModeAction?
        get() =
            when {
                !supported || !overlayGranted || busy || pairing || usable -> null
                mode == "shizuku" && !installed -> InputModeAction("安装 Shizuku", "installShizuku")
                mode == "shizuku" && !binderAlive -> InputModeAction("打开 Shizuku", "openShizuku")
                mode == "shizuku" && (!binderReady || permissionState == "unavailable") ->
                    InputModeAction("重新检查", "refresh")
                mode == "shizuku" && !permissionGranted -> InputModeAction("授权并连接", "authorize")
                mode == "shizuku" -> InputModeAction(if (connected) "重新连接" else "连接", "connect")
                mode == "wireless" && !wirelessSupported -> null
                mode == "wireless" && !paired -> InputModeAction("配对并连接", "pairingGuide")
                mode == "wireless" && !connected && !wifiConnected ->
                    InputModeAction("连接 Wi-Fi", "wifiSettings")
                mode == "wireless" && !connected && !wirelessEnabled ->
                    InputModeAction("开启无线调试并连接", "connect")
                mode == "wireless" -> InputModeAction(if (connected) "重新连接" else "连接", "connect")
                !accessibilityEnabled -> InputModeAction("打开无障碍设置", "openSettings")
                else -> null
            }

    companion object {
        fun read(bridge: SharedInput.Bridge?): InputModeState {
            if (bridge == null) return InputModeState()
            return runCatching {
                val state = bridge.state()
                InputModeState(
                    supported = true,
                    mode = state.getString("mode").orEmpty().ifBlank { "accessibility" },
                    installed = state.getBoolean("installed"),
                    binderAlive = state.getBoolean("binderAlive"),
                    binderReady = state.getBoolean("binderReady", state.getBoolean("binderAlive")),
                    permissionGranted = state.getBoolean("permissionGranted"),
                    permissionState = state.getString("permissionState").orEmpty(),
                    connected = state.getBoolean("connected"),
                    touchReady = state.getBoolean("touchReady"),
                    accessibilityEnabled = state.getBoolean("accessibilityEnabled"),
                    wirelessSupported = state.getBoolean("wirelessSupported"),
                    notificationGranted = state.getBoolean("notificationGranted"),
                    localNetworkGranted = state.getBoolean("localNetworkGranted"),
                    wifiConnected = state.getBoolean("wifiConnected"),
                    wirelessEnabled = state.getBoolean("wirelessEnabled"),
                    pairingPort = state.getInt("pairingPort"),
                    connectionPort = state.getInt("connectionPort"),
                    busy = state.getBoolean("busy"),
                    pairing = state.getBoolean("pairing"),
                    paired = state.getBoolean("paired"),
                    message = state.getString("message").orEmpty(),
                )
            }
                .getOrElse { InputModeState(message = "输入服务暂不可用，请重新检查") }
        }
    }
}

internal data class InputModeAction(val label: String, val command: String)

internal fun currentInputBridge(): SharedInput.Bridge? = runCatching {
    SharedInput.current()
}
    .getOrNull()

internal fun inputCommand(action: String, arguments: Bundle = Bundle()) {
    runCatching { currentInputBridge()?.command(action, arguments) }
}

/** 查询只读内存快照；权限及设备检查由宿主异步执行。离开页面或进入后台即停止刷新。 */
@Composable
internal fun rememberInputModeState(): InputModeState {
    val visible = LocalPageVisible.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var bridge by remember { mutableStateOf(currentInputBridge()) }
    var state by remember { mutableStateOf(InputModeState.read(bridge)) }

    fun refresh() {
        bridge = currentInputBridge()
        runCatching { bridge?.command("refresh", Bundle()) }
        state = InputModeState.read(bridge)
    }

    DisposableEffect(lifecycleOwner, visible, bridge) {
        var subscription: AutoCloseable? = null
        fun observe() {
            if (!visible || subscription != null) return
            subscription =
                runCatching {
                    bridge?.observe { scope.launch { state = InputModeState.read(bridge) } }
                }
                    .getOrNull()
        }
        fun stop() {
            runCatching { subscription?.close() }
            subscription = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (visible) refresh()
                    observe()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) observe()
        onDispose {
            stop()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(lifecycleOwner, visible) {
        if (visible)
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(1_500)
                }
            }
    }
    return state.copy(overlayGranted = rememberOverlayPermissionGranted())
}
