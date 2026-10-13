package app.luoxianlv.ensemble

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog
import app.luoxianlv.library.Song
import app.luoxianlv.library.SongRepository
import app.luoxianlv.platform.SessionStore
import app.luoxianlv.shared.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

@Composable
fun EnsembleScreen(onBack: () -> Unit, onLogin: () -> Unit, onInputSetup: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { EnsembleSession.current }
    val state =
        controller?.state?.collectAsStateWithLifecycle()?.value
            ?: EnsembleController.ViewState(supported = false)
    var code by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(start = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("多人合奏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.supported) {
                SettingsCard { Text("多人合奏尚未开放", Modifier.padding(20.dp)) }
            } else if (SessionStore(context).current() == null) {
                SettingsCard {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("登录后，和朋友一起演奏")
                        Button(onClick = onLogin) { Text("登录") }
                    }
                }
            } else {
                val connected = state.connection == "connected" && state.account.isNotEmpty()
                if (!connected) Text("正在连接…", style = MaterialTheme.typography.bodyMedium)
                val room = state.room
                if (room == null) {
                    SettingsCard {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("由主控选谱，最多六人", style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { controller?.create() },
                                enabled = connected && !state.working,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("创建房间")
                            }
                            OutlinedTextField(
                                code,
                                { code = it.filter(Char::isDigit).take(6) },
                                label = { Text("六位房间码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = { controller?.join(code) },
                                enabled = connected && !state.working && code.length == 6,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("加入房间")
                            }
                        }
                    }
                } else {
                    val owner = room.getString("owner") == state.account
                    val phase = room.getString("phase")
                    val members = room.getJSONArray("members")
                    val allReady =
                        members.length() >= 2 &&
                            (0 until members.length()).all {
                                members.getJSONObject(it).let { member ->
                                    member.getBoolean("online") &&
                                        member.optJSONObject("ready") != null
                                }
                            }
                    SettingsCard {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "房间 ${room.getString("code")}",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                room.optJSONObject("score")?.optString("title") ?: "还未选择曲目",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                when (phase) {
                                    "playing" -> "演奏中"
                                    "paused" -> "已暂停"
                                    "proposed" -> "正在确认开始"
                                    else -> "等待准备"
                                }
                            )
                            if (owner)
                                OutlinedButton(
                                    onClick = { choosing = true },
                                    enabled = !state.working && phase in listOf("idle", "paused"),
                                ) {
                                    Text("选择曲目")
                                }
                        }
                    }
                    SettingsCard {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val members = room.getJSONArray("members")
                            repeat(members.length()) { index ->
                                MemberRow(
                                    members.getJSONObject(index),
                                    room,
                                    owner,
                                    state.working,
                                    onAssign = { account, track ->
                                        controller?.assign(account, track)
                                    },
                                    onKick = { controller?.control("kick", it) },
                                    onRejoin = { controller?.control("rejoin", it) },
                                )
                            }
                        }
                    }
                    SettingsCard {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "开启悬浮窗后可在后台等待。进入游戏点击准备，由主控同步开始。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = onInputSetup) { Text("启动悬浮窗") }
                            if (owner) {
                                Button(
                                    onClick = {
                                        controller?.control(
                                            if (phase == "paused") "resume" else "start"
                                        )
                                    },
                                    enabled =
                                        connected &&
                                            allReady &&
                                            !state.working &&
                                            phase in listOf("idle", "paused"),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (phase == "paused") "继续合奏" else "开始合奏")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { controller?.control("pause") }) {
                                        Text("全员暂停")
                                    }
                                    TextButton(
                                        onClick = { controller?.control("restart") },
                                        enabled =
                                            connected &&
                                                allReady &&
                                                !state.working &&
                                                phase in listOf("idle", "paused"),
                                    ) {
                                        Text("重新开始")
                                    }
                                }
                            }
                            if (state.ready)
                                TextButton(onClick = { controller?.stopLocal(true) }) {
                                    Text("取消我的准备")
                                }
                            TextButton(onClick = { controller?.synchronize() }) { Text("重新同步") }
                            TextButton(
                                onClick = { controller?.leave() },
                                enabled = !state.working,
                            ) {
                                Text(if (owner) "结束房间" else "退出房间")
                            }
                        }
                    }
                }
            }
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.message.isNotBlank())
                Text(state.message, color = MaterialTheme.colorScheme.error)
        }
    }
    if (choosing)
        ScoreDialog({ choosing = false }) { song, speed, transpose ->
            choosing = false
            controller?.choose(song, speed, transpose)
        }
}

