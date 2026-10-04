package app.luoxianlv.service

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import app.luoxianlv.R
import app.luoxianlv.business.BusinessJobs
import app.luoxianlv.business.playback.PlaybackSession
import app.luoxianlv.data.Kv
import app.luoxianlv.data.Song
import app.luoxianlv.data.SongRepository
import app.luoxianlv.debug.AppLog
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.ui.floating.FloatingPanel
import app.luoxianlv.ui.floating.FloatingPlaylistWindow
import app.luoxianlv.ui.floating.FloatingTouchMarker
import app.luoxianlv.ui.floating.FloatingWindowLayout
import app.luoxianlv.ui.floating.PlayerUi
import app.luoxianlv.ui.floating.PlayerUi.dp
import app.luoxianlv.ui.floating.PlayerUiPalette
import kotlin.math.abs
import kotlinx.coroutines.*

/**
 * 悬浮窗（无障碍 overlay）。
 *
 * 视觉跟随 App 主题：白卡（92% 不透明 + 细描边 + 阴影）、品牌蓝主按钮、 深藏青标题；深色模式下白卡换成深石板蓝、字色反相（见 [palette]）。两个形态：
 * - 收起：44dp 气泡（浅色白底 / 深色深蓝底）+ 蓝音符，可拖动；
 * - 展开：播放控制、倍速滑动条和底部播放进度条；播放进度条可点按/拖动 seek。 「选歌」开居中独立小窗，不再是贴面板下拉。
 */
class FloatingControls(private val service: PlaybackSession) {
    private val context = ContextThemeWrapper(service, R.style.AppTheme)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = Kv.of(service, "floating_position")
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playlistJob: Job? = null

    /**
     * 当前配色。
     *
     * 悬浮窗是独立系统窗口，不跟着 Activity 重组，所以在每次 [render] / [showPlaylist]
     * 开头重新取一次（读数开销很小），用户在设置里改了深色模式，下次重绘就是新配色； 想立即生效由 [refreshTheme] 触发。
     */
    private var palette: PlayerUiPalette = PlayerUi.palette(context)

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val playlistWindow = FloatingPlaylistWindow(context, wm)
    private val touchMarker = FloatingTouchMarker(context, wm, handler) { palette }
    private var dock =
        FloatingDock.entries.firstOrNull { it.name == prefs.getString("dock", null) }
            ?: FloatingDock.NONE
    private var dockAnimator: ValueAnimator? = null
    private var expanded = false
    private var restoredExpanded: Boolean? = null
    private var speedControlsVisible = false
    private var panel: FloatingPanel? = null
    private var cachedPanel: FloatingPanel? = null
    private var cachedPanelGeometry: FloatingDisplayGeometry? = null
    private var cachedPanelPalette: PlayerUiPalette? = null
    private var panelPrewarm: MessageQueue.IdleHandler? = null
    private var displayRequested = false
    private var showRetries = 0
    private var destroyed = false
    private var displayedGeometry: FloatingDisplayGeometry? = null
    private var pendingGeometry: FloatingDisplayGeometry? = null
    private val applyGeometry = Runnable { applyDisplayGeometry() }
    var revision = 0L
        private set

    private var touching = false
    val interacting
        get() =
            touching ||
                panel?.touching == true ||
                playlistWindow.isShowing ||
                playlistJob?.isActive == true ||
                dockAnimator?.isRunning == true ||
                pendingGeometry != null ||
                panelPrewarm != null

    val released
        get() = destroyed && scope.coroutineContext[Job]?.isCompleted == true

    // 选歌窗打开时面板先退出，关闭后恢复（两者不共存）。
    private var panelHiddenForPicker = false
    private var x = prefs.getInt("x", context.dp(16))
    private var y = prefs.getInt("y", context.dp(140))
    private val tick =
        object : Runnable {
            override fun run() {
                refresh()
                if (root != null) handler.postDelayed(this, 200)
            }
        }

    /**
     * 悬浮窗此刻是否（应当）显示在屏幕上。
     *
     * [show] / [hide] 同步改写 [displayRequested]，拖拽、选歌窗临时顶掉面板都不影响它， 所以界面可以直接拿它判断「运行中」——不必再看持久化偏好：
     * 服务被系统回收后偏好仍是 true，界面就会谎报运行中，点「关闭」还会再打开一次。
     */
    val isVisible: Boolean
        get() = displayRequested

    fun snapshot() =
        Bundle().apply {
            putInt("schema", 1)
            putInt("x", x)
            putInt("y", y)
            putString("dock", dock.name)
            putBoolean("expanded", expanded)
            putBoolean("speedControls", speedControlsVisible)
        }

