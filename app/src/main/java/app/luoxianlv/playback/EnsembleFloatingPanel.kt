package app.luoxianlv.playback

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.luoxianlv.R
import app.luoxianlv.ensemble.EnsembleActionUiState
import app.luoxianlv.ensemble.EnsembleController
import app.luoxianlv.ensemble.EnsembleRoomUiState
import app.luoxianlv.playback.PlayerUi.dp

internal data class EnsembleTransportAction(
    val command: String,
    val label: String,
    val allowed: EnsembleActionUiState = EnsembleActionUiState(true),
)

internal fun ensembleTransportActions(
    room: EnsembleRoomUiState,
    ready: Boolean,
    preparing: Boolean,
    playing: Boolean,
): List<EnsembleTransportAction> = buildList {
    val stop = EnsembleTransportAction("stop", if (playing) "停止演奏" else "取消准备")
    val prepare = EnsembleTransportAction("prepare", "准备演奏", room.prepare)
    val running = room.phase in setOf("playing", "proposed")
    if (room.isOwner && running) {
        add(
            EnsembleTransportAction(
                "pause",
                if (room.phase == "proposed" || room.pendingStart) "取消开始" else "全员暂停",
                room.pause,
            )
        )
        if (room.phase == "playing" && !playing && !room.pendingStart && !room.pendingRejoin) {
            if (preparing) add(stop)
            else if (!ready) add(prepare)
            else room.self?.let { add(EnsembleTransportAction("rejoin", "重新接入", it.rejoin)) }
        }
    } else if (playing || preparing || (ready && !room.isOwner)) {
        add(stop)
    } else if (!ready) {
        add(prepare)
    } else {
        add(
            when {
                room.finished -> EnsembleTransportAction("restart", "重新开始", room.restart)
                room.phase == "paused" -> EnsembleTransportAction("resume", "继续合奏", room.resume)
                else -> EnsembleTransportAction("start", "开始合奏", room.start)
            }
        )
        if (room.phase == "paused" && !room.finished)
            add(EnsembleTransportAction("restart", "从头重来", room.restart))
        add(stop)
    }
}