@Composable
private fun MemberRow(
    member: JSONObject,
    room: JSONObject,
    owner: Boolean,
    working: Boolean,
    onAssign: (String, Int) -> Unit,
    onKick: (String) -> Unit,
    onRejoin: (String) -> Unit,
) {
    val account = member.getString("account")
    val isOwner = account == room.getString("owner")
    var expanded by remember(account) { mutableStateOf(false) }
    Column {
        Text(
            member.getString("nickname") + if (isOwner) " · 主控" else "",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (!member.getBoolean("online")) "已离线"
                else if (member.optBoolean("playing")) "演奏中"
                else if (member.optJSONObject("ready") != null) "已就绪" else "未准备",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            val tracks = room.optJSONObject("score")?.optJSONArray("tracks")
            if (tracks != null)
                Box {
                    TextButton(
                        onClick = { expanded = true },
                        enabled =
                            owner &&
                                !working &&
                                room.getString("phase") in listOf("idle", "paused"),
                    ) {
                        Text(tracks.getJSONObject(member.getInt("track")).getString("name"))
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        repeat(tracks.length()) { index ->
                            DropdownMenuItem(
                                text = { Text(tracks.getJSONObject(index).getString("name")) },
                                onClick = {
                                    expanded = false
                                    onAssign(account, index)
                                },
                            )
                        }
                    }
                }
            if (owner && !isOwner) {
                if (
                    room.getString("phase") == "playing" &&
                        !member.optBoolean("playing") &&
                        member.optJSONObject("ready") != null
                )
                    TextButton(onClick = { onRejoin(account) }, enabled = !working) { Text("接入") }
                TextButton(onClick = { onKick(account) }, enabled = !working) { Text("移出") }
            }
        }
    }
}

@Composable
private fun ScoreDialog(onDismiss: () -> Unit, onSelect: (Song, Float, Int?) -> Unit) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var transpose by remember { mutableStateOf<Int?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var transposeMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { songs = withContext(Dispatchers.IO) { SongRepository(context).songs() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择曲目") },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onSelect(it, speed, transpose) } },
                enabled = selected != null,
            ) {
                Text("使用曲目")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column {
                Row {
                    Box {
                        TextButton(onClick = { speedMenu = true }) { Text("速度 $speed×") }
                        DropdownMenu(speedMenu, { speedMenu = false }) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                                DropdownMenuItem(
                                    text = { Text("$value×") },
                                    onClick = {
                                        speed = value
                                        speedMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { transposeMenu = true }) {
                            Text("MIDI 移调 ${transpose?.toString() ?: "自动"}")
                        }
                        DropdownMenu(
                            transposeMenu,
                            { transposeMenu = false },
                            modifier = Modifier.heightIn(max = 260.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("自动") },
                                onClick = {
                                    transpose = null
                                    transposeMenu = false
                                },
                            )
                            (-12..12).forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(if (value > 0) "+$value" else "$value") },
                                    onClick = {
                                        transpose = value
                                        transposeMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                LazyColumn(Modifier.height(280.dp)) {
                    if (songs == null) item { CircularProgressIndicator() }
                    items(songs.orEmpty(), key = { it.id }) { song ->
                        TextButton(
                            onClick = { selected = song },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(selected?.id == song.id, onClick = null)
                            Text(song.title)
                        }
                    }
                }
            }
        },
    )
}
