package app.luoxianlv.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.core.Analytics
import app.luoxianlv.ui.components.FloatingNavBar
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.discover.DiscoverScreen
import app.luoxianlv.ui.discover.PlatformScreen
import app.luoxianlv.ui.discover.SearchScreen
import app.luoxianlv.ui.home.HomeScreen
import app.luoxianlv.ui.importer.ImportScreen
import app.luoxianlv.ui.library.LibraryScreen
import app.luoxianlv.ui.settings.AboutScreen
import app.luoxianlv.ui.settings.AnalyticsDebugScreen
import app.luoxianlv.ui.settings.LoginScreen
import app.luoxianlv.ui.settings.PlaybackDiagnosticsScreen
import app.luoxianlv.ui.settings.SettingsScreen
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.update.AppUpdateViewModel
import app.luoxianlv.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 顶级 Tab：我的-曲库-发现-设置。
 *
 * 「我的」是首页式页面（无曲目列表），它的左侧导航栏指向另外三个；
 * 「导入」从顶级 Tab 降为「曲库」内的子页面。
 */
private val tabs = listOf(Routes.HOME, Routes.LIBRARY, Routes.DISCOVER, Routes.SETTINGS)

/** 子页面（覆盖层，不参与手势滑动） */
private val subPages =
    listOf(Routes.SEARCH, Routes.PLATFORM, Routes.LOGIN, Routes.ABOUT, Routes.IMPORT, Routes.DIAGNOSTICS, Routes.ANALYTICS_DEBUG)

/** 「我的」在 [tabs] 中的下标：它显示时底部导航栏要隐藏。 */
private val HOME_INDEX = tabs.indexOf(Routes.HOME)

/**
 * 微信式导航骨架：四个顶级 Tab 用 HorizontalPager 承载，左右滑动切换；
 * 子页面以覆盖层形式滑入；浮空导航栏的胶囊跟随滑动进度。
 */