/** 只有房间内使用的游戏面板。所有发往服务端的动作继续使用房间页同一套权限和就绪判断。 */
internal class EnsembleFloatingPanel(
    context: Context,
    private val palette: PlayerUiPalette,
    private val read: () -> EnsembleController.ViewState?,
    private val playing: () -> Boolean,
    private val feedback: () -> String?,
    private val dispatch: (String) -> Unit,
    onCollapse: () -> Unit,
    attachDrag: (View) -> Unit,
) : LinearLayout(context) {
    companion object {
        const val WIDTH_DP = 184
    }

    private val count = PlayerUi.text(context, "", 11f, palette.muted)
    private val title = PlayerUi.text(context, "", 13f, palette.text, bold = true)
    private val notice = PlayerUi.text(context, "", 11f, palette.muted)
    private val buttons = PlayerUi.row(context)
    private val extra = PlayerUi.column(context)
    private var expanded = false
    private var snapshot: EnsembleController.ViewState? = null
    private var room: EnsembleRoomUiState? = null
    private var renderedActions = emptyList<EnsembleTransportAction>()
    private var renderedState: EnsembleController.ViewState? = null
    var touching = false
        private set

    init {
        orientation = VERTICAL
        setPadding(context.dp(8), context.dp(2), context.dp(6), context.dp(5))
        background = PlayerUi.background(context, palette.popup, 14, true, palette.line)
        elevation = context.dp(3).toFloat()
        val header = PlayerUi.row(context)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        header.addView(title, LayoutParams(0, -2, 1f))
        header.addView(count)
        val close =
            ImageButton(context).apply {
                contentDescription = "收起"
                setImageResource(androidx.appcompat.R.drawable.abc_ic_clear_material)
                imageTintList = ColorStateList.valueOf(palette.muted)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(context.dp(7), context.dp(7), context.dp(5), context.dp(7))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { onCollapse() }
            }
        header.addView(close, LayoutParams(context.dp(26), context.dp(28)))
        addView(header)
        attachDrag(title)
        addView(buttons)
        addView(notice)
        addView(extra)
        refresh()
    }

    private fun currentRoom(): EnsembleRoomUiState? {
        val state = read()
        if (state !== snapshot) {
            snapshot = state
            room =
                state?.room?.let {
                    EnsembleRoomUiState.parse(
                        it,
                        state.account,
                        state.supported &&
                            !state.serviceUnavailable &&
                            state.connection == "connected",
                        state.working,
                        state.preparing,
                        state.ready,
                    )
                }
        }
        return room
    }

    private fun actions(current: EnsembleRoomUiState): List<EnsembleTransportAction> {
        val state = checkNotNull(snapshot)
        return ensembleTransportActions(
            current,
            state.ready,
            state.preparing,
            playing() || current.self?.playing == true,
        )
    }

    fun refresh() {
        val current = currentRoom() ?: return
        val state = checkNotNull(snapshot)
        val next = actions(current)
        count.setIfChanged("${current.readyCount}/${current.members.size}")
        count.contentDescription = "${count.text} 已准备"
        title.setIfChanged(current.scoreTitle)
        val part = current.self?.trackName.orEmpty()
        title.tooltipText =
            listOf(current.scoreTitle, if (current.isOwner) "房主" else "成员", part)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        val message =
            feedback()?.takeIf { it.isNotBlank() }
                ?: state.message.takeIf { it.isNotBlank() }
                ?: when {
                    state.connection != "connected" -> "连接已断开"
                    state.preparing -> "正在准备…"
                    !next.first().allowed.enabled -> next.first().allowed.reason
                    else -> ""
                }
        notice.setIfChanged(message)
        notice.visibility = if (message.isEmpty()) GONE else VISIBLE
        val canChoose = current.isOwner && state.connection == "connected" && !state.working
        if (touching || (state === renderedState && next == renderedActions)) return
        if (current.id != renderedState?.room?.optString("id") || next != renderedActions)
            expanded = false
        renderedState = state
        renderedActions = next
        buttons.removeAllViews()
        extra.removeAllViews()
        extra.visibility = if (expanded) VISIBLE else GONE

        fun actionButton(action: EnsembleTransportAction, primary: Boolean): View {
            val button = compactButton(action.label, primary)
            button.isEnabled = action.allowed.enabled
            button.alpha = if (button.isEnabled) 1f else .55f
            val invoke = {
                val latest = currentRoom()
                if (
                    latest?.id == current.id &&
                        actions(latest).any { it.command == action.command && it.allowed.enabled }
                ) {
                    expanded = false
                    extra.visibility = GONE
                    dispatch(action.command)
                }
            }
            bindPlaybackButton(
                button,
                { action.command == "pause" || action.command == "stop" },
                invoke,
                invoke,
            )
            return button
        }
        buttons.addView(actionButton(next.first(), true), LayoutParams(0, context.dp(32), 1f))
        if (current.isOwner) {
            val pick =
                compactButton("换曲", false).apply {
                    isEnabled = canChoose
                    setOnClickListener {
                        if (currentRoom()?.let { it.id == current.id && it.isOwner } == true)
                            dispatch("choose")
                    }
                }
            buttons.addView(pick, LayoutParams(context.dp(38), context.dp(32)))
        } else if (part.isNotBlank()) {
            val track =
                PlayerUi.text(context, part, 11f, palette.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER
                }
            buttons.addView(track, LayoutParams(0, -2, .65f))
        }
        if (next.size > 1) {
            val more =
                compactButton("···", false).apply {
                    contentDescription = "更多操作"
                    setOnClickListener {
                        expanded = !expanded
                        extra.visibility = if (expanded) VISIBLE else GONE
                    }
                }
            buttons.addView(more, LayoutParams(context.dp(30), context.dp(32)))
            val options = PlayerUi.row(context)
            for (action in next.drop(1)) options.addView(
                actionButton(action, false),
                LayoutParams(0, context.dp(32), 1f),
            )
            extra.addView(options)
        }
    }

    private fun compactButton(label: String, primary: Boolean) =
        PlayerUi.button(context, label, primary = primary).apply {
            setPadding(context.dp(2), 0, context.dp(2), 0)
            textSize = 12f
            if (primary) {
                backgroundTintList = null
                setBackgroundResource(R.drawable.ensemble_primary_button)
            } else {
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                setTextColor(palette.text)
            }
        }

    private fun TextView.setIfChanged(value: String) {
        if (text.toString() != value) text = value
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observePlaybackTouch(event, display?.displayId ?: 0)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        try {
            return super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL))
                touching = false
        }
    }
}
