package app.luoxianlv.business.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import app.luoxianlv.hot.contract.HostActions
import app.luoxianlv.hot.contract.NativePage

/** 原生 View 业务页拥有自己的生命周期，异步准备随代际关闭取消。 */
abstract class ViewPage : NativePage, LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var current = NativePage.CREATED
    protected var closed = false
        private set

    protected lateinit var pageContext: Context
        private set

    protected lateinit var host: HostActions
        private set

    protected val activity: Activity
        get() = checkNotNull(pageContext.findActivity())

    override val lifecycle: Lifecycle
        get() = registry

    final override fun attachHost(host: HostActions) {
        this.host = host
    }

    final override fun create(
        context: Context,
        state: Bundle,
        hostState: Bundle,
        events: NativePage.Events,
        ready: NativePage.Ready,
    ): View {
        check(!::pageContext.isInitialized && !closed)
        pageContext = context
        registry.currentState = Lifecycle.State.CREATED
        updateHostState(hostState)
        return createView(state, ready).also {
            it.isSaveFromParentEnabled = false
            it.setViewTreeLifecycleOwner(this)
        }
    }

    protected abstract fun createView(state: Bundle, ready: NativePage.Ready): View

    protected open fun lifecycleChanged(state: Int) {}

    protected open fun dispose() {}

    override fun updateHostState(state: Bundle) {}

    override fun save() = Bundle()

    final override fun lifecycle(state: Int) {
        if (closed) return
        registry.currentState =
            when (state) {
                NativePage.RESUMED -> Lifecycle.State.RESUMED
                NativePage.STARTED -> Lifecycle.State.STARTED
                else -> Lifecycle.State.CREATED
            }
        if (current != state) {
            current = state
            lifecycleChanged(state)
        }
    }

    final override fun close() {
        if (closed) return
        closed = true
        try {
            dispose()
        } finally {
            if (registry.currentState != Lifecycle.State.INITIALIZED)
                registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
