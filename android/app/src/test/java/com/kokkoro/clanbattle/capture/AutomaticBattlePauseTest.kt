package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.control.ControlSafetyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticBattlePauseTest {
    @Test fun `one second reached waits until axis operations finish`() {
        assertNull(
            automaticBattlePauseReason(
                enabled = true,
                alreadyRequested = false,
                battleRunning = true,
                executionBlocked = false,
                safety = ControlSafetyState.RUNNING,
                oneSecondReached = true,
                axisOperationsFinished = false
            )
        )
    }

    @Test fun `finished axis does not pause before one second is reached`() {
        assertNull(
            automaticBattlePauseReason(
                enabled = true,
                alreadyRequested = false,
                battleRunning = true,
                executionBlocked = false,
                safety = ControlSafetyState.RUNNING,
                oneSecondReached = false,
                axisOperationsFinished = true
            )
        )
    }

    @Test fun `pause requests after one second was reached and axis is finished`() {
        assertEquals(
            "one-second-axis-operations-finished",
            automaticBattlePauseReason(
                enabled = true,
                alreadyRequested = false,
                battleRunning = true,
                executionBlocked = false,
                safety = ControlSafetyState.RUNNING,
                oneSecondReached = true,
                axisOperationsFinished = true
            )
        )
    }

    @Test fun `pause is suppressed when disabled blocked or already requested`() {
        val base = automaticBattlePauseReason(
            enabled = false,
            alreadyRequested = false,
            battleRunning = true,
            executionBlocked = false,
            safety = ControlSafetyState.RUNNING,
            oneSecondReached = true,
            axisOperationsFinished = true
        )
        assertNull(base)
        assertNull(
            automaticBattlePauseReason(
                enabled = true,
                alreadyRequested = true,
                battleRunning = true,
                executionBlocked = false,
                safety = ControlSafetyState.RUNNING,
                oneSecondReached = true,
                axisOperationsFinished = true
            )
        )
        assertNull(
            automaticBattlePauseReason(
                enabled = true,
                alreadyRequested = false,
                battleRunning = true,
                executionBlocked = true,
                safety = ControlSafetyState.RUNNING,
                oneSecondReached = true,
                axisOperationsFinished = true
            )
        )
    }

    @Test fun `existing safety pause owns the menu`() {
        assertNull(
            automaticBattlePauseReason(
                enabled = true,
                alreadyRequested = false,
                battleRunning = true,
                executionBlocked = false,
                safety = ControlSafetyState.SAFETY_PAUSING,
                oneSecondReached = true,
                axisOperationsFinished = true
            )
        )
    }
}
