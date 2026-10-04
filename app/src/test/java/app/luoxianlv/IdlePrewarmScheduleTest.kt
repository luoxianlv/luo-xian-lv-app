package app.luoxianlv

import app.luoxianlv.practice.IdlePrewarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

class IdlePrewarmScheduleTest {
    private class Queue {
        val callbacks = mutableListOf<Runnable>()
        var cancels = 0
        var pauses = 0
        var prepares = 0
        val schedule =
            IdlePrewarmSchedule(
                enqueue = callbacks::add,
                cancel = { cancels++ },
                pause = { pauses++ },
                prepare = { prepares++ },
            )
    }

    @Test
    fun repeatedCompositionDoesNotEnqueueAnotherPrewarm() {
        val queue = Queue()
        queue.schedule.update(true)
        repeat(10) { queue.schedule.update(true) }
        assertEquals(1, queue.callbacks.size)
        queue.callbacks.single().run()
        queue.schedule.update(true)
        assertEquals(1, queue.prepares)
        assertEquals(1, queue.callbacks.size)
    }

    @Test
    fun navigationAndTouchCancelEvenAnAlreadyPostedIdleCallback() {
        val queue = Queue()
        queue.schedule.update(true)
        val old = queue.callbacks.single()
        queue.schedule.update(false)
        old.run()
        assertEquals(0, queue.prepares)
        assertEquals(1, queue.pauses)

        queue.schedule.update(true)
        old.run()
        queue.callbacks.last().run()
        assertEquals(1, queue.prepares)
    }

    @Test
    fun pauseStopsAStartedRendererWithoutDeletingItsCache() {
        val queue = Queue()
        queue.schedule.update(true)
        queue.callbacks.single().run()
        queue.schedule.update(false)
        queue.schedule.update(false)
        assertEquals(1, queue.prepares)
        assertEquals(1, queue.pauses)
        queue.schedule.update(true)
        queue.callbacks.last().run()
        assertEquals(2, queue.prepares)
    }

    @Test
    fun destructionInvalidatesCallbacksAndDoesNotRestartAfterTouchRelease() {
        val queue = Queue()
        queue.schedule.update(true)
        queue.schedule.close()
        queue.callbacks.single().run()
        queue.schedule.update(false)
        queue.schedule.update(true)
        queue.schedule.close()
        assertEquals(0, queue.prepares)
        assertEquals(1, queue.pauses)
        assertEquals(1, queue.callbacks.size)
    }
}
