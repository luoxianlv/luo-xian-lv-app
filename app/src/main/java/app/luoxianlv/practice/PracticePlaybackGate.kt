package app.luoxianlv.practice

import android.os.Bundle
import app.luoxianlv.hot.contract.PracticeBridge
import java.util.function.BooleanSupplier

/** 控制演练场就绪状态；固定布局模式可读取真实音区。 */
object PracticePlaybackGate {
    private var owner: Any? = null
    private var session: PracticeSession? = null

    fun bindSession(owner: Any, value: PracticeSession) {
        if (owns(owner)) session = value
    }

    fun pitchState(): Pair<app.luoxianlv.library.PlayMode, Boolean>? =
        PracticeBridge.pitch()?.let {
            runCatching {
                app.luoxianlv.library.PlayMode.valueOf(it.mode) to it.half
            }
                .getOrNull()
        }

    val active
        get() = PracticeBridge.active()

    val ready
        get() = PracticeBridge.ready()

    fun owns(value: Any) = PracticeBridge.owns(value)

    val readyToken
        get() = PracticeBridge.readyToken()

    fun geometry() = PracticeBridge.geometry()

    fun token(value: Any) = PracticeBridge.token(value)

    fun matchesPlayback(expected: Long, current: Long) = expected != 0L && expected == current

    fun enter(value: Any, foreground: () -> Boolean = { true }, geometry: (() -> Bundle?)? = null) {
        if (owns(value)) return
        owner = value
        session = null
        PracticeBridge.enter(
            value,
            { session?.let { PracticeBridge.Pitch(it.mode.name, it.half) } },
            BooleanSupplier { foreground() },
            geometry?.let { provider -> java.util.function.Supplier { provider() } },
        )
    }

    fun setReady(owner: Any, value: Boolean) {
        PracticeBridge.setReady(owner, value)
    }

    fun invalidateSession(value: PracticeSession) {
        if (session === value) owner?.let { PracticeBridge.setReady(it, false) }
    }

    fun leave(value: Any) {
        PracticeBridge.leave(value)
        if (owner !== value) return
        owner = null
        session = null
    }
}