@Composable
fun AppNavHost(appUpdates: AppUpdateViewModel = viewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = HOME_INDEX) { tabs.size }
    var subPage by remember { mutableStateOf<String?>(null) }

    fun goTab(route: String) {
        val index = tabs.indexOf(route)
        if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
    }

    // 子页面打开时拦截返回键：先退回主页面
    androidx.activity.compose.BackHandler(enabled = subPage != null) { subPage = null }

    // 小窗/分屏下拖动窗口改变尺寸时，进行中的 Tab 切换动画可能被打断在半路，
    // Pager 不会自行纠正（表现为两页各占半屏、内容点不动）。滚动停止后若不在整页就吸回去。
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPageOffsetFraction }
            .collect { (scrolling, offset) ->
                if (!scrolling && offset != 0f) pagerState.animateScrollToPage(pagerState.currentPage)
            }
    }

    // 连续页码（当前页 + 手势偏移）：导航胶囊用它跟手
    val position =
        (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, (tabs.size - 1).toFloat())
    val libraryActive = pagerState.currentPage == HOME_INDEX
    val updateState by appUpdates.state.collectAsState()
    val activity = LocalContext.current as android.app.Activity
    val updater = remember { UpdateManager(activity) }
    // U-App 页面统计：单 Activity + Compose 只能手动按页面名打点（U-APM 的页面维度是 Activity）。
    // 顶级 Tab 是跟手切换的 Pager、子页面是覆盖层，这里统一按当前页面名成对上报开始/结束。
    val currentPage = subPage ?: tabs.getOrNull(pagerState.currentPage) ?: Routes.HOME
    androidx.compose.runtime.DisposableEffect(currentPage) {
        Analytics.pageStart(currentPage)
        onDispose { Analytics.pageEnd(currentPage) }
    }
    androidx.compose.runtime.LaunchedEffect(updateState.message) {
        updateState.message?.let {
            snackbarHostState.showSnackbar(it)
            appUpdates.consumeMessage()
        }
    }

    // Debug-only：注册导出广播，adb 可主动触发更新弹窗用于 UI 验证。
    if (app.luoxianlv.BuildConfig.DEBUG) {
        val context = LocalContext.current
        androidx.compose.runtime.DisposableEffect(Unit) {
            val receiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(
                        c: android.content.Context,
                        i: android.content.Intent,
                    ) {
                        appUpdates.debugTriggerUpdate()
                    }
                }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter("app.luoxianlv.DEBUG_TRIGGER_UPDATE"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    Scaffold(
        // 顶级 Tab 页面容器透明：全局背景在 MainActivity 最底层铺开，这里不能把它盖住。
        // Tab 页之间是并排的兄弟关系，透明是刻意的。
        containerColor = Color.Transparent,
        // 导航栏是浮层，不放进 bottomBar 插槽：
        // 否则 Scaffold 的内容内边距会跟着它的显隐动画变化，页面随之回流。
        // 现在内边距只由系统栏决定，恒定的。
        // Snackbar 需要自己抬高，否则会被浮层胶囊盖住。
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = NavBarClearance),
            )
        },
    ) { padding ->
        // 外层不加内边距：渐变底要顶到屏幕边缘。
        // 内边距加在内层，与参考实现 HomeScaffold 的 windowInsetsPadding 位置一致。
        Box(modifier = Modifier.fillMaxSize()) {
            // 四个顶级页面共用同一张渐变底，所以常驻绘制，
            // 不需要再跟滑动位置做淡入淡出。
            GradientBackdrop()

            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    when (tabs[page]) {
                        Routes.HOME -> {
                            HomeScreen(
                                onLibrary = { goTab(Routes.LIBRARY) },
                                onDiscover = { goTab(Routes.DISCOVER) },
                                onSettings = { goTab(Routes.SETTINGS) },
                                snackbarHostState = snackbarHostState,
                            )
                        }

                        Routes.LIBRARY -> {
                            LibraryScreen(
                                onImport = { subPage = Routes.IMPORT },
                                snackbarHostState = snackbarHostState,
                            )
                        }

                        Routes.DISCOVER -> {
                            DiscoverScreen(
                                onSearch = { subPage = Routes.SEARCH },
                                snackbarHostState = snackbarHostState,
                            )
                        }

                        Routes.SETTINGS -> {
                            SettingsScreen(
                                onLogin = { subPage = Routes.LOGIN },
                                onAbout = { subPage = Routes.ABOUT },
                                onDiagnostics = { subPage = Routes.DIAGNOSTICS },
                                onAnalyticsDebug = { subPage = Routes.ANALYTICS_DEBUG },
                                snackbarHostState = snackbarHostState,
                            )
                        }
                    }
                }
                // 子页面覆盖层：右滑入/右滑出，与之前 NavHost 转场一致
                subPages.forEach { route ->
                    AnimatedVisibility(
                        visible = subPage == route,
                        enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(280)),
                        exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280)),
                    ) {
                        // 覆盖层必须是一张「不透明的整页」。
                        //
                        // 子页面是叠在当前 Tab 页之上的，容器一旦透明，下面那一页就会透出来：
                        // 从「设置」打开「关于」会看到设置列表穿透在关于页下面，
                        // 从「我的」打开平台页会看到曲目列表穿透在平台页下面。
                        //
                        // 所以这里自己铺一遍渐变底：既盖住下层页面，又与其它页面保持同一个底色。
                        Box(modifier = Modifier.fillMaxSize()) {
                            GradientBackdrop()
                            when (route) {
                                Routes.SEARCH -> {
                                    SearchScreen(
                                        onBack = { subPage = null },
                                        snackbarHostState = snackbarHostState,
                                    )
                                }

                                Routes.PLATFORM -> {
                                    PlatformScreen(
                                        onBack = { subPage = null },
                                        snackbarHostState = snackbarHostState,
                                    )
                                }

                                Routes.LOGIN -> {
                                    LoginScreen(
                                        onBack = { subPage = null },
                                        onShushuLogin = { updater.startShushuLogin(activity) },
                                        onRegister = {
                                            runCatching {
                                                activity.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse("https://luoxianlv.com/login?mode=register"),
                                                    ),
                                                )
                                            }.onFailure {
                                                android.widget.Toast
                                                    .makeText(
                                                        activity,
                                                        "无法打开浏览器，请访问 luoxianlv.com 注册",
                                                        android.widget.Toast.LENGTH_LONG,
                                                    ).show()
                                            }
                                        },
                                        snackbarHostState = snackbarHostState,
                                    )
                                }

                                Routes.ABOUT -> {
                                    AboutScreen(
                                        onBack = { subPage = null },
                                        snackbarHostState = snackbarHostState,
                                        onCheckUpdate = { appUpdates.check(manual = true) },
                                        checkingUpdate = updateState.checking,
                                    )
                                }

                                Routes.IMPORT -> {
                                    ImportScreen(
                                        onBack = { subPage = null },
                                        onImported = {
                                            subPage = null
                                            goTab(Routes.LIBRARY)
                                        },
                                        snackbarHostState = snackbarHostState,
                                    )
                                }

                                Routes.DIAGNOSTICS -> {
                                    PlaybackDiagnosticsScreen(
                                        onBack = { subPage = null },
                                        snackbarHostState = snackbarHostState,
                                    )
                                }

                                Routes.ANALYTICS_DEBUG -> {
                                    AnalyticsDebugScreen(onBack = { subPage = null })
                                }
                            }
                        }
                    }
                }
            }

            // 浮空导航栏：叠在内容之上。显隐只影响它自己，不会改变任何页面的布局。
            // 两种情况下不显示：
            //   1. 平台谱库页（与旧版一致，出入用下滑/上滑动画）
            //   2. 「我的」——它是首页，跳转交给它自己的左侧导航栏
            AnimatedVisibility(
                // 子页面是不透明覆盖层；隐藏导航栏，避免它盖住「关于」页并拦截点击。
                visible = subPage == null && !libraryActive,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
                exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(250)),
            ) {
                FloatingNavBar(position = position, onNavigate = ::goTab)
            }

            app.luoxianlv.ui.components.AppUpdateDialog(
                state = updateState,
                onDismiss = appUpdates::dismiss,
                onSource = appUpdates::selectSource,
                onDownload = appUpdates::download,
                onInstall = { appUpdates.install(activity) },
            )
        }
    }
}
