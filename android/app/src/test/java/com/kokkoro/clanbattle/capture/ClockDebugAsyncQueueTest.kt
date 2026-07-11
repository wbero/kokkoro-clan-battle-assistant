package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

class ClockDebugAsyncQueueTest {
    @Test fun `close drains accepted tail and rejects later tasks`() {
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val queue = ClockDebugAsyncQueue(4)
        repeat(4) { assertTrue(queue.submit { values += it }) }
        queue.close()
        assertEquals(listOf(0, 1, 2, 3), values)
        assertFalse(queue.submit { values += 9 })
    }

    @Test fun `control is not lost when record queue is full`() {
        val blocker = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<String>())
        val queue = ClockDebugAsyncQueue(1)
        queue.submit { entered.countDown(); blocker.await() }
        entered.await()
        assertTrue(queue.submit { values += "record" })
        val controlThread = Thread { queue.control { values += "session" } }.apply { start() }
        blocker.countDown()
        controlThread.join()
        queue.close()
        assertEquals(listOf("record", "session"), values)
    }

    @Test fun `worker continues after task throws`() {
        val values = Collections.synchronizedList(mutableListOf<String>())
        val queue = ClockDebugAsyncQueue(4)
        queue.submit { error("boom") }
        queue.submit { values += "after" }
        queue.close()
        assertEquals(listOf("after"), values)
        assertEquals(1, queue.errorCount)
    }

    @Test fun `frame sampler selects all digit crops together`() {
        val sampler = ClockDebugFrameSampler()
        assertTrue(sampler.shouldSaveFrame(1_000, recognitionOk = true, ambiguousOrOverridden = true))
        assertEquals(
            listOf("frame-7-minute.png", "frame-7-second_tens.png", "frame-7-second_ones.png"),
            sampler.cropFileNames(7, listOf("MINUTE", "SECOND_TENS", "SECOND_ONES"), saveFrame = true)
        )
        assertFalse(sampler.shouldSaveFrame(1_050, recognitionOk = true, ambiguousOrOverridden = true))
        assertTrue(sampler.shouldSaveFrame(1_100, recognitionOk = true, ambiguousOrOverridden = true))
    }
}
