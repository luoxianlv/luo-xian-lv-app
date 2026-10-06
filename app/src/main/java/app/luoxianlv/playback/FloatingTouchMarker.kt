package app.luoxianlv.playback

import android.content.Context
import android.os.Handler
import android.view.View
import android.view.WindowManager
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.playback.PlayerUi.dp

/** 复用一个不可触摸的 20dp 标记窗口，120ms 后透明隐藏，退出时才移除。 */
internal class FloatingTouchMarker(
    private val context: Context,
    private val windows: WindowManager,
    private val handler: Handler,
    private val palette: () -> PlayerUiPalette,
) {
    private var view: View? = null
    private var layout: WindowManager.LayoutParams? = null
    private var lastPalette: PlayerUiPalette? = null
    private var pendingRemoval: View? = null
    private val hide = Runnable { view?.alpha = 0f }

    val released
        get() = view == null && pendingRemoval == null

    fun show(x: Float, y: Float) {
        pendingRemoval?.let {
            detach(it)
            if (pendingRemoval != null) return
        }
        handler.removeCallbacks(hide)
        val target = view ?: View(context)
        try {
            val nextPalette = palette()
            val left = x.toInt() - context.dp(10)
            val top = y.toInt() - context.dp(10)
            if (lastPalette != nextPalette) {
                target.background =
                    PlayerUi.background(context, 0xaa007aff.toInt(), 20, true, nextPalette.line)
            }
            val params = layout
            if (params == null) {
                val initial =
                    FloatingWindowLayout.create(context.dp(20), context.dp(20)).apply {
                        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        // Android 12 起按窗口 alpha 判断遮挡；View.alpha=0 本身不能保证触摸穿透。
                        alpha = 0.5f
                        this.x = left
                        this.y = top
                    }
                // 首次添加已包含正确坐标，不紧接着再发一次窗口更新。
                windows.addView(target, initial)
                view = target
                layout = initial
            } else if (params.x != left || params.y != top) {
                params.x = left
                params.y = top
                windows.updateViewLayout(target, params)
            }
            lastPalette = nextPalette
            target.alpha = 1f
            handler.postDelayed(hide, 120)
        } catch (failure: RuntimeException) {
            detach(target)
            AppLog.w("悬浮窗", "显示点击标记失败：${failure.javaClass.simpleName}")
        }
    }

    fun close() {
        detach(view ?: pendingRemoval)
    }

    private fun detach(target: View?) {
        handler.removeCallbacks(hide)
        view = null
        layout = null
        lastPalette = null
        pendingRemoval = null
        if (target == null) return
        target.alpha = 0f
        try {
            windows.removeView(target)
        } catch (_: IllegalArgumentException) {
            // 已被系统或其他清理路径移除。
        } catch (first: RuntimeException) {
            AppLog.w("悬浮窗", "移除点击标记失败，改用立即清理：${first.javaClass.simpleName}")
            // 添加可能只完成一部分；即使系统窗口已丢失，也清掉本地视图和定时任务。
            try {
                windows.removeViewImmediate(target)
            } catch (_: IllegalArgumentException) {
                // View 未注册时无需再次清理。
            } catch (failure: RuntimeException) {
                // 未确认移除前保留清理目标，禁止叠加新窗；下次 close 或 show 再尝试。
                pendingRemoval = target
                AppLog.w("悬浮窗", "清理点击标记失败：${failure.javaClass.simpleName}")
            }
        }
    }
}