    fun restore(state: Bundle?) {
        check(root == null && !playlistWindow.isShowing && !touching)
        if (state == null) return
        require(state.getInt("schema") == 1)
        x = state.getInt("x")
        y = state.getInt("y")
        dock = FloatingDock.valueOf(checkNotNull(state.getString("dock")))
        expanded = state.getBoolean("expanded")
        speedControlsVisible = state.getBoolean("speedControls")
        restoredExpanded = expanded
    }

    fun show() {
        revision++
        destroyed = false
        displayRequested = true
        showRetries = 0
        if (root != null) return
        val open = restoredExpanded ?: false
        restoredExpanded = null
        handler.post { if (displayRequested && root == null) render(open) }
    }

    fun hide() {
        detachWindow()
        cachedPanel = null
        cachedPanelGeometry = null
        cachedPanelPalette = null
    }

    private fun detachWindow() {
        revision++
        cancelPanelPrewarm()
        handler.removeCallbacks(applyGeometry)
        pendingGeometry = null
        displayedGeometry = null
        touching = false
        displayRequested = false
        dockAnimator?.cancel()
        dockAnimator = null
        // removeView 可能抛（视图已被系统移除）。这里必须吞掉异常并把字段清干净：
        // 一旦抛出去，root 会停在非空值上，之后 show() 会因为 root != null 永远直接返回，
        // 悬浮窗就再也打不开了。
        runCatching { dismissPlaylist() }
        handler.removeCallbacks(tick)
        root?.let { view -> runCatching { wm.removeView(view) } }
        root = null
        panel = null
    }

    fun destroy() {
        destroyed = true
        hide()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        touchMarker.close()
    }

    fun reposition() {
        if (destroyed || !displayRequested) return
        val geometry = service.floatingGeometry()
        if (geometry == displayedGeometry || geometry == pendingGeometry) return
        revision++
        cancelPanelPrewarm()
        pendingGeometry = geometry
        handler.removeCallbacks(applyGeometry)
        // 合并转屏的短时连发通知，亮度和刷新率变化不会进入此路径。
        handler.postDelayed(applyGeometry, DISPLAY_SETTLE_MS)
    }

    private fun applyDisplayGeometry() {
        if (destroyed || !displayRequested) {
            pendingGeometry = null
            return
        }
        if (touching || panel?.touching == true || dockAnimator?.isRunning == true) {
            handler.postDelayed(applyGeometry, DISPLAY_SETTLE_MS)
            return
        }
        val geometry = service.floatingGeometry()
        pendingGeometry = null
        if (geometry == displayedGeometry) return
        if (playlistWindow.isShowing) {
            if (geometry.needsNewContent(displayedGeometry) || !playlistWindow.reposition()) {
                dismissPlaylist()
                return
            }
            displayedGeometry = geometry
            return
        }
        val view = root ?: return
        val layout = params ?: return
        if (geometry.needsNewContent(displayedGeometry)) {
            render(expanded)
            return
        }
        updateLayout(view, layout, geometry)
        preparePanelWhenIdle()
    }

