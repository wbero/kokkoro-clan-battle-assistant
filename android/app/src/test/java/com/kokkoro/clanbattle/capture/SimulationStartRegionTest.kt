package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationStartRegionTest {
    @Test
    fun `simulation start region sits centered inside the battle start button`() {
        val outer = BattleReferenceRegions.START_BUTTON
        val inner = BattleReferenceRegions.SIMULATION_START_BUTTON

        assertTrue(inner.x >= outer.x)
        assertTrue(inner.y >= outer.y)
        assertTrue(inner.x + inner.width <= outer.x + outer.width)
        assertTrue(inner.y + inner.height <= outer.y + outer.height)
        // 左右、上下留白相差不超过 1px（整数除法的余数），确保子矩形确实居中。
        assertTrue(
            kotlin.math.abs((inner.x - outer.x) - (outer.x + outer.width - (inner.x + inner.width))) <= 1
        )
        assertTrue(
            kotlin.math.abs((inner.y - outer.y) - (outer.y + outer.height - (inner.y + inner.height))) <= 1
        )
    }

    @Test
    fun `simulation start region keeps the template aspect ratio`() {
        val inner = BattleReferenceRegions.SIMULATION_START_BUTTON
        val templateAspect = 216.0 / 51.0
        val regionAspect = inner.width.toDouble() / inner.height

        assertEquals(templateAspect, regionAspect, 0.15)
    }

    @Test
    fun `centered sub region scales with the outer control`() {
        val region = centeredSubRegion(ReferenceRegion(100, 200, 200, 100), 50, 25, 100, 50)

        assertEquals(ReferenceRegion(150, 225, 100, 50), region)
    }

    @Test
    fun `centered sub region never collapses to zero size`() {
        val region = centeredSubRegion(ReferenceRegion(0, 0, 10, 10), 1, 1, 1000, 1000)

        assertTrue(region.width >= 1)
        assertTrue(region.height >= 1)
    }
}
