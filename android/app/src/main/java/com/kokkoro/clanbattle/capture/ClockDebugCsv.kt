package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.DigitRecognitionTrace
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import java.io.Closeable
import java.io.Writer

class ClockDebugCsv(private val writer: Writer, header: String) : Closeable {
    var isClosed = false
        private set

    init { writer.append(header).append('\n') }

    @Synchronized fun write(values: List<Any?>): Boolean {
        if (isClosed) return false
        writer.append(values.joinToString(",") { escape(it?.toString().orEmpty()) }).append('\n')
        return true
    }

    @Synchronized override fun close() {
        if (isClosed) return
        isClosed = true
        writer.flush()
        writer.close()
    }

    companion object {
        const val FRAME_HEADER = "frameId,wallMs,gate,recognitionRaw,recognitionOk,recognitionConfidence,recognitionReason,filterAccepted,filterTime,filterReason,filterSource,dropped"
        const val DIGIT_HEADER = "frameId,wallMs,slot,scoreKind,rawTop1,rawTop2,rawMargin,chosen,chosenScore,decisionMargin,decisionRule,decision0,decision1,decision2,decision3,decision4,decision5,decision6,decision7,decision8,decision9,ncc0,ncc1,ncc2,ncc3,ncc4,ncc5,ncc6,ncc7,ncc8,ncc9,cropFile"
        const val ENERGY_HEADER = "frameId,wallMs,energyDelta,triggeredRoles,role1Ratio,role1Full,role1Delta,role1Triggered,role2Ratio,role2Full,role2Delta,role2Triggered,role3Ratio,role3Full,role3Delta,role3Triggered,role4Ratio,role4Full,role4Delta,role4Triggered,role5Ratio,role5Full,role5Delta,role5Triggered"

        fun energyValues(frameId: Long, wallMs: Long, result: EnergyDetectionResult): List<Any?> =
            listOf(frameId, wallMs, result.energyDelta, result.triggeredRoles.sortedBy { it.ordinal }.joinToString("|")) +
                CharacterRole.entries.flatMap { role ->
                    val state = result.characters.getValue(role)
                    listOf(state.blueRatio, state.isFull, state.delta, state.triggered)
                }

        fun digitValues(
            frameId: Long,
            wallMs: Long,
            digit: DigitRecognitionTrace,
            cropFile: String
        ): List<Any?> = listOf(
            frameId,
            wallMs,
            digit.slot,
            digit.scoreKind,
            digit.rawTop1,
            digit.rawTop2,
            digit.rawMargin,
            digit.chosen,
            digit.chosenScore,
            digit.decisionMargin,
            digit.decisionRule
        ) + (0..9).map { digit.decisionScores[it] } +
            (0..9).map { digit.nccScores[it] } + cropFile

        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
