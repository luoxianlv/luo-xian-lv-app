package app.luoxianlv

import app.luoxianlv.core.playback.SongLoadGate
import org.junit.Assert.*
import org.junit.Test

class SongLoadGateTest {
    @Test
    fun `刚打开立即播放 准备完成只起播一次`() {
        val gate = SongLoadGate()
        val token = gate.begin()
        assertTrue(gate.requestPlay())
        assertTrue(gate.requestPlay())
        assertEquals(true, gate.finish(token))
        assertFalse(gate.loading)
        assertFalse(gate.requestPlay())
        assertFalse(gate.playWhenReady)
    }

    @Test
    fun `准备中暂停 完成后不能偷偷播放`() {
        val gate = SongLoadGate()
        val token = gate.begin()
        gate.requestPlay()
        gate.pause()
        assertEquals(false, gate.finish(token))
    }

    @Test
    fun `快速切歌 旧结果不能覆盖新选择或消耗新播放请求`() {
        val gate = SongLoadGate()
        val old = gate.begin()
        gate.requestPlay()
        val current = gate.begin()
        assertFalse(gate.playWhenReady)
        gate.requestPlay()
        assertNull(gate.finish(old))
        assertTrue(gate.loading)
        assertTrue(gate.playWhenReady)
        assertEquals(true, gate.finish(current))
    }
}
