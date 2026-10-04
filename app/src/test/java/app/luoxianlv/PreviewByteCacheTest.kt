package app.luoxianlv

import app.luoxianlv.wallpaper.render.PreviewByteCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewByteCacheTest {
    @Test
    fun `读取元数据期间清理无需等待读取完成`() {
        val cache = PreviewByteCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val load =
                workers.submit<ByteArray> {
                    cache.load(
                        key = {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            "preview"
                        },
                        read = { byteArrayOf(1) },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            workers.submit { cache.clear() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(1, load.get(5, TimeUnit.SECONDS).single().toInt())
            assertEquals(2, cache.load({ "preview" }) { byteArrayOf(2) }.single().toInt())
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `清理后迟到的磁盘读取不能重新填充缓存`() {
        val cache = PreviewByteCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val load =
                workers.submit<ByteArray> {
                    cache.load(
                        key = { "preview" },
                        read = {
                            reads.incrementAndGet()
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            byteArrayOf(1)
                        },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            workers.submit { cache.clear() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(1, load.get(5, TimeUnit.SECONDS).single().toInt())
            val refreshed =
                cache.load({ "preview" }) {
                    reads.incrementAndGet()
                    byteArrayOf(2)
                }
            assertEquals(2, reads.get())
            assertEquals(2, refreshed.single().toInt())
            assertSame(refreshed, cache.load({ "preview" }) { error("缓存命中不应读取磁盘") })
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `项目或文件元数据变化后读取新预览`() {
        val cache = PreviewByteCache()
        val original = byteArrayOf(1)
        val changed = byteArrayOf(2)
        assertSame(original, cache.load({ "project:100:1" }) { original })
        assertSame(original, cache.load({ "project:100:1" }) { error("不应重复读取") })
        assertSame(changed, cache.load({ "project:200:1" }) { changed })
        cache.clear()
        assertEquals(3, cache.load({ "project:200:1" }) { byteArrayOf(3) }.single().toInt())
    }
}
