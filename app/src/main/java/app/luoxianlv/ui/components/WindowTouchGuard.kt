package app.luoxianlv.ui.components

import app.luoxianlv.debug.AppLog

/** 部分 ROM 的小窗标题栏在窗口模式已改变后仍请求切换；只拦截这一条已知系统异常。 */
internal inline fun dispatchWindowTouch(dispatch: () -> Boolean): Boolean =
    try {
        dispatch()
    } catch (error: IllegalStateException) {
        if (!error.isStaleFreeformToggle()) throw error
        AppLog.w("窗口交互", "系统小窗状态已变化，忽略失效的标题栏点击", error)
        false
    }

internal fun IllegalStateException.isStaleFreeformToggle(): Boolean =
    message == "This activity is currently not freeform-enabled" &&
        stackTrace.any {
            it.className == "com.android.internal.widget.DecorCaptionView" &&
                it.methodName == "toggleFreeformWindowingMode"
        }
