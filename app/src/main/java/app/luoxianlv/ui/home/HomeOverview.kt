package app.luoxianlv.ui.home

import android.graphics.RectF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.luoxianlv.BuildConfig
import app.luoxianlv.ui.components.ActionPill
import app.luoxianlv.ui.practice.openPracticeStage
import app.luoxianlv.ui.theme.OnBackdropContent

/** 插画下方的信息与操作区。 */
@Composable
internal fun HomeOverview(
    greeting: String,
    headline: String,
    minimumHeight: Dp,
    running: Boolean,
    onToggleFloating: () -> Unit,
    onPractice: (Boolean) -> Unit,
    settingsButton: @Composable () -> Unit,
) {
    // heightIn(min) 保证信息区至少占满「整列高度 - 插画高度」，
    // 内部用 weight 撑开把页脚推到底部，于是右下角不会留白。
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = minimumHeight)
                .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.titleSmall,
            // 这两行与标题一样，直接坐在内容卡的渐变上，不是坐在容器里：
            // 用「压在底上」的次级字色（OnBackdropContent 降一档），
            // 而不是为容器准备的 onSurfaceVariant —— 后者在深色渐变上偏暗。
            color = OnBackdropContent.copy(alpha = 0.72f),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            // 必须显式给色：不指定时吃 LocalContentColor 的默认值（Color.Black），
            // 深色下就是黑字压深卡，整句话直接消失。
            color = OnBackdropContent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 演练场入口：原来这里是状态胶囊（「无障碍未开启 · 点击去开启」），
        // 现在换成同款圆角按钮，幕布从按钮自身展开。
        HomeStagePill(onPractice = onPractice)

        Spacer(modifier = Modifier.height(22.dp))

        // 宽胶囊：启动 / 关闭，按真实运行状态切换；按钮只留动词，文案更短。
        // 左侧固定一枚「首页设置」图标（编辑一言 / 侧边栏开关）。
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            settingsButton()
            Spacer(modifier = Modifier.width(10.dp))
            ActionPill(
                label = if (running) "关闭" else "启动",
                onClick = onToggleFloating,
                icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                modifier = Modifier.weight(1f),
            )
        }

        // 页脚：与参考实现的「Thanks to YumeBox」同款。
        // 放在信息区底部而不是压在插画上，既避免和插画主体重叠，也把右下角的空间用上。
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            VersionFooter()
        }
    }
}

/**
 * 演练场入口：与状态胶囊同款的圆角按钮。
 *
 * 按钮自身的窗口坐标就是开场幕布的展开原点（退出时的碎光也飞回这里），
 * 所以位置变化不影响转场，只要量的是实际布局边界。
 */
@Composable
private fun HomeStagePill(
    onPractice: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var bounds by remember { mutableStateOf(RectF()) }
    Surface(
        onClick = { openPracticeStage(context, bounds, dark, onPractice) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier =
            modifier.onGloballyPositioned {
                val r = it.boundsInWindow()
                bounds = RectF(r.left, r.top, r.right, r.bottom)
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.MusicVideo,
                contentDescription = null,
                tint = OnBackdropContent.copy(alpha = 0.72f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = "演练场",
                style = MaterialTheme.typography.labelMedium,
                color = OnBackdropContent.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 页脚版本号。
 *
 * 与参考实现的 `ThanksYumeBoxFooter` 同款：用横向渐变画刷当文字颜色， 两端 alpha 0.12、中间 0.48，于是文字首尾自然淡出、整体呈半透明灰。
 *
 * 画刷挂在 Text 自己身上（而不是铺满整行），淡出才是相对文字宽度而不是屏幕宽度。
 */
@Composable
private fun VersionFooter(modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val gradient =
        Brush.horizontalGradient(
            listOf(
                secondary.copy(alpha = 0.12f),
                secondary.copy(alpha = 0.48f),
                secondary.copy(alpha = 0.12f),
            )
        )
    Text(
        text = "落弦律 · v${BuildConfig.VERSION_NAME}",
        modifier = modifier,
        style =
            TextStyle(
                brush = gradient,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.1.sp,
            ),
    )
}
