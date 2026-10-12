"""Compile the real sampler and exercise slow/failing native audio startup on the JVM."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
(ROOT / '.local').mkdir(exist_ok=True)
fixture = Path(tempfile.mkdtemp(prefix='audio-startup-', dir=ROOT / '.local'))
sources = {
    'Context.kt': 'package android.content\nopen class Context\n',
    'Os.kt': '''package android.os
import java.util.concurrent.LinkedBlockingQueue
object Looper { fun getMainLooper() = this }
class Handler(loop: Looper) {
    fun post(action: () -> Unit): Boolean { tasks.add(action); return true }
    companion object { val tasks = LinkedBlockingQueue<() -> Unit>() }
}
object Process { const val THREAD_PRIORITY_AUDIO = -16; fun setThreadPriority(value: Int) {} }
''',
    'Jobs.kt': '''package app.luoxianlv.app
import android.os.Handler
import java.util.concurrent.atomic.AtomicInteger
object BusinessJobs {
    val gate = Gate()
    class Gate {
        var accepting = true
        val retained = AtomicInteger()
        fun retain(): AutoCloseable? {
            if (!accepting) return null
            retained.incrementAndGet()
            return AutoCloseable { retained.decrementAndGet() }
        }
    }
    fun post(handler: Handler, action: () -> Unit) = handler.post(action)
}
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
fun audioThread() { check(Thread.currentThread().name == "harmonica-output") { "native audio called on caller thread" } }
class AudioAttributes {
    class Builder { fun setUsage(value: Int) = this; fun setContentType(value: Int) = this; fun build() = AudioAttributes() }
    companion object { const val USAGE_GAME = 1; const val CONTENT_TYPE_MUSIC = 1 }
}
class AudioFormat {
    class Builder { fun setSampleRate(value: Int) = this; fun setChannelMask(value: Int) = this; fun setEncoding(value: Int) = this; fun build() = AudioFormat() }
    companion object { const val CHANNEL_OUT_MONO = 1; const val ENCODING_PCM_16BIT = 1 }
}
class AudioTrack {
    val state get() = if (mode == "uninitialized") 0 else STATE_INITIALIZED
    class Builder {
        fun setAudioAttributes(value: AudioAttributes) = this
        fun setAudioFormat(value: AudioFormat) = this
        fun setTransferMode(value: Int) = this
        fun setPerformanceMode(value: Int) = this
        fun setBufferSizeInBytes(value: Int) = this
        fun build(): AudioTrack {
            audioThread(); builds.incrementAndGet(); building.countDown()
            if (mode == "build-close") check(allow.await(10, TimeUnit.SECONDS))
            if (mode == "build-failure") error("fixture builder failed")
            return AudioTrack()
        }
    }
    fun play() { audioThread(); plays.incrementAndGet(); playing.countDown() }
    fun write(buffer: ShortArray, offset: Int, count: Int, mode: Int): Int {
        audioThread(); check(mode == WRITE_NON_BLOCKING); wrote.countDown(); return 0
    }
    fun pause() { audioThread() }
    fun flush() { audioThread() }
    fun stop() { audioThread() }
    fun release() { audioThread(); releases.incrementAndGet() }
    companion object {
        const val STATE_INITIALIZED = 1; const val MODE_STREAM = 1
        const val PERFORMANCE_MODE_LOW_LATENCY = 1; const val WRITE_NON_BLOCKING = 1
        var mode = "normal"
        val querying = CountDownLatch(1); val building = CountDownLatch(1)
        val playing = CountDownLatch(1); val wrote = CountDownLatch(1); val allow = CountDownLatch(1)
        val queries = AtomicInteger(); val builds = AtomicInteger(); val plays = AtomicInteger(); val releases = AtomicInteger()
        fun getMinBufferSize(rate: Int, channel: Int, encoding: Int): Int {
            audioThread(); queries.incrementAndGet(); querying.countDown()
            if (mode == "min-close") check(allow.await(10, TimeUnit.SECONDS))
            if (mode == "min-failure" || mode == "failure-closed") error("fixture buffer query failed")
            return 1920
        }
    }
}
''',
    'Checks.kt': '''package checks
import android.os.Handler
import android.media.AudioTrack
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.practice.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) }
fun idle() {
    val end = System.nanoTime() + 5_000_000_000L
    while (BusinessJobs.gate.retained.get() != 0) { check(System.nanoTime() < end); Thread.sleep(1) }
}
fun main(args: Array<String>) {
    val mode = args.single(); AudioTrack.mode = mode
    val interrupted = AtomicInteger()
    val samples = mapOf(60 to HarmonicaSample(ShortArray(2000), 500, 1900, 100))
    if (mode == "retired") {
        BusinessJobs.gate.accepting = false
        check(runCatching { HarmonicaSampler(samples, onInterrupted = { interrupted.incrementAndGet() }) }.isFailure)
        check(AudioTrack.queries.get() == 0 && BusinessJobs.gate.retained.get() == 0)
        println("PASS: $mode"); return
    }
    val sampler = HarmonicaSampler(samples, onInterrupted = { interrupted.incrementAndGet() })
    when (mode) {
        "min-close", "build-close" -> {
            try {
                await(if (mode == "min-close") AudioTrack.querying else AudioTrack.building)
                check(BusinessJobs.gate.retained.get() == 1)
                check(sampler.noteOn(60)); sampler.close(); sampler.close()
                check(!sampler.noteOn(60)); check(AudioTrack.plays.get() == 0)
                check(BusinessJobs.gate.retained.get() == 1)
            } finally { AudioTrack.allow.countDown() }
            idle()
            check(AudioTrack.plays.get() == 0 && interrupted.get() == 0)
            if (mode == "min-close") check(AudioTrack.builds.get() == 0 && AudioTrack.releases.get() == 0)
            else check(AudioTrack.releases.get() == 1)
        }
        "normal" -> {
            await(AudioTrack.playing); await(AudioTrack.wrote)
            check(sampler.noteOn(60)); sampler.noteOff(); sampler.close(); idle()
            check(AudioTrack.releases.get() == 1 && interrupted.get() == 0)
        }
        else -> {
            val callback = Handler.tasks.poll(5, TimeUnit.SECONDS)
            check(callback != null)
            if (mode == "failure-closed") sampler.close()
            callback(); idle()
            check(interrupted.get() == if (mode == "failure-closed") 0 else 1)
            check(!sampler.noteOn(60))
            check(AudioTrack.releases.get() == if (mode == "uninitialized") 1 else 0)
            sampler.close()
        }
    }
    println("PASS: $mode")
}
''',
}
for name, text in sources.items():
    (fixture / name).write_text(text, encoding='utf-8')
production = [ROOT / 'app/src/main/java/app/luoxianlv/practice' / name
              for name in ('HarmonicaSampler.kt', 'HarmonicaVoice.kt', 'HarmonicaCommands.kt', 'HarmonicaPcm.kt')]
jar = fixture / 'checks.jar'
args = [*fixture.glob('*.kt'), *production, '-jvm-target', '17', '-include-runtime', '-d', jar]
argfile = fixture / 'compiler.args'
argfile.write_text('\n'.join('"' + str(a).replace('\\', '/') + '"' for a in args), encoding='utf-8')
compiler = shutil.which('kotlinc.bat') or shutil.which('kotlinc')
if not compiler and os.environ.get('KOTLIN_HOME'):
    compiler = str(Path(os.environ['KOTLIN_HOME']) / 'bin' / ('kotlinc.bat' if os.name == 'nt' else 'kotlinc'))
if not compiler:
    raise RuntimeError('Kotlin compiler is required on PATH or in KOTLIN_HOME')
subprocess.run([compiler, '@' + str(argfile)], check=True)
for mode in ('min-close', 'build-close', 'normal', 'uninitialized', 'min-failure', 'build-failure', 'failure-closed', 'retired'):
    subprocess.run(['java', '-jar', str(jar), mode], check=True)
