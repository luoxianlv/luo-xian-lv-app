package app.luoxianlv.app

import android.content.Context
import android.os.Bundle
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.hot.contract.NativePlaybackSession

internal class OfficialCheckedPlayback(private val delegate: NativePlaybackSession) :
    NativePlaybackSession by delegate {
    private var validation: AutoCloseable? = null
    private var closed = false
    private var usable = false

    // 显式转发 Java default，保留业务交接能力、状态和后台任务释放判断。
    override fun supportsHandover() = delegate.supportsHandover()

    override fun snapshot(): Bundle? = delegate.snapshot()

    override fun restore(state: Bundle?, ready: NativePage.Ready?) = delegate.restore(state, ready)

    override fun activate() = delegate.activate()

    override fun deactivate() = delegate.deactivate()

    override fun revision() = delegate.revision()

    override fun released() = delegate.released()

    override fun connect(context: Context, binding: AccessibilityBinding) {
        validation =
            OfficialRendererGate.verify(context, null, false) { error ->
                if (!closed) {
                    if (error != null) binding.contentFailed(error)
                    else
                        try {
                            delegate.connect(context, binding)
                            usable = true
                        } catch (failure: Throwable) {
                            binding.contentFailed(failure)
                        }
                }
            }
    }

    override fun query(kind: String): Bundle = if (usable) delegate.query(kind) else Bundle()

    override fun command(action: String, arguments: Bundle) {
        if (usable) delegate.command(action, arguments)
    }

    override fun interrupt() {
        if (usable) delegate.interrupt()
    }

    override fun event(event: android.view.accessibility.AccessibilityEvent) {
        if (usable) delegate.event(event)
    }

    override fun canReplace() = usable && delegate.canReplace()

    private fun checked(context: Context, ready: NativePage.Ready): NativePage.Ready =
        object : NativePage.Ready {
            override fun ready() {
                validation?.close()
                validation =
                    OfficialRendererGate.verify(context, null, false) { error ->
                        if (!closed) {
                            if (error == null) {
                                usable = true
                                ready.ready()
                            } else ready.failed(error)
                        }
                    }
            }

            override fun failed(failure: Throwable) {
                if (!closed) ready.failed(failure)
            }
        }

    override fun prepare(
        context: Context,
        binding: AccessibilityBinding,
        state: Bundle,
        ready: NativePage.Ready,
    ) {
        delegate.prepare(context, binding, state, checked(context, ready))
    }

    override fun prepareRecovery(
        context: Context,
        binding: AccessibilityBinding,
        state: Bundle?,
        ready: NativePage.Ready,
    ) {
        delegate.prepareRecovery(context, binding, state, checked(context, ready))
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
