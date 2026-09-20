package app.luoxianlv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.BuildConfig
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.ui.components.AccessibilityPromptDialog
import app.luoxianlv.ui.components.ActionPill
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.SmallSwitch
import app.luoxianlv.ui.components.SnackbarNotice
import app.luoxianlv.ui.components.decodeSampleSize
import app.luoxianlv.ui.library.LibraryViewModel
import app.luoxianlv.ui.theme.OnBackdropContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime

/**
 * 顶部插画候选资源名，按序取第一个能解码的。
 *
 * 多扩展名是为了能直接丢一张现成的图进来，不用改名。
 *
 * ⚠️ 当前 `hero_home.png` 是临时占位：拷自参考项目 QEdge 的 `logo.png`，
 * 属于第三方美术，**正式发布前必须换掉**（换成自制插画或买断素材）。
 * 文件不存在时自动回退到渐变插画位，删掉图片不会编译失败。
 */
private val HERO_ASSETS = listOf("hero_home.webp", "hero_home.png", "hero_home.jpg")

/** 问候语下方的一言。 */
private const val HOME_QUOTE = "天空你是否知晓一切？"

/**
 * 「我的」：首页式页面。
 *
 * 布局参照 QEdge 的 `HomeScaffold` / `HomeContentPanel`：
 * 左侧竖直栏（竖排时钟 + 底部对齐的三个入口），右侧整块圆角内容列
 * （插画 → 问候语 → 标语 → 状态胶囊 → 圆形刷新 + 宽胶囊启动）。
 *
 * 这一页**没有曲目列表**：列表已移到左侧「曲库」入口。
 * 本页显示时底部浮空导航栏会隐藏（见 AppNavHost），跳转由左侧栏承担。
 */
