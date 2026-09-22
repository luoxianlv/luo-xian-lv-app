package app.luoxianlv.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.components.GroupCardCornerRadius
import app.luoxianlv.ui.components.RemoteScoreRow
import app.luoxianlv.update.PlatformScore

/**
 * 发现 / 搜索 / 平台三页共用的平台谱子列表。
 *
 * 与曲库歌曲列表同一套连排语言：整列一张卡，内部零分隔，行与行直接相邻。
 * 每首曲目是独立的惰性列表项，接近末尾时消费预取缓存。
 *
 * 「一张卡」是**按行拼**出来的：首行只给上圆角、末行只给下圆角、中间保持直角，
 * 而不是把整列包进一个 `Card`。理由有两条：
 * 1. 每行都要能独立惰性回收，包 Card 就得让 `LazyColumn` 住在 Card 里，
 *    而 Card 会把它**内容之外**的剩余高度也铺上容器色 —— 列表短的时候卡片会拖出
 *    一大块空板子（曲库那张卡是「整列一个 item」，所以没这个问题，但也就没有惰性）。
 * 2. 半透明容器色逐行铺开与整块铺开在观感上没有差别：相邻行不重叠，不会叠色，
 *    只要圆角对得上，看起来就是同一张卡。
 *
 * 圆角取值与 `SettingsCard` 共用 [GroupCardCornerRadius]，两种拼法不会走偏。
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
            itemsIndexed(visible, key = { _, remote -> remote.id }) { index, remote ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    // 首行两个上圆角、末行两个下圆角，其余角为 0 与相邻行拼平；
                    // 只有一行时四个角同时成立，正好是一张完整的小卡。
                    shape =
                        RoundedCornerShape(
                            topStart = if (index == 0) GroupCardCornerRadius else 0.dp,
                            topEnd = if (index == 0) GroupCardCornerRadius else 0.dp,
                            bottomStart = if (index == visible.lastIndex) GroupCardCornerRadius else 0.dp,
                            bottomEnd = if (index == visible.lastIndex) GroupCardCornerRadius else 0.dp,
                        ),
                ) {
                    RemoteScoreRow(
                        remote = remote,
                        downloading = remote.id in state.downloading,
                        onDownload = { onDownload(remote) },
                    )
                }
            }
            if (state.hasMore) {
                // 这行刻意留在卡外：它是加载状态提示（可能变成「加载失败，点击重试」），
                // 不是列表内容。让它参与卡面的话，卡片下边缘会随加载态在中缝和底边之间跳。
                item {
                    Text(
                        if (state.error !=
                            null
                        ) {
                            "加载失败，点击重试"
                        } else if (state.loadingMore && state.visibleCount >= state.scores.size) {
                            "正在加载…"
                        } else {
                            "加载更多"
                        },
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
