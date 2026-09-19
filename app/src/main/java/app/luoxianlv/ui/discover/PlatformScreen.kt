package app.luoxianlv.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.RemoteScoreRow
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.ui.components.SnackbarNotice

/** 平台谱库页（独立页面，对应旧版 showPlatformPage；旧版此页无底部导航，新版保持一致）。 */
@Composable
fun PlatformScreen(
    onBack: () -> Unit,
    onDownloaded: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: DiscoverViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadPublic() }
    // 下载成功：提示 + 跳回曲库
    SnackbarNotice(state.downloaded?.let { "已下载 $it" }, snackbarHostState) {
        vm.ackDownloaded()
        onDownloaded()
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 8.dp, top = 4.dp, end = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "平台谱库",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索标题或标签") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.loadPublic(query) }),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
        )
        Text(
            state.status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, bottom = 16.dp),
        ) {
            // 与曲库歌曲列表同一套连排语言：整列一张卡，内部零分隔，
            // 行与行直接相邻，靠行高与内容自然分格。
            if (state.scores.isNotEmpty()) {
                item {
                    SettingsCard {
                        Column {
                            state.scores.forEach { remote ->
                                RemoteScoreRow(
                                    remote = remote,
                                    downloading = remote.id in state.downloading,
                                    onDownload = { vm.download(remote) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ErrorDialogHost(state.error, vm::dismissError)
}
