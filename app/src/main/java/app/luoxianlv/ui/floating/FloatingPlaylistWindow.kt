package app.luoxianlv.ui.floating

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import app.luoxianlv.data.Song
import app.luoxianlv.ui.floating.PlayerUi.dp

/** 管理选歌窗口的布局、焦点和键盘；曲目读取与播放器状态由调用方负责。 */
internal class FloatingPlaylistWindow(
    private val context: Context,
    private val windows: WindowManager,
) {
    private var view: View? = null
    val isShowing: Boolean
        get() = view != null

    fun show(
        songs: List<Song>,
        selectedId: String,
        palette: PlayerUiPalette,
        screenBounds: () -> Rect,
        onSelect: (Song) -> Unit,
        onDismiss: () -> Unit,
    ): Boolean {
        val (card, search, list) =
            createPlaylistContent(
                context,
                palette,
                songs,
                selectedId,
                onSelect,
                onDismiss,
            )
        val bounds = screenBounds()
        val maxHeight = minOf(context.dp(300), bounds.height() / 2)
        val scroll =
            ScrollView(context).apply {
                addView(list)
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        onDismiss()
                        true
                    } else false
                }
            }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
        card.measure(
            View.MeasureSpec.makeMeasureSpec(context.dp(264), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val height = minOf(card.measuredHeight, maxHeight)
        val centeredY = ((bounds.height() - height) / 2).coerceAtLeast(0)
        val params =
            FloatingWindowLayout.create(context.dp(264), height).apply {
                // 允许搜索框获焦，并吞掉窗外点按，防止穿透到游戏。
                flags =
                    (flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                x = ((bounds.width() - context.dp(264)) / 2).coerceAtLeast(0)
                y = centeredY
            }
        view = card
        search.setOnFocusChangeListener { _, hasFocus ->
            val current =
                view?.layoutParams as? WindowManager.LayoutParams ?: return@setOnFocusChangeListener
            // 无障碍窗口没有 IME insets；获焦时主动上移，失焦后回到中央。
            current.y = if (hasFocus) (bounds.height() / 8).coerceAtLeast(0) else centeredY
            runCatching { view?.let { windows.updateViewLayout(it, current) } }
        }
        search.setOnClickListener {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(search, 0)
        }
        return try {
            windows.addView(card, params)
            true
        } catch (_: WindowManager.BadTokenException) {
            view = null
            false
        } catch (_: IllegalStateException) {
            view = null
            false
        }
    }

    fun close() {
        view?.let { current ->
            // 先收起键盘再移除窗口，避免输入法留在游戏画面上。
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(current.windowToken, 0)
            windows.removeView(current)
        }
        view = null
    }
}
