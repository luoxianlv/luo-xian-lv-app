package app.luoxianlv

import app.luoxianlv.recognition.ScreenRecognizer
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 私人截图与耗时报告留在本地；性能数字不作为跨设备的通过门槛。 */
class RecognitionBenchmarkTest {
    private data class Fixture(val name: String, val pixels: FloatArray, val w: Int, val h: Int)

    private data class Measurement(
        val medianMs: Double,
        val p95Ms: Double,
        val allocatedBytes: Long?,
        val result: ScreenRecognizer.Result?,
    )

    /** 公开样本始终执行；同一 JVM 同时报告完整搜索与当前画面验证，耗时不作硬阈值。 */
    @Test
    fun publicKeyboardBenchmark() {
        val report = StringBuilder()
        for (name in listOf("keyboard-game-1280.gray", "keyboard-game-1920.gray")) {
            val fixture =
                fixture(name, javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() })
            val full = measure(fixture, null)
            assertNotNull(name, full.result)
            val verified = measure(fixture, full.result)
            // 旧布局仍须通过当前圆框验证；不能为加速强制复用本应重新搜索的样本。
            assertNotNull(name, verified.result)
            assertEquals(full.result!!.layout.noteY, verified.result!!.layout.noteY, .005f)
            assertEquals(full.result.mode, verified.result.mode)
            assertEquals(full.result.halfTone, verified.result.halfTone)
            for (i in 0..7) {
                assertEquals(
                    name,
                    full.result.layout.noteX[i],
                    verified.result.layout.noteX[i],
                    .003f,
                )
            }
            for ((kind, measurement) in listOf("full" to full, "previous" to verified)) {
                assertEquals(name, 8, measurement.result!!.noteBorders)
                assertEquals(name, 4, measurement.result.modeBorders)
                report.appendLine(
                    "$name\t$kind\t${measurement.medianMs}\t${measurement.p95Ms}\t${measurement.allocatedBytes}\t${measurement.result.reusedGeometry}\t${coordinates(measurement.result)}"
                )
            }
        }
        System.getenv("LX_RECOGNITION_PUBLIC_REPORT")?.let {
            File(it).writeText(report.toString(), Charsets.UTF_8)
        }
        println("公开识别基准：样本、路径、P50毫秒、P95毫秒、每帧分配字节、复用、识别结果\n$report")
    }

    @Test
    fun diagnosticCaptureBenchmark() {
        val path = System.getenv("LX_RECOGNITION_BENCHMARK")
        assumeTrue(path != null)
        val files = File(path!!).listFiles { f -> f.extension == "gray" }!!.sortedBy { it.name }
        val report = StringBuilder()
        for (file in files) {
            val fixture = fixture(file.name, file.readBytes())
            val previous =
                if (System.getenv("LX_RECOGNITION_HINT") == "true")
                    ScreenRecognizer.analyze(fixture.pixels, fixture.w, fixture.h)
                else null
            val measurement = measure(fixture, previous)
            report.appendLine(
                "${file.name}\t${measurement.medianMs}\t${coordinates(measurement.result)}\t${measurement.result?.reusedGeometry}\t${measurement.p95Ms}\t${measurement.allocatedBytes}"
            )
        }
        System.getenv("LX_RECOGNITION_REPORT")?.let {
            File(it).writeText(report.toString(), Charsets.UTF_8)
        }
        println(report)
    }

    private fun fixture(name: String, bytes: ByteArray): Fixture {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        require(w > 0 && h > 0 && bytes.size.toLong() == 8L + w.toLong() * h)
        return Fixture(name, FloatArray(w * h) { bytes[it + 8].toInt().and(255).toFloat() }, w, h)
    }

    private fun measure(fixture: Fixture, previous: ScreenRecognizer.Result?): Measurement {
        repeat(15) { ScreenRecognizer.analyze(fixture.pixels, fixture.w, fixture.h, previous) }
        val bean =
            (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.takeIf {
                it.isThreadAllocatedMemorySupported
            }
        bean?.let {
            if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
        }
        @Suppress("DEPRECATION") // JVM 基准仍兼容 JDK 17。
        val thread = Thread.currentThread().id
        val times = LongArray(21)
        val allocations = LongArray(times.size)
        var result: ScreenRecognizer.Result? = null
        var expected: String? = null
        repeat(times.size) { i ->
            val before = bean?.getThreadAllocatedBytes(thread) ?: -1L
            val start = System.nanoTime()
            result = ScreenRecognizer.analyze(fixture.pixels, fixture.w, fixture.h, previous)
            times[i] = System.nanoTime() - start
            allocations[i] =
                if (bean != null) bean.getThreadAllocatedBytes(thread) - before else -1L
            // 结果比较和格式化在计时与分配统计之外，验证重复测量不改变定位或状态。
            val signature = coordinates(result)
            if (expected == null) expected = signature
            else assertEquals(fixture.name, expected, signature)
        }
        times.sort()
        allocations.sort()
        return Measurement(
            times[times.size / 2] / 1_000_000.0,
            times[(times.size * 95 + 99) / 100 - 1] / 1_000_000.0,
            allocations[allocations.size / 2].takeIf { it >= 0 },
            result,
        )
    }

    private fun coordinates(result: ScreenRecognizer.Result?): String =
        result?.let { r ->
            listOf(
                    r.layout.noteX.joinToString(","),
                    r.layout.noteY,
                    r.layout.modes.entries.joinToString(";") {
                        "${it.key}:${it.value.joinToString(",")}"
                    },
                    r.mode,
                    r.halfTone,
                    r.observedNotes,
                    r.observedModes,
                    r.noteBorders,
                    r.modeBorders,
                )
                .joinToString("|")
        } ?: "null"
}
