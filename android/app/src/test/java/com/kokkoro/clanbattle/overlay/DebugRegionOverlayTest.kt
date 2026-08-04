package com.kokkoro.clanbattle.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugRegionOverlayTest {
    @Test
    fun `capture box maps one to one when overlay matches capture size`() {
        val mapped = scaleCaptureBox(100, 40, 164, 67, 1920, 1080, 1920, 1080)

        assertEquals(100f, mapped.left, 0.001f)
        assertEquals(40f, mapped.top, 0.001f)
        assertEquals(164f, mapped.right, 0.001f)
        assertEquals(67f, mapped.bottom, 0.001f)
    }

    @Test
    fun `capture box scales when overlay window differs from capture size`() {
        val mapped = scaleCaptureBox(200, 100, 400, 300, 2000, 1000, 1000, 500)

        assertEquals(100f, mapped.left, 0.001f)
        assertEquals(50f, mapped.top, 0.001f)
        assertEquals(200f, mapped.right, 0.001f)
        assertEquals(150f, mapped.bottom, 0.001f)
    }

    @Test
    fun `degenerate capture size does not produce infinite coordinates`() {
        val mapped = scaleCaptureBox(0, 0, 10, 10, 0, 0, 100, 100)

        assertEquals(0f, mapped.left, 0.001f)
        assertEquals(0f, mapped.right, 0.001f)
    }

    @Test
    fun `tp bar fill is proportional and clamped to the track`() {
        assertEquals(0f, tpBarFillWidth(200f, 0f), 0.001f)
        assertEquals(50f, tpBarFillWidth(200f, 0.25f), 0.001f)
        assertEquals(200f, tpBarFillWidth(200f, 1f), 0.001f)
        assertEquals(200f, tpBarFillWidth(200f, 1.4f), 0.001f)
        assertEquals(0f, tpBarFillWidth(200f, -0.3f), 0.001f)
    }
}
