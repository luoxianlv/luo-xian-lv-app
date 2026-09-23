package app.luoxianlv

import app.luoxianlv.data.hiddenBuiltInsJson
import app.luoxianlv.data.parseHiddenBuiltIns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置谱面隐藏清单的编解码回归测试。
 *
 * 内置谱面随包发布、删不掉文件，「删除」只能表达为把 id 记进这份清单。
 * 清单写坏一次的后果很直接：要么内置示例谱面一起消失，要么删掉的又回来。
 * 这里锁住空值 / 坏 JSON / 空白项 / 往返四种情况。
 */
class HiddenBuiltInsTest {
    @Test
    fun `没有清单或清单为空时视为一首都没删`() {
        assertTrue(parseHiddenBuiltIns(null).isEmpty())
        assertTrue(parseHiddenBuiltIns("").isEmpty())
        assertTrue(parseHiddenBuiltIns("[]").isEmpty())
    }

    @Test
    fun `清单损坏时不抛异常`() {
        assertTrue(parseHiddenBuiltIns("not json").isEmpty())
        assertTrue(parseHiddenBuiltIns("""{"rain-love":true}""").isEmpty())
    }

    @Test
    fun `忽略空白项并去重`() {
        assertEquals(
            setOf("rain-love"),
            parseHiddenBuiltIns("""["rain-love","","  ","rain-love"]"""),
        )
    }

    @Test
    fun `写出再读回保持一致`() {
        val ids = setOf("rain-love", "night-sky")
        assertEquals(ids, parseHiddenBuiltIns(hiddenBuiltInsJson(ids)))
        assertTrue(parseHiddenBuiltIns(hiddenBuiltInsJson(emptySet())).isEmpty())
    }
}
