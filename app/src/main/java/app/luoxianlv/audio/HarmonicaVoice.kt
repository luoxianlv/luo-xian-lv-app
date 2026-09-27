package app.luoxianlv.audio

/** Mono PCM renderer, independent of Android. Loop overlap keeps the original attack out of the sustain. */
data class HarmonicaSample(val pcm: ShortArray, val loopStart: Int, val loopEnd: Int, val blend: Int) {
    init { require(loopStart >= 0 && blend > 0 && loopStart + blend < loopEnd - blend && loopEnd <= pcm.size) }
}

class HarmonicaVoice(private val samples: Map<Int, HarmonicaSample>) {
    private data class Voice(val sample: HarmonicaSample, var position: Int = 0, var gain: Float = 0f)
    private var current: Voice? = null
    private var retiring: Voice? = null
    private var held = false
    fun noteOn(midi: Int) {
        val sample = requireNotNull(samples[midi]) { "Missing harmonica sample $midi" }
        retiring = current
        current = Voice(sample)
        held = true
    }
    fun noteOff() { held = false }
    fun clear() { current = null; retiring = null; held = false }
    private fun next(voice: Voice): Float {
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
                v.gain = if (held) (v.gain + 1f / 240).coerceAtMost(1f) else (v.gain - 1f / 576).coerceAtLeast(0f)
                value += next(v)
                if (!held && v.gain == 0f) current = null
            }
            retiring?.let { v ->
                v.gain = (v.gain - 1f / 240).coerceAtLeast(0f)
                value += next(v)
                if (v.gain == 0f) retiring = null
            }
            output[i] = (value * .8f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
