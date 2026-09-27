package app.luoxianlv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** One stream per stage; asset decoding happens before construction on an IO thread. */
class HarmonicaSampler(
    context: Context,
    samples: Map<Int, HarmonicaSample>,
    private val onInterrupted: () -> Unit,
) : AutoCloseable {
    private data class Command(val midi: Int?)
    private val command = AtomicReference<Command?>(null)
    private val voice = HarmonicaVoice(samples)
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = false
                noteOff()
                onInterrupted()
            }
        }, main).build()
    private var hasFocus = false
    @Volatile private var running = true
    private val track = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setBufferSizeInBytes(maxOf(1920, AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .build()
    private val worker: Thread
    init {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化音频输出" }
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                track.play()
                val buffer = ShortArray(240)
                while (running) {
                    command.getAndSet(null)?.let { if (it.midi == null) voice.noteOff() else voice.noteOn(it.midi) }
                    voice.render(buffer)
                    var offset = 0
                    while (running && offset < buffer.size) {
                        val written = track.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(written > 0) { "音频输出中断 ($written)" }
                        offset += written
                    }
                }
            } catch (_: Exception) {
                if (running) main.post { onInterrupted() }
            } finally {
                running = false
                runCatching { track.stop() }
                track.release()
            }
        }, "harmonica-output").apply { start() }
    }
    fun noteOn(midi: Int): Boolean {
        if (!running) return false
        if (!hasFocus) hasFocus = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) return false
        command.set(Command(midi))
        return true
    }
    fun noteOff() { command.set(Command(null)) }
    override fun close() {
        running = false
        manager.abandonAudioFocusRequest(focus)
        hasFocus = false
        // Pausing unblocks a pending write; the output thread alone releases the track.
        runCatching { track.pause(); track.flush() }
    }
    companion object {
        fun load(context: Context): Map<Int, HarmonicaSample> {
            val index = context.assets.open("harmonica/index.tsv").bufferedReader().use { it.readLines() }
            return index.filter { it.isNotBlank() }.associate { line ->
                val (midi, count, start, end, blend) = line.split('\t').map(String::toInt)
                val bytes = context.assets.open("harmonica/$midi.pcm").use { it.readBytes() }
                require(bytes.size == count * 2) { "损坏的口琴音源 $midi" }
                val pcm = ShortArray(count)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                midi to HarmonicaSample(pcm, start, end, blend)
            }.also { require(it.keys == (48..85).toSet()) { "口琴音源不完整" } }
        }
    }
}
