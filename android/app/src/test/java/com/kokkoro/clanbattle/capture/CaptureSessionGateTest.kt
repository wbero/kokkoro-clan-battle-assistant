package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionGateTest {
    @Test
    fun `duplicate pending or active session is ignored`() {
        val gate = CaptureSessionGate()

        assertTrue(gate.begin(1L))
        assertFalse(gate.begin(1L))
        assertTrue(gate.activate(1L))
        assertFalse(gate.begin(1L))
    }

    @Test
    fun `failed replacement can be retried without losing active session`() {
        val gate = CaptureSessionGate()
        assertTrue(gate.begin(1L))
        assertTrue(gate.activate(1L))

        assertTrue(gate.begin(2L))
        gate.fail(2L)

        assertFalse(gate.begin(1L))
        assertTrue(gate.begin(2L))
    }

    @Test
    fun `clear permits a new service lifetime`() {
        val gate = CaptureSessionGate()
        assertTrue(gate.begin(1L))
        assertTrue(gate.activate(1L))

        gate.clear()

        assertTrue(gate.begin(1L))
    }
}
