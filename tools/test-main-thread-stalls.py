"""Run actual MIDI kick/audio output code with controllable storage/audio JVM doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / '.local'
RUNS.mkdir(exist_ok=True)
run = Path(tempfile.mkdtemp(prefix='main-thread-stalls-', dir=RUNS))
sources = {
    'Context.kt': '''package android.content
open class Context { val applicationContext: Context get() = this }
''',
    'Os.kt': '''package android.os
object Looper { fun getMainLooper() = this }
class Handler(loop: Looper) { fun post(action: () -> Unit): Boolean { action(); return true } }
object Process { const val THREAD_PRIORITY_AUDIO = -16; fun setThreadPriority(value: Int) {} }
''',
    'Jobs.kt': '''package app.luoxianlv.app
import android.os.Handler
import java.util.concurrent.atomic.AtomicInteger
object BusinessJobs {
    var accepting = true
    val gate = Gate()
    class Gate {
        val retained = AtomicInteger()
        fun retain(): AutoCloseable { retained.incrementAndGet(); return AutoCloseable { retained.decrementAndGet() } }
    }
    fun thread(name: String, action: () -> Unit): Boolean {
        if (!accepting) return false
        Thread(action, name).apply { isDaemon = true }.start()
        return true
    }
    fun post(handler: Handler, action: () -> Unit) = handler.post(action)
}
''',
    'Kv.kt': '''package app.luoxianlv.shared
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
object Kv {
    var entered = CountDownLatch(1)
    var allow = CountDownLatch(1)
    var stamp = 0L
    val opens = AtomicInteger()
    fun of(context: Context, name: String): Kv {
        opens.incrementAndGet(); entered.countDown()
        check(allow.await(3, TimeUnit.SECONDS)); return this
    }
    fun getLong(key: String, fallback: Long) = stamp
    fun edit() = this
    fun putLong(key: String, value: Long): Kv { stamp = value; return this }
    fun apply() {}
}
''',
    'Log.kt': '''package app.luoxianlv.diagnostics
object AppLog { fun w(tag: String, text: String, failure: Throwable) {} }
''',
    'Assets.kt': '''package app.luoxianlv.hot.contract
import android.content.Context
object OfficialAssets {
    fun text(context: Context, type: String, name: String, fallback: String, limit: Int) = ""
    fun read(context: Context, type: String, name: String, fallback: String, limit: Int) = byteArrayOf()
}
''',
    'Audio.kt': '''package android.media
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class AudioAttributes {
    class Builder { fun setUsage(value: Int) = this; fun setContentType(value: Int) = this; fun build() = AudioAttributes() }
    companion object { const val USAGE_GAME = 1; const val CONTENT_TYPE_MUSIC = 1 }
}
class AudioFormat {
    class Builder { fun setSampleRate(value: Int) = this; fun setChannelMask(value: Int) = this; fun setEncoding(value: Int) = this; fun build() = AudioFormat() }
    companion object { const val CHANNEL_OUT_MONO = 1; const val ENCODING_PCM_16BIT = 1 }
}
class AudioTrack {
    val state = STATE_INITIALIZED
    class Builder {
        fun setAudioAttributes(value: AudioAttributes) = this
        fun setAudioFormat(value: AudioFormat) = this
        fun setTransferMode(value: Int) = this
        fun setPerformanceMode(value: Int) = this
        fun setBufferSizeInBytes(value: Int) = this
        fun build() = AudioTrack()
    }
    fun play() {}
    fun write(buffer: ShortArray, offset: Int, count: Int, mode: Int): Int {
        check(mode == WRITE_NON_BLOCKING)
        writes.incrementAndGet(); wrote.countDown(); return 0 // Full native buffer.
    }
    fun pause() {
        check(Thread.currentThread().name == "harmonica-output")
        pauses.incrementAndGet(); pausing.countDown(); check(allowPause.await(3, TimeUnit.SECONDS))
    }
    fun flush() { check(Thread.currentThread().name == "harmonica-output") }
    fun stop() { check(Thread.currentThread().name == "harmonica-output") }
    fun release() { releases.incrementAndGet(); released.countDown() }
    companion object {
        const val STATE_INITIALIZED = 1; const val MODE_STREAM = 1
        const val PERFORMANCE_MODE_LOW_LATENCY = 1; const val WRITE_NON_BLOCKING = 1
        val wrote = CountDownLatch(1); val pausing = CountDownLatch(1)
        val allowPause = CountDownLatch(1); val released = CountDownLatch(1)
        val writes = AtomicInteger(); val pauses = AtomicInteger(); val releases = AtomicInteger()
        fun getMinBufferSize(rate: Int, channel: Int, encoding: Int) = 1920
    }
}
''',
    'Checks.kt': '''package checks
import android.content.Context
import android.media.AudioTrack
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.practice.*
import app.luoxianlv.shared.Kv
import app.luoxianlv.update.MidiCoreFixer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) }
fun waitIdle() {
    val field = MidiCoreFixer.javaClass.getDeclaredField("running").apply { isAccessible = true }
    val running = field.get(MidiCoreFixer) as java.util.concurrent.atomic.AtomicBoolean
    val end = System.nanoTime() + 3_000_000_000L
    while (running.get()) { check(System.nanoTime() < end); Thread.sleep(1) }
}
fun main() {
    val callerReturned = CountDownLatch(1)
    Thread({ MidiCoreFixer.kick(Context()); callerReturned.countDown() }, "ui-caller").start()
    try {
        await(Kv.entered); await(callerReturned)
        repeat(20) { MidiCoreFixer.kick(Context()) }
        check(Kv.opens.get() == 1) // Concurrent foreground edges coalesce while storage blocks.
    } finally { Kv.allow.countDown() }
    waitIdle(); check(MidiCoreFixer.checks.get() == 1)
    MidiCoreFixer.kick(Context()); waitIdle(); check(MidiCoreFixer.checks.get() == 1)
    BusinessJobs.accepting = false
    MidiCoreFixer.kick(Context()); waitIdle()
    BusinessJobs.accepting = true; Kv.stamp = 0
    MidiCoreFixer.kick(Context()); waitIdle(); check(MidiCoreFixer.checks.get() == 2)
    val sampler = HarmonicaSampler(mapOf(60 to HarmonicaSample(ShortArray(2000), 500, 1900, 100)), onInterrupted = { error("Unexpected interruption") })
    await(AudioTrack.wrote)
    val closed = CountDownLatch(1)
    Thread({ sampler.close(); sampler.close(); closed.countDown() }, "ui-close").start()
    try {
        await(closed); await(AudioTrack.pausing)
        check(!sampler.noteOn(60))
        check(BusinessJobs.gate.retained.get() == 1) // No release/retirement while native cleanup stalls.
        check(AudioTrack.releases.get() == 0)
    } finally { AudioTrack.allowPause.countDown() }
    await(AudioTrack.released)
    val end = System.nanoTime() + 3_000_000_000L
    while (BusinessJobs.gate.retained.get() != 0) { check(System.nanoTime() < end); Thread.sleep(1) }
    check(AudioTrack.pauses.get() == 1 && AudioTrack.releases.get() == 1)
    println("PASS: slow storage caller, duplicate coalescing, throttle, rejected-job retry, full audio buffer close, stalled cleanup lease/idempotence")
}
''',
}
# Execute the production kick body; remote version/download work is outside this regression.
midi = (ROOT / 'app/src/main/java/app/luoxianlv/update/MidiCoreFixer.kt').read_text(encoding='utf-8')
body = midi[midi.index('object MidiCoreFixer {'):midi.index('    /** 修复一首歌')]
sources['Midi.kt'] = '''package app.luoxianlv.update
import android.content.Context
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.shared.Kv
import app.luoxianlv.diagnostics.AppLog
import java.util.concurrent.atomic.AtomicBoolean
''' + body + '''    val checks = java.util.concurrent.atomic.AtomicInteger()
    private fun runCheck(app: Context) { checks.incrementAndGet() }
}
'''
for name, content in sources.items():
    (run / name).write_text(content, encoding='utf-8')
production = [ROOT / 'app/src/main/java/app/luoxianlv/practice' / name
              for name in ('HarmonicaSampler.kt', 'HarmonicaVoice.kt')]
jar = run / 'checks.jar'
args = [*run.glob('*.kt'), *production, '-jvm-target', '17', '-include-runtime', '-d', jar]
argfile = run / 'compiler.args'
argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in args), encoding='utf-8')
subprocess.run(['C:/kotlin/bin/kotlinc.bat', '@' + str(argfile)], check=True)
subprocess.run(['java', '-jar', str(jar)], check=True)
print(f'Fixture directory: {run}')
