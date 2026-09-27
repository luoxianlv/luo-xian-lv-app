package app.luoxianlv.service

import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.luoxianlv.R
import app.luoxianlv.data.Kv
import app.luoxianlv.data.SongRepository
import app.luoxianlv.ui.floating.FloatingPanel
import app.luoxianlv.ui.floating.PlayerUi
import app.luoxianlv.ui.floating.PlayerUi.dp
import app.luoxianlv.ui.floating.PlayerUiPalette
import app.luoxianlv.ui.floating.createPlaylistContent
import kotlin.math.abs

/**
 * 悬浮窗（无障碍 overlay）。
 *
 * 视觉跟随 App 主题：白卡（92% 不透明 + 细描边 + 阴影）、品牌蓝主按钮、 深藏青标题；深色模式下白卡换成深石板蓝、字色反相（见 [palette]）。两个形态：
 * - 收起：40dp 气泡（浅色白底 / 深色深蓝底）+ 蓝音符，可拖动；
 * - 展开：播放控制、倍速滑动条和底部播放进度条；播放进度条可点按/拖动 seek。 「选歌」开居中独立小窗，不再是贴面板下拉。
 */
class FloatingControls(private val service: MusicAccessibilityService) {
    private val context = ContextThemeWrapper(service, R.style.AppTheme)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = Kv.of(service, "floating_position")
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 当前配色。
     *
     * 悬浮窗是独立系统窗口，不跟着 Activity 重组，所以在每次 [render] / [showPlaylist]
     * 开头重新取一次（读数开销很小），用户在设置里改了深色模式，下次重绘就是新配色； 想立即生效由 [refreshTheme] 触发。
     */
    private var palette: PlayerUiPalette = PlayerUi.palette(context)

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var popup: View? = null
    private var marker: View? = null
    private var expanded = false
    private var speedControlsVisible = false
    private var panel: FloatingPanel? = null
    private var displayRequested = false
    private var showRetries = 0
    private var destroyed = false

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
    private val removeMarker = Runnable {
        marker?.let { wm.removeView(it) }
        marker = null
    }

    /**
     * 悬浮窗此刻是否（应当）显示在屏幕上。
     *
     * [show] / [hide] 同步改写 [displayRequested]，拖拽、选歌窗临时顶掉面板都不影响它， 所以界面可以直接拿它判断「运行中」——不必再看持久化偏好：
     * 服务被系统回收后偏好仍是 true，界面就会谎报运行中，点「关闭」还会再打开一次。
     */
    val isVisible: Boolean
        get() = displayRequested

    fun show() {
        destroyed = false
        displayRequested = true
        showRetries = 0
        if (root != null) return
        handler.post { if (displayRequested && root == null) render(false) }
    }

    fun hide() {
        displayRequested = false
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
        handler.removeCallbacksAndMessages(null)
        removeMarker.run()
    }

    fun reposition() {
        if (root != null) render(expanded)
    }

    /**
     * 深色模式切换后调一次：重画当前显示的悬浮窗。
     *
     * 面板在显示就整个重建（配色是建视图时写进去的，改属性得逐个子视图追）； 选歌窗开着就关掉它，而关窗路径会自己带出面板重建。
     */
    fun refreshTheme() {
        when {
            root != null -> render(expanded)
            popup != null -> dismissPlaylist()
            else -> Unit
        }
    }

    private fun layout(
        width: Int,
        height: Int,
    ) =
        WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            .apply { gravity = Gravity.TOP or Gravity.START }

    private fun render(open: Boolean) {
        if (destroyed || !displayRequested || !MusicAccessibilityService.isEnabled(service)) return
        if (!open) speedControlsVisible = false
        palette = PlayerUi.palette(context)
        // render 会先 hide() → dismissPlaylist()，先清标记避免在里面递归恢复面板。
        panelHiddenForPicker = false
        hide()
        displayRequested = true
        expanded = open
        val bounds = service.screenBounds()
        val view: View
        val width: Int
        if (!open) {
            width = context.dp(44)
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
            width = minOf(context.dp(236), bounds.width() - context.dp(16))
            view =
                FloatingPanel(
                        context,
                        palette,
                        service,
                        speedControlsVisible,
                        onSelectSong = { if (popup == null) showPlaylist() else dismissPlaylist() },
                        onToggleSpeed = {
                            speedControlsVisible = !speedControlsVisible
                            render(true)
                        },
                        onCollapse = { render(false) },
                        onAttachDrag = { attachDrag(it, false) },
                    )
                    .also { panel = it }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        params =
            layout(width, if (open) -2 else context.dp(44)).apply {
                x = this@FloatingControls.x.coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
                y =
                    this@FloatingControls.y.coerceIn(
                        context.dp(24),
                        (bounds.height() - view.measuredHeight - context.dp(24)).coerceAtLeast(
                            context.dp(24)
                        ),
                    )
            }
        try {
            wm.addView(view, params)
            root = view
            showRetries = 0
            handler.post(tick)
        } catch (_: WindowManager.BadTokenException) {
            root = null
            // Some OEMs bind the accessibility window a moment after the
            // callback. Retry once the window token is available.
            retryShow()
        } catch (_: IllegalStateException) {
            root = null
            retryShow()
        }
    }

    private fun retryShow() {
        if (
            !destroyed &&
                displayRequested &&
                MusicAccessibilityService.isEnabled(service) &&
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
        handle.setOnTouchListener { v, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                                .coerceIn(0, (bounds.width() - (root?.width ?: 0)).coerceAtLeast(0))
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
                    if (moved) {
                        x = p.x
                        y = p.y
                        prefs.edit().putInt("x", x).putInt("y", y).apply()
                    } else if (clickable) {
                        v.performClick()
                    }
                    true
                }

                else -> {
                    true
                }
            }
        }
    }