@Composable
fun HomeScreen(
    onLibrary: () -> Unit,
    onDiscover: () -> Unit,
    onSettings: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: LibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val appearance by AppearanceStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val time = rememberHomeTime()
    // 一言：用户自定义优先，未设置或清空时用内置文案
    val headline = appearance.homeQuote.ifBlank { HOME_QUOTE }
    // 侧边栏开关（首页设置菜单里可切）：收起时内容卡独立成卡
    val showRail = appearance.sideRailEnabled

    SnackbarNotice(state.notice, snackbarHostState, vm::consumeNotice)

    // 与参考实现 HomeScaffold 同构：外层 BoxWithConstraints 量出宽度算侧栏，
    // 内层 Row 放「侧栏 + 内容卡」。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 侧栏宽度：屏宽的 18%，夹在 68–86dp（抄参考实现）
        val railWidth = (maxWidth * 0.18f).coerceIn(68.dp, 86.dp)

        Row(modifier = Modifier.fillMaxSize()) {
            if (showRail) {
                HomeSideRail(
                    time = time,
                    onLibrary = onLibrary,
                    onDiscover = onDiscover,
                    onSettings = onSettings,
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                )
            }
            // 内容卡：取值全部抄参考实现 HomeScaffold ——
            // 只留 top / end / bottom 8dp，**左侧不留**（紧贴侧栏），
            // 靠 topStart / bottomStart 的 36dp 大圆角与侧栏渐变区分开。
            // 侧栏收起时内容卡独立成卡：补上左侧 8dp，四角统一 18dp。
            //
            // 整张卡铺同一支渐变（就是原先插画那支，画布从插画扩大到全卡）：
            // 插画不再是孤立的彩色块，蓝 → 灰白 → 粉的光晕一路铺到卡底，
            // 问候语、一言、状态胶囊、启动按钮、版本页脚全都坐在这层渐变上，
            // 文本区与白卡底之间不再有大反差断层。
            //
            // 内部再按参考实现的 HomeContentPanel 分配高度：
            // 插画按可用高度取比例，信息区至少拿到剩余高度，于是整列总被填满。
            // 渐变三站 = 原插画渐变取值（深色主题下参考实现另有一套，我们只做浅色）。
            val cardBrush =
                Brush.linearGradient(
                    listOf(Color(0xFF8FD8F7), Color(0xFFDCE6F2), Color(0xFFF4DDEB)),
                )
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = if (showRail) 0.dp else 8.dp,
                            top = 8.dp,
                            end = 8.dp,
                            bottom = 8.dp,
                        ).clip(
                            RoundedCornerShape(
                                topStart = if (showRail) 36.dp else 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (showRail) 36.dp else 18.dp,
                                bottomEnd = 18.dp,
                            ),
                        ).background(cardBrush),
            ) {
                // 插画高度：抄参考实现的取值（矮屏 47%、高屏 54%，夹 270–520dp）。
                // 之前担心方图被横向裁掉而压到 0.42，现在图标是圆裁居中，不再有裁剪问题。
                val heroHeight =
                    (maxHeight * (if (maxHeight < 720.dp) 0.47f else 0.54f))
                        .coerceIn(270.dp, 520.dp)
                val overviewMinHeight = (maxHeight - heroHeight).coerceAtLeast(0.dp)

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    HomeHero(height = heroHeight)
                    HomeOverview(
                        // 直接从 time 派生：rememberHomeTime() 每分钟写回一个 MutableState，
                        // 这里读 time.hour 就订阅了它，跨时段会自动重算，不需要额外的刷新逻辑。
                        greeting = greetingFor(time.hour),
                        headline = headline,
                        statusText = state.statusText,
                        statusColor =
                            if (state.service.connected && state.service.error == null) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        minimumHeight = overviewMinHeight,
                        // 窗口真的在跑才算运行中（[LibraryUiState.floatingRunning]），
                        // 界面不再靠持久化偏好猜状态。
                        running = state.floatingRunning,
                        // 状态胶囊：无障碍没开时直接去系统设置最快；
                        // 其余情况一律切换悬浮窗，所以「运行中 · 点击关闭」点下去真能关。
                        onStatusClick = {
                            if (!state.service.accessibilityEnabled) {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } else {
                                vm.toggleFloating()
                            }
                        },
                        // 「启动 / 关闭」：同一个按钮按真实状态开或关。
                        onToggleFloating = vm::toggleFloating,
                        // 启动按钮左侧的「首页设置」：编辑一言 + 侧边栏开关
                        settingsButton = {
                            HomePageSettings(
                                quote = headline,
                                sideRailEnabled = appearance.sideRailEnabled,
                                onQuoteChange = {
                                    AppearanceStore.save(context.applicationContext, appearance.copy(homeQuote = it))
                                },
                                onSideRailChange = {
                                    AppearanceStore.save(context.applicationContext, appearance.copy(sideRailEnabled = it))
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (state.showAccessibilityPrompt) {
        AccessibilityPromptDialog(
            // 已开启却弹引导，说明服务被系统回收没跑起来：处理方式不一样，要说清楚
            alreadyEnabled = state.service.accessibilityEnabled,
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                vm.dismissAccessibilityPrompt()
            },
            onDismiss = vm::dismissAccessibilityPrompt,
        )
    }
    ErrorDialogHost(state.error, vm::dismissError)
}

/** 左侧竖直栏：竖排时钟在上，三个入口贴底（与参考实现的 `HomeSideRail` 一致）。 */
@Composable
private fun HomeSideRail(
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

/**
 * 电量快照。`level` 为空表示系统还没给出读数。
 */
private data class BatteryState(
    val level: Int? = null,
    val isCharging: Boolean = false,
)

/**
 * 监听系统电量。
 *
 * `ACTION_BATTERY_CHANGED` 是粘性广播：`registerReceiver` 的返回值就是当前状态，
 * 所以注册后立即有读数，不用等下一次变化。
 */
@Composable
private fun rememberBatteryState(): BatteryState {
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

/**
 * 侧栏电量指示：电池轮廓 + 按比例填充的进度条 + 百分比，充电时中间显示闪电。
 * 配色与参考实现一致：充电绿 / 20% 以下红 / 其余跟随侧栏字色。
 */
@Composable
private fun BatteryIndicator(
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
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(44.dp)
                        .fillMaxHeight()
                        .border(1.4.dp, content.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                        .padding(3.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(3.dp))
                            .background(fillColor),
                )
            }
            // 电池正极的小凸点
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(3.dp)
                        .height(9.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(content.copy(alpha = 0.62f)),
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

/** 侧栏入口之间的细分隔线。 */
@Composable
private fun RailDivider() {
    Box(
        modifier =
            Modifier
                .padding(vertical = 10.dp)
                .width(24.dp)
                .height(1.dp)
                .background(OnBackdropContent.copy(alpha = 0.28f)),
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
            Modifier
                .size(46.dp)
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

/**
 * 顶部插画。
 *
 * 结构抄自参考实现的 `HomeHero`：两个半透明装饰圆 + 居中图标。
 * 底不铺自己的渐变 —— 外层内容卡整卡铺着同一支渐变（见 HomeScreen
 * 的 cardBrush），插画只是坐在渐变上段，彩色自然延伸进下方信息区，
 * 所以这里也不再需要「底部渐变过渡带」。
 *
 * 居中图标用 `shadow(18.dp, CircleShape).clip(CircleShape)`：
 * **不是整块铺满**，而是裁成圆形 + 一圈阴影作边界，四周露出渐变。
 * 我们那张占位图恰好是 512×512 方图，圆裁后正好不被截，
 * 侧边也不会再出现“人像显示不全”。
 */
@Composable
private fun HomeHero(
    height: Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        // 图标直径：屏宽的 76%、可用高度的 72%、350dp 三者取小（抄参考实现）
        val logoSize = minOf(maxWidth * 0.76f, maxHeight * 0.72f, 350.dp)

        // 两个半透明装饰圆
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 18.dp)
                    .size(118.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 22.dp, bottom = 70.dp)
                    .size(76.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape),
        )

        // 居中圆形图标：shadow 形成“边框”感，clip 裁圆，四周就是渐变
        val image = rememberHeroImage()
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(logoSize)
                        .shadow(18.dp, CircleShape)
                        .clip(CircleShape),
            )
        } else {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(logoSize)
                        .shadow(18.dp, CircleShape)
                        .clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(logoSize * 0.46f),
                    )
                }
            }
        }

        // 底部不再有渐变过渡带：整卡同一片渐变，插画与信息区之间
        // 没有需要「过渡」的断层（原 124dp 落白过渡已随白卡一起撤掉）。
    }
}

