package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ClockDebugSessionStateTest {
    @Test fun `failed replacement clears old session and later start recovers`() {
        val first = FakeSession()
        val recovered = FakeSession()
        var attempts = 0
        val state = ClockDebugSessionState<FakeSession> {
            attempts++
            when (attempts) {
                1 -> first
                2 -> error("disk unavailable")
                else -> recovered
            }
        }

        state.start()
        try { state.start() } catch (_: IllegalStateException) {}
        assertEquals(1, first.closeCount)
        assertNull(state.current())

        state.start()
        assertSame(recovered, state.current())
        state.current()!!.writes++
        assertEquals(1, recovered.writes)
    }

    private class FakeSession : AutoCloseable {
        var closeCount = 0
        var writes = 0
        override fun close() { closeCount++ }
    }
}
