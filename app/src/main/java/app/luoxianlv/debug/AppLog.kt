package app.luoxianlv.debug

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.luoxianlv.storage.AppStorage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 统一中文诊断日志：后台有界写入、8 MiB 轮转，日志和截图合计保留至多 50 MiB。 */
object AppLog {
    private val lock = app.luoxianlv.hot.contract.ProcessLocks.monitor("diagnostics")
    private val dropped = AtomicLong()
    private var lastTrim = 0L
    private val worker =
        java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
            java.util.concurrent.ThreadFactory { task ->
                Thread(task, "playback-diagnostics").apply { isDaemon = true }
            },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    private val pendingShot = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun enqueue(task: () -> Unit): Boolean =
        try {
            worker.execute {
                runCatching(task).onFailure {
                    dropped.incrementAndGet()
                    Log.w("落弦律日志", "诊断写入失败，业务继续运行", it)
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            dropped.incrementAndGet()
            false
        }

    /** 仅供后台导出调用：等待已入队记录写完；队列满时仍可插入屏障。 */
    fun flush() {
        if (worker.isShutdown) {
            check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "旧日志队列尚未退出"
            }
            return
        }
        val barrier = java.util.concurrent.FutureTask<Unit> {}
        try {
            worker.execute(barrier)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            check(worker.queue.offer(barrier, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                "日志队列繁忙"
            }
        }
        barrier.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA)
    @Volatile private var dir: File? = null
    @Volatile private var exports: File? = null

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        dir = AppStorage.logs(context)
        exports = AppStorage.diagnostics(context)
        enqueue {
            runCatching { AppStorage.migrateDiagnostics(context.applicationContext) }
                .onFailure { Log.w("落弦律日志", "旧诊断迁移失败，原文件已保留", it) }
            synchronized(lock) { trim() }
        }
        i("应用", "日志已启动：包名=${context.packageName}；目录=${dir?.absolutePath}")
    }

    fun d(tag: String, message: String) = write("调试", tag, message, null, Log.DEBUG)

    fun i(tag: String, message: String) = write("信息", tag, message, null, Log.INFO)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write("警告", tag, message, error, Log.WARN)

    fun e(tag: String, message: String, error: Throwable? = null) =
        write("错误", tag, message, error, Log.ERROR)

    fun log(message: String) = d("播放", message)

    private fun write(
        level: String,
        tag: String,
        message: String,
        error: Throwable?,
        priority: Int,
    ) {
        val detail = if (error == null) message else "$message\n${Log.getStackTraceString(error)}"
        Log.println(priority, tag, detail)
        append("[$level][$tag] $detail")
    }

    private fun append(message: String) {
        val d = dir ?: return
        val at = Date()
        enqueue {
            synchronized(lock) {
                try {
                    val file = File(d, "play-debug.log")
                    FileOutputStream(file, true).use {
                        it.write(
                            (stamp.format(at) +
                                    " " +
                                    message +
                                    "\n" +
                                    dropped
                                        .getAndSet(0)
                                        .takeIf { count -> count > 0 }
                                        ?.let { count ->
                                            "${stamp.format(at)} [警告][日志] 因队列繁忙或写入失败，丢弃 $count 条诊断任务\n"
                                        }
                                        .orEmpty())
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                    if (file.length() > 8L * 1024 * 1024) {
                        File(d, "play-debug.log.1").delete()
                        file.renameTo(File(d, "play-debug.log.1"))
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastTrim >= 30000) {
                        trim()
                        lastTrim = now
                    }
                } catch (error: Exception) {
                    dropped.incrementAndGet()
                    Log.w("落弦律日志", "日志文件写入失败", error)
                }
            }
        }
    }

    /** 保存识别用的截图（ARGB 软件副本），只保留最近 15 张。 */
    fun saveScreenshot(bitmap: Bitmap) {
        val d = dir ?: return
        if (!pendingShot.compareAndSet(false, true)) return
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copy == null) {
            pendingShot.set(false)
            return
        }
        val at = Date()
        if (
            !enqueue {
                try {
                    synchronized(lock) {
                        val shots = File(d, "shots").apply { mkdirs() }
                        val file =
                            File(
                                shots,
                                "shot_${fileStamp.format(at)}_${copy.width}x${copy.height}.jpg",
                            )
                        FileOutputStream(file).use {
                            copy.compress(Bitmap.CompressFormat.JPEG, 85, it)
                        }
                        shots
                            .listFiles()
                            ?.sortedBy { it.name }
                            ?.dropLast(15)
                            ?.forEach { it.delete() }
                        trim()
                    }
                } finally {
                    copy.recycle()
                    pendingShot.set(false)
                }
            }
        ) {
            copy.recycle()
            pendingShot.set(false)
        }
    }

    internal fun trim() {
        val logs = dir ?: return
        val zipDirectory = exports ?: return
        DiagnosticRetention.trim(logs, zipDirectory)
    }

    fun <T> withSnapshot(action: () -> T): T = synchronized(lock) { action() }

    fun directory(): File? = dir

    fun retire() {
        worker.shutdown()
    }

    val released: Boolean
        get() = worker.isTerminated
}
