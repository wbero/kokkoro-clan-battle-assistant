package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFrameDispatchGateTest {
    @Test
    fun `only one slow frame can be in flight`() {
        val gate = CaptureFrameDispatchGate(50L)

        val first = gate.tryBeginSlowFrame(100L)
        assertTrue(first != null)
        assertTrue(gate.tryBeginSlowFrame(200L) == null)

        gate.completeSlowFrame(requireNotNull(first))
        assertTrue(gate.tryBeginSlowFrame(200L) != null)
    }

    @Test
    fun `completed slow frames still respect the interval`() {
        val gate = CaptureFrameDispatchGate(50L)

        val first = requireNotNull(gate.tryBeginSlowFrame(100L))
        gate.completeSlowFrame(first)

        assertTrue(gate.tryBeginSlowFrame(149L) == null)
        assertTrue(gate.tryBeginSlowFrame(150L) != null)
    }

    @Test
    fun `reset immediately allows a new slow frame`() {
        val gate = CaptureFrameDispatchGate(50L)
        assertTrue(gate.tryBeginSlowFrame(100L) != null)

        gate.reset()

        assertTrue(gate.tryBeginSlowFrame(101L) != null)
    }

    @Test
    fun `completion from an old generation cannot release the new lease`() {
        val gate = CaptureFrameDispatchGate(50L)
        val oldLease = requireNotNull(gate.tryBeginSlowFrame(100L))

        gate.reset()
        val newLease = requireNotNull(gate.tryBeginSlowFrame(101L))
        gate.completeSlowFrame(oldLease)

        assertTrue(gate.tryBeginSlowFrame(200L) == null)
        gate.completeSlowFrame(newLease)
        assertTrue(gate.tryBeginSlowFrame(200L) != null)
    }
}
