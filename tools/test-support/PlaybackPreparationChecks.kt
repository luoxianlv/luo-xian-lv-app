package app.luoxianlv

import app.luoxianlv.playback.ActualPlaybackEdgeFixture
import org.junit.Assert.*
import org.junit.Test

/** runner逐字提取真实PlaybackSession setter/reportUsage；仅Binding/Looper替换为离线输入。 */
class PlaybackPreparationChecks {
    @Test
    fun `直接准备开始结束即使一直非播放也同步通知`() {
        val events = mutableListOf<Boolean>()
        val session = ActualPlaybackEdgeFixture(events::add)
        session.prepare(true)
        assertEquals(listOf(false), events)
        session.prepare(false)
        assertEquals(listOf(false, false), events)
    }

    @Test
    fun `重复赋值和查询没有状态通知`() {
        val events = mutableListOf<Boolean>()
        val session = ActualPlaybackEdgeFixture(events::add)
        session.prepare(false)
        session.play(false)
        repeat(10) { assertFalse(session.playing || session.preparing) }
        assertTrue(events.isEmpty())
        session.prepare(true)
        session.prepare(true)
        assertEquals(listOf(false), events)
    }

    @Test
    fun `开始演奏和暂停使用真实演奏值通知`() {
        val events = mutableListOf<Boolean>()
        val session = ActualPlaybackEdgeFixture(events::add)
        session.prepare(true)
        session.prepare(false)
        session.play(true)
        session.play(true)
        session.play(false)
        assertEquals(listOf(false, false, true, false), events)
    }
}
