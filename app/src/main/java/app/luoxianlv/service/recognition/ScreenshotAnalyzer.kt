package app.luoxianlv.service.recognition

import android.graphics.Bitmap
import android.os.SystemClock
import app.luoxianlv.business.playback.PlaybackSession
import app.luoxianlv.debug.AppLog
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.profile.ScreenRecognizer

internal object ScreenshotAnalyzer {
    /** 图像转换和识别仅在工作线程执行，不操作播放状态或视图。 */
    fun recognize(screenshot: AccessibilityBinding.Frame): ScreenRecognizer.Result? {
        val started = SystemClock.uptimeMillis()
        val result = runCatching {
            val hardware =
                try {
                    Bitmap.wrapHardwareBuffer(screenshot.buffer, screenshot.colorSpace)
                } finally {
                    screenshot.close()
                }
            val bitmap =
                try {
                    hardware?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hardware?.recycle()
                }
            try {
                bitmap?.let(AppLog::saveScreenshot)
                AppLog.log("开始分析截图：尺寸=${bitmap?.width}x${bitmap?.height}")
                bitmap?.let(ScreenRecognizer::fromBitmap)
            } finally {
                bitmap?.recycle()
            }
        }
            .onFailure {
                AppLog.w(PlaybackSession.TAG, "截图识别失败", it)
            }
            .getOrNull()
        AppLog.log("识别耗时毫秒=${SystemClock.uptimeMillis() - started}")
        AppLog.log(
            result?.let { r ->
                "识别结果：音符横坐标=" +
                    r.layout.noteX.joinToString(",") { "%.3f".format(it) } +
                    " 音符纵坐标=" +
                    "%.3f".format(r.layout.noteY) +
                    " 音区=${r.mode} 半音=${r.halfTone}" +
                    " 直接识别音符=${r.observedNotes}/8 直接识别音区=${r.observedModes}/4" +
                    " 音符边框=${r.noteBorders}/8 音区边框=${r.modeBorders}/4"
            } ?: "识别结果为空"
        )
        return result
    }
}
