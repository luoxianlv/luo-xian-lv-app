package app.luoxianlv.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.luoxianlv.update.PlatformScore

/**
 * 发现 / 搜索 / 平台三页共用的平台谱子列表。
 *
 * 与曲库歌曲列表同一套连排语言：整列一张卡，内部零分隔，行与行直接相邻。
 * 每首曲目是独立的惰性列表项，接近末尾时消费预取缓存。
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
            last.index >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearBottom, state.visibleCount, state.loadingMore, state.error) {
        if (nearBottom && state.error == null && (!state.loadingMore || state.visibleCount < state.scores.size)) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        val visible = state.visibleScores
        if (visible.isNotEmpty()) {
            items(visible, key = { it.id }) { remote ->
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
                    RemoteScoreRow(
                        remote = remote,
                        downloading = remote.id in state.downloading,
                        onDownload = { onDownload(remote) },
                    )
                }
            }
            if (state.hasMore) {
                item {
                    Text(
                        if (state.error != null) "加载失败，点击重试" else if (state.loadingMore && state.visibleCount >= state.scores.size) "正在加载…" else "加载更多",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { onLoadMore() }.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
