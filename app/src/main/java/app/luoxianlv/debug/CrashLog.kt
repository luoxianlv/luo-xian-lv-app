package app.luoxianlv.debug

import android.content.Context
import android.os.Build
import android.os.Process
import app.luoxianlv.BuildConfig
import app.luoxianlv.storage.AppStorage
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.Date
import kotlin.system.exitProcess

/** 崩溃时直接落盘，不能依赖来不及执行的异步日志队列；保存后仍交给系统正常终止进程。 */
object CrashLog {
    @Synchronized
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is FatalErrorHandler) return
        val directory = AppStorage.logs(context)
        val header =
            "包名=${context.packageName}；版本=${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）；" +
                "设备=${Build.MANUFACTURER} ${Build.MODEL}；Android=${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        Thread.setDefaultUncaughtExceptionHandler(
            FatalErrorHandler(
                save = { thread, error -> write(directory, header, thread, error) },
                next =
                    previous
                        ?: Thread.UncaughtExceptionHandler { _, _ ->
                            Process.killProcess(Process.myPid())
                            exitProcess(10)
                        },
            )
        )
    }

    internal fun write(directory: File, header: String, thread: Thread, error: Throwable) {
        directory.mkdirs()
        // 沿用导出器的日志前缀，只保留最近两次；独立文件避免等待已崩溃线程持有的日志锁。
        val current = File(directory, "play-debug-crash.log")
        val previous = File(directory, "play-debug-crash.log.1")
        if (current.exists()) {
            previous.delete()
            current.renameTo(previous)
        }
        FileOutputStream(current).use { stream ->
            val writer = PrintWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            writer.println("时间=${Date()}；线程=${thread.name}")
            writer.println(header)
            writer.println("未捕获异常（保留原始堆栈与原因）：")
            error.printStackTrace(writer)
            writer.flush()
            stream.fd.sync()
        }
    }
}

/** 日志写入失败也不能吞掉原始异常，或阻断系统及统计 SDK 已有的崩溃处理。 */
internal class FatalErrorHandler(
    private val save: (Thread, Throwable) -> Unit,
    private val next: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            runCatching { save(thread, error) }
        } finally {
            next.uncaughtException(thread, error)
        }
    }
}
