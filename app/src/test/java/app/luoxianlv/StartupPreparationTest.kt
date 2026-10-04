package app.luoxianlv

import app.luoxianlv.wallpaper.render.awaitStartupPreparation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPreparationTest {
    @Test
    fun `等待超时转为加载异常且迟到引擎仍可完成`() =
        runBlocking<Unit> {
            val preparation = CompletableDeferred<Unit>()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { awaitStartupPreparation(preparation, 20) }
            }
            assertFalse(preparation.isCompleted)
            preparation.complete(Unit)
            awaitStartupPreparation(preparation, 20)
        }

    @Test
    fun `页面取消不会取消其他页面共享的引擎准备`() =
        runBlocking<Unit> {
            val preparation = CompletableDeferred<Unit>()
            val waiter = launch { awaitStartupPreparation(preparation) }
            yield()
            waiter.cancel()
            waiter.join()
            assertTrue(waiter.isCancelled)
            assertFalse(preparation.isCompleted)
            preparation.complete(Unit)
            awaitStartupPreparation(preparation)
        }

    @Test
    fun `已就绪引擎立即可用且初始化异常保留`() =
        runBlocking<Unit> {
            awaitStartupPreparation(CompletableDeferred(Unit))
            val failed = CompletableDeferred<Unit>()
            failed.completeExceptionally(IllegalArgumentException("初始化失败"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { awaitStartupPreparation(failed) }
            }
        }
}
