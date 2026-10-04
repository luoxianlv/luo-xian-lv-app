package app.luoxianlv.wallpaper.render

import android.content.Context
import android.os.Looper
import android.os.Process
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import app.luoxianlv.business.BusinessJobs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** 共享后台引擎准备；取消等待不取消底层启动，租约持续到真实回调，避免跨代执行。 */
internal object WebViewStartup {
    private var preparation: CompletableDeferred<Unit>? = null

    suspend fun await(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val result = preparation ?: begin(context.applicationContext)
        awaitStartupPreparation(result)
    }

    private fun begin(context: Context): CompletableDeferred<Unit> {
        val lease = BusinessJobs.gate.acquire() ?: throw CancellationException("本代业务已停止准备壁纸引擎")
        val result = CompletableDeferred<Unit>()
        preparation = result
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "wallpaper-webview-startup").apply { isDaemon = true }
        }
        val finished = AtomicBoolean()
        fun complete(failure: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            try {
                if (failure == null) result.complete(Unit)
                else result.completeExceptionally(failure)
            } finally {
                executor.shutdown()
                lease.close()
            }
        }
        try {
            // 实例及必须在主线程执行的剩余任务，由可见检查或空闲预热稍后触发。
            val background =
                StartupTaskExecutor(executor, ::complete) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                }
            val config =
                WebViewStartUpConfig.Builder(background)
                    .setShouldRunUiThreadStartUpTasks(false)
                    .build()
            WebViewCompat.startUpWebView(
                context,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) = complete()

                    override fun onError(error: WebViewStartupException) = complete(error)
                },
            )
        } catch (failure: Exception) {
            complete(failure)
        } catch (failure: LinkageError) {
            complete(failure)
        }
        return result
    }
}
