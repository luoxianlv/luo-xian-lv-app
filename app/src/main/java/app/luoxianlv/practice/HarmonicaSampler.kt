package app.luoxianlv.practice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.hot.contract.OfficialAssets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** 每个演练场持有一条口琴音频流；构造前先在 IO 线程解码采样。 */
class HarmonicaSampler(
    samples: Map<Int, HarmonicaSample>,
    private val gain: Float = 1f,
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
    @Volatile private var closed = false
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
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("无法初始化音频输出")
        }
        val lease =
            BusinessJobs.gate.retain()
                ?: run {
                    track.release()
                    error("本代音频已退役")
                }
        worker =
            Thread(
                    {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                            track.play()
                            val buffer = ShortArray(240)
                            while (running) {
                                command.getAndSet(null)?.let {
                                    if (it.midi == null) voice.noteOff() else voice.noteOn(it.midi)
                                }
                                voice.render(buffer)
                                if (gain != 1f)
                                    for (i in buffer.indices) buffer[i] =
                                        (buffer[i] * gain).toInt().toShort()
                                var offset = 0
                                while (running && offset < buffer.size) {
                                    val written =
                                        track.write(
                                            buffer,
                                            offset,
                                            buffer.size - offset,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    check(written >= 0) { "音频输出中断 ($written)" }
                                    if (written == 0) LockSupport.parkNanos(1_000_000L)
                                    else offset += written
                                }
                            }
                        } catch (_: Exception) {
                            if (running)
                                BusinessJobs.post(main) {
                                    if (!closed) onInterrupted()
                                }
                        } finally {
                            running = false
                            try {
                                runCatching { track.pause() }
                                runCatching { track.flush() }
                                runCatching { track.stop() }
                                track.release()
                            } finally {
                                lease.close()
                            }
                        }
                    },
                    "harmonica-output",
                )
                .apply {
                    try {
                        start()
                    } catch (failure: Throwable) {
                        try {
                            track.release()
                        } finally {
                            lease.close()
                        }
                        throw failure
                    }
                }
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
        closed = true
        running = false
        // 非阻塞写入使输出线程自行退出；UI 不等待厂商音频 Binder。
        LockSupport.unpark(worker)
    }

    companion object {
        fun load(context: Context): Map<Int, HarmonicaSample> {
            val index =
                OfficialAssets.text(context, "harmonica", "index.tsv", "harmonica/index.tsv", 65536)
                    .lines()
            return index
                .filter { it.isNotBlank() }
                .associate { line ->
                    val (midi, count, start, end, blend) = line.split('\t').map(String::toInt)
                    val bytes =
                        OfficialAssets.read(
                            context,
                            "harmonica",
                            "$midi.pcm",
                            "harmonica/$midi.pcm",
                            16 * 1024 * 1024,
                        )
                    require(bytes.size == count * 2) { "损坏的口琴音源 $midi" }
                    val pcm = ShortArray(count)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    midi to HarmonicaSample(pcm, start, end, blend)
                }
                .also { require(it.keys == (48..85).toSet()) { "口琴音源不完整" } }
        }
    }
}
