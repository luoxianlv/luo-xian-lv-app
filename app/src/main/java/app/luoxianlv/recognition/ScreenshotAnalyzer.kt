package app.luoxianlv.recognition

import android.os.SystemClock
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.playback.PlaybackSession

internal object ScreenshotAnalyzer {
    /** 图像转换和识别仅在工作线程执行，不操作播放状态或视图。 */
    fun recognize(
        screenshot: AccessibilityBinding.Frame,
        previous: ScreenRecognizer.Result? = null,
    ): ScreenRecognizer.Result? {
        val started = SystemClock.uptimeMillis()
        var conversionMs = 0L
        var analysisMs = 0L
        val result = runCatching {
            val bitmap = ScreenshotBitmapReader.read(screenshot)
            try {
                conversionMs = SystemClock.uptimeMillis() - started
                AppLog.log("开始分析截图：尺寸=${bitmap?.width}x${bitmap?.height}")
                val analysisStarted = SystemClock.uptimeMillis()
                bitmap
                    ?.let { ScreenRecognizer.fromBitmap(it, previous) }
                    .also {
                        analysisMs = SystemClock.uptimeMillis() - analysisStarted
                    }
            } finally {
                bitmap?.let { if (!AppLog.saveScreenshotOwned(it)) it.recycle() }
            }
        }
            .onFailure {
                AppLog.w(PlaybackSession.TAG, "截图识别失败", it)
            }
            .getOrNull()
        AppLog.log("识别耗时毫秒=${SystemClock.uptimeMillis() - started}")
        AppLog.log(
            "识别分段：转换=$conversionMs 毫秒 分析=$analysisMs 毫秒 验证已有布局=${result?.reusedGeometry == true}"
        )
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
