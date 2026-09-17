package app.luoxianlv.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.luoxianlv.ui.components.RemoteScoreCard
import app.luoxianlv.ui.components.SnackbarNotice
import app.luoxianlv.ui.theme.containerBorder
import app.luoxianlv.ui.theme.containerElevation

/** 发现页：搜索入口 + 热门推荐。 */
@Composable
fun DiscoverScreen(
    onSearch: () -> Unit,
    onDownloaded: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: DiscoverViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadPopular() }
    // 下载成功：提示 + 跳回曲库
    SnackbarNotice(state.downloaded?.let { "已下载 $it" }, snackbarHostState) {
        vm.ackDownloaded()
        onDownloaded()
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
        PageTitle(
            title = "发现",
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Card(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            elevation = containerElevation(),
            border = containerBorder(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("搜索谱子", modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "热门推荐",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "曲库",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            state.status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 导航栏是叠层，这里自行留出它占的高度
            contentPadding = PaddingValues(bottom = NavBarClearance),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(state.scores, key = { it.id }) { remote ->
                RemoteScoreCard(
                    remote = remote,
                    downloading = remote.id in state.downloading,
                    onDownload = { vm.download(remote) },
                )
            }
        }
    }

    ErrorDialogHost(state.error, vm::dismissError)
}
