package app.luoxianlv.ensemble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.luoxianlv.business.ui.PageAlertDialog
import app.luoxianlv.library.Song
import app.luoxianlv.library.SongRepository
import app.luoxianlv.shared.GroupCardCornerRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EnsembleScorePicker(
    onDismiss: () -> Unit,
    onSelect: (Song, Float, Int?) -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var transpose by rememberSaveable { mutableStateOf<Int?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var transposeMenu by remember { mutableStateOf(false) }
    val midiSources = remember { mutableStateMapOf<String, Boolean>() }
    val screenHeight =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val contentHeight = (screenHeight - 220.dp).coerceIn(140.dp, 460.dp)
    val menuHeight = (screenHeight * .4f).coerceAtMost(260.dp)

    LaunchedEffect(context, attempt) {
        songs = null
        failed = false
        try {
            songs = withContext(Dispatchers.IO) { SongRepository(context).songs() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    val selected = songs?.firstOrNull { it.id == selectedId }
    val midiAvailable = selected?.let { if (it.remoteId.isNotBlank()) true else midiSources[it.id] }
    LaunchedEffect(context, selected?.id) {
        val song = selected ?: return@LaunchedEffect
        if (song.remoteId.isBlank() && song.id !in midiSources) {
            midiSources[song.id] =
                withContext(Dispatchers.IO) {
                    // The MIDI label alone does not mean an older cached score kept its source.
                    runCatching { EnsembleSources(context).read(song.id) != null }
                        .getOrDefault(false)
                }
        }
    }
    val settingsLabel =
        "速度 ${speedLabel(speed)}× · " +
            when (midiAvailable) {
                true -> "MIDI 移调 ${transposeLabel(transpose)}"
                false -> "原谱音高"
                null -> "正在检查 MIDI"
            }
    val visibleSongs =
        remember(songs, query) {
            val keyword = query.trim()
            songs.orEmpty().filter {
                keyword.isEmpty() || it.title.contains(keyword, ignoreCase = true)
            }
        }

    PageAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择曲目")
                if (selected != null) {
                    Text(
                        "已选：${selected.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        settingsLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let {
                        keyboard?.hide()
                        onSelect(it, speed, if (midiAvailable == true) transpose else null)
                    }
                },
                enabled = selected != null && midiAvailable != null,
                modifier =
                    Modifier.semantics {
                        stateDescription =
                            selected?.let { "${it.title}，$settingsLabel" } ?: "请先选择曲目"
                    },
            ) {
                Text("使用曲目")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            // Keep the actions outside this scroll area, even with a keyboard or a short window.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = contentHeight).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索本地曲目") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "options") {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stackOptions =
                            maxWidth < 240.dp || LocalDensity.current.fontScale > 1.15f
                        val speedOption: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                TextButton(
                                    onClick = { speedMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("速度 ${speedLabel(speed)}×")
                                }
                                DropdownMenu(
                                    speedMenu,
                                    { speedMenu = false },
                                    modifier = Modifier.heightIn(max = menuHeight),
                                ) {
                                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                                        DropdownMenuItem(
                                            text = { Text("${speedLabel(value)}×") },
                                            trailingIcon = {
                                                if (speed == value) Icon(Icons.Filled.Check, "当前速度")
                                            },
                                            onClick = {
                                                speed = value
                                                speedMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        val transposeOption: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                TextButton(
                                    onClick = { transposeMenu = true },
                                    enabled = midiAvailable == true,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (midiAvailable == false) "MIDI 移调不可用"
                                        else "MIDI 移调 ${transposeLabel(transpose)}"
                                    )
                                }
                                DropdownMenu(
                                    transposeMenu,
                                    { transposeMenu = false },
                                    modifier = Modifier.heightIn(max = menuHeight),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("自动") },
                                        trailingIcon = {
                                            if (transpose == null) Icon(Icons.Filled.Check, "当前移调")
                                        },
                                        onClick = {
                                            transpose = null
                                            transposeMenu = false
                                        },
                                    )
                                    (-12..12).forEach { value ->
                                        DropdownMenuItem(
                                            text = { Text(transposeLabel(value)) },
                                            trailingIcon = {
                                                if (transpose == value)
                                                    Icon(Icons.Filled.Check, "当前移调")
                                            },
                                            onClick = {
                                                transpose = value
                                                transposeMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (stackOptions) {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                speedOption(Modifier.fillMaxWidth())
                                transposeOption(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                speedOption(Modifier.weight(1f))
                                transposeOption(Modifier.weight(1f))
                            }
                        }
                    }
                    if (midiAvailable == false)
                        Text("此曲目没有原始 MIDI，使用原谱音高", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    failed ->
                        item {
                            PickerNotice("曲库读取失败") {
                                TextButton(onClick = { attempt++ }) { Text("重试") }
                            }
                        }
                    songs == null ->
                        item {
                            PickerNotice("正在读取曲库") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    songs.orEmpty().isEmpty() -> item { PickerNotice("曲库中还没有曲目") }
                    visibleSongs.isEmpty() ->
                        item {
                            PickerNotice("没有找到匹配的曲目") {
                                TextButton(onClick = { query = "" }) { Text("清除搜索") }
                            }
                        }
                    else ->
                        items(visibleSongs, key = { "song:${it.id}" }) { song ->
                            val checked = song.id == selectedId
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(GroupCardCornerRadius))
                                        .background(
                                            if (checked)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .selectable(
                                            selected = checked,
                                            onClick = {
                                                if (selectedId != song.id) {
                                                    transpose = null
                                                    transposeMenu = false
                                                }
                                                selectedId = song.id
                                                keyboard?.hide()
                                            },
                                            role = Role.RadioButton,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(checked, onClick = null)
                                Column(
                                    Modifier.weight(1f).padding(start = 8.dp, end = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight =
                                            if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                        color =
                                            if (checked)
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (song.isMidi) "${song.bpm} BPM · MIDI"
                                        else "${song.bpm} BPM",
                                        style = MaterialTheme.typography.labelSmall,
                                        color =
                                            if (checked)
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                }
            }
        },
    )
}

@Composable
private fun PickerNotice(message: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

private fun speedLabel(speed: Float): String = speed.toString().removeSuffix(".0")

private fun transposeLabel(transpose: Int?): String =
    when {
        transpose == null -> "自动"
        transpose == 0 -> "原调"
        transpose > 0 -> "+$transpose 半音"
        else -> "$transpose 半音"
    }
