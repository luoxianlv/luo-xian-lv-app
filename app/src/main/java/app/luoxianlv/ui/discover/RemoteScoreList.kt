package app.luoxianlv.ui.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.components.RemoteScoreRow
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.update.PlatformScore

/** 触底提前量（px）：约两行的高度，滑到这里就先追加下一批，避免到底后空滑一帧。 */
private const val LOAD_MORE_THRESHOLD_PX = 400

/**
 * 发现 / 搜索 / 平台三页共用的平台谱子列表。
 *
 * 与曲库歌曲列表同一套连排语言：整列一张卡，内部零分隔，行与行直接相邻。
 * 数据服务端一次返回、内存持有，这里一次只组合前 [RemoteUiState.visibleCount] 行，
 * 滑近底部自动放大渲染窗口，数据量大时进页面不再一次渲染全部行。
 */
@Composable
fun RemoteScoreList(
    state: RemoteUiState,
    onDownload: (PlatformScore) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val listState = rememberLazyListState()
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.offset + last.size <= info.viewportEndOffset + LOAD_MORE_THRESHOLD_PX
        }
    }
    LaunchedEffect(nearBottom) { if (nearBottom) onLoadMore() }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        val visible = state.visibleScores
        if (visible.isNotEmpty()) {
            item {
                SettingsCard {
                    Column {
                        visible.forEach { remote ->
                            RemoteScoreRow(
                                remote = remote,
                                downloading = remote.id in state.downloading,
                                onDownload = { onDownload(remote) },
                            )
                        }
                        if (state.hasMore) {
                            Text(
                                "上滑加载更多",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
