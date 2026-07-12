package com.kokkoro.clanbattle.recognition

import kotlin.math.abs

enum class CharacterRole {
    ROLE_1,
    ROLE_2,
    ROLE_3,
    ROLE_4,
    ROLE_5
}

data class EnergyRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(x >= 0 && y >= 0)
        require(width > 0 && height > 0)
    }
}

data class CharacterEnergyState(
    val blueRatio: Float,
    val isFull: Boolean,
    val delta: Float?,
    val triggered: Boolean
)

data class EnergyDetectionResult(
    val characters: Map<CharacterRole, CharacterEnergyState>,
    val energyDelta: Float?,
    val triggeredRoles: Set<CharacterRole>
)

class EnergyDetector(
    private val regions: Map<CharacterRole, EnergyRegion>,
    private val fullThreshold: Float = 0.97f,
    private val triggeredBelowThreshold: Float = 0.3f
) {
    private var previousRatios: Map<CharacterRole, Float>? = null

    init {
        require(regions.keys == CharacterRole.entries.toSet())
        require(fullThreshold in 0f..1f)
        require(triggeredBelowThreshold in 0f..1f)
    }

    fun detect(image: PixelImage): EnergyDetectionResult {
        val ratios = regions.mapValues { (_, region) -> fillExtentRatio(image, region) }
        val previous = previousRatios
        val characters = ratios.mapValues { (role, ratio) ->
            val previousRatio = previous?.get(role)
            val triggered = previousRatio != null &&
                previousRatio >= fullThreshold &&
                ratio < triggeredBelowThreshold
            CharacterEnergyState(
                blueRatio = ratio,
                isFull = ratio >= fullThreshold,
                delta = previousRatio?.let { abs(ratio - it) },
                triggered = triggered
            )
        }
        val triggeredRoles = characters
            .filterValues(CharacterEnergyState::triggered)
            .keys

        previousRatios = ratios
        return EnergyDetectionResult(
            characters = characters,
            energyDelta = if (previous == null) {
                null
            } else {
                characters.values.sumOf { it.delta!!.toDouble() }.toFloat() / characters.size
            },
            triggeredRoles = triggeredRoles
        )
    }

    fun reset() {
        previousRatios = null
    }

    private fun fillExtentRatio(image: PixelImage, region: EnergyRegion): Float {
        require(region.x + region.width <= image.width)
        require(region.y + region.height <= image.height)

        val blueColumns = BooleanArray(region.width) { column ->
            var bluePixels = 0
            for (y in region.y until region.y + region.height) {
                if (isBluePixel(image[region.x + column, y])) bluePixels++
            }
            bluePixels.toFloat() / region.height >= MIN_BLUE_COLUMN_RATIO
        }
        val smoothedColumns = BooleanArray(region.width) { column ->
            val start = maxOf(0, column - SMOOTHING_RADIUS)
            val end = minOf(region.width - 1, column + SMOOTHING_RADIUS)
            var blueColumnsInWindow = 0
            for (candidate in start..end) {
                if (blueColumns[candidate]) blueColumnsInWindow++
            }
            blueColumnsInWindow * 2 > end - start + 1
        }
        val lastFilledColumn = smoothedColumns.indexOfLast { it }
        return if (lastFilledColumn < 0) 0f else (lastFilledColumn + 1).toFloat() / region.width
    }

    companion object {
        private const val MIN_BLUE_COLUMN_RATIO = 0.2f
        private const val SMOOTHING_RADIUS = 2

        fun isBluePixel(color: Int): Boolean {
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            return blue > red + 40 && blue > green + 30 && blue > 80
        }
    }
}
