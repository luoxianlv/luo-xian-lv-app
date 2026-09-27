package app.luoxianlv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalTime
import kotlinx.coroutines.delay

internal fun greetingFor(hour: Int): String =
    when (hour) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        else -> "晚上好"
    }

/** 每分钟对齐刷新一次的时间，用于竖排时钟。 与参考实现一致：睡到下一分钟再更新，不做每秒轮询。 */
@Composable
internal fun rememberHomeTime(): LocalTime {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            val untilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L
            delay(untilNextMinute)
        }
    }
    return now
}
