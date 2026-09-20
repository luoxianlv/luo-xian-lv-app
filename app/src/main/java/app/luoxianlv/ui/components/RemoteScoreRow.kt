package app.luoxianlv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.theme.PlayerQqBlue
import app.luoxianlv.update.PlatformScore

/**
 * 平台乐谱行：发现 / 搜索 / 平台三个页面共用的连排列表行，**不带卡片外壳**。
 *
 * 整列由各自页面包成一张连续卡片（与曲库歌曲列表同一套语言），
 * 行与行直接相邻，靠行高与内容自然分格。
 * 行高与节奏对齐曲库 SongRow（64dp / 上下 8dp），
 * 尾部只留一枚 QQ 蓝下载图标（设置开关同款强调色），下载中换成转圈。
 */
@Composable
fun RemoteScoreRow(
    remote: PlatformScore,
    downloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(remote.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "${remote.author} · ${remote.bpm} BPM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        IconButton(onClick = onDownload, enabled = !downloading) {
            if (downloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = PlayerQqBlue)
            } else {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载 ${remote.title}",
                    tint = PlayerQqBlue,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
