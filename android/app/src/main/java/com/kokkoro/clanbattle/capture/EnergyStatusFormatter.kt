package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import kotlin.math.roundToInt

object EnergyStatusFormatter {
    fun format(result: EnergyDetectionResult?): String {
        if (result == null) return "TP --"
        return CharacterRole.entries.joinToString(prefix = "TP ", separator = " ") { role ->
            val percent = (result.characters.getValue(role).blueRatio * 100).roundToInt()
            "${role.ordinal + 1}:$percent"
        }
    }
}
