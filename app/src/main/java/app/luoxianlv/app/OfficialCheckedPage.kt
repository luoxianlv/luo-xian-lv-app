package app.luoxianlv.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import app.luoxianlv.business.ui.findActivity
import app.luoxianlv.hot.contract.HostActions
import app.luoxianlv.hot.contract.NativePage

internal class OfficialCheckedPage(private val delegate: NativePage) : NativePage by delegate {
    private var validation: AutoCloseable? = null
    private var closed = false

    // Kotlin by 不为 Java default 方法生成委托桥；这些宿主回调必须到达实际页面。
    override fun attachHost(host: HostActions?) = delegate.attachHost(host)

    override fun newIntent(intent: Intent?) = delegate.newIntent(intent)

    override fun result(key: String?, resultCode: Int, data: Intent?) =
        delegate.result(key, resultCode, data)

    override fun back() = delegate.back()

    override fun windowTouch() = delegate.windowTouch()

    override fun hostWarning(code: String?, error: Throwable?) = delegate.hostWarning(code, error)

    override fun configurationChanged(configuration: Configuration?) =
        delegate.configurationChanged(configuration)

    override fun finishing() = delegate.finishing()

    override fun retain(): NativePage.Retained? = delegate.retain()

    override fun restoreRetained(state: NativePage.Retained?) = delegate.restoreRetained(state)

    override fun canReplace() = delegate.canReplace()

    override fun create(
        context: Context,
        state: Bundle,
        hostState: Bundle,
        events: NativePage.Events,
        ready: NativePage.Ready,
    ): View {
        var pageReady = false
        var resourcesReady = false
        var failed = false
        var sentReady = false
        fun publish(error: Throwable? = null) {
            if (closed || failed) return
            if (error != null) {
                failed = true
                ready.failed(error)
            } else if (!sentReady && pageReady && resourcesReady) {
                sentReady = true
                ready.ready()
            }
        }
        val content =
            delegate.create(
                context,
                state,
                hostState,
                events,
                object : NativePage.Ready {
                    override fun ready() {
                        pageReady = true
                        publish()
                    }

                    override fun failed(failure: Throwable) {
                        publish(failure)
                    }
                },
            )
        // 候选页在旧页后面预绘制；验证视图挂到真实窗口Decor，避免把离屏回调当作窗口首帧。
        val decor =
            context.findActivity()?.window?.decorView as? android.view.ViewGroup
                ?: throw IllegalStateException("官方渲染器可见验证缺少真实窗口")
        validation =
            OfficialRendererGate.verify(context, decor, true) { error ->
                resourcesReady = error == null
                publish(error)
            }
        return content
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            validation?.close()
            validation = null
        } finally {
            delegate.close()
        }
    }
}