    fun refresh() {
        panel?.refresh()
    }

    /** 选歌：居中独立小窗（卡片 + 当前曲目蓝色高亮）。打开时面板退出，关闭后恢复。 */
    private fun showPlaylist() {
        palette = PlayerUi.palette(context)
        if (root != null) {
            panelHiddenForPicker = true
            handler.removeCallbacks(tick)
            root?.let { wm.removeView(it) }
            root = null
        }
        val (card, search, list) =
            createPlaylistContent(
                context = context,
                palette = palette,
                songs = SongRepository(service).songs(),
                selectedId = service.song.id,
                onSelect = { song ->
                    service.select(song)
                    dismissPlaylist()
                    refresh()
                },
                onDismiss = ::dismissPlaylist,
            )
        val bounds = service.screenBounds()
        val maxHeight = minOf(context.dp(300), bounds.height() / 2)
        val scroll =
            ScrollView(context).apply {
                addView(list)
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        dismissPlaylist()
                        true
                    } else {
                        false
                    }
                }
            }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) },
        )
        card.measure(
            View.MeasureSpec.makeMeasureSpec(context.dp(264), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val height = minOf(card.measuredHeight, maxHeight)
        val centeredY = ((bounds.height() - height) / 2).coerceAtLeast(0)
        val p =
            layout(context.dp(264), height).apply {
                // 搜索框要收键盘：overlay 窗口默认带 FLAG_NOT_FOCUSABLE，键盘挂不上来，
                // 去掉它才能获焦。不追加 FLAG_NOT_TOUCH_MODAL：窗口外的点按继续被本窗口
                // 吞掉，否则会穿到下面的游戏里去。
                flags =
                    (flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                x = ((bounds.width() - context.dp(264)) / 2).coerceAtLeast(0)
                y = centeredY
            }
        popup = card
        // overlay 窗口拿不到 IME 的 insets，键盘弹起时不会自动上移：
        // 搜索框获得焦点就把面板挪到屏幕上方，否则列表下半截会被键盘盖住。
        search.setOnFocusChangeListener { _, hasFocus ->
            val lp =
                popup?.layoutParams as? WindowManager.LayoutParams
                    ?: return@setOnFocusChangeListener
            lp.y = if (hasFocus) (bounds.height() / 8).coerceAtLeast(0) else centeredY
            runCatching { popup?.let { wm.updateViewLayout(it, lp) } }
        }
        // 点一下就把键盘叫出来：overlay 窗口的 EditText 不一定会自动弹输入法。
        // 第二参传 0（SHOW_IMPLICIT 已废弃，语义相同）。
        search.setOnClickListener {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager)
                ?.showSoftInput(search, 0)
        }
        try {
            wm.addView(card, p)
        } catch (_: WindowManager.BadTokenException) {
            popup = null
            panelHiddenForPicker = false
        } catch (_: IllegalStateException) {
            popup = null
            panelHiddenForPicker = false
        }
    }

    private fun dismissPlaylist() {
        popup?.let { view ->
            // 搜索框可能还开着键盘：窗口移除前主动收一次，避免键盘留在游戏画面上。
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            wm.removeView(view)
        }
        popup = null
        if (panelHiddenForPicker) {
            panelHiddenForPicker = false
            if (displayRequested && root == null) render(expanded)
        }
    }

    fun mark(
        x: Float,
        y: Float,
    ) {
        handler.removeCallbacks(removeMarker)
        if (marker == null) {
            marker =
                View(context).apply {
                    background = PlayerUi.background(context, 0x55007aff, 20, true, palette.line)
                }
            val p =
                layout(context.dp(20), context.dp(20)).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            try {
                wm.addView(marker, p)
            } catch (_: WindowManager.BadTokenException) {
                marker = null
                return
            } catch (_: IllegalStateException) {
                marker = null
                return
            }
        }
        val p = marker!!.layoutParams as WindowManager.LayoutParams
        p.x = x.toInt() - context.dp(10)
        p.y = y.toInt() - context.dp(10)
        wm.updateViewLayout(marker, p)
        handler.postDelayed(removeMarker, 120)
    }
}
