package app.luoxianlv.playback

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import app.luoxianlv.library.Song
import app.luoxianlv.playback.PlayerUi.dp

internal data class PlaylistContent(
    val card: LinearLayout,
    val search: EditText,
    val list: LinearLayout,
)

/** 负责本地列表绘制；窗口位置和播放由服务管理。 */
internal fun createPlaylistContent(
    context: Context,
    palette: PlayerUiPalette,
    songs: List<Song>,
    selectedId: String,
    onSelect: (Song) -> Unit,
    onDismiss: () -> Unit,
): PlaylistContent {
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
            setOnClickListener { onDismiss() }
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
                PlayerUi.text(
                        context,
                        if (songs.isEmpty()) "先去曲库添加谱子" else "没有匹配的谱子",
                        13f,
                        palette.muted,
                    )
                    .apply {
                        setPadding(0, context.dp(16), 0, context.dp(16))
                        gravity = Gravity.CENTER
                    },
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        matched.forEach { song ->
            val current = song.id == selectedId
            val row =
                PlayerUi.text(
                        context,
                        song.title,
                        13f,
                        if (current) 0xffffffff.toInt() else palette.text,
                        bold = current,
                    )
                    .apply {
                        setPadding(context.dp(12), 0, context.dp(12), 0)
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        background =
                            PlayerUi.background(
                                context,
                                if (current) PlayerUi.BLUE else 0x00000000,
                                10,
                                false,
                            )
                        setOnClickListener {
                            onSelect(song)
                        }
                    }
            list.addView(row, LinearLayout.LayoutParams(-1, context.dp(40)))
        }
    }
    fillList("")
    search.doAfterTextChanged { fillList(it?.toString().orEmpty()) }
    return PlaylistContent(card, search, list)
}
