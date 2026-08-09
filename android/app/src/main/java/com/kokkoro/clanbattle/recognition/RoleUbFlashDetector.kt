package com.kokkoro.clanbattle.recognition

data class RoleUbFlashDetection(
    val role: CharacterRole?,
    val rawRole: CharacterRole?,
    val borderlineRole: CharacterRole?,
    val scores: Map<CharacterRole, Float>,
    val strongestScore: Float,
    val margin: Float
) {
    companion object {
        fun empty(): RoleUbFlashDetection = RoleUbFlashDetection(
            role = null,
            rawRole = null,
            borderlineRole = null,
            scores = CharacterRole.entries.associateWith { 0f },
            strongestScore = 0f,
            margin = 0f
        )
    }
}

/**
 * Locates the universal yellow/white activation burst that originates from the
 * portrait slot of the character starting a UB.
 *
 * TP bars are heavily corrupted by this same flash: a real full bar can appear
 * only one-third filled while a neighbouring empty bar can appear full. The
 * burst origin is therefore independent evidence and is only consumed together
 * with a following UB-name banner by [RoleUbBannerGate].
 */
class RoleUbFlashDetector(
    private val minimumScore: Float = DEFAULT_MINIMUM_SCORE,
    private val minimumMargin: Float = DEFAULT_MINIMUM_MARGIN,
    private val corroboratedMinimumMargin: Float = DEFAULT_CORROBORATED_MINIMUM_MARGIN,
    private val immediateScore: Float = DEFAULT_IMMEDIATE_SCORE,
    private val minimumConsecutiveFrames: Int = DEFAULT_MINIMUM_CONSECUTIVE_FRAMES
) {
    private var pendingRole: CharacterRole? = null
    private var consecutiveFrames = 0

    init {
        require(minimumScore in 0f..1f)
        require(minimumMargin in 0f..1f)
        require(corroboratedMinimumMargin in 0f..minimumMargin)
        require(immediateScore in minimumScore..1f)
        require(minimumConsecutiveFrames >= 1)
    }

    fun detect(image: PixelImage): RoleUbFlashDetection {
        val scores = CharacterRole.entries.associateWith { role ->
            brightFraction(image, role)
        }
        val sorted = scores.entries.sortedByDescending { it.value }
        val strongest = sorted.first()
        val secondScore = sorted.getOrNull(1)?.value ?: 0f
        val margin = strongest.value - secondScore
        val rawRole = strongest.key.takeIf {
            strongest.value >= minimumScore && margin >= minimumMargin
        }
        // Some real phones slightly smear the portrait-origin burst across the
        // neighbouring slot. Do not lower the normal identity margin globally;
        // expose only a high-strength borderline role so RoleUbBannerGate can
        // use it when an independent TP-release history corroborates the same
        // role. By itself this hint is never a confirmed UB identity.
        val borderlineRole = strongest.key.takeIf {
            strongest.value >= immediateScore && margin >= corroboratedMinimumMargin
        }

        if (rawRole == null) {
            pendingRole = null
            consecutiveFrames = 0
        } else if (rawRole == pendingRole) {
            consecutiveFrames++
        } else {
            pendingRole = rawRole
            consecutiveFrames = 1
        }

        val confirmedRole = rawRole.takeIf {
            strongest.value >= immediateScore || consecutiveFrames >= minimumConsecutiveFrames
        }

        return RoleUbFlashDetection(
            // Only strong, localised portrait flashes are identity evidence.
            // The expanding tail is much weaker and may drift over another
            // portrait slot, so it must not keep emitting or replace the
            // original role. RoleUbBannerGate retains the strong flash until
            // the following moving skill-name banner confirms the UB.
            role = confirmedRole,
            rawRole = rawRole,
            borderlineRole = borderlineRole,
            scores = scores,
            strongestScore = strongest.value,
            margin = margin
        )
    }

    fun reset() {
        pendingRole = null
        consecutiveFrames = 0
    }

    private fun brightFraction(image: PixelImage, role: CharacterRole): Float {
        val referenceLeft = role.ordinal * ROLE_STRIDE
        val left = referenceLeft * image.width / REFERENCE_WIDTH
        val right = (referenceLeft + ROLE_WIDTH) * image.width / REFERENCE_WIDTH
        val width = maxOf(1, right - left)
        var matching = 0
        for (y in 0 until image.height) {
            for (x in left until minOf(image.width, left + width)) {
                if (isActivationFlashPixel(image[x, y])) matching++
            }
        }
        return matching.toFloat() / (width * image.height)
    }

    companion object {
        const val REFERENCE_WIDTH = 1170
        const val ROLE_STRIDE = 240
        const val ROLE_WIDTH = 210

        // Full-battle video replay shows a clean separation on the current HUD:
        // all ten real character-UB portrait bursts peak at >= 0.848, while
        // ordinary skill/BOSS interference remains below 0.70. Keep headroom
        // for capture variation without accepting the weak expanding tail.
        const val DEFAULT_MINIMUM_SCORE = 0.70f
        const val DEFAULT_MINIMUM_MARGIN = 0.12f
        const val DEFAULT_CORROBORATED_MINIMUM_MARGIN = 0.10f
        // Real-phone replay contains a valid one-frame ROLE_5 origin at
        // 0.78065 immediately before the UB-name banner. All other confirmed
        // phone origins in the same runs are >= 0.825, while known ordinary /
        // BOSS interference stays below DEFAULT_MINIMUM_SCORE (0.70). Keep the
        // normal 0.70 + margin gate and the following banner requirement, but
        // allow this narrow capture-device variation to confirm in one frame.
        const val DEFAULT_IMMEDIATE_SCORE = 0.77f
        const val DEFAULT_MINIMUM_CONSECUTIVE_FRAMES = 2

        fun isActivationFlashPixel(color: Int): Boolean {
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            val yellowWhite = red >= 225 && green >= 190 && blue <= 190
            val neutralWhite = red >= 235 && green >= 235 && blue >= 220
            return yellowWhite || neutralWhite
        }
    }
}
