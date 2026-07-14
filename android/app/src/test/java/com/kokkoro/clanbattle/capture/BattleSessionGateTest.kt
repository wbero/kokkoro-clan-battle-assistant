package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleSessionGateTest {
    @Test
    fun `reset requires start and loading templates before evaluating opening window`() {
        val gate = BattleSessionGate()
        gate.prepare()

        assertTrue(gate.isWaitingForStart())
        assertFalse(gate.shouldEvaluate(60))
        assertFalse(gate.shouldEvaluate(90))

        gate.onStartMatched()
        assertTrue(gate.isWaitingForLoading())
        assertFalse(gate.shouldEvaluate(90))

        gate.onLoadingMatched()
        assertTrue(gate.isWaitingForClock())
        assertFalse(gate.shouldEvaluate(60))
        assertFalse(gate.shouldEvaluate(87))
        assertTrue(gate.shouldEvaluate(88))
        assertTrue(gate.shouldEvaluate(90))
        assertFalse(gate.shouldEvaluate(91))
    }

    @Test
    fun `accepted 1 30 opens the session and allows later clocks`() {
        val gate = BattleSessionGate()
        gate.prepare()
        gate.onStartMatched()
        gate.onLoadingMatched()

        assertTrue(gate.onAccepted(90))
        assertFalse(gate.isWaiting())
        assertTrue(gate.shouldEvaluate(89))
        assertTrue(gate.onAccepted(89))
    }

    @Test
    fun `non opening acceptance cannot start a waiting session`() {
        val gate = BattleSessionGate()
        gate.prepare()
        gate.onStartMatched()
        gate.onLoadingMatched()

        assertFalse(gate.onAccepted(87))
        assertTrue(gate.isWaiting())
    }

    @Test
    fun `first trusted clock inside opening window starts session`() {
        val gate = BattleSessionGate()
        gate.prepare()
        gate.onStartMatched()
        gate.onLoadingMatched()

        assertTrue(gate.onAccepted(88))
        assertFalse(gate.isWaiting())
        assertTrue(gate.shouldEvaluate(87))
    }
}
