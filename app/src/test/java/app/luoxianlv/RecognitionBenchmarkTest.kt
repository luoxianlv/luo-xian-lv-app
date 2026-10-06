package app.luoxianlv

import app.luoxianlv.recognition.ScreenRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 私人截图与耗时报告留在本地；性能数字不作为跨设备的通过门槛。 */
class RecognitionBenchmarkTest {
    @Test
    fun diagnosticCaptureBenchmark() {
        val path = System.getenv("LX_RECOGNITION_BENCHMARK")
        assumeTrue(path != null)
        val files = File(path!!).listFiles { f -> f.extension == "gray" }!!.sortedBy { it.name }
        val report = StringBuilder()
        for (file in files) {
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val w = header.int
            val h = header.int
            val pixels = FloatArray(w * h) { bytes[it + 8].toInt().and(255).toFloat() }
            val previous =
                if (System.getenv("LX_RECOGNITION_HINT") == "true")
                    ScreenRecognizer.analyze(pixels, w, h)
                else null
            repeat(3) { ScreenRecognizer.analyze(pixels, w, h, previous) }
            val times = LongArray(7)
            var result: ScreenRecognizer.Result? = null
            repeat(times.size) { i ->
                val start = System.nanoTime()
                result = ScreenRecognizer.analyze(pixels, w, h, previous)
                times[i] = System.nanoTime() - start
            }
            times.sort()
            val coordinates =
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
            report.appendLine(
                "${file.name}\t${times[times.size / 2] / 1_000_000.0}\t$coordinates\t${result?.reusedGeometry}"
            )
        }
        System.getenv("LX_RECOGNITION_REPORT")?.let {
            File(it).writeText(report.toString(), Charsets.UTF_8)
        }
        println(report)
    }
}
