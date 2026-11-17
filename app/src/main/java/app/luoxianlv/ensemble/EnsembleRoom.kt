package app.luoxianlv.ensemble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.luoxianlv.business.ui.PageAlertDialog
import app.luoxianlv.library.timeLabel
import app.luoxianlv.shared.SettingsCard
import app.luoxianlv.ui.theme.OnBackdropContent

@Composable
internal fun EnsembleRoomHeader(
    room: EnsembleRoomUiState,
    connected: Boolean,
    working: Boolean,
    actions: EnsembleActions,
) {
    val context = LocalContext.current
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "房间码",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EnsembleStatus(
                    when {
                        room.finished -> "已结束"
                        room.phase == "proposed" -> "正在确认开始"
                        room.pendingRejoin -> "同步接入中"
                        room.pendingStart -> "等待同步开始"
                        room.phase == "playing" -> "演奏中"
                        room.phase == "paused" -> "已暂停"
                        room.phase == "idle" -> "等待准备"
                        else -> "状态待同步"
                    },
                    active = room.phase == "playing" && room.members.any { it.playing },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.code,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                )
                IconButton(
                    onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("房间码", room.code))
                        Toast.makeText(context, "房间码已复制", Toast.LENGTH_SHORT).show()
                    },
                    enabled = room.code.length == 6,
                ) {
                    Icon(Icons.Filled.ContentCopy, "复制房间码")
                }
                IconButton(onClick = actions.synchronize, enabled = connected && !working) {
                    Icon(Icons.Filled.Refresh, "重新同步")
                }
            }
        }
    }
}

