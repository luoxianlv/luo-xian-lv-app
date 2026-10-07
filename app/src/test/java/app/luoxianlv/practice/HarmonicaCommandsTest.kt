package app.luoxianlv.practice

import org.junit.Assert.*
import org.junit.Test

class HarmonicaCommandsTest {
    private fun voice() =
        HarmonicaVoice(mapOf(60 to HarmonicaSample(ShortArray(2000) { 10000 }, 1000, 1900, 100)))

    @Test
    fun fastDownAndUpStillRenderTheTap() {
        val commands = HarmonicaCommands()
        val voice = voice()
        commands.submit(60)
        commands.submit(null)
        val buffer = ShortArray(240)
        commands.apply(voice)
        voice.render(buffer)
        assertTrue("快速松键吞掉了起音", buffer.any { it > 0 })
        commands.apply(voice)
        voice.render(buffer)
        assertTrue("松键截断了尾音", buffer.any { it > 0 })
        voice.render(ShortArray(HarmonicaVoice.RELEASE_FRAMES))
        voice.render(buffer)
        assertTrue(buffer.all { it == 0.toShort() })
    }

    @Test
    fun burstCannotDropTheFinalReleaseOrAccumulateOldInput() {
        val commands = HarmonicaCommands()
        val voice = voice()
        repeat(10000) { commands.submit(60) }
        commands.submit(null)
        repeat(32) {
            commands.apply(voice)
            voice.render(ShortArray(240))
        }
        voice.render(ShortArray(HarmonicaVoice.RELEASE_FRAMES))
        val buffer = ShortArray(240)
        voice.render(buffer)
        assertTrue("输入突发后没有释放音符", buffer.all { it == 0.toShort() })
    }
}
