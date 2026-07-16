package com.kokkoro.clanbattle.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class GameCoordinateMapperTest {
    @Test fun `reference and proportional 16 by 9 screens have no offset`() {
        assertEquals(GameViewport(1f, 0f, 0f), GameCoordinateMapper.viewport(1920, 1080))
        assertEquals(GameViewport(0.5f, 0f, 0f), GameCoordinateMapper.viewport(960, 540))
    }

    @Test fun `ultrawide device uses center and right anchors without horizontal stretching`() {
        GameCoordinateCalibration.reset()
        val viewport = GameCoordinateMapper.viewport(2780, 1264)
        assertEquals(1264f / 1080f, viewport.scale, 0.0001f)
        assertEquals(532.89f, viewport.spareX, 0.1f)
        assertEquals(0f, viewport.offsetY, 0.0001f)
        assertEquals(2098.1f, GameCoordinateMapper.mapX(1565, 2780, 1264, HorizontalAnchor.CENTER), 0.2f)
        assertEquals(2364.5f, GameCoordinateMapper.mapX(1565, 2780, 1264, HorizontalAnchor.RIGHT), 0.2f)
        assertEquals(995.0f, GameCoordinateMapper.mapY(850, 2780, 1264), 0.2f)
    }

    @Test fun `calibration is shared with mapped coordinates`() {
        GameCoordinateCalibration.reset()
        val before = GameCoordinateMapper.mapX(960, 2780, 1264, HorizontalAnchor.CENTER)
        GameCoordinateCalibration.update(HorizontalAnchor.CENTER, -72f)
        assertEquals(before - 72f, GameCoordinateMapper.mapX(960, 2780, 1264, HorizontalAnchor.CENTER), 0.01f)
        GameCoordinateCalibration.reset()
    }
}
