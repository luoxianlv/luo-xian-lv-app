package app.luoxianlv.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.luoxianlv.ui.theme.OnBackdropContent

/**
 * 顶级页面大标题：标题 + 可选右侧计数/说明。
 * 我的 / 曲库 / 发现 / 设置共用同一套排版，避免各写一遍字号与对齐。
 */
@Composable
fun PageTitle(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            // 深藏青：页面标题现在压在蓝灰渐变底上，浅灰会糊；
            // 发现页没有渐变底，这个颜色在浅灰背景上同样能看。
            color = OnBackdropContent,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = OnBackdropContent.copy(alpha = 0.72f),
            )
        }
    }
}
