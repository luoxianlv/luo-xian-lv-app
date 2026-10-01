package app.luoxianlv

import app.luoxianlv.core.playback.SongLoadGate
import org.junit.Assert.*
import org.junit.Test

class SongLoadGateTest {
    @Test
    fun `准备和等待意图成对完成后仅真实边沿通知`() {
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        lateinit var gate: SongLoadGate
        gate = SongLoadGate { states += gate.loading to gate.playWhenReady }
        val token = gate.begin()
        gate.requestPlay()
        gate.requestPlay()
        gate.pause()
        gate.pause()
        assertEquals(false, gate.finish(token))
        gate.pause()
        assertEquals(
            listOf(true to false, true to true, true to false, false to false),
            states,
        )
    }

    @Test
    fun `旧完成和同状态新代次不产生伪边沿`() {
        var changed = 0
        val gate = SongLoadGate { changed++ }
        val old = gate.begin()
        val current = gate.begin()
        assertEquals(1, changed)
        assertNull(gate.finish(old))
        assertEquals(1, changed)
        gate.requestPlay()
        assertEquals(2, changed)
        assertEquals(true, gate.finish(current))
        assertEquals(3, changed)
        assertEquals(false, gate.finish(current))
        assertEquals(3, changed)
    }

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
