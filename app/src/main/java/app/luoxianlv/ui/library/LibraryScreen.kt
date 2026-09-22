package app.luoxianlv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.data.Song
import app.luoxianlv.ui.components.ActionPill
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PageTitle
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.ui.components.SnackbarNotice
import app.luoxianlv.ui.components.SongRow
import app.luoxianlv.ui.theme.LocalBackdropPalette

/**
 * 曲库：谱面列表 + 筛选。
 *
 * 这个列表原本在「我的」页，「我的」改成首页式布局后移到这里，
 * 由「我的」左侧导航栏的「曲库」入口进入。导入入口也随之回到本页。
 */
@Composable
fun LibraryScreen(
    onImport: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: LibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Song?>(null) }
    var deleting by remember { mutableStateOf<Song?>(null) }

    SnackbarNotice(state.notice, snackbarHostState, vm::consumeNotice)

    Column(modifier = Modifier.fillMaxSize()) {
        PageTitle(
            title = "曲库",
            trailing = "${state.visibleSongs.size} 首",
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 与「我的」的「启动」同一套白色胶囊。
            // 原来用 FilledTonalButton，它的容器是浅蓝 secondaryContainer，
            // 压在同为蓝灰的渐变底上会糊成一片，白色才拉得开对比。
            ActionPill(
                label = "导入谱子",
                onClick = onImport,
                icon = Icons.Filled.FileUpload,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SongFilterRow(
            selected = state.filter,
            onSelect = vm::setFilter,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding =
                PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = NavBarClearance),
        ) {
            if (state.visibleSongs.isEmpty()) {
                item {
                    Text(
                        // 区分「一首都没有」与「这一类没有」，筛选后不再给出误导提示
                        if (state.hasAnySong) {
                            "没有${state.filter.label}类的谱面"
                        } else {
                            "还没有谱面，点上面的「导入谱子」添加吧"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // 所有曲目连成一张卡：内部零分隔（先试了居中细线，仍嫌线条多），
                // 行与行直接相邻，靠行高与内容自然分格；整列只有外轮廓一条边界。
                item {
                    SettingsCard {
                        Column {
                            state.visibleSongs.forEach { song ->
                                SongRow(
                                    song = song,
                                    selected = song.id == state.highlightedSongId,
                                    onClick = { vm.select(song) },
                                    onEdit = { editing = song },
                                    onDelete = if (song.builtIn) null else ({ deleting = song }),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { song ->
        EditSongDialog(
            song = song,
            onDismiss = { editing = null },
            onSave = { title, score ->
                if (vm.saveAs(song, title, score)) editing = null
            },
        )
    }
    deleting?.let { song ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${song.title}？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeSong(song)
                        deleting = null
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    ErrorDialogHost(state.error, vm::dismissError)
}

/** 编辑谱面 → 另存为（与旧版一致：不落盘覆盖，只新增）。 */
@Composable
private fun EditSongDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (title: String, score: String) -> Unit,
) {
    var title by remember { mutableStateOf(song.title) }
    var score by remember { mutableStateOf(song.score) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑谱面") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("曲名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = score,
                    onValueChange = { score = it },
                    label = { Text("谱面") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, score) }) { Text("另存为") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 谱面筛选：三种归类用 Material 的分段按钮。
 *
 * 原来用 `TabRow`：它是给页面级导航用的控件（自带指示条 + 一条贯穿整屏的分隔线），
 * 拿来做列表筛选既不是 Material 的用法，视觉上也与圆角卡片格格不入。
 */
@Composable
private fun SongFilterRow(
    selected: SongFilter,
    onSelect: (SongFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SongFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SongFilter.entries.size,
                    ),
                colors = filterSegmentColors(),
                // 等宽分配：三个标签长短不一，不 weight 会左对齐挤在一起
                modifier = Modifier.weight(1f),
                // 不显示默认的对勾：它会改变分段宽度，切换选中时整行会跳一下
                icon = {},
            ) { Text(filter.label) }
        }
    }
}

/**
 * 分段按钮配色。
 *
 * 三个都是被底色逼出来的选择：
 * 1. 未选中态必须给 surface 白底 —— Material 默认是透明色，
 *    透明容器直接压在渐变底上就只剩一圈细边框，看着像控件失效。
 * 2. 选中态用主题的 [LocalBackdropPalette.segmentSelected] 而不是 primaryContainer：
 *    primaryContainer 是浅蓝，压在同为蓝灰的渐变底上，
 *    和白底的未选中态几乎分不出来，选中的那一格会「消失」。
 *    这个块深浅两套各给一个值：浅色是半透明深藏青，
 *    深色反过来要比未选中的 surface 更亮才叫「选中」（见 Backdrop.kt）。
 * 3. 两态的边框全透明：outlineVariant 的灰框会让整行看起来像
 *    三个描边小盒拼在一起，和圆角卡片格格不入；去掉后整行融成
 *    一个圆角长条（选中格是唯一强调），分隔交给底色对比完成。
 */
@Composable
private fun filterSegmentColors(): SegmentedButtonColors =
    SegmentedButtonDefaults.colors(
        activeContainerColor = LocalBackdropPalette.current.segmentSelected,
        activeContentColor = Color.White,
        activeBorderColor = Color.Transparent,
        inactiveContainerColor = MaterialTheme.colorScheme.surface,
        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        inactiveBorderColor = Color.Transparent,
    )
