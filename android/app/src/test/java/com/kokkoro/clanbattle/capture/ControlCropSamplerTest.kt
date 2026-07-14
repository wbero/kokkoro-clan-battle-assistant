package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCropSamplerTest {
    @Test fun `unknown frames are throttled but clicks and safety transitions are always saved`() {
        val sampler = ControlCropSampler(unknownIntervalMs = 1_000)

        assertTrue(sampler.shouldSave(0, unknown = true, ControlAction.None, ControlSafetyState.RUNNING))
        assertFalse(sampler.shouldSave(100, unknown = true, ControlAction.None, ControlSafetyState.RUNNING))
        assertTrue(sampler.shouldSave(200, unknown = false, ControlAction.TapAuto, ControlSafetyState.RUNNING))
        assertTrue(sampler.shouldSave(300, unknown = false, ControlAction.None, ControlSafetyState.SAFETY_PAUSING))
        assertFalse(sampler.shouldSave(400, unknown = false, ControlAction.None, ControlSafetyState.SAFETY_PAUSING))
        assertTrue(sampler.shouldSave(1_100, unknown = true, ControlAction.None, ControlSafetyState.SAFETY_PAUSING))
    }
}
