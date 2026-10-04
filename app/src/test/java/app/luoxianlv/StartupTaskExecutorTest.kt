package app.luoxianlv

import app.luoxianlv.wallpaper.render.StartupTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTaskExecutorTest {
    @Test
    fun `后台初始化异常反馈等待者且不进入未捕获异常处理`() {
        val uncaught = AtomicReference<Throwable?>()
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor { task ->
            Thread(task, "startup-test").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    uncaught.set(error)
                }
            }
        }
        try {
            val expected = IllegalStateException("模拟供应商初始化失败")
            val executor =
                StartupTaskExecutor(
                    workers,
                    {
                        failure.set(it)
                        completed.countDown()
                    },
                )
            executor.execute { throw expected }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            workers.submit {}.get(5, TimeUnit.SECONDS)
            assertSame(expected, failure.get())
            assertNull(uncaught.get())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `拒绝提交时立即反馈且不执行任务`() {
        val expected = RejectedExecutionException("队列已关闭")
        val failure = AtomicReference<Throwable?>()
        val executions = AtomicInteger()
        StartupTaskExecutor(Executor { throw expected }, failure::set).execute {
            executions.incrementAndGet()
        }
        assertSame(expected, failure.get())
        assertEquals(0, executions.get())
    }

    @Test
    fun `链接错误反馈等待者`() {
        val expected = NoClassDefFoundError("模拟供应商接口缺失")
        val failure = AtomicReference<Throwable?>()
        StartupTaskExecutor(Executor { it.run() }, failure::set).execute { throw expected }
        assertSame(expected, failure.get())
    }

    @Test
    fun `正常任务仅执行一次且先设置线程状态`() {
        val steps = mutableListOf<String>()
        StartupTaskExecutor(
                Executor { it.run() },
                { throw AssertionError("正常任务不应报告异常", it) },
                { steps += "before" },
            )
            .execute { steps += "task" }
        assertEquals(listOf("before", "task"), steps)
    }

    @Test
    fun `线程状态设置失败也反馈且不继续初始化`() {
        val expected = SecurityException("模拟线程优先级设置失败")
        val failure = AtomicReference<Throwable?>()
        val executions = AtomicInteger()
        StartupTaskExecutor(Executor { it.run() }, failure::set) { throw expected }
            .execute { executions.incrementAndGet() }
        assertSame(expected, failure.get())
        assertEquals(0, executions.get())
    }

    @Test
    fun `内存耗尽和线程终止不转为普通启动错误`() {
        val failures = AtomicInteger()
        val executor = StartupTaskExecutor(Executor { it.run() }, { failures.incrementAndGet() })
        val exhausted = OutOfMemoryError("模拟内存耗尽")
        assertSame(
            exhausted,
            assertThrows(OutOfMemoryError::class.java) { executor.execute { throw exhausted } },
        )
        val terminated = ThreadDeath()
        assertSame(
            terminated,
            assertThrows(ThreadDeath::class.java) { executor.execute { throw terminated } },
        )
        assertEquals(0, failures.get())
    }
}
