package app.luoxianlv.practice

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class HarmonicaPcmTest {
    private fun packed(pcm: ShortArray): ByteArray {
        val delta = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var previous = 0
        var older = 0
        for (sample in pcm) {
            delta.putShort((sample.toInt() - 2 * previous + older).toShort())
            older = previous
            previous = sample.toInt()
        }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(delta.array()) }
        return ByteBuffer.allocate(8 + compressed.size())
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(76, 88, 72, 49))
            .putInt(pcm.size)
            .put(compressed.toByteArray())
            .array()
    }

    @Test
    fun predictorRestoresFullRangePcmExactly() {
        val random = Random(19)
        val pcm = ShortArray(5000) { random.nextInt(-32768, 32768).toShort() }
        pcm[0] = Short.MIN_VALUE
        pcm[1] = Short.MAX_VALUE
        pcm[2] = 0
        assertArrayEquals(pcm, HarmonicaPcm.decode(packed(pcm), pcm.size, true))
    }

    @Test
    fun legacyRawDataIsNotGuessedFromItsFirstSamples() {
        val pcm = shortArrayOf(22604, 12616, Short.MAX_VALUE, Short.MIN_VALUE)
        val raw = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in pcm) raw.putShort(value)
        assertArrayEquals(pcm, HarmonicaPcm.decode(raw.array(), pcm.size))
    }

    @Test
    fun rejectsWrongCountHeaderAndTruncatedOrCorruptedPayload() {
        val pcm = ShortArray(1000) { (it * 31).toShort() }
        val bytes = packed(pcm)
        assertThrows(IllegalArgumentException::class.java) { HarmonicaPcm.decode(bytes, 0, true) }
        assertThrows(IllegalArgumentException::class.java) {
            HarmonicaPcm.decode(bytes, 576001, true)
        }
        assertThrows(IllegalArgumentException::class.java) { HarmonicaPcm.decode(bytes, 999, true) }
        assertThrows(IllegalArgumentException::class.java) {
            HarmonicaPcm.decode(bytes.copyOf().also { it[0] = 0 }, 1000, true)
        }
        assertThrows(IOException::class.java) {
            HarmonicaPcm.decode(bytes.copyOf(bytes.size - 4), 1000, true)
        }
        assertThrows(IOException::class.java) {
            HarmonicaPcm.decode(
                bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
                1000,
                true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarmonicaPcm.decode(byteArrayOf(0, 1), 1000)
        }
    }
}