    private fun updateLayout(
        view: View,
        layout: WindowManager.LayoutParams,
        geometry: FloatingDisplayGeometry,
        remeasure: Boolean = false,
        updateAnchor: Boolean = true,
    ) {
        val width = geometry.windowWidth(expanded, context.dp(44), context.dp(236), context.dp(16))
        if (layout.width != width || remeasure) {
            layout.width = width
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        val position =
            geometry.position(
                layout.x,
                layout.y,
                layout.width,
                view.measuredHeight,
                context.dp(24),
                expanded,
                dock,
            )
        layout.x = position.x
        layout.y = position.y
        try {
            wm.updateViewLayout(view, layout)
            displayedGeometry = geometry
            if (updateAnchor) {
                x = position.x
                y = position.y
            }
        } catch (_: IllegalArgumentException) {
            // 系统已移除窗口时才重新挂载，普通转屏保留现有内容与触摸状态。
            render(expanded)
        }
    }

    /**
     * 深色模式切换后调一次：重画当前显示的悬浮窗。
     *
     * 面板在显示就整个重建（配色是建视图时写进去的，改属性得逐个子视图追）； 选歌窗开着就关掉它，而关窗路径会自己带出面板重建。
     */
    fun refreshTheme() {
        when {
            root != null -> render(expanded)
            playlistWindow.isShowing -> dismissPlaylist()
            else -> Unit
        }
    }

    private fun render(open: Boolean) {
        revision++
        if (destroyed || !displayRequested || !PlaybackBridge.isEnabled(service)) return
        if (!open) speedControlsVisible = false
        palette = PlayerUi.palette(context)
        // 先清标记再摘下窗口，避免关闭选歌窗时递归恢复面板。
        panelHiddenForPicker = false
        detachWindow()
        displayRequested = true
        expanded = open
        val geometry = service.floatingGeometry()
        discardOldPanel(geometry)
        val view: View
        val width: Int
        if (!open) {
            width = geometry.windowWidth(false, context.dp(44), context.dp(236), context.dp(16))
            view =
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_music_note)
                    background =
                        PlayerUi.background(context, palette.bubble, 22, true, palette.line)
                    imageTintList = ColorStateList.valueOf(PlayerUi.BLUE)
                    setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                    elevation = context.dp(3).toFloat()
                    contentDescription = "展开播放器"
                    setOnClickListener { render(true) }
                }
            attachDrag(view, true)
        } else {
            width = geometry.windowWidth(true, context.dp(44), context.dp(236), context.dp(16))
            view =
                panelFor(geometry).also {
                    it.showSpeedControls(speedControlsVisible)
                    it.refresh()
                    panel = it
                }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        params =
            FloatingWindowLayout.create(width, if (open) -2 else context.dp(44)).apply {
                // 仅气泡允许越过屏幕边缘，面板和选歌窗始终完整可见。
                if (!open) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                val position =
                    geometry.position(
                        this@FloatingControls.x,
                        this@FloatingControls.y,
                        width,
                        view.measuredHeight,
                        context.dp(24),
                        open,
                        dock,
                    )
                x = position.x
                y = position.y
            }
        view.alpha = if (!open && dock != FloatingDock.NONE) DOCK_ALPHA else 1f
        try {
            wm.addView(view, params)
            root = view
            displayedGeometry = geometry
            showRetries = 0
            handler.post(tick)
            if (!open) preparePanelWhenIdle()
        } catch (_: WindowManager.BadTokenException) {
            root = null
            // 部分系统稍后才绑定无障碍窗口；取得窗口令牌后重试。
            retryShow()
        } catch (_: IllegalStateException) {
            root = null
            retryShow()
        }
    }

    private fun discardOldPanel(geometry: FloatingDisplayGeometry) {
        if (geometry.needsNewContent(cachedPanelGeometry) || palette != cachedPanelPalette) {
            cachedPanel = null
            cachedPanelGeometry = null
            cachedPanelPalette = null
        }
    }

    private fun panelFor(geometry: FloatingDisplayGeometry): FloatingPanel {
        discardOldPanel(geometry)
        return cachedPanel
            ?: FloatingPanel(
                    context,
                    palette,
                    service,
                    speedControlsVisible,
                    onSelectSong = {
                        if (!playlistWindow.isShowing) showPlaylist() else dismissPlaylist()
                    },
                    onToggleSpeed = {
                        revision++
                        speedControlsVisible = !speedControlsVisible
                        panel?.let { view ->
                            view.showSpeedControls(speedControlsVisible)
                            params?.let {
                                // 倍速区变高只钳制面板，不改变气泡的拖动锚点。
                                updateLayout(view, it, service.floatingGeometry(), true, false)
                            }
                        }
                    },
                    onCollapse = { render(false) },
                    onAttachDrag = { attachDrag(it, false) },
                )
                .also {
                    cachedPanel = it
                    cachedPanelGeometry = geometry
                    cachedPanelPalette = palette
                }
    }

    private fun cancelPanelPrewarm() {
        panelPrewarm?.let { Looper.myQueue().removeIdleHandler(it) }
        panelPrewarm = null
    }

    private fun preparePanelWhenIdle() {
        if (
            destroyed ||
                !displayRequested ||
                expanded ||
                root == null ||
                cachedPanel != null ||
                panelPrewarm != null
        )
            return
        panelPrewarm = MessageQueue.IdleHandler {
            panelPrewarm = null
            if (
                !destroyed &&
                    displayRequested &&
                    !expanded &&
                    root != null &&
                    !interacting &&
                    !service.playing &&
                    !service.preparing
            ) {
                // View 仍在主线程创建；空闲时提前准备，不附加系统窗口。
                try {
                    palette = PlayerUi.palette(context)
                    panelFor(service.floatingGeometry())
                } catch (error: Exception) {
                    AppLog.w("悬浮窗", "提前准备播放器失败，将在展开时重试", error)
                }
            }
            false
        }
        Looper.myQueue().addIdleHandler(panelPrewarm!!)
    }

    private fun retryShow() {
        if (
            !destroyed &&
                displayRequested &&
                PlaybackBridge.isEnabled(service) &&
                ++showRetries <= 3
        ) {
            handler.postDelayed({ if (displayRequested && root == null) render(expanded) }, 500)
        } else {
            // 重试也没挂上：认输并把显示意图清掉，
            // 否则 [isVisible] 会一直报「运行中」，界面上却什么都没有。
            displayRequested = false
        }
    }

