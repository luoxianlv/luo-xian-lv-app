package app.luoxianlv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 约 30fps 更新一次：雪花落得很慢，没必要跟着 60/120Hz 重绘。 */
private const val SNOW_FRAME_INTERVAL_MS = 33L

private const val TWO_PI = (2 * PI).toFloat()

/**
 * 单片雪花的状态。全部字段在创建时定下来，飘出底部后按相位回到顶部循环，
 * 不需要维护“存活”标志或对象池。
 */
private data class Snowflake(
    /** 横向基准位置，0..1 的屏宽比例 */
    val xFraction: Float,
    val radius: Dp,
    /** 每秒下落多少屏高 */
    val speed: Float,
    /** 横向摆动幅度，屏宽比例 */
    val swayAmplitude: Float,
    /** 横向摆动频率 */
    val swaySpeed: Float,
    /** 起始相位 0..1，让每片雪花的纵向位置错开 */
    val phase: Float,
    val alpha: Float,
)

/** 用下标做种子，保证每次重组/重启拿到的雪花分布一致，不会突然换一批。 */
private fun snowflakeAt(index: Int): Snowflake {
    val random = Random(index * 7919 + 13)
    return Snowflake(
        xFraction = random.nextFloat(),
        // 0.6–2.2dp：要求就是「微小」，不要抢内容
        radius = (0.6f + random.nextFloat() * 1.6f).dp,
        speed = 0.045f + random.nextFloat() * 0.06f,
        swayAmplitude = 0.008f + random.nextFloat() * 0.022f,
        swaySpeed = 0.4f + random.nextFloat() * 0.9f,
        phase = random.nextFloat(),
        alpha = 0.45f + random.nextFloat() * 0.4f,
    )
}

/**
 * 从屏幕上方飘落的微小雪花。
 *
 * 纯绘制实现，不依赖任何图片资源。白色、半透明、半径 0.6–2.2dp，
 * 落在蓝灰渐变底上刚好是若隐若现的效果；飘到内容卡上会自然淡掉。
 *
 * 两点实现取舍：
 * - 时间从 [withFrameMillis] 取，并按 [SNOW_FRAME_INTERVAL_MS] 节流，
 *   避免为了一个装饰效果跑满刷新率。
 * - 时间写在 snapshot state 里、且**只在绘制 lambda 里读**，
 *   所以每帧只触发重绘（draw 失效），不会触发重组。
 *
 * [enabled] 为 false 时整棵子树不参与组合，零开销。
 */
@Composable
fun Snowfall(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    flakeCount: Int = 42,
) {
    if (!enabled) return

    val flakes = remember(flakeCount) { List(flakeCount) { snowflakeAt(it) } }
    val clock = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var lastUpdate = 0L
        while (true) {
            withFrameMillis { frameTime ->
                if (frameTime - lastUpdate >= SNOW_FRAME_INTERVAL_MS) {
                    lastUpdate = frameTime
                    clock.longValue = frameTime
                }
            }
        }
    }

    // Canvas 不消费触摸事件，所以雪花只是盖在上面，不会挡住底下的按钮和列表
    Canvas(modifier = modifier.fillMaxSize()) {
        val seconds = clock.longValue / 1000f
        flakes.forEach { flake ->
            val progress = (flake.phase + seconds * flake.speed) % 1f
            val sway = sin((seconds * flake.swaySpeed + flake.phase) * TWO_PI)
            drawCircle(
                color = Color.White.copy(alpha = flake.alpha),
                radius = flake.radius.toPx(),
                center =
                    Offset(
                        x = (flake.xFraction + flake.swayAmplitude * sway) * size.width,
                        y = progress * size.height,
                    ),
            )
        }
    }
}
