package app.luoxianlv.business

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BusinessJobsTest {
    @Test(timeout = 20000)
    fun cancelledBlockingIoKeepsLeaseUntilWorkActuallyReturns() = runBlocking {
        val began = CountDownLatch(1)
        val release = CountDownLatch(1)
        val task =
            launch(Dispatchers.Default) {
                BusinessJobs.io {
                    began.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        try {
            assertTrue(began.await(10, TimeUnit.SECONDS))
            assertFalse(BusinessJobs.gate.freeze())
            task.cancel()
            assertEquals(1, BusinessJobs.gate.activeCount())
            assertFalse(BusinessJobs.gate.idle())
        } finally {
            release.countDown()
            task.join()
        }
        assertTrue(BusinessJobs.gate.idle())
    }

    @Test(timeout = 20000)
    fun frozenGenerationRejectsIoWithoutRunningBusinessAndCanResume() = runBlocking {
        assertTrue(BusinessJobs.gate.freeze())
        var called = false
        try {
            try {
                BusinessJobs.io { called = true }
                fail("冻结后不应接受工作")
            } catch (expected: CancellationException) {
                assertFalse(called)
                assertEquals(0, BusinessJobs.gate.activeCount())
            }
        } finally {
            BusinessJobs.gate.resume()
        }
        assertEquals(7, BusinessJobs.io { 7 })
        assertTrue(BusinessJobs.gate.idle())
    }
}
