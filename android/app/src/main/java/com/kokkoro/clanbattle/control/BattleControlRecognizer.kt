package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.capture.FixedTemplateMatcher
import com.kokkoro.clanbattle.recognition.CharacterRole

class BattleControlRecognizer(
    private val templates: BattleControlTemplates,
    private val pairMinScore: Double = DEFAULT_PAIR_MIN_SCORE,
    private val pairMinMargin: Double = DEFAULT_PAIR_MIN_MARGIN,
    private val badgeOnThreshold: Double = DEFAULT_BADGE_ON_THRESHOLD,
    private val badgeOffThreshold: Double = DEFAULT_BADGE_OFF_THRESHOLD
) {
    fun recognize(crops: ControlCrops): BattleControlObservation {
        require(crops.roles.keys == CharacterRole.entries.toSet())

        val auto = classifyPair(
            onScore = FixedTemplateMatcher.score(crops.auto, templates.autoOn),
            offScore = FixedTemplateMatcher.score(crops.auto, templates.autoOff),
            minScore = pairMinScore,
            minMargin = pairMinMargin
        )
        val globalSet = classifyPair(
            onScore = FixedTemplateMatcher.score(crops.globalSet, templates.globalSetOn),
            offScore = FixedTemplateMatcher.score(crops.globalSet, templates.globalSetOff),
            minScore = pairMinScore,
            minMargin = pairMinMargin
        )
        val roles = crops.roles.mapValues { (_, crop) ->
            classifyBadge(
                onScore = FixedTemplateMatcher.bestScaleScore(crop, templates.roleSetOn),
                onThreshold = badgeOnThreshold,
                offThreshold = badgeOffThreshold
            )
        }
        val explicitlyOffRoles = CharacterRole.entries.filter { role ->
            roles.getValue(role).state == VisualToggleState.OFF
        }
        val inconsistent = globalSet.state == VisualToggleState.ON && explicitlyOffRoles.isNotEmpty()

        return BattleControlObservation(
            auto = auto,
            globalSet = globalSet,
            roles = roles,
            consistent = !inconsistent,
            reason = if (inconsistent) {
                "global-set-on-but-role-off:${explicitlyOffRoles.joinToString(",")}"
            } else {
                null
            }
        )
    }

    companion object {
        const val DEFAULT_PAIR_MIN_SCORE = 0.65
        const val DEFAULT_PAIR_MIN_MARGIN = 0.08
        const val DEFAULT_BADGE_ON_THRESHOLD = 0.75
        const val DEFAULT_BADGE_OFF_THRESHOLD = 0.55

        fun classifyPair(
            onScore: Double,
            offScore: Double,
            minScore: Double = DEFAULT_PAIR_MIN_SCORE,
            minMargin: Double = DEFAULT_PAIR_MIN_MARGIN
        ): ToggleObservation {
            val margin = kotlin.math.abs(onScore - offScore)
            val state = when {
                maxOf(onScore, offScore) < minScore -> VisualToggleState.UNKNOWN
                margin < minMargin -> VisualToggleState.UNKNOWN
                onScore > offScore -> VisualToggleState.ON
                else -> VisualToggleState.OFF
            }
            return ToggleObservation(state, onScore, offScore, margin)
        }

        fun classifyBadge(
            onScore: Double,
            onThreshold: Double = DEFAULT_BADGE_ON_THRESHOLD,
            offThreshold: Double = DEFAULT_BADGE_OFF_THRESHOLD
        ): ToggleObservation {
            require(offThreshold <= onThreshold)
            val state = when {
                onScore >= onThreshold -> VisualToggleState.ON
                onScore <= offThreshold -> VisualToggleState.OFF
                else -> VisualToggleState.UNKNOWN
            }
            return ToggleObservation(state = state, onScore = onScore)
        }
    }
}