/** 插画下方的信息与操作区。 */
@Composable
private fun HomeOverview(
    greeting: String,
    headline: String,
    statusText: String,
    statusColor: Color,
    minimumHeight: Dp,
    running: Boolean,
    onStatusClick: () -> Unit,
    onToggleFloating: () -> Unit,
    settingsButton: @Composable () -> Unit,
) {
    // heightIn(min) 保证信息区至少占满「整列高度 - 插画高度」，
    // 内部用 weight 撑开把页脚推到底部，于是右下角不会留白。
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minimumHeight)
                .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 状态胶囊：无障碍 / 悬浮窗的连接状态
        Surface(
            onClick = onStatusClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // 宽胶囊：启动 / 关闭，按真实运行状态切换。
        // 动作对象由上方状态胶囊（“悬浮窗运行中 · 点击关闭”）交代，按钮只留动词，文案更短。
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
 * 页脚版本号。
 *
 * 与参考实现的 `ThanksYumeBoxFooter` 同款：用横向渐变画刷当文字颜色，
 * 两端 alpha 0.12、中间 0.48，于是文字首尾自然淡出、整体呈半透明灰。
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
            ),
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

/**
 * 「首页设置」：启动按钮左侧的 Tune 图标，下拉两项——
 * 「编辑一言」弹窗输入，改动即时写盘生效（无需保存按钮）；
 * 「展开侧边栏」即点即开，控制「我的」页左侧竖栏，默认开。
 */
@Composable
private fun HomePageSettings(
    quote: String,
    sideRailEnabled: Boolean,
    onQuoteChange: (String) -> Unit,
    onSideRailChange: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.Tune, contentDescription = "首页设置")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("编辑一言") },
                leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                onClick = {
                    menu = false
                    editing = true
                },
            )
            DropdownMenuItem(
                text = { Text("展开侧边栏") },
                trailingIcon = {
                    SmallSwitch(checked = sideRailEnabled, onCheckedChange = onSideRailChange)
                },
                onClick = { onSideRailChange(!sideRailEnabled) },
            )
        }
    }

    if (editing) {
        HomeQuoteEditor(
            quote = quote,
            onChange = onQuoteChange,
            onClose = { editing = false },
        )
    }
}

/**
 * 一言编辑弹窗：没有保存按钮 —— 每次输入都直接写盘，
 * 弹窗背后的问候语区实时跟着变，「完成」只是关窗。
 */
@Composable
private fun HomeQuoteEditor(
    quote: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(quote) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("编辑一言") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onChange(it)
                },
                singleLine = true,
                placeholder = { Text("问候语下方的那句话") },
            )
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("完成") }
        },
    )
}

private fun greetingFor(hour: Int): String =
    when (hour) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        else -> "晚上好"
    }

/**
 * 每分钟对齐刷新一次的时间，用于竖排时钟。
 * 与参考实现一致：睡到下一分钟再更新，不做每秒轮询。
 */
@Composable
private fun rememberHomeTime(): LocalTime {
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

/** 读取插画资源；全部候选都不存在或都解码失败时返回 null，由调用方回退到渐变。 */
@Composable
private fun rememberHeroImage(): ImageBitmap? {
    val context = LocalContext.current
    val state =
        produceState<ImageBitmap?>(initialValue = null) {
            value = withContext(Dispatchers.IO) { decodeHeroAsset(context) }
        }
    return state.value
}

private fun decodeHeroAsset(context: Context): ImageBitmap? {
    HERO_ASSETS.forEach { name ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readable =
            runCatching {
                context.assets.open(name).use { BitmapFactory.decodeStream(it, null, bounds) }
            }.isSuccess
        if (!readable || bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEach

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight)
            }
        val decoded =
            runCatching {
                context.assets.open(name).use {
                    BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        if (decoded != null) return decoded
    }
    return null
}
