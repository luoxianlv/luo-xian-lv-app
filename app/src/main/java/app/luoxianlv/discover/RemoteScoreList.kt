package app.luoxianlv.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import app.luoxianlv.platform.PlatformScore
import app.luoxianlv.shared.GroupCardCornerRadius

/**
 * 发现、搜索和平台页共用的惰性曲目列表，接近末尾时消费预取缓存。 首末行分别绘制上下圆角，中间直角，保持连续卡片外观及独立回收；不套整列 Card，避免短列表出现多余底色。
 * 相邻半透明行不重叠，圆角统一使用 [GroupCardCornerRadius]。
 */
@Composable
fun RemoteScoreList(
    state: RemoteUiState,
    onDownload: (PlatformScore) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: LazyListScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearBottom, state.visibleCount, state.loadingMore, state.loadMoreFailed) {
        if (nearBottom && state.canAutoLoadMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        header()
        val visible = state.visibleScores
        if (visible.isNotEmpty()) {
            itemsIndexed(
                visible,
                key = { _, remote -> remote.id },
                contentType = { _, _ -> "score" },
            ) { index, remote ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    // 首行两个上圆角、末行两个下圆角，其余角为 0 与相邻行拼平；
                    // 只有一行时四个角同时成立，正好是一张完整的小卡。
                    shape =
                        RoundedCornerShape(
                            topStart = if (index == 0) GroupCardCornerRadius else 0.dp,
                            topEnd = if (index == 0) GroupCardCornerRadius else 0.dp,
                            bottomStart =
                                if (index == visible.lastIndex) GroupCardCornerRadius else 0.dp,
                            bottomEnd =
                                if (index == visible.lastIndex) GroupCardCornerRadius else 0.dp,
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
                item(key = "load-more", contentType = "footer") {
                    Text(
                        if (state.loadMoreFailed && state.visibleCount >= state.scores.size) {
                            "加载失败，点击重试"
                        } else if (state.loadingMore && state.visibleCount >= state.scores.size) {
                            "正在加载…"
                        } else {
                            "加载更多"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onLoadMore() }
                                .padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
