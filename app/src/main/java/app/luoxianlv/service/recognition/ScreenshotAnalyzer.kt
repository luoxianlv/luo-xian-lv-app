package app.luoxianlv.service.recognition

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.luoxianlv.debug.PlaybackDebugLog
import app.luoxianlv.profile.ScreenRecognizer
import app.luoxianlv.service.MusicAccessibilityService

internal object ScreenshotAnalyzer {
    /** Worker-only image conversion/analysis; never touches playback or views. */
    fun recognize(screenshot: AccessibilityService.ScreenshotResult): ScreenRecognizer.Result? {
        val started = SystemClock.uptimeMillis()
        val result = runCatching {
            val hardware =
                try {
                    Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                } finally {
                    screenshot.hardwareBuffer.close()
                }
            val bitmap =
                try {
                    hardware?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hardware?.recycle()
                }
            try {
                bitmap?.let(PlaybackDebugLog::saveScreenshot)
                PlaybackDebugLog.log("screenshot ${bitmap?.width}x${bitmap?.height} analyze start")
                bitmap?.let(ScreenRecognizer::fromBitmap)
            } finally {
                bitmap?.recycle()
            }
        }
            .onFailure {
                Log.w(MusicAccessibilityService.TAG, "截图识别失败", it)
                PlaybackDebugLog.log("recognize failure: ${it.message}")
            }
            .getOrNull()
        PlaybackDebugLog.log("recognition elapsedMs=${SystemClock.uptimeMillis() - started}")
        PlaybackDebugLog.log(
            result?.let { r ->
                "recognized noteX=" +
                    r.layout.noteX.joinToString(",") { "%.3f".format(it) } +
                    " noteY=" +
                    "%.3f".format(r.layout.noteY) +
                    " mode=${r.mode} half=${r.halfTone}" +
                    " observedNotes=${r.observedNotes}/8 observedModes=${r.observedModes}/4" +
                    " noteBorders=${r.noteBorders}/8 modeBorders=${r.modeBorders}/4"
            } ?: "recognize returned null"
        )
        return result
    }
}
