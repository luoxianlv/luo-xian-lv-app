package app.luoxianlv

import android.app.Instrumentation
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.luoxianlv.practice.HarmonicaSampler
import java.util.concurrent.CopyOnWriteArrayList

/** 模拟已播放的媒体持有焦点，按键、松手及释放口琴均不能令它暂停或压低音量。 */
internal fun Instrumentation.checkIndependentAudio() {
    val manager = targetContext.getSystemService(AudioManager::class.java)
    val changes = CopyOnWriteArrayList<Int>()
    val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            .setOnAudioFocusChangeListener({ changes.add(it) }, Handler(Looper.getMainLooper()))
            .build()
    val samples = HarmonicaSampler.load(targetContext)
    var output: HarmonicaSampler? = null
    try {
        runOnMainSync {
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            output = HarmonicaSampler(samples) { error("口琴输出意外中断") }
        }
        repeat(8) { index ->
            runOnMainSync { check(output!!.noteOn(60 + index)) }
            SystemClock.sleep(80)
            runOnMainSync { output!!.noteOff() }
        }
        runOnMainSync {
            output!!.close()
            output = null
        }
        SystemClock.sleep(300)
        check(changes.none { it != AudioManager.AUDIOFOCUS_GAIN }) { "口琴干扰媒体焦点：$changes" }
    } finally {
        runOnMainSync {
            output?.close()
            manager.abandonAudioFocusRequest(focus)
        }
    }
}
