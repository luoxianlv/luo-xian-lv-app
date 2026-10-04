package app.luoxianlv.home

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.luoxianlv.ui.theme.OnBackdropContent
import java.time.LocalTime

/** 左侧竖直栏：竖排时钟在上，三个入口贴底（与参考实现的 `HomeSideRail` 一致）。 */
@Composable
internal fun HomeSideRail(
    time: LocalTime,
    onLibrary: () -> Unit,
    onDiscover: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val battery = rememberBatteryState()
    // 不自带背景：渐变底由 AppNavHost 铺在整屏（因此能 edge-to-edge），
    // 侧栏只是叠在它上面，这样「左侧栏」与「卡片四周留白」是同一层底色。
    Column(
        modifier = modifier.padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 竖排时钟：时 / 破折号 / 分，参考设计的标志性元素
        Text(
            text = time.hour.toString().padStart(2, '0'),
            color = OnBackdropContent,
            fontSize = 32.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = "—",
            color = OnBackdropContent.copy(alpha = 0.72f),
            fontSize = 18.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = time.minute.toString().padStart(2, '0'),
            color = OnBackdropContent,
            fontSize = 32.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Light,
        )

        Spacer(modifier = Modifier.height(16.dp))
        BatteryIndicator(state = battery)

        // 把三个入口推到底部
        Spacer(modifier = Modifier.weight(1f))

        RailEntry(Icons.Filled.LibraryMusic, "曲库", onLibrary)
        RailDivider()
        RailEntry(Icons.Filled.Explore, "发现", onDiscover)
        RailDivider()
        RailEntry(Icons.Filled.Settings, "设置", onSettings)
    }
}

/** 侧栏入口之间的细分隔线。 */
@Composable
private fun RailDivider() {
    Box(
        modifier =
            Modifier.padding(vertical = 10.dp)
                .width(24.dp)
                .height(1.dp)
                .background(OnBackdropContent.copy(alpha = 0.28f))
    )
}

/** 侧栏圆形入口。底色用侧栏字色的低透明叠加，与参考实现的 `RailActionButton` 一致。 */
@Composable
private fun RailEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(46.dp)
                .clip(CircleShape)
                .background(OnBackdropContent.copy(alpha = 0.10f))
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = OnBackdropContent,
            modifier = Modifier.size(22.dp),
        )
    }
}