    private fun attachDrag(
        handle: View,
        clickable: Boolean,
    ) {
        var sx = 0f
        var sy = 0f
        var bx = 0
        var by = 0
        var moved = false
        var initialDock = FloatingDock.NONE
        handle.setOnTouchListener { v, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    revision++
                    cancelPanelPrewarm()
                    touching = true
                    dockAnimator?.cancel()
                    dockAnimator = null
                    initialDock = dock
                    root?.alpha = 1f
                    sx = e.rawX
                    sy = e.rawY
                    bx = p.x
                    by = p.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop)
                        moved = true
                    if (moved) {
                        dismissPlaylist()
                        val bounds = service.screenBounds()
                        p.x =
                            (bx + dx)
                                .toInt()
                                .coerceIn(
                                    if (expanded) 0 else -(root?.width ?: 0) / 2,
                                    (bounds.width() - (root?.width ?: 0) / (if (expanded) 1 else 2))
                                        .coerceAtLeast(0),
                                )
                        p.y =
                            (by + dy)
                                .toInt()
                                .coerceIn(
                                    0,
                                    (bounds.height() - (root?.height ?: 0)).coerceAtLeast(0),
                                )
                        root?.let { wm.updateViewLayout(it, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    touching = false
                    if (moved) {
                        dock =
                            if (expanded) FloatingDock.NONE
                            else
                                FloatingDock.afterDrag(
                                    p.x,
                                    service.screenBounds().width(),
                                    root?.width ?: 0,
                                    context.dp(12),
                                )
                        settleDock()
                    } else if (clickable) {
                        v.performClick()
                    }
                    preparePanelWhenIdle()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    // 系统接管手势时回到按下前的位置，不把取消误判成点击或贴边。
                    dock = initialDock
                    p.x = bx
                    p.y = by
                    settleDock()
                    preparePanelWhenIdle()
                    true
                }

                else -> {
                    true
                }
            }
        }
    }

    private fun settleDock() {
        val view = root ?: return
        val p = params ?: return
        val startX = p.x
        val startAlpha = view.alpha
        val targetX =
            (if (expanded) FloatingDock.NONE else dock).position(
                startX,
                service.screenBounds().width(),
                view.width,
            )
        val targetAlpha = if (!expanded && dock != FloatingDock.NONE) DOCK_ALPHA else 1f
        x = targetX
        y = p.y
        prefs.edit().putInt("x", x).putInt("y", y).putString("dock", dock.name).apply()
        dockAnimator?.cancel()
        dockAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    if (root !== view || !view.isAttachedToWindow) return@addUpdateListener
                    p.x = (startX + (targetX - startX) * fraction).toInt()
                    view.alpha = startAlpha + (targetAlpha - startAlpha) * fraction
                    wm.updateViewLayout(view, p)
                }
                start()
            }
    }

    private companion object {
        const val DOCK_ALPHA = 0.45f
        const val DISPLAY_SETTLE_MS = 80L
    }

    fun refresh() {
        panel?.refresh()
    }

    /** 选歌：居中独立小窗（卡片 + 当前曲目蓝色高亮）。打开时面板退出，关闭后恢复。 */
    private fun showPlaylist() {
        if (playlistJob?.isActive == true) return
        playlistJob = scope.launch {
            try {
                val songs = BusinessJobs.io { SongRepository(service).songs() }
                if (!destroyed && displayRequested) showPlaylist(songs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w("悬浮窗", "读取选歌列表失败", error)
            }
        }
    }

    private fun showPlaylist(songs: List<Song>) {
        palette = PlayerUi.palette(context)
        if (root != null) {
            panelHiddenForPicker = true
            handler.removeCallbacks(tick)
            root?.let { wm.removeView(it) }
            root = null
        }
        val opened =
            playlistWindow.show(
                songs = songs,
                selectedId = service.song.id,
                palette = palette,
                screenBounds = service::screenBounds,
                onSelect = { song ->
                    service.select(song)
                    dismissPlaylist()
                    refresh()
                },
                onDismiss = ::dismissPlaylist,
            )
        if (opened) displayedGeometry = service.floatingGeometry()
        if (!opened) panelHiddenForPicker = false
    }

    private fun dismissPlaylist() {
        playlistJob?.cancel()
        playlistJob = null
        playlistWindow.close()
        if (panelHiddenForPicker) {
            panelHiddenForPicker = false
            if (displayRequested && root == null) render(expanded)
        }
    }

    fun mark(x: Float, y: Float) = touchMarker.show(x, y)
}
