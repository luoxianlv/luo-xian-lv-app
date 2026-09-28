package app.luoxianlv

import app.luoxianlv.core.score.NoteEvent
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.core.score.ScoreParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScoreParserRegressionTest {
    @Test
    fun `紧邻减号是降调 空格减号是延音`() {
        val notes = ScoreParser.parse("1 -2 -#3 4 - 5 ~ -i")
        assertEquals(listOf(1.0, 1.0, 1.0, 2.0, 2.0, 1.0), notes.map { it.beats })
        assertEquals(
            listOf(
                PlayMode.NATURAL,
                PlayMode.LOWER,
                PlayMode.LOWER,
                PlayMode.NATURAL,
                PlayMode.NATURAL,
                PlayMode.LOWER,
            ),
            notes.map { it.mode },
        )
        assertEquals(true, notes[2].halfTone)
    }

    @Test
    fun `嵌套音区注释中文括号与拍数保持一致`() {
        val notes = ScoreParser.parse("tempo=120 unit=0.5 【1（#2）3】 // 这里不解析\nREST:2 休 R 8'")
        assertEquals(
            listOf(
                NoteEvent(0, PlayMode.RAISE, .5),
                NoteEvent(1, PlayMode.LOWER, .5, halfTone = true),
                NoteEvent(2, PlayMode.RAISE, .5),
                NoteEvent.rest(2.0),
                NoteEvent.rest(.5),
                NoteEvent.rest(.5),
                NoteEvent(7, PlayMode.RAISE, .5),
            ),
            notes,
        )
    }

    @Test
    fun `格式化往返保留所有音符语义`() {
        val expected = ScoreParser.parse("1:0.25 #2:1.5 [+3] (4) 0:2 i,")
        assertEquals(expected, ScoreParser.parse(ScoreParser.format(expected, 120)))
    }

    @Test
    fun `长谱达到上限仍正确解析 超过上限拒绝`() {
        assertEquals(100000, ScoreParser.parse("1:0.5 ".repeat(100000)).size)
        assertThrows(IllegalArgumentException::class.java) {
            ScoreParser.parse("1 ".repeat(100001))
        }
    }

    @Test
    fun `非法间隔末尾与未完成标记继续报错`() {
        listOf("1?2", "1错误", "1 #", "[1)", "1 -x", "- 1", "1:0").forEach { score ->
            assertThrows(score, IllegalArgumentException::class.java) { ScoreParser.parse(score) }
        }
    }
}
