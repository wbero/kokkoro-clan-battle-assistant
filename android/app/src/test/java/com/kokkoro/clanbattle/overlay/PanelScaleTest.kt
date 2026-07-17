package com.kokkoro.clanbattle.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelScaleTest {
    @Test fun `steps to the next larger preset`() {
        assertEquals(0.85f, nextPanelScale(0.72f))
    }

    @Test fun `snaps a dragged scale to the next preset above`() {
        assertEquals(1.00f, nextPanelScale(0.9f))
    }

    @Test fun `wraps to smallest preset after the largest`() {
        assertEquals(0.60f, nextPanelScale(1.15f))
    }

    @Test fun `labels scale as percentage`() {
        assertEquals("缩放72%", panelScaleLabel(0.72f))
    }
}
