package app.luoxianlv

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeViewModelStoreOwner

/** 验证实际业务 View 树的所有者，不再假定系统 Activity 兼任 Jetpack 容器。 */
internal fun Activity.businessComposeView(): ComposeView {
    fun find(view: View): ComposeView? =
        when (view) {
            is ComposeView -> view
            is ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
    return checkNotNull(find(window.decorView)) { "未找到业务 Compose 页面" }
}

internal fun Activity.businessModels() =
    checkNotNull(businessComposeView().findViewTreeViewModelStoreOwner())
