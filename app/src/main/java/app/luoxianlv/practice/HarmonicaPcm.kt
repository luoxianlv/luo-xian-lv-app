package app.luoxianlv.practice

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/** 兼容旧 PCM；新音源用二阶差分及 zlib 无损存储，解码只在加载线程执行。 */
internal object HarmonicaPcm {
    private val magic = byteArrayOf(76, 88, 72, 49)

    fun decode(bytes: ByteArray, count: Int, compressed: Boolean = false): ShortArray {
        require(count in 1..HarmonicaVoice.SAMPLE_RATE * 12) { "口琴音源长度无效" }
        val rawSize = count * 2
        if (!compressed) {
            require(bytes.size == rawSize) { "口琴 PCM 帧数不匹配" }
            return ShortArray(count).also {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
            }
        }
        require(bytes.size > 8 && bytes.take(4).toByteArray().contentEquals(magic)) {
            "口琴音源格式无效"
        }
        require(ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int == count) {
            "口琴音源帧数不匹配"
        }
        val delta = ByteArray(rawSize)
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes, 8, bytes.size - 8))).use {
            it.readFully(delta)
            require(it.read() == -1) { "口琴音源包含多余帧" }
        }
        val encoded = ByteBuffer.wrap(delta).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var previous = 0
        var older = 0
        return ShortArray(count) {
            val sample = (encoded.get().toInt() + 2 * previous - older).toShort()
            older = previous
            previous = sample.toInt()
            sample
        }
    }
}
