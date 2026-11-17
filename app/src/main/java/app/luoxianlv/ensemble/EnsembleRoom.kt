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
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                        room.phase == "idle" &&
                            room.members.size >= 2 &&
                            room.readyCount == room.members.size -> "全员已准备"
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
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            EnsembleRoomScore(room, actions.selectScore)
        }
    }
}

@Composable
private fun EnsembleRoomScore(room: EnsembleRoomUiState, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        if (room.isOwner) {
            TextButton(onClick = onChoose, enabled = room.chooseScore.enabled) { Text("选择曲目") }
        }
    }
}

@Composable
internal fun EnsembleMembersCard(room: EnsembleRoomUiState, actions: EnsembleActions) {
    var removing by rememberSaveable(room.id, room.isOwner) { mutableStateOf<String?>(null) }
    SettingsCard {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val role =
                listOfNotNull(if (member.isOwner) "主控" else null, if (member.isSelf) "我" else null)
                    .joinToString(" · ")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    member.nickname,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (role.isNotEmpty())
                    Text(
                        role,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !member.online -> "已离线"
                    member.waitingForStart && room.pendingRejoin -> "等待接入"
                    member.waitingForStart && room.phase == "proposed" -> "确认中"
                    member.waitingForStart -> "等待开始"
                    member.playing -> "演奏中"
                    member.ready -> "已准备"
                    else -> "未准备"
                },
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (member.online && (member.ready || member.playing))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
            } else if (room.isOwner) Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
internal fun EnsembleRoomControls(
    room: EnsembleRoomUiState,
    state: EnsembleController.ViewState,
    actions: EnsembleActions,
) {
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${room.readyCount}/${room.members.size} 已准备",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "进入游戏后，通过悬浮窗演奏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = actions.inputSetup, enabled = !state.preparing && !state.working) {
                Text("启动悬浮窗")
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
