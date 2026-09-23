package app.luoxianlv.service

import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.luoxianlv.PlayerUi
import app.luoxianlv.PlayerUi.dp
import app.luoxianlv.PlayerUiPalette
import app.luoxianlv.R
import app.luoxianlv.data.Kv
import app.luoxianlv.data.SongRepository
import app.luoxianlv.data.timeLabel
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗（无障碍 overlay）。
 *
 * 视觉跟随 App 主题：白卡（92% 不透明 + 细描边 + 阴影）、品牌蓝主按钮、
 * 深藏青标题；深色模式下白卡换成深石板蓝、字色反相（见 [palette]）。两个形态：
 * - 收起：40dp 气泡（浅色白底 / 深色深蓝底）+ 蓝音符，可拖动；
 * - 展开：单行小胶囊（播放钮 + 曲名/状态 + 选歌 + 收起），底部 3dp 蓝色进度条，
 *   进度条区域可点按/拖动 seek。
 * 「选歌」开居中独立小窗，不再是贴面板下拉。
 */
class FloatingControls(
    private val service: MusicAccessibilityService,
) {
    private val context = ContextThemeWrapper(service, R.style.AppTheme)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = Kv.of(service, "floating_position")
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 当前配色。
     *
     * 悬浮窗是独立系统窗口，不跟着 Activity 重组，所以在每次 [render] / [showPlaylist]
     * 开头重新取一次（读数开销很小），用户在设置里改了深色模式，下次重绘就是新配色；
     * 想立即生效由 [refreshTheme] 触发。
     */
    private var palette: PlayerUiPalette = PlayerUi.palette(context)

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var popup: View? = null
    private var marker: View? = null
    private var expanded = false
    private var title: TextView? = null
    private var status: TextView? = null
    private var play: ImageView? = null
    private var progressFill: View? = null
    private var progressTrack: View? = null
    private var seeking = false
    private var displayRequested = false
    private var showRetries = 0

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
    private val removeMarker =
        Runnable {
            marker?.let { wm.removeView(it) }
            marker = null
        }

    /**
     * 悬浮窗此刻是否（应当）显示在屏幕上。
     *
     * [show] / [hide] 同步改写 [displayRequested]，拖拽、选歌窗临时顶掉面板都不影响它，
     * 所以界面可以直接拿它判断「运行中」——不必再看持久化偏好：
     * 服务被系统回收后偏好仍是 true，界面就会谎报运行中，点「关闭」还会再打开一次。
     */
    val isVisible: Boolean get() = displayRequested

    fun show() {
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
        title = null
        status = null
        play = null
        progressFill = null
        progressTrack = null
    }

    fun destroy() {
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
     * 面板在显示就整个重建（配色是建视图时写进去的，改属性得逐个子视图追）；
     * 选歌窗开着就关掉它，而关窗路径会自己带出面板重建。
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
    ) = WindowManager
        .LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun render(open: Boolean) {
        if (!displayRequested) return
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
                    background = PlayerUi.background(context, palette.bubble, 22, true, palette.line)
                    imageTintList = ColorStateList.valueOf(PlayerUi.BLUE)
                    setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                    elevation = context.dp(3).toFloat()
                    contentDescription = "展开播放器"
                    setOnClickListener { render(true) }
                }
            attachDrag(view, true)
        } else {
            width = minOf(context.dp(236), bounds.width() - context.dp(16))
            view = buildPanel(width)
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
                        (bounds.height() - view.measuredHeight - context.dp(24)).coerceAtLeast(context.dp(24)),
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

    /** 展开态：单行小胶囊，进度条叠在胶囊底部边缘（不占额外高度，内容才居中）。 */
    private fun buildPanel(width: Int): View {
        val panel =
            android.widget.FrameLayout(context).apply {
                background = PlayerUi.background(context, palette.panel, 26, true, palette.line)
                elevation = context.dp(4).toFloat()
            }
        val row =
            PlayerUi.row(context).apply {
                setPadding(context.dp(6), 0, context.dp(4), 0)
            }
        // 播放/暂停：蓝色实心圆钮（ImageView 画圆，图标严格居中——
        // MaterialButton 的 icon 布局在圆形小按钮上对不齐）。
        play =
            ImageView(context).apply {
                contentDescription = "播放"
                setImageResource(R.drawable.ic_play)
                imageTintList = ColorStateList.valueOf(0xffffffff.toInt())
                background = PlayerUi.background(context, PlayerUi.BLUE, 17, false)
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { service.toggle() }
            }
        row.addView(play, LinearLayout.LayoutParams(context.dp(34), context.dp(34)))
        // 曲名 + 状态（拖动把手）
        val info =
            PlayerUi.column(context).apply {
                setPadding(context.dp(8), 0, context.dp(4), 0)
            }
        title =
            PlayerUi.text(context, service.song.title, 12f, palette.text, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        info.addView(title, LinearLayout.LayoutParams(-1, -2))
        status = PlayerUi.text(context, "", 10f, palette.muted)
        info.addView(status, LinearLayout.LayoutParams(-1, -2))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        attachDrag(info, false)
        // 选歌：居中弹独立小窗
        val picker =
            PlayerUi.button(context, "选歌", R.drawable.ic_folder_music, iconOnly = true).apply {
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                iconTint = ColorStateList.valueOf(PlayerUi.BLUE)
                iconSize = context.dp(18)
                cornerRadius = context.dp(16)
                setPadding(0, 0, 0, 0)
                setOnClickListener { if (popup == null) showPlaylist() else dismissPlaylist() }
            }
        row.addView(picker, LinearLayout.LayoutParams(context.dp(32), context.dp(32)))
        // 收起
        val collapse =
            PlayerUi.button(context, "收起", iconOnly = true).apply {
                text = "×"
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                setTextColor(palette.muted)
                textSize = 16f
                cornerRadius = context.dp(14)
                setPadding(0, 0, 0, 0)
                setOnClickListener { render(false) }
            }
        row.addView(collapse, LinearLayout.LayoutParams(context.dp(28), context.dp(32)))
        // 底部进度条：3dp 蓝条，区域可点按/拖动 seek
        val track =
            object : LinearLayout(context) {
                init {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dp(12), 0, context.dp(12), 0)
                }
            }
        val rail =
            View(context).apply {
                background = PlayerUi.background(context, palette.line, 2, false)
            }
        track.addView(rail, LinearLayout.LayoutParams(-1, context.dp(3)))
        progressTrack = track
        val fillHolder =
            object : LinearLayout(context) {}.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                clipChildren = false
                // 与轨道同样的左右内边距，填充条才对齐轨道。
                setPadding(context.dp(12), 0, context.dp(12), 0)
            }
        val fill =
            View(context).apply {
                background = PlayerUi.background(context, PlayerUi.BLUE, 2, false)
            }
        fillHolder.addView(fill, LinearLayout.LayoutParams(0, context.dp(3)))
        progressFill = fill
        // 叠放：轨道在下、填充在上，整体作为 seek 触控区
        val strip =
            android.widget.FrameLayout(context).apply {
                addView(track, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(fillHolder, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
        strip.tag = fillHolder
        strip.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    seeking = true
                    true
                }

                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val fraction = (e.x / v.width).coerceIn(0f, 1f)
                    service.seek((fraction * service.durationMs).toLong())
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        seeking = false
                        refresh()
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
        // 内容行固定 46dp，进度条叠在底部 10dp 内，胶囊整体 46dp 高。
        panel.addView(row, android.widget.FrameLayout.LayoutParams(-1, context.dp(46)))
        panel.addView(
            strip,
            android.widget.FrameLayout.LayoutParams(-1, context.dp(10), Gravity.BOTTOM),
        )
        return panel
    }

    private fun retryShow() {
        if (++showRetries <= 3) {
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
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    if (moved) {
                        dismissPlaylist()
                        val bounds = service.screenBounds()
                        p.x = (bx + dx).toInt().coerceIn(0, (bounds.width() - (root?.width ?: 0)).coerceAtLeast(0))
                        p.y = (by + dy).toInt().coerceIn(0, (bounds.height() - (root?.height ?: 0)).coerceAtLeast(0))
                        root?.let { wm.updateViewLayout(it, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        x = p.x
                        y = p.y
                        prefs
                            .edit()
                            .putInt("x", x)
                            .putInt("y", y)
                            .apply()
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
        title?.text = service.song.title
        status?.text =
            service.error
                ?: if (service.preparing) {
                    "识别按键中…"
                } else {
                    "${service.modeLabel} · ${timeLabel(
                        service.positionMs,
                    )}/${timeLabel(service.durationMs)}"
                }
        play?.apply {
            setImageResource(if (service.playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (service.playing) "暂停" else "播放"
        }
        if (!seeking) {
            val fraction = (service.positionMs.toFloat() / service.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            val strip = progressFill?.parent as? View ?: return
            val width = ((strip.width - context.dp(24)).coerceAtLeast(0) * fraction).roundToInt()
            progressFill?.let { fill ->
                val lp = fill.layoutParams
                if (lp.width != width) {
                    lp.width = width
                    fill.layoutParams = lp
                }
            }
        }
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
        val songs = SongRepository(service).songs()
        val card =
            PlayerUi.column(context).apply {
                background = PlayerUi.background(context, palette.popup, 20, true, palette.line)
                elevation = context.dp(6).toFloat()
                setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            }
        val header = PlayerUi.row(context)
        header.addView(
            PlayerUi.text(context, "选择谱子", 14f, palette.text, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val close =
            PlayerUi.button(context, "关闭", iconOnly = true).apply {
                text = "×"
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                setTextColor(palette.muted)
                textSize = 16f
                cornerRadius = context.dp(14)
                setPadding(0, 0, 0, 0)
                setOnClickListener { dismissPlaylist() }
            }
        header.addView(close, LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
        card.addView(header)

        // 搜索：只过滤已经读进内存的 songs，纯本地字符串匹配，不发网络请求。
        val search =
            EditText(context).apply {
                hint = "搜索谱子"
                textSize = 13f
                setTextColor(palette.text)
                setHintTextColor(palette.muted)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(context.dp(10), 0, context.dp(10), 0)
                background = PlayerUi.background(context, 0x00000000, 10, true, palette.line)
            }
        card.addView(
            search,
            LinearLayout.LayoutParams(-1, context.dp(34)).apply { topMargin = context.dp(6) },
        )

        val list = PlayerUi.column(context)

        /** 按关键词重建列表：关键词为空就是全部曲目，匹配不到给一句说明。 */
        fun fillList(query: String) {
            list.removeAllViews()
            val keyword = query.trim()
            val matched =
                if (keyword.isEmpty()) {
                    songs
                } else {
                    songs.filter { it.title.contains(keyword, ignoreCase = true) }
                }
            if (matched.isEmpty()) {
                list.addView(
                    PlayerUi
                        .text(
                            context,
                            if (songs.isEmpty()) "先去曲库添加谱子" else "没有匹配的谱子",
                            13f,
                            palette.muted,
                        ).apply {
                            setPadding(0, context.dp(16), 0, context.dp(16))
                            gravity = Gravity.CENTER
                        },
                    LinearLayout.LayoutParams(-1, -2),
                )
                return
            }
            matched.forEach { song ->
                val current = song.id == service.song.id
                val row =
                    PlayerUi.text(context, song.title, 13f, if (current) 0xffffffff.toInt() else palette.text, bold = current).apply {
                        setPadding(context.dp(12), 0, context.dp(12), 0)
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        background = PlayerUi.background(context, if (current) PlayerUi.BLUE else 0x00000000, 10, false)
                        setOnClickListener {
                            service.select(song)
                            dismissPlaylist()
                            refresh()
                        }
                    }
                list.addView(row, LinearLayout.LayoutParams(-1, context.dp(40)))
            }
        }
        fillList("")
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) = fillList(s?.toString().orEmpty())
            },
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
            val lp = popup?.layoutParams as? WindowManager.LayoutParams ?: return@setOnFocusChangeListener
            lp.y = if (hasFocus) (bounds.height() / 8).coerceAtLeast(0) else centeredY
            runCatching { popup?.let { wm.updateViewLayout(it, lp) } }
        }
        // 点一下就把键盘叫出来：overlay 窗口的 EditText 不一定会自动弹输入法。
        // 第二参传 0（SHOW_IMPLICIT 已废弃，语义相同）。
        search.setOnClickListener {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(search, 0)
        }
        wm.addView(card, p)
    }

    private fun dismissPlaylist() {
        popup?.let { view ->
            // 搜索框可能还开着键盘：窗口移除前主动收一次，避免键盘留在游戏画面上。
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
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
            marker = View(context).apply { background = PlayerUi.background(context, 0x55007aff, 20, true, palette.line) }
            val p = layout(context.dp(20), context.dp(20)).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
            wm.addView(marker, p)
        }
        val p = marker!!.layoutParams as WindowManager.LayoutParams
        p.x = x.toInt() - context.dp(10)
        p.y = y.toInt() - context.dp(10)
        wm.updateViewLayout(marker, p)
        handler.postDelayed(removeMarker, 120)
    }
}
