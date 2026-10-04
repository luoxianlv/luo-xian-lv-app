package app.luoxianlv.library

import android.os.Process
import app.luoxianlv.app.BusinessJobs
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher

/** 播放与列表各用独立队列：列表单线程低优先级，不占播放的工作线程。 */
object ScoreWork {
    private val playbackWorker = worker("谱面播放准备", Process.THREAD_PRIORITY_DEFAULT)
    private val previewWorker = worker("曲目时长", Process.THREAD_PRIORITY_BACKGROUND)
    val playback = playbackWorker.asCoroutineDispatcher()
    val preview = previewWorker.asCoroutineDispatcher()

    fun retire() {
        playbackWorker.shutdown()
        previewWorker.shutdown()
    }

    val released: Boolean
        get() = playbackWorker.isTerminated && previewWorker.isTerminated

    private fun worker(name: String, priority: Int) =
        object :
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                java.util.concurrent.ThreadFactory { task ->
                    Thread(
                        {
                            runCatching { Process.setThreadPriority(priority) }
                            task.run()
                        },
                        name,
                    )
                },
            ) {
            override fun execute(command: Runnable) {
                val lease =
                    BusinessJobs.gate.acquire() ?: throw RejectedExecutionException("本代谱面队列已停用")
                try {
                    super.execute {
                        try {
                            command.run()
                        } finally {
                            lease.close()
                        }
                    }
                } catch (failure: Throwable) {
                    lease.close()
                    throw failure
                }
            }
        }
}
