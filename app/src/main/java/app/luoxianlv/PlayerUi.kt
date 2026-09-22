package app.luoxianlv

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.luoxianlv.data.AppearanceStore
import com.google.android.material.button.MaterialButton

/**
 * 悬浮窗配色。
 *
 * 悬浮窗是挂在 `WindowManager` 上的传统 View，拿不到 `MaterialTheme`，
 * 所以深浅两套在这里写死，取值与 `ui/theme` 的色板对齐：
 * 字体色对 `PlayerText`/`PlayerTextNight`，描边对 `PlayerDivider`/`PlayerDividerNight`，
 * 容器底则是「白卡」在深色下的对应物（深石板蓝，而不是近黑 —— 它要浮在游戏画面之上）。
 */
data class PlayerUiPalette(
    /** 主字色：曲名、「选择谱子」标题。 */
    val text: Int,
    /** 次级字色：播放状态、空列表说明、关闭按钮。 */
    val muted: Int,
    /** 描边与进度轨道底色。 */
    val line: Int,
    /** 收起态气泡底。 */
    val bubble: Int,
    /** 展开态面板底。 */
    val panel: Int,
    /** 选歌窗底。 */
    val popup: Int,
)

object PlayerUi {
    const val BLUE = 0xff0a84ff.toInt()
    const val TEXT = 0xff17191d.toInt()
    const val MUTED = 0xff697386.toInt()
    const val BG = 0xfff6f7fb.toInt()
    const val LINE = 0xffe5e8ef.toInt()

    /** 浅色悬浮窗：本应用原来的样子，未改动。 */
    val LightPalette =
        PlayerUiPalette(
            text = TEXT,
            muted = MUTED,
            line = LINE,
            bubble = 0xf2ffffff.toInt(),
            panel = 0xebffffff.toInt(),
            popup = 0xf5ffffff.toInt(),
        )

    /**
     * 深色悬浮窗：白卡换成深石板蓝。
     *
     * 主按钮、进度条、当前曲目高亮仍是品牌蓝 [BLUE] 与蓝底白字：
     * 蓝在深底上本来就够跳，换成浅蓝反而会和「未选中」的行拉不开。
     */
    val DarkPalette =
        PlayerUiPalette(
            text = 0xffe8ecf4.toInt(),
            muted = 0xff9aa6bc.toInt(),
            line = 0xff35415a.toInt(),
            bubble = 0xf0303c52.toInt(),
            panel = 0xeb28334a.toInt(),
            popup = 0xf52b3650.toInt(),
        )

    /**
     * 当前应当使用的悬浮窗配色。
     *
     * 读的是「深色模式」偏好叠加系统设置，与 Compose 那边同一套判据
     * （见 `data/AppearanceStore.kt` 的 ThemeMode）。悬浮窗活在无障碍服务里，
     * 不受 Activity 重建影响，所以每次渲染前重新取一次，主题改了下次重绘就生效。
     */
    fun palette(context: Context): PlayerUiPalette = if (isDarkTheme(context)) DarkPalette else LightPalette

    private fun isDarkTheme(context: Context): Boolean {
        val systemDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return AppearanceStore.load(context).themeMode.isDark(systemDark)
    }

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    fun text(
        context: Context,
        value: String,
        size: Float = 15f,
        color: Int = TEXT,
        bold: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        letterSpacing = 0f
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
    }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: Context) = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    /**
     * 圆角底。
     *
     * [stroke] 默认走浅色的分隔线；深色调用点要把主题的 line 传进来
     * （`palette.line`），否则深底上会挂一圈浅灰描边。
     */
    fun background(
        context: Context,
        color: Int,
        radius: Int = 8,
        border: Boolean = false,
        stroke: Int = LINE,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radius).toFloat()
        if (border) setStroke(context.dp(1), stroke)
    }

    fun button(
        context: Context,
        label: String,
        icon: Int? = null,
        primary: Boolean = false,
        iconOnly: Boolean = false,
    ) = MaterialButton(context).apply {
        text = if (iconOnly) "" else label
        contentDescription = label
        tooltipText = label
        isAllCaps = false
        textSize = 14f
        letterSpacing = 0f
        cornerRadius = context.dp(if (primary) 12 else 10)
        insetTop = 0
        insetBottom = 0
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(context.dp(if (iconOnly) 12 else 16), 0, context.dp(if (iconOnly) 12 else 16), 0)
        backgroundTintList = ColorStateList.valueOf(if (primary) BLUE else 0xffffffff.toInt())
        val foreground = if (primary) 0xffffffff.toInt() else TEXT
        setTextColor(foreground)
        iconTint = ColorStateList.valueOf(foreground)
        if (icon != null) {
            setIconResource(icon)
            iconSize = context.dp(22)
            iconPadding = if (iconOnly) 0 else context.dp(6)
        }
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        elevation = 0f
        stateListAnimator = null
    }

    fun divider(context: Context) =
        View(context).apply {
            setBackgroundColor(LINE)
            layoutParams =
                LinearLayout.LayoutParams(-1, context.dp(1))
        }
}
