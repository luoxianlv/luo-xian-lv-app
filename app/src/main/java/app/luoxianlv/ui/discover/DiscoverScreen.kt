package app.luoxianlv.ui.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PageTitle
import app.luoxianlv.ui.components.SnackbarNotice
import app.luoxianlv.ui.theme.containerElevation

/** 发现页：搜索入口 + 全部公开谱子。 */
@Composable
fun DiscoverScreen(
    onSearch: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: DiscoverViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadScores() }
    SnackbarNotice(state.downloaded?.let { "已加入曲库：$it" }, snackbarHostState, vm::ackDownloaded)

    // 标题与搜索入口共用一个滚动容器，小窗也能从页头直接滑动整页。
    RemoteScoreList(
        state = state,
        onDownload = vm::download,
        onLoadMore = vm::loadMore,
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = NavBarClearance),
        header = {
            item(key = "discover-title", contentType = "header") {
                PageTitle(
                    title = "发现",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item(key = "discover-search", contentType = "header") {
                Card(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    elevation = containerElevation(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text("搜索谱子", modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            item(key = "discover-section", contentType = "header") {
                Text(
                    "全部谱子",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                )
                Text(
                    state.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        },
    )

    ErrorDialogHost(state.error, vm::dismissError)
}
