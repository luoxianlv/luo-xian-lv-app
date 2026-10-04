package app.luoxianlv.wallpaper.render

import java.util.concurrent.Executor

/** 启动任务的提交和后台异常都反馈给等待者；内存耗尽等致命错误继续传播。 */
internal class StartupTaskExecutor(
    private val delegate: Executor,
    private val onFailure: (Throwable) -> Unit,
    private val beforeTask: () -> Unit = {},
) : Executor {
    override fun execute(command: Runnable) {
        try {
            delegate.execute {
                try {
                    beforeTask()
                    command.run()
                } catch (failure: Exception) {
                    onFailure(failure)
                } catch (failure: LinkageError) {
                    onFailure(failure)
                }
            }
        } catch (failure: Exception) {
            onFailure(failure)
        } catch (failure: LinkageError) {
            onFailure(failure)
        }
    }
}
