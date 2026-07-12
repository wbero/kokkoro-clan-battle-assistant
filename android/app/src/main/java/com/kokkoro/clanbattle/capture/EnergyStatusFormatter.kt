package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.scheduler.GameState
import kotlin.math.roundToInt

object EnergyStatusFormatter {
    fun format(
        result: EnergyDetectionResult?,
        state: GameState? = null,
        scheduleReason: String? = null
    ): String {
        val energy = if (result == null) {
            "TP --"
        } else {
            CharacterRole.entries.joinToString(prefix = "TP ", separator = " ") { role ->
                val percent = (result.characters.getValue(role).blueRatio * 100).roundToInt()
                "${role.ordinal + 1}:$percent"
            }
        }
        return if (state == null || scheduleReason == null) energy
        else "$energy  STATE:${state.name}/$scheduleReason"
    }
}
