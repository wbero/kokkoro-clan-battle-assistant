package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.axis.PauseFrameTarget
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseFrameCaptureGateTest {
    @Test fun `capture processing stops while manual pause frame owns game focus`() {
        assertFalse(captureProcessingAllowed(PauseFrameTarget.Role(CharacterRole.ROLE_3)))
        assertFalse(captureProcessingAllowed(PauseFrameTarget.Auto))
        assertTrue(captureProcessingAllowed(null))
    }

    @Test fun `capture processing remains stopped while confirmation menu is closing`() {
        assertFalse(captureProcessingAllowed(null, pauseFrameProcessingBlocked = true))
        assertTrue(captureProcessingAllowed(null, pauseFrameProcessingBlocked = false))
    }
}
