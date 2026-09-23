package app.luoxianlv

import app.luoxianlv.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坏谱面不能让曲库闪退。
 *
 * 曲库列表行渲染要读 [Song.durationMs]，而它会触发惰性解析；[app.luoxianlv.core.score.ScoreParser]
 * 对不合法谱面抛 IllegalArgumentException —— 抛在组合期就是整屏闪退（打开曲库即崩）。
 * 这里锁住「解析失败退化为空谱面」这一行为，以后谁把 runCatching 去掉都会直接挂测试。
 */
class SongParseSafetyTest {
    private fun song(score: String) = Song(id = "x", title = "测试", score = score, bpm = 120, source = "简谱")

    @Test
    fun `无法解析的谱面不抛异常且时长为零`() {
        val bad = song("这不是谱面，解析器认不出来")
        assertTrue(bad.events.isEmpty())
        assertEquals(0L, bad.durationMs)
        assertEquals(0, bad.noteCount)
    }

    @Test
    fun `空谱面同样安全`() {
        val blank = song("")
        assertTrue(blank.events.isEmpty())
        assertEquals(0L, blank.durationMs)
    }

    @Test
    fun `不完整标记的谱面也不抛`() {
        // 半音标记没配音符、括号不闭合：都是 ScoreParser 里 require 会拦下的形态
        val bad = song("1:1 # 2:1 (3:1")
        assertTrue(bad.events.isEmpty())
        assertEquals(0L, bad.durationMs)
    }

    @Test
    fun `合法谱面照常解析`() {
        val good = song("tempo=120 unit=1\n1:1 2:1 3:1 4:1")
        assertEquals(4, good.events.size)
        assertEquals(4, good.noteCount)
        // 4 拍 × 60000 / 120 = 2000ms
        assertEquals(2000L, good.durationMs)
    }
}
