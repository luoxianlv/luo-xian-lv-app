package app.luoxianlv.playback

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import app.luoxianlv.library.Song
import app.luoxianlv.playback.PlayerUi.dp

/** 管理选歌窗口的布局、焦点和键盘；曲目读取与播放器状态由调用方负责。 */
internal class FloatingPlaylistWindow(
    private val context: Context,
    private val windows: WindowManager,
) {
    private var view: View? = null
    private var searchView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenBounds: (() -> Rect)? = null
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
        val maxHeight = minOf(context.dp(300), bounds.height() / 2).coerceAtLeast(1)
        val width = minOf(context.dp(264), (bounds.width() - context.dp(16)).coerceAtLeast(1))
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
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val height = minOf(card.measuredHeight, maxHeight)
        val centeredY = ((bounds.height() - height) / 2).coerceAtLeast(0)
        val params =
            FloatingWindowLayout.create(width, height).apply {
                // 允许搜索框获焦，并吞掉窗外点按，防止穿透到游戏。
                flags =
                    (flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                x = ((bounds.width() - width) / 2).coerceAtLeast(0)
                y = centeredY
            }
        view = card
        searchView = search
        this.params = params
        this.screenBounds = screenBounds
        search.setOnFocusChangeListener { _, _ -> reposition() }
        search.setOnClickListener {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(search, 0)
        }
        return try {
            windows.addView(card, params)
            true
        } catch (_: WindowManager.BadTokenException) {
            clear()
            false
        } catch (_: IllegalStateException) {
            clear()
            false
        }
    }

    /** 原位适配转屏，保留搜索文字、焦点和列表滚动位置。 */
    fun reposition(): Boolean {
        val current = view ?: return false
        val layout = params ?: return false
        val bounds = screenBounds?.invoke() ?: return false
        layout.width = minOf(context.dp(264), (bounds.width() - context.dp(16)).coerceAtLeast(1))
        val maxHeight = minOf(context.dp(300), bounds.height() / 2).coerceAtLeast(1)
        current.measure(
            View.MeasureSpec.makeMeasureSpec(layout.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        layout.height = minOf(current.measuredHeight, maxHeight)
        layout.x = ((bounds.width() - layout.width) / 2).coerceAtLeast(0)
        // 无障碍窗口没有 IME insets；获焦时上移，失焦后居中，始终取最新屏幕尺寸。
        layout.y =
            if (searchView?.hasFocus() == true) (bounds.height() / 8).coerceAtLeast(0)
            else ((bounds.height() - layout.height) / 2).coerceAtLeast(0)
        return runCatching { windows.updateViewLayout(current, layout) }.isSuccess
    }

    private fun clear() {
        view = null
        searchView = null
        params = null
        screenBounds = null
    }

    fun close() {
        val current = view
        clear()
        current?.let {
            // 先收起键盘再移除窗口，避免输入法留在游戏画面上。
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(current.windowToken, 0)
            windows.removeView(current)
        }
    }
}
