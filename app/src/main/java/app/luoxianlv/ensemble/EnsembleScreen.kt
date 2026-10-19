package app.luoxianlv.ensemble

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.luoxianlv.platform.SessionStore
import app.luoxianlv.shared.ActionPill
import app.luoxianlv.shared.PageTitle
import app.luoxianlv.shared.SettingsCard
import app.luoxianlv.ui.theme.OnBackdropContent

internal object EnsembleNavigation {
    var requested by mutableStateOf(false)

    fun open(context: Context) {
        requested = true
        context.startActivity(
            Intent()
                .setClassName(context, "app.luoxianlv.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}

internal data class EnsembleActions(
    val back: () -> Unit,
    val login: () -> Unit,
    val inputSetup: () -> Unit,
    val create: () -> Unit,
    val join: (String) -> Unit,
    val selectScore: () -> Unit,
    val assign: (String, Int) -> Unit,
    val control: (String, String?) -> Unit,
    val prepare: () -> Unit,
    val stop: () -> Unit,
    val synchronize: () -> Unit,
    val leave: () -> Unit,
)

@Composable
fun EnsembleScreen(onBack: () -> Unit, onLogin: () -> Unit, onInputSetup: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { EnsembleSession.current }
    val state =
        controller?.state?.collectAsStateWithLifecycle()?.value
            ?: EnsembleController.ViewState(supported = false)
    val room = state.room
    var choosing by rememberSaveable(room?.optString("id"), state.account) { mutableStateOf(false) }
    val signedIn = SessionStore(context).current() != null
    val connected =
        signedIn &&
            state.supported &&
            !state.serviceUnavailable &&
            state.connection == "connected" &&
            state.account.isNotBlank()
    val canChoose =
        room != null &&
            EnsembleRoomUiState.parse(
                    room,
                    state.account,
                    connected,
                    state.working,
                    state.preparing,
                    state.ready,
                )
                .chooseScore
                .enabled
    EnsemblePage(
        state = state,
        signedIn = signedIn,
        actions =
            EnsembleActions(
                back = onBack,
                login = onLogin,
                inputSetup = onInputSetup,
                create = { controller?.create() },
                join = { controller?.join(it) },
                selectScore = { choosing = true },
                assign = { account, track -> controller?.assign(account, track) },
                control = { action, account -> controller?.control(action, account) },
                prepare = { controller?.prepare() },
                stop = { controller?.stopLocal(true) },
                synchronize = { controller?.synchronize() },
                leave = { controller?.leave() },
            ),
    )
    if (choosing && canChoose) {
        EnsembleScorePicker(onDismiss = { choosing = false }) { song, speed, transpose ->
            choosing = false
            controller?.choose(song, speed, transpose)
        }
    } else if (choosing) {
        LaunchedEffect(Unit) { choosing = false }
    }
}

/** Stateless rendering also allows room states to be checked without a live multiplayer session. */
@Composable
internal fun EnsemblePage(
    state: EnsembleController.ViewState,
    signedIn: Boolean,
    actions: EnsembleActions,
) {
    val connected =
        signedIn &&
            state.supported &&
            !state.serviceUnavailable &&
            state.connection == "connected" &&
            state.account.isNotBlank()
    val room =
        state.room?.let {
            EnsembleRoomUiState.parse(
                it,
                state.account,
                connected,
                state.working,
                state.preparing,
                state.ready,
            )
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = OnBackdropContent)
                }
                PageTitle("多人合奏", modifier = Modifier.weight(1f))
            }
        }
        if (state.working)
            item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!state.supported || !signedIn || !connected) {
            item(key = "connection") { EnsembleConnectionCard(state, signedIn, actions) }
        }
        if (state.message.isNotBlank()) {
            item(key = "message") {
                SettingsCard {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            state.message,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (connected)
                            IconButton(onClick = actions.synchronize, enabled = !state.working) {
                                Icon(Icons.Filled.Refresh, "重新同步")
                            }
                    }
                }
            }
        }
        if (room == null) {
            item(key = "create") { CreateRoomCard(connected && !state.working, actions.create) }
            item(key = "join") { JoinRoomCard(connected && !state.working, actions.join) }
        } else {
            item(key = "room") { EnsembleRoomHeader(room, connected, state.working, actions) }
            item(key = "score") { EnsembleRoomScore(room, actions.selectScore) }
            item(key = "controls") { EnsembleRoomControls(room, state, actions) }
            item(key = "members") { EnsembleMembersCard(room, actions) }
            item(key = "leave") { EnsembleLeaveRoom(room, state.working, connected, actions.leave) }
        }
    }
}

@Composable
private fun EnsembleConnectionCard(
    state: EnsembleController.ViewState,
    signedIn: Boolean,
    actions: EnsembleActions,
) {
    val connecting =
        state.supported &&
            !state.serviceUnavailable &&
            signedIn &&
            (state.connection in setOf("connecting", "backoff") ||
                (state.connection == "connected" && state.account.isBlank()))
    val label =
        when {
            state.serviceUnavailable -> "合奏服务尚未开放"
            !state.supported -> "多人合奏尚未开放"
            !signedIn -> "登录后，和朋友一起演奏"
            state.connection == "waiting_network" -> "网络已断开"
            state.connection == "unauthorized" -> "暂时无法连接合奏服务"
            state.connection == "backoff" -> "连接中断，正在重连"
            state.connection == "connecting" -> "正在连接合奏服务"
            state.connection == "connected" -> "正在同步账号"
            else -> "实时连接尚未建立"
        }
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (connecting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else
                    Icon(
                        if (!signedIn && !state.serviceUnavailable) Icons.AutoMirrored.Filled.Login
                        else Icons.Filled.CloudOff,
                        null,
                    )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            if (!signedIn && !state.serviceUnavailable) {
                ActionPill(
                    label = "登录",
                    onClick = actions.login,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Filled.Login,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun CreateRoomCard(enabled: Boolean, onCreate: () -> Unit) {
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EnsembleIcon(Icons.Filled.Groups)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "发起一场合奏",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "2–6 人 · 同谱演奏或分配声部",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onCreate,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("创建房间")
            }
        }
    }
}

@Composable
private fun JoinRoomCard(enabled: Boolean, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun join() {
        if (enabled && code.length == 6) {
            keyboard?.hide()
            onJoin(code)
        }
    }
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "加入朋友的房间",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(6) },
                label = { Text("六位房间码") },
                leadingIcon = { Icon(Icons.Filled.Tag, null) },
                trailingIcon = {
                    if (code.isNotEmpty())
                        IconButton(onClick = { code = "" }) { Icon(Icons.Filled.Close, "清空房间码") }
                },
                singleLine = true,
                enabled = enabled,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { join() }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = ::join,
                enabled = enabled && code.length == 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("加入房间")
            }
        }
    }
}

@Composable
internal fun EnsembleIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
internal fun EnsembleStatus(label: String, active: Boolean = false) {
    Surface(
        shape = CircleShape,
        color =
            if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (active) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
