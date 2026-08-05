package com.kokkoro.clanbattle.automation

import kotlin.math.min

enum class HorizontalAnchor { CENTER, RIGHT, TOP_HUD }

data class GameViewport(val scale: Float, val spareX: Float, val offsetY: Float)

object GameCoordinateCalibration {
    @Volatile private var centerDeltaX = 0f
    @Volatile private var rightDeltaX = 0f
    @Volatile private var topHudDeltaX = 0f

    fun reset() {
        centerDeltaX = 0f
        rightDeltaX = 0f
        topHudDeltaX = 0f
    }

    fun horizontalDelta(anchor: HorizontalAnchor): Float = when (anchor) {
        HorizontalAnchor.CENTER -> centerDeltaX
        HorizontalAnchor.RIGHT -> rightDeltaX
        HorizontalAnchor.TOP_HUD -> topHudDeltaX
    }

    fun update(anchor: HorizontalAnchor, deltaX: Float) {
        when (anchor) {
            HorizontalAnchor.CENTER -> centerDeltaX = deltaX
            HorizontalAnchor.RIGHT -> rightDeltaX = deltaX
            HorizontalAnchor.TOP_HUD -> topHudDeltaX = deltaX
        }
    }
}

object GameCoordinateMapper {
    private const val REFERENCE_WIDTH = 1920f
    private const val REFERENCE_HEIGHT = 1080f

    fun viewport(width: Int, height: Int): GameViewport {
        require(width > 0 && height > 0)
        val scale = min(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT)
        return GameViewport(
            scale = scale,
            spareX = (width - REFERENCE_WIDTH * scale).coerceAtLeast(0f),
            offsetY = ((height - REFERENCE_HEIGHT * scale) / 2f).coerceAtLeast(0f)
        )
    }

    fun mapX(
        referenceX: Int,
        width: Int,
        height: Int,
        anchor: HorizontalAnchor,
        includeCalibration: Boolean = true
    ): Float {
        val viewport = viewport(width, height)
        val baseOffset = when (anchor) {
            HorizontalAnchor.CENTER -> viewport.spareX / 2f
            HorizontalAnchor.RIGHT -> viewport.spareX
            // Top-right clock/menu placement differs across devices with the
            // same aspect ratio. Start from the centered game viewport, then
            // let the menu template supply an independent calibration delta.
            HorizontalAnchor.TOP_HUD -> viewport.spareX / 2f
        }
        val calibration = if (includeCalibration) GameCoordinateCalibration.horizontalDelta(anchor) else 0f
        return baseOffset + calibration + referenceX * viewport.scale
    }

    fun mapY(referenceY: Int, width: Int, height: Int): Float {
        val viewport = viewport(width, height)
        return viewport.offsetY + referenceY * viewport.scale
    }
}
