package com.kokkoro.clanbattle.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayUiStateTest {
    @Test fun `idle allows axis selection but not pause frame actions`() {
        val state = OverlayUiState.idle("E5刀1")

        assertEquals("选择轴：E5刀1", state.selectAxis.label)
        assertTrue(state.selectAxis.enabled)
        assertFalse(state.nextFrame.enabled)
        assertFalse(state.confirm.enabled)
        assertTrue(state.reset.enabled)
        assertEquals(OverlayPanelColor.GRAY, state.panelColor)
    }

    @Test fun `running locks axis selection and enables safety menu`() {
        val state = OverlayUiState.running("E5刀1")

        assertFalse(state.selectAxis.enabled)
        assertFalse(state.nextFrame.enabled)
        assertFalse(state.confirm.enabled)
        assertTrue(state.safetyMenu.enabled)
        assertEquals(OverlayPanelColor.GREEN, state.panelColor)
    }

    @Test fun `pause frame enables manual advance and confirmation`() {
        val state = OverlayUiState.pauseFrame("E5刀1", "角色3")

        assertEquals("下一帧", state.nextFrame.label)
        assertTrue(state.nextFrame.enabled)
        assertEquals("确定：角色3", state.confirm.label)
        assertTrue(state.confirm.enabled)
        assertFalse(state.selectAxis.enabled)
        assertEquals(OverlayPanelColor.AMBER, state.panelColor)
    }

    @Test fun `safety paused disables actions until manual recovery or reset`() {
        val state = OverlayUiState.safetyPaused("E5刀1")

        assertFalse(state.selectAxis.enabled)
        assertFalse(state.nextFrame.enabled)
        assertFalse(state.confirm.enabled)
        assertFalse(state.safetyMenu.enabled)
        assertTrue(state.reset.enabled)
        assertEquals(OverlayPanelColor.RED, state.panelColor)
    }
}
