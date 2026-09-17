package app.luoxianlv

import app.luoxianlv.data.Song
import app.luoxianlv.ui.library.SongFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲库归类规则回归测试。
 *
 * 「MIDI / 简谱」的判断原本在界面里重复实现两次
 * （列表筛选 `filter == 1 == source.startsWith("MIDI")` 与卡片图标），
 * 现已收敛为 [Song.isMidi] + [SongFilter]。这里锁住 source 取值与归类语义，
 * 以后谁改了 source 文案而忘了归类会直接挂测试。
 */
class SongFilterTest {
    private fun song(source: String) = Song(id = source, title = source, score = "", bpm = 120, source = source)

    /** SongRepository 里实际会写入的全部 source 取值。 */
    private val midiSources = listOf("MIDI · Rust 编译", "MIDI · 引擎编译")
    private val notationSources = listOf("简谱", "平台下载", "平台同步", "热更")

    @Test
    fun `MIDI 编译而来的谱面归类为 MIDI`() {
        midiSources.forEach { assertTrue(it, song(it).isMidi) }
    }

    @Test
    fun `其余来源一律归为简谱`() {
        notationSources.forEach { assertFalse(it, song(it).isMidi) }
    }

    @Test
    fun `全部筛选不做过滤`() {
        val songs = (midiSources + notationSources).map(::song)
        assertEquals(songs.size, songs.count(SongFilter.All::matches))
    }

    @Test
    fun `MIDI 与简谱筛选互补且互不重叠`() {
        val songs = (midiSources + notationSources).map(::song)
        val midi = songs.filter(SongFilter.Midi::matches)
        val notation = songs.filter(SongFilter.Notation::matches)

        assertEquals(midiSources.size, midi.size)
        assertEquals(notationSources.size, notation.size)
        // 两个子集不重叠，且并集等于全集：漏掉一类或多算都会挂
        assertTrue((midi intersect notation.toSet()).isEmpty())
        assertEquals(songs.toSet(), (midi + notation).toSet())
    }

    @Test
    fun `筛选标签与导航一致`() {
        assertEquals("全部", SongFilter.All.label)
        assertEquals("MIDI", SongFilter.Midi.label)
        assertEquals("简谱", SongFilter.Notation.label)
    }

    @Test
    fun `没有谱面时任何筛选都为空`() {
        assertTrue(emptyList<Song>().none(SongFilter.All::matches))
        assertTrue(emptyList<Song>().none(SongFilter.Midi::matches))
        assertTrue(emptyList<Song>().none(SongFilter.Notation::matches))
    }
}