@Composable
internal fun EnsembleRoomScore(room: EnsembleRoomUiState, onChoose: () -> Unit) {
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                EnsembleIcon(Icons.Filled.MusicNote)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        room.scoreTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (room.hasScore) {
                        Text(
                            buildList {
                                    add("${room.tracks.size} 个声部")
                                    if (room.durationUs > 0) add(timeLabel(room.durationUs / 1000))
                                }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (room.phase == "paused" && !room.finished && room.positionUs > 0) {
                            Text(
                                "从 ${timeLabel(room.positionUs / 1000)} 继续",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (!room.isOwner)
                        Text(
                            "由主控选择曲目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
            if (room.isOwner) {
                OutlinedButton(
                    onClick = onChoose,
                    enabled = room.chooseScore.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.LibraryMusic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择曲目")
                }
            }
        }
    }
}

@Composable
internal fun EnsembleMembersCard(room: EnsembleRoomUiState, actions: EnsembleActions) {
    var removing by rememberSaveable(room.id, room.isOwner) { mutableStateOf<String?>(null) }
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "房间成员",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${room.members.size}/6",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            room.members.forEachIndexed { index, member ->
                if (index > 0)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)
                    )
                key(member.account) {
                    EnsembleMemberRow(member, room, actions) { removing = member.account }
                }
            }
            if (room.members.size < 6) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "分享房间码，邀请朋友加入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    val member = room.members.firstOrNull { it.account == removing }
    if (member == null && removing != null) {
        LaunchedEffect(removing) { removing = null }
    }
    if (member != null) {
        PageAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移出成员") },
            text = { Text("将 ${member.nickname} 移出房间？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        actions.control("kick", member.account)
                    },
                    enabled = member.kick.enabled,
                ) {
                    Text("移出")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EnsembleMemberRow(
    member: EnsembleMemberUiState,
    room: EnsembleRoomUiState,
    actions: EnsembleActions,
    onRemove: () -> Unit,
) {
    var tracksExpanded by remember(room.id, room.isOwner) { mutableStateOf(false) }
    var menuExpanded by remember(room.id, room.isOwner) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EnsembleIcon(if (member.isOwner) Icons.Filled.Headphones else Icons.Filled.Person)
        Column(Modifier.weight(1f)) {
            Text(
                member.nickname,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val role =
                listOfNotNull(if (member.isOwner) "主控" else null, if (member.isSelf) "我" else null)
                    .joinToString(" · ")
            if (role.isNotEmpty())
                Text(
                    role,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            if (room.hasScore) {
                if (room.isOwner) {
                    Box {
                        TextButton(
                            onClick = { tracksExpanded = true },
                            enabled = room.assign.enabled,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                member.trackName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(tracksExpanded, { tracksExpanded = false }) {
                            room.tracks.forEach { track ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            track.name,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingIcon = {
                                        if (member.trackIndex == track.index)
                                            Icon(Icons.Filled.Check, null)
                                    },
                                    onClick = {
                                        tracksExpanded = false
                                        actions.assign(member.account, track.index)
                                    },
                                    enabled = room.assign.enabled && track.valid,
                                )
                            }
                        }
                    }
                } else
                    Text(
                        member.trackName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            EnsembleStatus(
                when {
                    !member.online -> "已离线"
                    member.waitingForStart && room.pendingRejoin -> "等待接入"
                    member.waitingForStart && room.phase == "proposed" -> "确认中"
                    member.waitingForStart -> "等待开始"
                    member.playing -> "演奏中"
                    member.ready -> "已准备"
                    else -> "未准备"
                },
                active = member.online && (member.ready || member.playing),
            )
            if (room.isOwner && !member.isOwner) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, "管理${member.nickname}")
                    }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        if (room.phase == "playing" && !member.playing && !member.waitingForStart)
                            DropdownMenuItem(
                                text = { Text("重新接入") },
                                onClick = {
                                    menuExpanded = false
                                    actions.control("rejoin", member.account)
                                },
                                enabled = member.rejoin.enabled,
                            )
                        if (!member.isOwner)
                            DropdownMenuItem(
                                text = { Text("移出房间", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onRemove()
                                },
                                enabled = member.kick.enabled,
                            )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EnsembleRoomControls(
    room: EnsembleRoomUiState,
    state: EnsembleController.ViewState,
    actions: EnsembleActions,
) {
    val self = room.self
    val waitingForStart = state.ready && self?.waitingForStart == true
    SettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "演奏控制",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${room.readyCount}/${room.members.size} 已准备",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.ready || state.preparing) {
                OutlinedButton(
                    onClick = actions.stop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (state.preparing)
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.preparing) "取消准备"
                        else if (room.self?.playing == true) "停止我的演奏" else "取消我的准备"
                    )
                }
            } else {
                Button(
                    onClick = actions.prepare,
                    enabled = room.prepare.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("准备演奏")
                }
                if (!room.prepare.enabled && room.prepare.reason.isNotEmpty())
                    Text(
                        room.prepare.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (waitingForStart) {
                Text(
                    if (room.pendingRejoin) "等待接入" else "等待同步开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (
                room.isOwner &&
                    state.ready &&
                    room.phase == "playing" &&
                    self != null &&
                    !self.playing &&
                    !self.waitingForStart
            ) {
                FilledTonalButton(
                    onClick = { actions.control("rejoin", self.account) },
                    enabled = self.rejoin.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重新接入合奏")
                }
                if (!self.rejoin.enabled && self.rejoin.reason.isNotEmpty()) {
                    Text(
                        self.rejoin.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = actions.inputSetup,
                enabled = !state.preparing && !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("启动悬浮窗")
            }
            if (room.isOwner) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)
                )
                if (room.phase in setOf("playing", "proposed")) {
                    FilledTonalButton(
                        onClick = { actions.control("pause", null) },
                        enabled = room.pause.enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Filled.Pause, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (
                                room.phase == "proposed" ||
                                    (room.pendingStart && room.members.none { it.playing })
                            )
                                "取消开始"
                            else "全员暂停"
                        )
                    }
                } else {
                    val (start, command, label) =
                        when {
                            room.finished -> Triple(room.restart, "restart", "重新开始")
                            room.phase == "paused" -> Triple(room.resume, "resume", "继续合奏")
                            else -> Triple(room.start, "start", "开始合奏")
                        }
                    Button(
                        onClick = { actions.control(command, null) },
                        enabled = start.enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                    if (
                        !start.enabled &&
                            start.reason.isNotEmpty() &&
                            (state.ready || start.reason != room.prepare.reason)
                    )
                        Text(
                            start.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    if (room.phase == "paused" && !room.finished)
                        TextButton(
                            onClick = { actions.control("restart", null) },
                            enabled = room.restart.enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重新开始")
                        }
                }
            } else if (!waitingForStart && state.ready && room.phase in setOf("idle", "paused")) {
                Text(
                    "已准备，等待主控开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (
                !waitingForStart && state.ready && room.phase == "playing" && self?.playing != true
            ) {
                Text(
                    "已准备，等待主控接入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun EnsembleLeaveRoom(
    room: EnsembleRoomUiState,
    working: Boolean,
    connected: Boolean,
    onLeave: () -> Unit,
) {
    var confirming by rememberSaveable(room.id, room.isOwner) { mutableStateOf(false) }
    val label = if (room.isOwner) "结束房间" else "退出房间"
    TextButton(
        onClick = { confirming = true },
        enabled = connected && !working,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, color = OnBackdropContent)
    }
    if (confirming) {
        PageAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("${label}？") },
            text = { Text(if (room.isOwner) "结束后，所有成员都会离开房间。" else "退出后，将停止本机演奏。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onLeave()
                    },
                    enabled = connected && !working,
                ) {
                    Text(label, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
        )
    }
}
