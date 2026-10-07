package app.luoxianlv.practice

/** 原始采样及延音循环区间；循环交叠避开起音渐强段。 */
data class HarmonicaSample(
    val pcm: ShortArray,
    val loopStart: Int,
    val loopEnd: Int,
    val blend: Int,
) {
    init {
        require(
            loopStart >= 0 &&
                blend > 0 &&
                loopStart + blend < loopEnd - blend &&
                loopEnd <= pcm.size
        )
    }
}

/** 单音口琴：独立松键保留尾音，下一次起音用短交叠打断前音。 */
class HarmonicaVoice(private val samples: Map<Int, HarmonicaSample>) {
    companion object {
        internal const val SAMPLE_RATE = 48000
        internal const val ATTACK_FRAMES = 240
        internal const val RELEASE_FRAMES = SAMPLE_RATE / 2
    }

    private data class Voice(
        val sample: HarmonicaSample,
        var position: Int = 0,
        var gain: Float = 0f,
        var releaseGain: Float = 0f,
        var releaseRemaining: Int = -1,
        var releaseDuration: Int = RELEASE_FRAMES,
    )

    private var current: Voice? = null
    private var retiring: Voice? = null

    fun noteOn(midi: Int) {
        val sample = requireNotNull(samples[midi]) { "Missing harmonica sample $midi" }
        retiring = current?.apply {
            // 即使前音已经松键，也在新音开始后 5 ms 内结束，消除连按叠音。
            releaseGain = gain
            releaseRemaining = ATTACK_FRAMES
            releaseDuration = ATTACK_FRAMES
        }
        current = Voice(sample)
    }

    fun noteOff() {
        current?.let(::release)
    }

    fun clear() {
        current = null
        retiring = null
    }

    private fun release(voice: Voice) {
        // 重复松键不延长尾音；新音的短淡出由 noteOn 单独处理。
        if (voice.releaseRemaining >= 0) return
        voice.releaseGain = voice.gain
        voice.releaseRemaining = RELEASE_FRAMES
    }

    private fun next(voice: Voice): Float {
        if (voice.releaseRemaining < 0) {
            voice.gain = (voice.gain + 1f / ATTACK_FRAMES).coerceAtMost(1f)
        } else {
            voice.releaseRemaining = (voice.releaseRemaining - 1).coerceAtLeast(0)
            voice.gain = voice.releaseGain * voice.releaseRemaining / voice.releaseDuration
        }
        val s = voice.sample
        val p = voice.position
        var value = s.pcm[p].toFloat()
        if (p >= s.loopEnd - s.blend) {
            val offset = p - (s.loopEnd - s.blend)
            val alpha = offset.toFloat() / s.blend
            value = value * (1 - alpha) + s.pcm[s.loopStart + offset] * alpha
        }
        voice.position++
        if (voice.position >= s.loopEnd) voice.position = s.loopStart + s.blend
        return value * voice.gain
    }

    fun render(output: ShortArray) {
        for (i in output.indices) {
            var value = 0f
            current?.let { v ->
                value += next(v)
                if (v.releaseRemaining == 0) current = null
            }
            retiring?.let {
                value += next(it)
                if (it.releaseRemaining == 0) retiring = null
            }
            output[i] = (value * .8f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
