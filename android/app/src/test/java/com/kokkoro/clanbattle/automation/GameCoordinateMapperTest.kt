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

    @Test fun `right control calibration does not move loading or top hud`() {
        GameCoordinateCalibration.reset()
        val width = 3440
        val height = 1440
        val loadingBefore = GameCoordinateMapper.mapX(1545, width, height, HorizontalAnchor.LOADING)
        val topBefore = GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.TOP_HUD)
        val controlBefore = GameCoordinateMapper.mapX(1783, width, height, HorizontalAnchor.RIGHT_CONTROL)

        GameCoordinateCalibration.update(HorizontalAnchor.RIGHT_CONTROL, -220f)

        assertEquals(loadingBefore, GameCoordinateMapper.mapX(1545, width, height, HorizontalAnchor.LOADING), 0.01f)
        assertEquals(topBefore, GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.TOP_HUD), 0.01f)
        assertEquals(controlBefore - 220f, GameCoordinateMapper.mapX(1783, width, height, HorizontalAnchor.RIGHT_CONTROL), 0.01f)
        GameCoordinateCalibration.reset()
    }

    @Test fun `extreme ultrawide loading and right controls start from centered viewport`() {
        GameCoordinateCalibration.reset()
        val width = 2800
        val height = 800

        val loadingCenter = GameCoordinateMapper.mapX(1545, width, height, HorizontalAnchor.CENTER)
        val controlCenter = GameCoordinateMapper.mapX(1783, width, height, HorizontalAnchor.CENTER)
        val physicalRightControl = GameCoordinateMapper.mapX(1783, width, height, HorizontalAnchor.RIGHT)

        assertEquals(loadingCenter, GameCoordinateMapper.mapX(1545, width, height, HorizontalAnchor.LOADING), 0.01f)
        assertEquals(controlCenter, GameCoordinateMapper.mapX(1783, width, height, HorizontalAnchor.RIGHT_CONTROL), 0.01f)
        assertEquals(688.9f, physicalRightControl - controlCenter, 0.2f)
        GameCoordinateCalibration.reset()
    }

    @Test fun `top hud calibration is independent from center and right anchors`() {
        GameCoordinateCalibration.reset()
        val width = 2800
        val height = 1272
        val centerBefore = GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.CENTER)
        val rightBefore = GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.RIGHT)
        val topBefore = GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.TOP_HUD)

        assertEquals(centerBefore, topBefore, 0.01f)

        GameCoordinateCalibration.update(HorizontalAnchor.TOP_HUD, 232f)

        assertEquals(centerBefore, GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.CENTER), 0.01f)
        assertEquals(rightBefore, GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.RIGHT), 0.01f)
        assertEquals(topBefore + 232f, GameCoordinateMapper.mapX(1619, width, height, HorizontalAnchor.TOP_HUD), 0.01f)
        GameCoordinateCalibration.reset()
    }

    @Test fun `honor win top hud stays in centered safe area`() {
        GameCoordinateCalibration.reset()
        val width = 2800
        val height = 1272

        val clockInSafeArea = GameCoordinateMapper.mapX(
            1619,
            width,
            height,
            HorizontalAnchor.CENTER
        )
        val clockAtPhysicalRight = GameCoordinateMapper.mapX(
            1619,
            width,
            height,
            HorizontalAnchor.RIGHT
        )

        // The wrong physical-right anchor adds half of the ultrawide spare
        // area (about 269 device pixels), placing the clock crop over MENU.
        assertEquals(2176.2f, clockInSafeArea, 0.2f)
        assertEquals(269.3f, clockAtPhysicalRight - clockInSafeArea, 0.2f)
    }
}
