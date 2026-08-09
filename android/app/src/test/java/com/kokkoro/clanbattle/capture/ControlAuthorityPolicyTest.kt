package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.axis.AxisType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAuthorityPolicyTest {
    @Test fun `switch control truth cannot seed before executable battle frame`() {
        assertFalse(
            shouldSeedAuthoritativeControls(
                axisType = AxisType.SWITCH,
                battleRunning = false,
                sessionReady = false,
                openingControlsConfirmed = true
            )
        )
        assertFalse(
            shouldSeedAuthoritativeControls(
                axisType = AxisType.SWITCH,
                battleRunning = true,
                sessionReady = false,
                openingControlsConfirmed = true
            )
        )
        assertTrue(
            shouldSeedAuthoritativeControls(
                axisType = AxisType.SWITCH,
                battleRunning = true,
                sessionReady = true,
                openingControlsConfirmed = true
            )
        )
    }

    @Test fun `sequence control truth waits for opening target completion`() {
        assertFalse(
            shouldSeedAuthoritativeControls(
                axisType = AxisType.SEQUENCE,
                battleRunning = true,
                sessionReady = true,
                openingControlsConfirmed = false
            )
        )
        assertTrue(
            shouldSeedAuthoritativeControls(
                axisType = AxisType.SEQUENCE,
                battleRunning = true,
                sessionReady = true,
                openingControlsConfirmed = true
            )
        )
    }

    @Test fun `visual desync audit exists only in bounded post click window`() {
        val common = mapOf(
            "battleRunning" to true,
            "controlsTrustworthy" to true,
            "controlFrameHasUbEvidence" to false,
            "holdControlsForUbBanner" to false,
            "characterUbActive" to false
        )

        fun allowed(nowMs: Long) = shouldAuditAuthoritativeControls(
            nowMs = nowMs,
            holdUntilMs = 2_000,
            verifyUntilMs = 3_500,
            battleRunning = common.getValue("battleRunning"),
            controlsTrustworthy = common.getValue("controlsTrustworthy"),
            controlFrameHasUbEvidence = common.getValue("controlFrameHasUbEvidence"),
            holdControlsForUbBanner = common.getValue("holdControlsForUbBanner"),
            characterUbActive = common.getValue("characterUbActive")
        )

        assertFalse(allowed(1_999))
        assertTrue(allowed(2_001))
        assertTrue(allowed(3_500))
        assertFalse(allowed(3_501))
    }

    @Test fun `ub evidence disables post click visual audit`() {
        assertFalse(
            shouldAuditAuthoritativeControls(
                nowMs = 2_500,
                holdUntilMs = 2_000,
                verifyUntilMs = 3_500,
                battleRunning = true,
                controlsTrustworthy = true,
                controlFrameHasUbEvidence = true,
                holdControlsForUbBanner = false,
                characterUbActive = false
            )
        )
    }
}
