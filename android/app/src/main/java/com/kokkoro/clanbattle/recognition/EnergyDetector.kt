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
    private val fullThreshold: Float = 0.85f,
    private val triggeredBelowThreshold: Float = 0.3f
) {
    private var previousRatios: Map<CharacterRole, Float>? = null

    init {
        require(regions.keys == CharacterRole.entries.toSet())
        require(fullThreshold in 0f..1f)
        require(triggeredBelowThreshold in 0f..1f)
    }

    fun detect(image: PixelImage): EnergyDetectionResult {
        val ratios = regions.mapValues { (_, region) -> blueRatio(image, region) }
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

    private fun blueRatio(image: PixelImage, region: EnergyRegion): Float {
        require(region.x + region.width <= image.width)
        require(region.y + region.height <= image.height)
        var bluePixels = 0
        for (y in region.y until region.y + region.height) {
            for (x in region.x until region.x + region.width) {
                if (isBluePixel(image[x, y])) bluePixels++
            }
        }
        return bluePixels.toFloat() / (region.width * region.height)
    }

    companion object {
        fun isBluePixel(color: Int): Boolean {
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            return blue > red + 40 && blue > green + 30 && blue > 80
        }
    }
}
