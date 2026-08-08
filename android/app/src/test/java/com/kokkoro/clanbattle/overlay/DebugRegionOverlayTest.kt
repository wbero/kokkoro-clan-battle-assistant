package com.kokkoro.clanbattle.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `capture box maps through full display when overlay is inset by a cutout`() {
        val mapped = scaleCaptureBoxToOverlay(
            left = 300,
            top = 100,
            right = 500,
            bottom = 200,
            captureWidth = 2400,
            captureHeight = 1080,
            displayWidth = 2400,
            displayHeight = 1080,
            overlayScreenX = 80,
            overlayScreenY = 0
        )

        assertEquals(220f, mapped.left, 0.001f)
        assertEquals(420f, mapped.right, 0.001f)
        assertEquals(100f, mapped.top, 0.001f)
        assertEquals(200f, mapped.bottom, 0.001f)
    }

    @Test
    fun `capture box maps display scaling before subtracting overlay origin`() {
        val mapped = scaleCaptureBoxToOverlay(
            left = 200,
            top = 100,
            right = 400,
            bottom = 300,
            captureWidth = 2000,
            captureHeight = 1000,
            displayWidth = 1000,
            displayHeight = 500,
            overlayScreenX = 25,
            overlayScreenY = 10
        )

        assertEquals(75f, mapped.left, 0.001f)
        assertEquals(175f, mapped.right, 0.001f)
        assertEquals(40f, mapped.top, 0.001f)
        assertEquals(140f, mapped.bottom, 0.001f)
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

    @Test
    fun `debug outline stroke stays fully outside recognition roi`() {
        val left = 100f
        val top = 50f
        val right = 300f
        val bottom = 150f
        val strokeWidth = 6f
        val outline = debugOutlineBox(left, top, right, bottom, strokeWidth, margin = 2f)
        val halfStroke = strokeWidth / 2f

        assertTrue(outline.left + halfStroke < left)
        assertTrue(outline.top + halfStroke < top)
        assertTrue(outline.right - halfStroke > right)
        assertTrue(outline.bottom - halfStroke > bottom)
    }

    @Test
    fun `debug outline clamps negative stroke and margin inputs`() {
        val outline = debugOutlineBox(10f, 20f, 30f, 40f, strokeWidth = -4f, margin = -2f)

        assertEquals(10f, outline.left, 0.001f)
        assertEquals(20f, outline.top, 0.001f)
        assertEquals(30f, outline.right, 0.001f)
        assertEquals(40f, outline.bottom, 0.001f)
    }
}
