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
    val triggeredRoles: Set<CharacterRole>,
    val visualObstruction: Boolean = false,
    /** Capture timestamps for raw TP-release candidates, keyed by role. */
    val triggeredRoleTimesNanos: Map<CharacterRole, Long> = emptyMap()
)

class EnergyDetector(
    private val regions: Map<CharacterRole, EnergyRegion>,
    private val fullThreshold: Float = 0.97f,
    private val nearFullArmThreshold: Float = 0.93f,
    private val triggeredBelowThreshold: Float = 0.3f,
    private val minConsecutiveFullFrames: Int = 1,
    private val minConsecutiveNearFullFrames: Int = 3,
    private val minConsecutiveReleaseFrames: Int = 1
) {
    private var previousRatios: Map<CharacterRole, Float>? = null
    private val armedForRelease = mutableMapOf<CharacterRole, Boolean>()
    private val consecutiveFullFrames = mutableMapOf<CharacterRole, Int>()
    private val consecutiveNearFullFrames = mutableMapOf<CharacterRole, Int>()
    private val consecutiveReleaseFrames = mutableMapOf<CharacterRole, Int>()
    /**
     * Tracks whether a role has achieved the required consecutive full frames
     * since the last trigger or reset.  Once confirmed, the role remains
     * eligible to trigger on a subsequent drop even if the drop occurs on the
     * very next frame (fast UB scenario).
     */
    private val everConfirmed = mutableMapOf<CharacterRole, Boolean>()

    init {
        require(regions.keys == CharacterRole.entries.toSet())
        require(fullThreshold in 0f..1f)
        require(nearFullArmThreshold in 0f..fullThreshold)
        require(triggeredBelowThreshold in 0f..1f)
        require(minConsecutiveFullFrames >= 1)
        require(minConsecutiveNearFullFrames >= 1)
        require(minConsecutiveReleaseFrames >= 1)
    }

    fun detect(image: PixelImage): EnergyDetectionResult {
        val ratios = regions.mapValues { (_, region) -> fillExtentRatio(image, region) }
        val previous = previousRatios
        val visualObstruction = previous?.let { previousRatiosByRole ->
            val previousAllFull = previousRatiosByRole.values.all { it >= fullThreshold }
            val largeDrops = ratios.count { (role, ratio) ->
                previousRatiosByRole.getValue(role) - ratio >= VISUAL_OBSTRUCTION_DROP_DELTA
            }
            previousAllFull && largeDrops >= MIN_SIMULTANEOUS_LARGE_DROPS
        } ?: false

        val characters = ratios.mapValues { (role, ratio) ->
            val previousRatio = previous?.get(role)
            val wasArmed = armedForRelease[role] == true

            // Update consecutive full-frame counter
            val fullCount = if (ratio >= fullThreshold) {
                (consecutiveFullFrames[role] ?: 0) + 1
            } else {
                0
            }
            consecutiveFullFrames[role] = fullCount

            val nearFullCount = if (ratio >= nearFullArmThreshold) {
                (consecutiveNearFullFrames[role] ?: 0) + 1
            } else {
                0
            }
            consecutiveNearFullFrames[role] = nearFullCount

            // Mark as confirmed once the required consecutive frames are met
            if (
                fullCount >= minConsecutiveFullFrames ||
                nearFullCount >= minConsecutiveNearFullFrames
            ) {
                everConfirmed[role] = true
            }

            // A role is eligible to emit a raw release candidate if it was
            // armed and has ever been confirmed full since the last trigger or
            // reset. Production uses the first low sample: final UB
            // confirmation belongs to RoleUbBannerGate, which can revoke a
            // transient dip when TP immediately recovers and requires the
            // following moving skill-name banner.
            val trulyArmed = wasArmed && everConfirmed[role] == true
            val releaseCandidate = trulyArmed && ratio < triggeredBelowThreshold
            val releaseCount = if (releaseCandidate) {
                (consecutiveReleaseFrames[role] ?: 0) + 1
            } else {
                0
            }
            val triggered = !visualObstruction &&
                releaseCandidate &&
                releaseCount >= minConsecutiveReleaseFrames

            when {
                visualObstruction -> {
                    // The previous all-full frame was proven false by an
                    // impossible multi-role drop. Keep only roles that are
                    // genuinely full in the current frame armed.
                    consecutiveReleaseFrames[role] = 0
                    if (ratio >= fullThreshold) {
                        armedForRelease[role] = true
                        everConfirmed[role] = true
                    } else {
                        armedForRelease[role] = false
                        consecutiveFullFrames[role] = 0
                        consecutiveNearFullFrames[role] = 0
                        everConfirmed[role] = false
                    }
                }
                triggered -> {
                    armedForRelease[role] = false
                    consecutiveFullFrames[role] = 0
                    consecutiveNearFullFrames[role] = 0
                    consecutiveReleaseFrames[role] = 0
                    everConfirmed[role] = false
                }
                releaseCandidate -> {
                    // Retained for stricter detector configurations used by
                    // focused tests. Production emits on the first low sample.
                    armedForRelease[role] = true
                    consecutiveReleaseFrames[role] = releaseCount
                }
                ratio >= fullThreshold -> {
                    armedForRelease[role] = true
                    consecutiveReleaseFrames[role] = 0
                }
                nearFullCount >= minConsecutiveNearFullFrames -> {
                    armedForRelease[role] = true
                    consecutiveReleaseFrames[role] = 0
                }
                wasArmed -> {
                    armedForRelease[role] = true
                    consecutiveReleaseFrames[role] = 0
                }
                else -> {
                    armedForRelease[role] = false
                    consecutiveFullFrames[role] = 0
                    consecutiveReleaseFrames[role] = 0
                    everConfirmed[role] = false
                }
            }

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
            triggeredRoles = triggeredRoles,
            visualObstruction = visualObstruction
        )
    }

    fun reset() {
        previousRatios = null
        armedForRelease.clear()
        consecutiveFullFrames.clear()
        consecutiveNearFullFrames.clear()
        consecutiveReleaseFrames.clear()
        everConfirmed.clear()
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
        private const val VISUAL_OBSTRUCTION_DROP_DELTA = 0.15f
        private const val MIN_SIMULTANEOUS_LARGE_DROPS = 2

        fun isBluePixel(color: Int): Boolean {
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            return blue > red + 40 && blue > green + 30 && blue > 80
        }
    }
}