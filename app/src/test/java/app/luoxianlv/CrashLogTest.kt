package app.luoxianlv

import app.luoxianlv.debug.CrashLog
import app.luoxianlv.debug.FatalErrorHandler
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `交还系统处理前已保存中文和完整原因且只保留两次`() {
        val directory = temporary.newFolder()
        val current = File(directory, "play-debug-crash.log")
        val error = IllegalStateException("页面切换失败", IllegalArgumentException("原始原因"))
        var delegated = 0
        val handler =
            FatalErrorHandler(
                save = { thread, failure -> CrashLog.write(directory, "测试版本", thread, failure) },
                next =
                    Thread.UncaughtExceptionHandler { thread, failure ->
                        assertSame(Thread.currentThread(), thread)
                        assertSame(error, failure)
                        val bytes = current.readBytes()
                        assertFalse(
                            bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                        )
                        val text = bytes.toString(Charsets.UTF_8)
                        assertTrue(text.contains("页面切换失败"))
                        assertTrue(
                            text.contains("Caused by: java.lang.IllegalArgumentException: 原始原因")
                        )
                        assertTrue(text.contains("CrashLogTest"))
                        delegated++
                    },
            )
        repeat(3) { handler.uncaughtException(Thread.currentThread(), error) }
        assertEquals(3, delegated)
        assertEquals(2, directory.listFiles()!!.size)
        assertTrue(File(directory, "play-debug-crash.log.1").isFile)
    }

    @Test
    fun `磁盘异常仍把同一个崩溃交给原处理器`() {
        val original = NoSuchMethodError("缺少系统方法")
        var received: Throwable? = null
        FatalErrorHandler(
                save = { _, _ -> throw java.io.IOException("磁盘不可写") },
                next = Thread.UncaughtExceptionHandler { _, error -> received = error },
            )
            .uncaughtException(Thread.currentThread(), original)
        assertSame(original, received)
    }
}
