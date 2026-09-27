package app.luoxianlv

import app.luoxianlv.audio.HarmonicaSample
import app.luoxianlv.audio.HarmonicaVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class HarmonicaVoiceTest {
    @Test
    fun sustainedNoteNeverReplaysQuietAttack() {
        val pcm = ShortArray(2000) { if (it < 800) 0 else 10000 }
        val voice = HarmonicaVoice(mapOf(60 to HarmonicaSample(pcm, 1000, 1900, 100)))
        voice.noteOn(60)
        voice.render(ShortArray(2000))
        val held = ShortArray(12000)
        voice.render(held)
        assertTrue("Loop re-entered the attack", held.all { it == 8000.toShort() })
    }

    private fun samples(): Map<Int, HarmonicaSample> =
        File("src/main/assets/harmonica/index.tsv").readLines().associate { line ->
            val (pitch, count, start, end, blend) = line.split('\t').map(String::toInt)
            val bytes = File("src/main/assets/harmonica/$pitch.pcm").readBytes()
            assertEquals(count * 2, bytes.size)
            val pcm = ShortArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            pitch to HarmonicaSample(pcm, start, end, blend)
        }

    @Test
    fun allPackagedSamplesSustainForTwentySecondsAndReleaseToSilence() {
        val samples = samples()
        assertEquals((48..85).toSet(), samples.keys)
        for (pitch in samples.keys) {
            val voice = HarmonicaVoice(samples)
            voice.noteOn(pitch)
            val buffer = ShortArray(4800)
            repeat(200) {
                voice.render(buffer)
                assertTrue("$pitch block $it silent", buffer.any { abs(it.toInt()) > 40 })
            }
            voice.noteOff()
            voice.render(buffer)
            assertTrue("$pitch hangs after release", buffer.drop(576).all { it == 0.toShort() })
        }
    }

    @Test
    fun stealingAndRepeatedReleaseRemainFiniteAndSilentAfterRelease() {
        val voice = HarmonicaVoice(samples())
        val buffer = ShortArray(240)
        repeat(100) {
            voice.noteOn(48 + it % 38)
            voice.render(buffer)
            voice.noteOff()
            voice.render(buffer)
        }
        repeat(8) { voice.render(buffer) }
        assertTrue(buffer.all { it == 0.toShort() })
    }
}
