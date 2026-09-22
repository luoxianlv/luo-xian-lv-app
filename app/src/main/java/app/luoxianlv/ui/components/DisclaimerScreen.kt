package app.luoxianlv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.ui.theme.OnBackdropContent

// 判定"已读到底部"的剩余像素余量：到底前的吸附距离内都算已读，
// 避免最后一点内容被圆角/内边距遮住时按钮死活点不亮。
private const val BOTTOM_THRESHOLD_PX = 4

private val SECTION_HEADING = Regex("^[一二三四五六七八九十]+、")

/** 首次启动的免责协议全屏门：同意前不渲染正常 App。
 *
 * 视觉与顶层页面同一套语言：蓝灰渐变底 + 深藏青标题 + 白色圆角卡片，
 * 见 ui/theme/Backdrop.kt 与 ActionPill。协议正文按行渲染：
 * 小节标题（一、二、…）加粗强调，正文用常规字重。
 *
 * 内容不足一屏时 maxValue 为 0，视为无需滚动，"同意"直接可点。 */
@Composable
fun DisclaimerScreen(
    text: String,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val reachedBottom by remember {
        derivedStateOf { scrollState.value >= scrollState.maxValue - BOTTOM_THRESHOLD_PX }
    }
    val readProgress by remember {
        derivedStateOf {
            if (scrollState.maxValue <= 0) {
                1f
            } else {
                scrollState.value.toFloat() / scrollState.maxValue
            }
        }
    }
    // 资产文件首行是文档大标题（"落弦律 · 免责声明与使用条款"），
    // 页面顶部已有同类标题，卡片内不再重复。
    val bodyLines =
        remember(text) {
            text.lines().dropWhile { it.isBlank() }.let { lines ->
                if (lines.isNotEmpty()) lines.drop(1) else lines
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        GradientBackdrop()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(top = 44.dp, bottom = 20.dp),
        ) {
            Text(
                "落弦律",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = OnBackdropContent.copy(alpha = 0.72f),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "免责协议",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = OnBackdropContent,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "请阅读并同意以下条款后继续使用",
                style = MaterialTheme.typography.bodyMedium,
                color = OnBackdropContent.copy(alpha = 0.72f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        bodyLines.forEach { line ->
                            val isHeading = SECTION_HEADING.containsMatchIn(line)
                            Text(
                                line,
                                style =
                                    if (isHeading) {
                                        MaterialTheme.typography.titleSmall
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                                color =
                                    if (isHeading) {
                                        OnBackdropContent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                lineHeight = 22.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (!reachedBottom) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { readProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "上滑阅读全部条款",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                    // 走主题的 error 角色而不是写死的 PlayerDanger：
                    // 那个值是浅色专用的 #D95B67，压在深藏青渐变上只有 3.4:1。
                    Text("不同意并退出", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onAgree,
                    enabled = reachedBottom,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1.4f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Text(
                        if (reachedBottom) "我已阅读并同意" else "阅读完成后可同意",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
