package app.luoxianlv.playback

import android.os.Handler
import android.view.Display
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.recognition.ScreenRecognizer
import app.luoxianlv.recognition.ScreenshotAnalyzer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** 管理截图识别的工作队列和缓冲区，会话负责请求代际、显示状态及识别结果应用。 */
internal class PlaybackScreenCapture(
    private val handler: Handler,
    private val closed: () -> Boolean,
) {
    private val jobs = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor()

    val idle
        get() = jobs.get() == 0

    val released
        get() = idle && executor.isTerminated

    /** 允许已接受的截图任务执行 finally，确保缓冲区释放。 */
    fun close() = executor.shutdown()

    /** accept 与结果回调在主线程执行；拒绝过期截图时不进入识别队列。 */
    fun recognize(
        binding: AccessibilityBinding,
        accept: (AccessibilityBinding.Frame) -> Boolean,
        done: (PlaybackCoordinates.Frame, ScreenRecognizer.Result?) -> Unit,
        failed: () -> Unit,
    ) {
        try {
            binding.screenshot(
                Display.DEFAULT_DISPLAY,
                object : AccessibilityBinding.ScreenshotCallback {
                    override fun success(screenshot: AccessibilityBinding.Frame) {
                        if (!accept(screenshot)) {
                            screenshot.close()
                            failed()
                            return
                        }
                        val frame =
                            PlaybackCoordinates.Frame(
                                screenshot.buffer.width,
                                screenshot.buffer.height,
                            )
                        jobs.incrementAndGet()
                        try {
                            executor.execute {
                                val result =
                                    try {
                                        ScreenshotAnalyzer.recognize(screenshot)
                                    } finally {
                                        jobs.decrementAndGet()
                                    }
                                if (closed()) return@execute
                                handler.post {
                                    if (!closed()) done(frame, result)
                                }
                            }
                        } catch (failure: RejectedExecutionException) {
                            jobs.decrementAndGet()
                            screenshot.close()
                            if (!closed()) failed()
                        }
                    }

                    override fun failure(errorCode: Int) {
                        AppLog.w(PlaybackSession.TAG, "截图失败：错误码=$errorCode")
                        failed()
                    }
                },
            )
        } catch (failure: Exception) {
            AppLog.w(PlaybackSession.TAG, "无法请求截图", failure)
            failed()
        }
    }
}
