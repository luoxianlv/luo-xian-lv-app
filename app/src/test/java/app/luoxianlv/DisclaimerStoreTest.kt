package app.luoxianlv

import app.luoxianlv.data.DisclaimerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 免责协议哈希：与标准测试向量比对，保证同意状态判定不会漂移。 */
class DisclaimerStoreTest {
    @Test
    fun `sha256 与标准测试向量一致`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DisclaimerStore.sha256(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DisclaimerStore.sha256("abc"),
        )
    }

    @Test
    fun `sha256 按 UTF-8 编码且对小写十六进制稳定`() {
        assertEquals(
            "74af59dc4d82943598fe4d6a802c62db9dc0c2fd80b3714f3fa252cef644f0ee",
            DisclaimerStore.sha256("落弦律"),
        )
        assertEquals(DisclaimerStore.sha256("落弦律"), DisclaimerStore.sha256("落弦律"))
    }

    @Test
    fun `协议文本变化会导致哈希变化从而要求重新同意`() {
        assertNotEquals(DisclaimerStore.sha256("v1"), DisclaimerStore.sha256("v2"))
        // 仅空白差异也算新协议
        assertNotEquals(DisclaimerStore.sha256("文本"), DisclaimerStore.sha256("文本 "))
    }
}
