package com.kokkoro.clanbattle.pauseframe

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseFrameSessionTest {
    @Test fun `release runs one frame then reacquires focus`() {
        val log = mutableListOf<String>()
        val scheduler = FakeScheduler(log)
        val session = PauseFrameSession(FakePort(log), scheduler, perFrameMs = 40, focusTransitionMs = 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)

        assertTrue(session.release(1).accepted)
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(
            listOf("focus:on", "focus:off", "delay:1000", "back", "delay:40", "focus:on"),
            log
        )
        assertEquals(PauseFrameState.SOFT_PAUSED, session.snapshot().state)
    }

    @Test fun `release scales the run window by the frame count`() {
        val log = mutableListOf<String>()
        val scheduler = FakeScheduler(log)
        val session = PauseFrameSession(FakePort(log), scheduler, perFrameMs = 40, focusTransitionMs = 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)

        session.release(20)
        scheduler.runNext()

        assertTrue(log.contains("delay:800"))
    }

    @Test fun `confirm taps the avatar in the existing pause menu then dismisses it`() {
        val log = mutableListOf<String>()
        val diagnostics = mutableListOf<PauseFrameDiagnosticEvent>()
        val scheduler = FakeScheduler(log)
        val session = PauseFrameSession(
            FakePort(log), scheduler,
            perFrameMs = 40, focusTransitionMs = 1_000, menuSettleMs = 300, tapGapMs = 150,
            diagnosticCallback = diagnostics::add
        )
        session.enter("node-1", CharacterRole.ROLE_3)
        var completed: PauseFrameResult? = null

        val accepted = session.confirm { completed = it }
        scheduler.runNext()
        scheduler.runNext()

        assertTrue(accepted.accepted)
        assertFalse(accepted.readyForConvergence)
        assertEquals(CharacterRole.ROLE_3, completed?.confirmedRole)
        assertTrue(completed?.readyForConvergence == true)
        assertEquals(1, log.count { it == "menu-tap:ROLE_3" })
        assertEquals(
            listOf("focus:on", "focus:off", "delay:1300", "menu-tap:ROLE_3", "delay:150", "dismiss"),
            log
        )
        assertFalse(log.contains("back"))
        assertTrue(diagnostics.any { it.action == "confirm" && it.result == "requested" })
        assertTrue(diagnostics.any { it.action == "tap-role" && it.result == "success" })
        assertTrue(diagnostics.any { it.action == "dismiss" && it.result == "success" })
    }

    @Test fun `focus acquisition failure enters failed state without tapping`() {
        val log = mutableListOf<String>()
        val session = PauseFrameSession(
            FakePort(log, acquireResult = false),
            FakeScheduler(log),
            perFrameMs = 40,
            focusTransitionMs = 1_000
        )

        val result = session.enter("node-1", CharacterRole.ROLE_3)

        assertEquals(PauseFrameState.FAILED, result.state)
        assertFalse(log.any { it.startsWith("menu-tap:") })
    }

    @Test fun `unsafe menu tap fails confirmation without dismissing`() {
        val log = mutableListOf<String>()
        val scheduler = FakeScheduler(log)
        val session = PauseFrameSession(
            FakePort(log, tapResult = false),
            scheduler,
            perFrameMs = 40,
            focusTransitionMs = 1_000
        )
        session.enter("node-1", CharacterRole.ROLE_3)
        var completed: PauseFrameResult? = null

        session.confirm { completed = it }
        scheduler.runNext()

        assertEquals(PauseFrameState.FAILED, completed?.state)
        assertFalse(completed?.readyForConvergence == true)
        assertFalse(log.contains("dismiss"))
    }

    @Test fun `reentrant release is rejected until focus is reacquired`() {
        val log = mutableListOf<String>()
        val session = PauseFrameSession(FakePort(log), FakeScheduler(log), perFrameMs = 40, focusTransitionMs = 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)

        assertTrue(session.release(1).accepted)
        assertFalse(session.release(1).accepted)
    }

    @Test fun `reset invalidates delayed release callbacks`() {
        val log = mutableListOf<String>()
        val scheduler = FakeScheduler(log)
        val session = PauseFrameSession(FakePort(log), scheduler, perFrameMs = 40, focusTransitionMs = 1_000)
        session.enter("node-1", CharacterRole.ROLE_3)
        session.release(1)

        session.reset()
        scheduler.runNext()

        assertFalse(log.contains("back"))
        assertEquals(PauseFrameState.IDLE, session.snapshot().state)
    }

    private class FakePort(
        private val log: MutableList<String>,
        private val acquireResult: Boolean = true,
        private val tapResult: Boolean = true
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

        override fun tapMenuRole(role: CharacterRole): Boolean {
            log += "menu-tap:${role.name}"
            return tapResult
        }

        override fun dismissMenu(): Boolean {
            log += "dismiss"
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
