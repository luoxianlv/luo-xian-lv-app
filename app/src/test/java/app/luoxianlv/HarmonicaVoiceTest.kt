package app.luoxianlv

import app.luoxianlv.practice.HarmonicaPcm
import app.luoxianlv.practice.HarmonicaSample
import app.luoxianlv.practice.HarmonicaVoice
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
            val fields = line.split('\t').map(String::toInt)
            val (pitch, count, start, end, blend) = fields
            val bytes = File("src/main/assets/harmonica/$pitch.pcm").readBytes()
            val pcm = HarmonicaPcm.decode(bytes, count, fields.size == 6)
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
            val tail = ShortArray(HarmonicaVoice.RELEASE_FRAMES)
            voice.render(tail)
            assertTrue(
                "$pitch released without a tail",
                tail.take(4800).any { abs(it.toInt()) > 40 },
            )
            voice.render(buffer)
            assertTrue("$pitch hangs after release", buffer.all { it == 0.toShort() })
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
        repeat(102) { voice.render(buffer) }
        assertTrue(buffer.all { it == 0.toShort() })
    }

    private fun constant(value: Short = 10000): HarmonicaSample =
        HarmonicaSample(ShortArray(2000) { value }, 1000, 1900, 100)

    @Test
    fun releaseKeepsNaturalTailAndRepeatedOffDoesNotExtendIt() {
        val voice = HarmonicaVoice(mapOf(60 to constant()))
        voice.noteOn(60)
        voice.render(ShortArray(4800))
        voice.noteOff()
        val block = ShortArray(2400)
        repeat(10) {
            voice.noteOff()
            voice.render(block)
            if (it < 9) assertTrue("尾音提前消失：$it", block.last() > 0)
        }
        voice.render(block)
        assertTrue("重复松键延长了尾音", block.all { it == 0.toShort() })
    }

    @Test
    fun nextNoteCutsTheReleasedTailWithinOneAudioBlock() {
        val voice = HarmonicaVoice(mapOf(60 to constant(), 62 to constant(0)))
        voice.noteOn(60)
        voice.render(ShortArray(4800))
        voice.noteOff()
        voice.render(ShortArray(4800))
        voice.noteOn(62)
        val transition = ShortArray(HarmonicaVoice.ATTACK_FRAMES)
        voice.render(transition)
        assertTrue("切换直接切断了波形", transition.first() > 0)
        assertEquals(0.toShort(), transition.last())
        val tail = ShortArray(4800)
        voice.render(tail)
        assertTrue("新音开始后仍叠加旧尾音", tail.all { it == 0.toShort() })
        voice.noteOff()
        voice.render(tail)
        assertTrue("松开新键后旧音恢复了", tail.all { it == 0.toShort() })
    }

    @Test
    fun rapidSwitchCutsEveryOlderNoteIncludingRepeatedPitch() {
        val voice = HarmonicaVoice(mapOf(60 to constant(), 62 to constant(0)))
        val block = ShortArray(240)
        repeat(20) {
            voice.noteOn(60)
            voice.render(block)
            voice.noteOff()
        }
        voice.noteOn(62)
        voice.render(block)
        voice.render(block)
        assertTrue("快速连按残留了更早的音符", block.all { it == 0.toShort() })
    }

    @Test
    fun clearImmediatelyStopsCurrentAndAllPreviousTails() {
        val voice = HarmonicaVoice(samples())
        repeat(30) {
            voice.noteOn(48 + it % 38)
            voice.render(ShortArray(240))
        }
        voice.clear()
        val buffer = ShortArray(4800)
        voice.render(buffer)
        assertTrue(buffer.all { it == 0.toShort() })
    }

    @Test
    fun releaseIsIndependentOfRenderBlockSize() {
        val one = HarmonicaVoice(mapOf(60 to constant()))
        val chunks = HarmonicaVoice(mapOf(60 to constant()))
        for (voice in listOf(one, chunks)) {
            voice.noteOn(60)
            voice.render(ShortArray(240))
            voice.noteOff()
        }
        val expected = ShortArray(25000)
        one.render(expected)
        val actual = ShortArray(expected.size)
        var offset = 0
        while (offset < actual.size) {
            val block = ShortArray(minOf(127, actual.size - offset))
            chunks.render(block)
            block.copyInto(actual, offset)
            offset += block.size
        }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun shortMelodyCutsPreviousNotesAndEndsInSilence() {
        val voice = HarmonicaVoice(samples())
        val audio = mutableListOf<ShortArray>()
        for (pitch in listOf(60, 64, 67, 72, 67, 64, 60)) {
            voice.noteOn(pitch)
            ShortArray(6400).also {
                voice.render(it)
                assertTrue("旋律起音没有输出", it.any { sample -> abs(sample.toInt()) > 40 })
                audio += it
            }
            voice.noteOff()
            ShortArray(5600).also {
                voice.render(it)
                audio += it
            }
        }
        ShortArray(24000).also {
            voice.render(it)
            audio += it
        }
        assertTrue("旋律结束后仍有残音", audio.last().takeLast(4800).all { it == 0.toShort() })
        val destination = System.getenv("LX_HARMONICA_PREVIEW") ?: return
        writePreview(destination, audio)
    }

    @Test
    fun longPressRepeatsOnlyMatureBodyAndReleasesNormally() {
        val bank = samples()
        val sample = bank.getValue(60)
        val voice = HarmonicaVoice(bank)
        voice.noteOn(60)
        val held = ShortArray(48000 * 20)
        voice.render(held)
        val period = sample.loopEnd - sample.loopStart - sample.blend
        val start = sample.loopEnd + 4800
        assertArrayEquals(
            held.copyOfRange(start, start + 4800),
            held.copyOfRange(start + period, start + period + 4800),
        )
        voice.noteOff()
        val tail = ShortArray(28800)
        voice.render(tail)
        assertTrue("长按松开后没有尾音", tail.take(4800).any { abs(it.toInt()) > 40 })
        assertTrue("长按松开后未结束", tail.takeLast(4800).all { it == 0.toShort() })
        System.getenv("LX_HARMONICA_PREVIEW")?.let {
            writePreview(File(it).resolveSibling("practice-held-note.wav").path, listOf(held, tail))
        }
    }

    private fun writePreview(destination: String, audio: List<ShortArray>) {
        val frames = audio.sumOf { it.size }
        val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000)
        wav.putShort(2).putShort(16).put("data".toByteArray()).putInt(frames * 2)
        for (block in audio) for (sample in block) wav.putShort(sample)
        File(destination).also {
            it.parentFile?.mkdirs()
            it.writeBytes(wav.array())
        }
    }
}
