"""Exercise the complete production AppLog with blocking Android Log JVM doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / '.local'
RUNS.mkdir(exist_ok=True)
fixture = Path(tempfile.mkdtemp(prefix='logcat-thread-', dir=RUNS))
sources = {
    'Context.kt': '''package android.content
open class Context {
    val applicationContext: Context get() = this
    val packageName = "logcat-fixture"
}
''',
    'Clock.kt': '''package android.os
object SystemClock { fun elapsedRealtime() = System.nanoTime() / 1_000_000 }
''',
    'Bitmap.kt': '''package android.graphics
import java.io.OutputStream
class Bitmap {
    val width = 1; val height = 1
    enum class Config { ARGB_8888 }
    enum class CompressFormat { JPEG }
    fun copy(config: Config, mutable: Boolean): Bitmap? = Bitmap()
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream) = true
    fun recycle() {}
}
''',
    'Storage.kt': '''package app.luoxianlv.shared
import android.content.Context
import java.io.File
object AppStorage {
    lateinit var root: File
    fun logs(context: Context) = File(root, "logs").apply { mkdirs() }
    fun diagnostics(context: Context) = File(root, "exports").apply { mkdirs() }
    fun migrateDiagnostics(context: Context) {}
}
''',
    'Locks.kt': '''package app.luoxianlv.hot.contract
object ProcessLocks { private val lock = Any(); fun monitor(name: String) = lock }
''',
    'Log.kt': '''package android.util
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
object Log {
    const val DEBUG = 3; const val INFO = 4; const val WARN = 5; const val ERROR = 6
    val lines = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var block: String? = null
    @Volatile var stackBlock = false
    var entered = CountDownLatch(1)
    var allow = CountDownLatch(1)
    fun println(priority: Int, tag: String, text: String): Int {
        check(Thread.currentThread().name == "playback-diagnostics") {
            "Log.println executed on caller: " + Thread.currentThread().name
        }
        if (text == block) {
            entered.countDown()
            check(allow.await(10, TimeUnit.SECONDS)) { "native logger wait timed out" }
        }
        lines.add("$priority|$tag|$text")
        return text.length
    }
    fun getStackTraceString(error: Throwable): String {
        check(Thread.currentThread().name == "playback-diagnostics") {
            "Stack trace formatted on caller: " + Thread.currentThread().name
        }
        if (stackBlock) {
            entered.countDown()
            check(allow.await(10, TimeUnit.SECONDS)) { "stack formatting wait timed out" }
        }
        return error.stackTraceToString()
    }
    fun w(tag: String, text: String, error: Throwable) = println(WARN, tag, text)
    fun blockNext(text: String) {
        block = text; stackBlock = false
        entered = CountDownLatch(1); allow = CountDownLatch(1)
    }
    fun unblock() { block = null; stackBlock = false; allow.countDown() }
}
''',
    'Checks.kt': '''package checks
import android.content.Context
import android.util.Log
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.shared.AppStorage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) }
fun caller(action: () -> Unit): CountDownLatch {
    val done = CountDownLatch(1)
    Thread({ action(); done.countDown() }, "ui-log-caller").apply { isDaemon = true }.start()
    return done
}
fun fileText() = File(AppLog.directory()!!, "play-debug.log").readText()
fun main(args: Array<String>) {
    AppStorage.root = File(args.single())
    AppLog.i("before", "before-init")
    AppLog.init(Context()); AppLog.flush()
    check(Log.lines.any { it.endsWith("before-init") })
    check(!fileText().contains("before-init"))

    Log.blockNext("blocked-system-log")
    try {
        val returned = caller { AppLog.log("blocked-system-log") }
        await(Log.entered); await(returned)
        check(!fileText().contains("blocked-system-log"))
    } finally { Log.unblock() }
    AppLog.flush()
    check(fileText().contains("[调试][播放] blocked-system-log"))

    Log.blockNext("unused"); Log.stackBlock = true
    try {
        val returned = caller { AppLog.e("异常", "error-entry", IllegalStateException("fixture-error")) }
        await(Log.entered); await(returned)
        AppLog.i("排序", "after-stack")
        check(Log.lines.none { it.endsWith("after-stack") })
    } finally { Log.unblock() }
    AppLog.flush()
    val ordered = fileText()
    check(ordered.contains("IllegalStateException: fixture-error"))
    check(ordered.indexOf("error-entry") < ordered.indexOf("after-stack"))

    Log.blockNext("flush-barrier")
    val snapshot = AtomicReference<String>()
    val flushed = CountDownLatch(1)
    try {
        AppLog.log("flush-barrier"); await(Log.entered)
        Thread({ AppLog.flush(); snapshot.set(fileText()); flushed.countDown() }, "background-export").start()
        check(!flushed.await(100, TimeUnit.MILLISECONDS))
    } finally { Log.unblock() }
    await(flushed)
    check(snapshot.get().contains("flush-barrier"))

    Log.blockNext("full-queue")
    try {
        AppLog.log("full-queue"); await(Log.entered)
        val returned = caller { repeat(1000) { AppLog.log("queued-$it") } }
        await(returned)
        val field = AppLog.javaClass.getDeclaredField("worker").apply { isAccessible = true }
        val worker = field.get(AppLog) as java.util.concurrent.ThreadPoolExecutor
        check(worker.queue.size == 256)
        check(Log.lines.none { it.contains("|queued-") })
    } finally { Log.unblock() }
    AppLog.flush()
    val retained = fileText()
    check(retained.contains("丢弃 744 条诊断任务"))
    check(retained.contains("queued-255") && !retained.contains("queued-256"))

    Log.blockNext("retiring-pending")
    try {
        AppLog.log("retiring-pending"); await(Log.entered)
        await(caller { AppLog.retire(); AppLog.log("after-retire") })
        check(!AppLog.released)
    } finally { Log.unblock() }
    AppLog.flush()
    check(AppLog.released)
    check(fileText().contains("retiring-pending") && !fileText().contains("after-retire"))
    println("PASS: pre-init console only, blocked logcat caller, blocked stack formatting/order, flush includes file append, bounded queue saturation/drop count, retirement drain/rejection")
}
''',
}
for name, content in sources.items():
    (fixture / name).write_text(content, encoding='utf-8')
production = [ROOT / 'app/src/main/java/app/luoxianlv/diagnostics' / name
              for name in ('AppLog.kt', 'DiagnosticRetention.kt')]
jar = fixture / 'checks.jar'
arguments = [*fixture.glob('*.kt'), *production, '-jvm-target', '17', '-include-runtime', '-d', jar]
argfile = fixture / 'compiler.args'
argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in arguments), encoding='utf-8')
subprocess.run(['C:/kotlin/bin/kotlinc.bat', '@' + str(argfile)], check=True)
subprocess.run(['java', '-jar', str(jar), str(fixture)], check=True)
print(f'Fixture directory: {fixture}')
