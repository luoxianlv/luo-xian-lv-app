package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import app.luoxianlv.practice.HarmonicaSample
import app.luoxianlv.practice.HarmonicaSampler
import java.util.concurrent.atomic.AtomicInteger

/** 在自建模拟器验证真实音频创建、播放循环和快速关闭，慢系统服务另用 JVM 替身覆盖。 */
class AudioStartupInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun worker(sampler: HarmonicaSampler): Thread =
        HarmonicaSampler::class.java.getDeclaredField("worker").run {
            isAccessible = true
            get(sampler) as Thread
        }

    override fun onStart() {
        val report = Bundle()
        val interrupted = AtomicInteger()
        val sample = HarmonicaSample(ShortArray(4000), 500, 3000, 100)
        var current: HarmonicaSampler? = null
        var passed = false
        try {
            repeat(3) {
                runOnMainSync {
                    current =
                        HarmonicaSampler(mapOf(60 to sample)) { interrupted.incrementAndGet() }
                    check(current!!.noteOn(60))
                }
                val audio = worker(current!!)
                val deadline = SystemClock.uptimeMillis() + 5000
                while (
                    audio.stackTrace.none {
                        it.methodName == "write" || it.methodName == "parkNanos"
                    }
                ) {
                    check(audio.isAlive && interrupted.get() == 0) { "真实音频创建失败" }
                    check(SystemClock.uptimeMillis() < deadline) { "真实音频未进入输出循环" }
                    SystemClock.sleep(10)
                }
                runOnMainSync {
                    current!!.noteOff()
                    current!!.close()
                    current!!.close()
                    check(!current!!.noteOn(60))
                }
                audio.join(5000)
                check(!audio.isAlive) { "音频输出线程未退出" }
                current = null
            }
            repeat(10) {
                runOnMainSync {
                    current =
                        HarmonicaSampler(mapOf(60 to sample)) { interrupted.incrementAndGet() }
                    current!!.close()
                }
                val audio = worker(current!!)
                audio.join(5000)
                check(!audio.isAlive) { "立即关闭留下音频线程" }
                current = null
            }
            check(interrupted.get() == 0)
            report.putString(
                "stream",
                "通过：三轮真实 AudioTrack 创建并进入输出循环、松键/重复关闭/关闭后拒绝音符，十轮创建后立即关闭均排空输出线程。\n",
            )
            passed = true
        } catch (failure: Throwable) {
            report.putString("stream", failure.stackTraceToString())
        } finally {
            current?.let {
                runOnMainSync { it.close() }
                worker(it).join(5000)
            }
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
