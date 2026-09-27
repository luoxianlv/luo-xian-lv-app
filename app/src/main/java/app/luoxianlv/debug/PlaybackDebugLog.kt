package app.luoxianlv.debug

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 播放调试文件日志：所有手势/识别/屏幕状态写入 filesDir/playback-debug/， 供诊断页打包 ZIP 导出。后台队列有界；写入失败时跳过诊断记录。 */
object PlaybackDebugLog {
    private val lock = Any()
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
            worker.execute { runCatching(task) }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }

    /** Export runs on a background thread, after pending diagnostics have drained. */
    fun flush() {
        worker.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    @Volatile private var dir: File? = null
    @Volatile private var exports: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "playback-debug").apply { mkdirs() }
        exports = File(context.cacheDir, "updates")
        enqueue { synchronized(lock) { trim() } }
        log("logger init pkg=${context.packageName}")
    }

    fun log(message: String) {
        val d = dir ?: return
        val at = Date()
        enqueue {
            synchronized(lock) {
                try {
                    val file = File(d, "play-debug.log")
                    FileOutputStream(file, true).use {
                        it.write(
                            (stamp.format(at) + " " + message + "\n").toByteArray(Charsets.UTF_8)
                        )
                    }
                    if (file.length() > 8L * 1024 * 1024) {
                        File(d, "play-debug.log.1").delete()
                        file.renameTo(File(d, "play-debug.log.1"))
                    }
                    trim()
                } catch (_: Exception) {}
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
}
