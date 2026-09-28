package app.luoxianlv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** 每个演练场持有一条口琴音频流；构造前先在 IO 线程解码采样。 */
class HarmonicaSampler(
    samples: Map<Int, HarmonicaSample>,
    private val onInterrupted: () -> Unit,
) : AutoCloseable {
    private data class Command(val midi: Int?)

    private val command = AtomicReference<Command?>(null)
    private val voice = HarmonicaVoice(samples)
    private val main = Handler(Looper.getMainLooper())
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    @Volatile private var running = true
    private val track =
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(
                maxOf(
                    1920,
                    AudioTrack.getMinBufferSize(
                        48000,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                )
            )
            .build()
    private val worker: Thread

    init {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化音频输出" }
        worker =
            Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                        try {
                            track.play()
                            val buffer = ShortArray(240)
                            while (running) {
                                command.getAndSet(null)?.let {
                                    if (it.midi == null) voice.noteOff() else voice.noteOn(it.midi)
                                }
                                voice.render(buffer)
                                var offset = 0
                                while (running && offset < buffer.size) {
                                    val written =
                                        track.write(
                                            buffer,
                                            offset,
                                            buffer.size - offset,
                                            AudioTrack.WRITE_BLOCKING,
                                        )
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
                    },
                    "harmonica-output",
                )
                .apply { start() }
    }

    fun noteOn(midi: Int): Boolean {
        if (!running) return false
        // 口琴是前台交互音效，直接混音；不抢占或压低壁纸持有的媒体焦点。
        command.set(Command(midi))
        return true
    }

    fun noteOff() {
        command.set(Command(null))
    }

    override fun close() {
        running = false
        // 暂停用于唤醒阻塞写入；音轨仅由输出线程释放。
        runCatching {
            track.pause()
            track.flush()
        }
    }

    companion object {
        fun load(context: Context): Map<Int, HarmonicaSample> {
            val index =
                context.assets.open("harmonica/index.tsv").bufferedReader().use { it.readLines() }
            return index
                .filter { it.isNotBlank() }
                .associate { line ->
                    val (midi, count, start, end, blend) = line.split('\t').map(String::toInt)
                    val bytes = context.assets.open("harmonica/$midi.pcm").use { it.readBytes() }
                    require(bytes.size == count * 2) { "损坏的口琴音源 $midi" }
                    val pcm = ShortArray(count)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    midi to HarmonicaSample(pcm, start, end, blend)
                }
                .also { require(it.keys == (48..85).toSet()) { "口琴音源不完整" } }
        }
    }
}
