package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseFrameCaptureGateTest {
    @Test fun `capture processing stops while manual pause frame owns game focus`() {
        assertFalse(captureProcessingAllowed(CharacterRole.ROLE_3))
        assertTrue(captureProcessingAllowed(null))
    }
}
