package app.luoxianlv.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.luoxianlv.ui.theme.OnBackdropContent

/** 电量快照。`level` 为空表示系统还没给出读数。 */
internal data class BatteryState(
    val level: Int? = null,
    val isCharging: Boolean = false,
)

/**
 * 监听系统电量。
 *
 * `ACTION_BATTERY_CHANGED` 是粘性广播：`registerReceiver` 的返回值就是当前状态， 所以注册后立即有读数，不用等下一次变化。
 */
@Composable
internal fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf(BatteryState()) }

    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context?,
                    intent: Intent?,
                ) {
                    intent?.toBatteryState()?.let { state = it }
                }
            }
        // 用 RECEIVER_EXPORTED 与参考实现保持一致。
        // BATTERY_CHANGED 是受保护的系统广播，第三方伪造的影响仅限于这个指示器显示的数值。
        val sticky =
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        sticky?.toBatteryState()?.let { state = it }

        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return state
}

private fun Intent.toBatteryState(): BatteryState {
    val rawLevel = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val level =
        if (rawLevel >= 0 && scale > 0) {
            ((rawLevel * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return BatteryState(
        level = level,
        isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** 侧栏电量指示：电池轮廓 + 按比例填充的进度条 + 百分比，充电时中间显示闪电。 配色与参考实现一致：充电绿 / 20% 以下红 / 其余跟随侧栏字色。 */
@Composable
internal fun BatteryIndicator(
    state: BatteryState,
    modifier: Modifier = Modifier,
) {
    val content = OnBackdropContent
    val fraction = (state.level ?: 0) / 100f
    val fillColor =
        when {
            state.isCharging -> Color(0xFF34C759)
            (state.level ?: 100) <= 20 -> Color(0xFFFF5A52)
            else -> content.copy(alpha = 0.84f)
        }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.width(48.dp).height(22.dp)) {
            Box(
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .width(44.dp)
                        .fillMaxHeight()
                        .border(1.4.dp, content.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                        .padding(3.dp)
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(3.dp))
                            .background(fillColor)
                )
            }
            // 电池正极的小凸点
            Box(
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .width(3.dp)
                        .height(9.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(content.copy(alpha = 0.62f))
            )
            if (state.isCharging) {
                Text(
                    text = "⚡",
                    modifier = Modifier.align(Alignment.Center),
                    color = content,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = state.level?.let { "$it%" } ?: "--%",
            color = content.copy(alpha = 0.78f),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
