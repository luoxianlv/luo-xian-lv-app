package app.luoxianlv.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.ui.components.AccessibilityPromptDialog
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.SnackbarNotice
import app.luoxianlv.ui.library.LibraryViewModel
import app.luoxianlv.ui.theme.LocalBackdropPalette

/** 问候语下方的一言。 */
private const val HOME_QUOTE = "天空你是否知晓一切？"

/**
 * 「我的」：首页式页面。
 *
 * 布局参照 QEdge 的 `HomeScaffold` / `HomeContentPanel`： 左侧竖直栏（竖排时钟 + 底部对齐的三个入口），右侧整块圆角内容列 （插画 → 问候语 →
 * 标语 → 状态胶囊 → 圆形刷新 + 宽胶囊启动）。
 *
 * 这一页**没有曲目列表**：列表已移到左侧「曲库」入口。 本页显示时底部浮空导航栏会隐藏（见 AppNavHost），跳转由左侧栏承担。
 */
@Composable
fun HomeScreen(
    onLibrary: () -> Unit,
    onDiscover: () -> Unit,
    onPractice: (Boolean) -> Unit,
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
            // 渐变三站 = 原插画渐变取值，深浅各一套（见 theme/Backdrop.kt 的 heroBrush）：
            // 深色版保留同一组「蓝 → 灰 → 粉」色相，只是压暗降饱和，
            // 换主题时整张卡还是同一种光，而不是另一套设计。
            val cardBrush = LocalBackdropPalette.current.heroBrush
            BoxWithConstraints(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = if (showRail) 0.dp else 8.dp,
                            top = 8.dp,
                            end = 8.dp,
                            bottom = 8.dp,
                        )
                        .clip(
                            RoundedCornerShape(
                                topStart = if (showRail) 36.dp else 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (showRail) 36.dp else 18.dp,
                                bottomEnd = 18.dp,
                            )
                        )
                        .background(cardBrush)
            ) {
                // 插画高度：抄参考实现的取值（矮屏 47%、高屏 54%，夹 270–520dp）。
                // 之前担心方图被横向裁掉而压到 0.42，现在图标是圆裁居中，不再有裁剪问题。
                val heroHeight =
                    (maxHeight * (if (maxHeight < 720.dp) 0.47f else 0.54f)).coerceIn(
                        270.dp,
                        520.dp,
                    )
                val overviewMinHeight = (maxHeight - heroHeight).coerceAtLeast(0.dp)

                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HomeHero(height = heroHeight)
                    HomeOverview(
                        onPractice = onPractice,
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
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
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
                                    AppearanceStore.save(
                                        context.applicationContext,
                                        appearance.copy(homeQuote = it),
                                    )
                                },
                                onSideRailChange = {
                                    AppearanceStore.save(
                                        context.applicationContext,
                                        appearance.copy(sideRailEnabled = it),
                                    )
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
