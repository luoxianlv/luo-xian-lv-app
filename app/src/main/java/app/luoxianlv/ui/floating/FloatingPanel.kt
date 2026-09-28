package app.luoxianlv.ui.floating

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.luoxianlv.R
import app.luoxianlv.data.timeLabel
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.service.bindPlaybackButton
import app.luoxianlv.ui.floating.PlayerUi.dp
import kotlin.math.roundToInt

/** 展开态悬浮控件；窗口定位和生命周期由 FloatingControls 管理。 */
internal class FloatingPanel(
    context: Context,
    private val palette: PlayerUiPalette,
    private val service: MusicAccessibilityService,
    private val speedControlsVisible: Boolean,
    private val onSelectSong: () -> Unit,
    private val onToggleSpeed: () -> Unit,
    private val onCollapse: () -> Unit,
    private val onAttachDrag: (View) -> Unit,
) : FrameLayout(context) {
    private var title: TextView? = null
    private var status: TextView? = null
    private var play: ImageView? = null
    private var progress: FloatingProgressView? = null

    init {
        build()
    }

    /** 展开态：播放控制和倍速滑动条，播放进度条叠在胶囊底部边缘。 */
    private fun build(): View {
        val panel =
            this.apply {
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
                bindPlaybackButton(
                    this,
                    { service.playing || service.preparing },
                    service::pause,
                    service::toggle,
                    service::canStartPlayback,
                )
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
        onAttachDrag(info)
        // 选歌：居中弹独立小窗
        val picker =
            PlayerUi.button(context, "选歌", R.drawable.ic_folder_music, iconOnly = true).apply {
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                iconTint = ColorStateList.valueOf(PlayerUi.BLUE)
                iconSize = context.dp(18)
                cornerRadius = context.dp(16)
                setPadding(0, 0, 0, 0)
                setOnClickListener { onSelectSong() }
            }
        row.addView(picker, LinearLayout.LayoutParams(context.dp(32), context.dp(32)))
        val speedToggle =
            ImageView(context).apply {
                setImageResource(R.drawable.ic_expand_more)
                imageTintList = ColorStateList.valueOf(PlayerUi.BLUE)
                rotation = if (speedControlsVisible) 180f else 0f
                setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                contentDescription = if (speedControlsVisible) "收起倍速设置" else "展开倍速设置"
                setOnClickListener { onToggleSpeed() }
            }
        row.addView(speedToggle, LinearLayout.LayoutParams(context.dp(28), context.dp(32)))
        // 收起
        val collapse =
            PlayerUi.button(context, "收起", iconOnly = true).apply {
                text = "×"
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                setTextColor(palette.muted)
                textSize = 16f
                cornerRadius = context.dp(14)
                setPadding(0, 0, 0, 0)
                setOnClickListener { onCollapse() }
            }
        row.addView(collapse, LinearLayout.LayoutParams(context.dp(28), context.dp(32)))
        val speedLabel = PlayerUi.text(context, "", 11f, palette.muted)
        val speedSlider =
            SeekBar(context).apply {
                max = 30 // 0.5x～2.0x，每格 0.05x
                progressTintList = ColorStateList.valueOf(PlayerUi.BLUE)
                progressBackgroundTintList = ColorStateList.valueOf(palette.line)
                thumbTintList = ColorStateList.valueOf(PlayerUi.BLUE)
                progress = ((service.speed.coerceIn(.5f, 2f) - .5f) * 20).roundToInt()
                contentDescription = "播放速度"
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            val speed = .5f + progress * .05f
                            speedLabel.text = "倍速 %.2f×".format(speed)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            service.setSpeed(.5f + seekBar.progress * .05f)
                        }
                    }
                )
                speedLabel.text = "倍速 %.2f×".format(.5f + progress * .05f)
            }
        val speedRow =
            PlayerUi.row(context).apply { setPadding(context.dp(12), 0, context.dp(12), 0) }
        speedRow.addView(speedLabel, LinearLayout.LayoutParams(context.dp(78), -2))
        speedRow.addView(speedSlider, LinearLayout.LayoutParams(0, context.dp(32), 1f))
        val strip =
            FloatingProgressView(
                    context,
                    palette,
                    duration = { service.durationMs },
                    onSeek = service::seek,
                    onSeekFinished = ::refresh,
                )
                .also { progress = it }
        // 播放控制 46dp，倍速滑动条 32dp，底部 10dp 留给播放进度条。
        panel.addView(row, android.widget.FrameLayout.LayoutParams(-1, context.dp(46)))
        if (speedControlsVisible) {
            panel.addView(
                speedRow,
                android.widget.FrameLayout.LayoutParams(-1, context.dp(32)).apply {
                    topMargin = context.dp(46)
                },
            )
        }
        panel.addView(
            strip,
            android.widget.FrameLayout.LayoutParams(-1, context.dp(10), Gravity.BOTTOM),
        )
        panel.minimumHeight = context.dp(if (speedControlsVisible) 88 else 46)
        return panel
    }

    fun refresh() {
        title?.text = service.song.title
        status?.text =
            service.error
                ?: if (service.loadingSong) {
                    if (service.waitingToPlay) "准备完成后立即播放…" else "正在准备谱面…"
                } else if (service.preparing) {
                    "识别按键中…"
                } else {
                    "${service.modeLabel} · ${timeLabel(
                        service.positionMs
                    )}/${timeLabel(service.durationMs)}"
                }
        play?.apply {
            val pending = service.playing || service.waitingToPlay
            setImageResource(if (pending) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (pending) "暂停" else "播放"
        }
        progress?.update(service.positionMs, service.durationMs)
    }
}
