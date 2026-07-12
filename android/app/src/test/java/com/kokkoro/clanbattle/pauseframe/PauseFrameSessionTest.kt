package com.kokkoro.clanbattle.pauseframe

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseFrameSessionTest {
    @Test fun `advance releases focus sends back then reacquires focus after one frame`() {
        val log = mutableListOf<String>()
        val scheduler = FakeScheduler(log)
        val session = PauseFrameSession(FakePort(log), scheduler, 40, 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)

        assertTrue(session.advance().accepted)
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(
            listOf("focus:on", "focus:off", "delay:1000", "back", "delay:40", "focus:on"),
            log
        )
        assertEquals(PauseFrameState.SOFT_PAUSED, session.snapshot().state)
    }

    @Test fun `confirm taps exactly the selected role and becomes ready for convergence`() {
        val log = mutableListOf<String>()
        val session = PauseFrameSession(FakePort(log), FakeScheduler(log), 40, 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)

        val result = session.confirm()

        assertTrue(result.accepted)
        assertEquals(CharacterRole.ROLE_3, result.confirmedRole)
        assertTrue(result.readyForConvergence)
        assertEquals(1, log.count { it == "tap:ROLE_3" })
        assertEquals("focus:off", log.last())
    }

    @Test fun `focus acquisition failure enters failed state without role tap`() {
        val log = mutableListOf<String>()
        val session = PauseFrameSession(
            FakePort(log, acquireResult = false),
            FakeScheduler(log),
            40,
            1_000
        )

        val result = session.enter("node-1", CharacterRole.ROLE_3)

        assertEquals(PauseFrameState.FAILED, result.state)
        assertFalse(log.any { it.startsWith("tap:") })
    }

    @Test fun `reentrant advance is rejected until focus is reacquired`() {
        val log = mutableListOf<String>()
        val session = PauseFrameSession(FakePort(log), FakeScheduler(log), 40, 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)

        assertTrue(session.advance().accepted)
        assertFalse(session.advance().accepted)
    }

    private class FakePort(
        private val log: MutableList<String>,
        private val acquireResult: Boolean = true
    ) : OverlayFocusPort {
        override fun acquireFocus(): Boolean {
            log += "focus:on"
            return acquireResult
        }

        override fun releaseFocus(): Boolean {
            log += "focus:off"
            return true
        }

        override fun sendBack(): Boolean {
            log += "back"
            return true
        }

        override fun tapRole(role: CharacterRole): Boolean {
            log += "tap:${role.name}"
            return true
        }
    }

    private class FakeScheduler(private val log: MutableList<String>) : PauseFrameScheduler {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun schedule(delayMs: Long, action: () -> Unit) {
            log += "delay:$delayMs"
            tasks.addLast(action)
        }

        fun runNext() = tasks.removeFirst().invoke()
    }
}
