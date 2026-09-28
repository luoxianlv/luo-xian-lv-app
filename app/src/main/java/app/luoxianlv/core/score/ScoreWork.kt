package app.luoxianlv.core.score

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** 播放与列表各用独立队列：列表单线程低优先级，不占播放的工作线程。 */
object ScoreWork {
    val playback = dispatcher("谱面播放准备", Process.THREAD_PRIORITY_DEFAULT)
    val preview = dispatcher("曲目时长", Process.THREAD_PRIORITY_BACKGROUND)

    private fun dispatcher(name: String, priority: Int) =
        Executors.newSingleThreadExecutor { task ->
                Thread(
                    {
                        Process.setThreadPriority(priority)
                        task.run()
                    },
                    name,
                )
            }
            .asCoroutineDispatcher()
}
