package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class StructuralMatcherTest {
    @Test
    fun `iou measures foreground overlap`() {
        val first = BinaryImage(2, 2, booleanArrayOf(true, true, false, false))
        val second = BinaryImage(2, 2, booleanArrayOf(true, false, true, false))

        assertEquals(1.0 / 3.0, StructuralMatcher.iou(first, second), 1e-12)
        assertEquals(1.0, StructuralMatcher.iou(first, first), 0.0)
    }

    @Test
    fun `iou of two empty images is zero`() {
        val empty = BinaryImage(2, 3, BooleanArray(6))

        assertEquals(0.0, StructuralMatcher.iou(empty, empty), 0.0)
    }
}
