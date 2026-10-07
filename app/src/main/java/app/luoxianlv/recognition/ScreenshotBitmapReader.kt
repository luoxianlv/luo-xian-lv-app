package app.luoxianlv.recognition

import android.graphics.Bitmap
import app.luoxianlv.hot.contract.AccessibilityBinding

/** 帧只消费一次；软件图直接交付，硬件图读回后立即归还 GPU 引用。 */
internal object ScreenshotBitmapReader {
    fun read(frame: AccessibilityBinding.Frame): Bitmap? {
        frame.takeBitmap()?.let {
            return it
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            frame.close()
            return null
        }
        val hardware =
            try {
                Bitmap.wrapHardwareBuffer(frame.buffer, frame.colorSpace)
            } finally {
                frame.close()
            } ?: return null
        return try {
            hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardware.recycle()
        }
    }
}
