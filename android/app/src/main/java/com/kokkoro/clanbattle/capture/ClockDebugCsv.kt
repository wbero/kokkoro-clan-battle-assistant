package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.DigitRecognitionTrace
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlState
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VisualToggleState
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
        const val CONTROL_HEADER = "frameId,wallMs,autoState,autoOnScore,autoOffScore,autoMargin,globalState,globalOnScore,globalOffScore,globalMargin,role1State,role1OnScore,role1OffScore,role1Margin,role2State,role2OnScore,role2OffScore,role2Margin,role3State,role3OnScore,role3OffScore,role3Margin,role4State,role4OnScore,role4OffScore,role4Margin,role5State,role5OnScore,role5OffScore,role5Margin,consistent,observationReason,observed,desired,expected,action,retryCount,confirmed,safetyState,stepReason,menuScore,cropPrefix"
        const val SWITCH_HEADER = "frameId,wallMs,axisName,nodeId,clockSeconds,triggeredRoles,controlsTrustworthy,busy,desired,safetyState,pauseFrameRole"

        fun switchValues(
            frameId: Long,
            wallMs: Long,
            axisName: String,
            nodeId: String?,
            clockSeconds: Int?,
            triggeredRoles: Set<CharacterRole>,
            controlsTrustworthy: Boolean,
            busy: Boolean,
            desired: String,
            safetyState: com.kokkoro.clanbattle.control.ControlSafetyState,
            pauseFrameRole: CharacterRole?
        ): List<Any?> = listOf(
            frameId,
            wallMs,
            axisName,
            nodeId,
            clockSeconds,
            triggeredRoles.sortedBy { it.ordinal }.joinToString("|"),
            controlsTrustworthy,
            busy,
            desired,
            safetyState,
            pauseFrameRole
        )

        fun controlValues(
            frameId: Long,
            wallMs: Long,
            observation: BattleControlObservation?,
            step: ControlStep,
            menuScore: Double,
            cropPrefix: String
        ): List<Any?> {
            val toggleValues = listOf(observation?.auto, observation?.globalSet).flatMap { toggle ->
                listOf(toggle?.state, toggle?.onScore, toggle?.offScore, toggle?.margin)
            } + CharacterRole.entries.flatMap { role ->
                val toggle = observation?.roles?.get(role)
                listOf(toggle?.state, toggle?.onScore, toggle?.offScore, toggle?.margin)
            }
            return listOf(frameId, wallMs) + toggleValues + listOf(
                observation?.consistent,
                observation?.reason,
                encodeState(step.observed),
                encodeTarget(step.desired),
                encodeState(step.expected),
                encodeAction(step.action),
                step.retryCount,
                step.confirmed,
                step.safety,
                step.reason,
                menuScore,
                cropPrefix
            )
        }

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

        private fun encodeAction(action: ControlAction): String = when (action) {
            ControlAction.None -> "None"
            ControlAction.TapAuto -> "TapAuto"
            ControlAction.TapGlobalSet -> "TapGlobalSet"
            is ControlAction.TapRole -> "TapRole:${action.role}"
            ControlAction.TapMenu -> "TapMenu"
        }

        private fun encodeTarget(target: OpeningControlTarget?): String = target?.let {
            "auto=${it.auto ?: "-"};roles=${encodeRoles(it.roles)}"
        }.orEmpty()

        private fun encodeState(state: BattleControlState?): String = state?.let {
            "auto=${it.auto};global=${it.globalSet};roles=${encodeRoles(it.roles)}"
        }.orEmpty()

        private fun encodeRoles(roles: Map<CharacterRole, VisualToggleState>?): String =
            CharacterRole.entries.joinToString("") { role ->
                when (roles?.get(role)) {
                    VisualToggleState.ON -> "O"
                    VisualToggleState.OFF -> "X"
                    VisualToggleState.UNKNOWN -> "?"
                    null -> "-"
                }
            }

        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
