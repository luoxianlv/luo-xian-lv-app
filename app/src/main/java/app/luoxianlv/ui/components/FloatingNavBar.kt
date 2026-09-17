package app.luoxianlv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.navigation.Routes
import kotlin.math.abs
import kotlin.math.roundToInt

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 顺序与 [tabs] 一致：我的-曲库-发现-设置。
 * 「我的」是首页式页面，它自己的左侧导航栏指向另外三个；
 * 其余三个页面仍用这条底部导航栏。
 */
private val items =
    listOf(
        NavItem(Routes.HOME, "我的", Icons.Filled.Home),
        NavItem(Routes.LIBRARY, "曲库", Icons.Filled.LibraryMusic),
        NavItem(Routes.DISCOVER, "发现", Icons.Filled.Explore),
        NavItem(Routes.SETTINGS, "设置", Icons.Filled.Settings),
    )

/**
 * 底部导航栏叠在页面内容之上所占的额外高度。
 *
 * 导航栏是浮层，**不参与 Scaffold 的布局**：一旦放进 `bottomBar` 插槽，
 * Scaffold 的内容内边距就会跟着它的显隐动画一起变化，
 * 「我的」这种按可用高度分配空间的页面会因此每帧重算而回流。
 *
 * 代价是可滚动页面要自己留出这段距离，否则最后一个条目会被胶囊盖住 ——
 * 各页的底部内容内边距直接用它。
 *
 * = 4dp（上外边距）+ 54dp（胶囊）+ 8dp（下外边距）+ 8dp（余量）
 */
val NavBarClearance: Dp = 74.dp

/**
 * 浮空底部导航栏：圆角胶囊悬浮于内容之上。
 * [position] 是带手势偏移的选中位置（页码 + 偏移分数，0..3）：
 * 滑动页面时胶囊随手指实时移动，位移越大胶囊横向拉伸越明显；
 * 选中态胶囊完整覆盖「图标 + 文字」。
 */
@Composable
fun FloatingNavBar(
    position: Float,
    onNavigate: (String) -> Unit,
) {
    // 容器色直接用主题的 surface —— 它已经被压到 ON_BACKDROP_SURFACE_ALPHA。
    // 这里不再单独再压一档，否则导航栏会比卡片透得不一样。
    // 阴影画在填充之下：填充半透就会从面板里透出来，让导航栏发灰；
    // 省掉阴影也无妨 —— 白胶囊叠在蓝灰渐变上，轮廓本来就够清楚。
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        val selectedIndex = position.roundToInt().coerceIn(0, items.size - 1)
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(5.dp),
        ) {
            val itemWidth = maxWidth / items.size
            // 手势位移驱动横向拉伸：滑到两页中间时胶囊最长，停下收回
            val dragFraction = abs(position - position.roundToInt())
            val stretch = (dragFraction * 0.44f).coerceIn(0f, 0.22f)
            Box(
                modifier =
                    Modifier
                        .offset(x = itemWidth * position - itemWidth * stretch / 2)
                        .width(itemWidth * (1 + stretch))
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(24.dp),
                        ),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    val tint by animateColorAsState(
                        targetValue =
                            if (index == selectedIndex) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        label = "nav-item-tint",
                    )
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(
                                    role = Role.Tab,
                                    onClickLabel = item.label,
                                ) { onNavigate(item.route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}
