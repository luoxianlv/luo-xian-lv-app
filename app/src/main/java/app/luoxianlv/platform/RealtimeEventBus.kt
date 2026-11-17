package app.luoxianlv.platform

import app.luoxianlv.hot.contract.SharedRealtime
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** 每代业务一个总线；入口由运行时后台线程调用，发布只更新内存，永不等待页面。 */
internal class RealtimeEventBus(
    private val subscribe:
        (
            onState: (RealtimeState) -> Unit,
            onMessage: (String, String, String) -> Unit,
        ) -> AutoCloseable
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RealtimeState())
    private val mutableChanges = MutableStateFlow(RealtimeInvalidations())

    private data class Posted(val generation: Long, val event: RealtimeEvent)

    private val mutableEvents =
        MutableSharedFlow<Posted>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val state = mutableState.asStateFlow()
    val invalidations = mutableChanges.asStateFlow()
    /** 瞬时通知允许合并；需要可靠刷新时比较 invalidations 中对应资源的版本。 */
    val events =
        mutableEvents
            .asSharedFlow()
            .filter { posted ->
                synchronized(lock) {
                    active &&
                        generation == posted.generation &&
                        when (val event = posted.event) {
                            is RealtimeEvent.ResourceChanged ->
                                event.identity == mutableState.value.identity
                            is RealtimeEvent.StateChanged -> event.state == mutableState.value
                            is RealtimeEvent.VersionsChanged ->
                                event.versions == mutableState.value.versions
                        }
                }
            }
            .map { it.event }
    private val eventRevisions = mutableMapOf<RealtimeResource, Long>()
    private var revision = 0L
    private var generation = 0L
    private var active = false
    private var closed = false
    private var resynchronize = false
    private var registration: AutoCloseable? = null

    fun resume() {
        val selected =
            synchronized(lock) {
                if (closed || active) return
                active = true
                resynchronize = true
                ++generation
            }
        val lease =
            try {
                subscribe(
                    { accept(selected, it) },
                    { topic, type, json -> accept(selected, topic, type, json) },
                )
            } catch (failure: Exception) {
                synchronized(lock) { if (generation == selected) active = false }
                throw failure
            }
        val keep =
            synchronized(lock) {
                if (active && generation == selected) {
                    registration = lease
                    true
                } else false
            }
        if (!keep) lease.close()
    }

    private fun accept(selected: Long, next: RealtimeState): Unit =
        synchronized(lock) {
            if (!active || generation != selected) return
            val previous = mutableState.value
            if (previous.identity != next.identity || resynchronize) {
                resynchronize = false
                eventRevisions.clear()
                val changed = if (next.identity.accountId.isEmpty()) 0 else ++revision
                mutableChanges.value =
                    RealtimeInvalidations(next.identity, changed, changed, changed, changed)
            }
            if (next != previous) {
                mutableState.value = next
                emit(RealtimeEvent.StateChanged(next))
                next.versions
                    ?.takeIf { it != previous.versions }
                    ?.let { emit(RealtimeEvent.VersionsChanged(it)) }
            }
        }

    private fun accept(selected: Long, topic: String, type: String, json: String) {
        val current =
            synchronized(lock) {
                if (!active || generation != selected) return
                mutableState.value
            }
        // 格式错误只丢弃本条，不能杀死连接或把原始正文写入日志。
        val event =
            runCatching { RealtimeEventFormat.decode(topic, type, json, current) }.getOrNull()
                ?: return
        synchronized(lock) {
            if (
                !active || generation != selected || mutableState.value.identity != current.identity
            )
                return
            when (event) {
                is RealtimeEvent.ResourceChanged -> {
                    if (event.revision <= (eventRevisions[event.resource] ?: 0L)) return
                    eventRevisions[event.resource] = event.revision
                    mutableChanges.value = mutableChanges.value.changed(event.resource, ++revision)
                }
                is RealtimeEvent.VersionsChanged -> {
                    if (mutableState.value.versions == event.versions) return
                    mutableState.value = mutableState.value.copy(versions = event.versions)
                }
                is RealtimeEvent.StateChanged -> return
            }
            emit(event)
        }
    }

    private fun emit(event: RealtimeEvent) {
        mutableEvents.tryEmit(Posted(generation, event))
    }

    fun pause() {
        val lease =
            synchronized(lock) {
                active = false
                ++generation
                registration.also { registration = null }
            }
        lease?.close()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        pause()
        synchronized(lock) {
            eventRevisions.clear()
            mutableState.value = RealtimeState()
            mutableChanges.value = RealtimeInvalidations()
        }
    }

    companion object {
        fun fromBridge(bridge: SharedRealtime.Bridge?) = RealtimeEventBus { onState, onMessage ->
            bridge?.observeEvents(
                object : SharedRealtime.Listener {
                    override fun changed(value: android.os.Bundle) =
                        onState(RealtimeState.read(value))

                    override fun message(topic: String, type: String, json: String) =
                        onMessage(topic, type, json)
                }
            ) ?: AutoCloseable {}
        }
    }
}

/** 只跨 Java 契约取值；Flow 与业务模型留在当前加载器，热更交接关闭旧订阅。 */
internal object RealtimeEvents {
    val bus = RealtimeEventBus.fromBridge(SharedRealtime.current())
}
