package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.CharacterRole

data class SwitchCoordinatorResult(
    val controlStep: ControlStep,
    val activeNodeId: String?,
    val pauseFrame: SwitchRuntimeCommand.EnterPauseFrame?,
    val busy: Boolean,
    val runtime: SwitchRuntimeSnapshot
)

class SwitchControlCoordinator(
    private val stateMachine: BattleControlStateMachine,
    opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>,
    openingGraceSeconds: Int = 0
) {
    private var runtime = SwitchAxisRuntime(opening, nodes, openingGraceSeconds)
    private var convergingNodeId: String? = null
    private var pauseFrame: SwitchRuntimeCommand.EnterPauseFrame? = null

    // Role UB triggers are single-frame pulses. If safety briefly leaves RUNNING
    // (e.g. a recognition-jitter false pause) during that exact frame, the pulse
    // would otherwise be dropped entirely before it ever reaches SwitchAxisRuntime.
    // Latch each observed role trigger with a wall-clock timestamp and keep
    // replaying it into the frame fed to the runtime until it expires or safety
    // recovers and consumes it.
    private val recentRoleUbEvents = mutableMapOf<CharacterRole, Long>()

    fun update(frame: SwitchFrameInput, controlStep: ControlStep): SwitchCoordinatorResult {
        latchRoleUbEvents(frame)

        // Freeze the switch runtime while safety is taking ownership of the game.
        // Returning the original step also keeps the real pause reason/action in
        // diagnostics instead of replacing it with a generic "snapshot".
        if (controlStep.safety != ControlSafetyState.RUNNING) {
            return result(controlStep)
        }
        val currentNodeId = convergingNodeId
        if (currentNodeId != null && controlStep.confirmed) {
            runtime.confirmConvergence(currentNodeId)
            convergingNodeId = null
            stateMachine.clearDesired()
        }

        val replayedFrame = frame.withReplayedRoleUbEvents()

        return when (val command = runtime.update(replayedFrame)) {
            SwitchRuntimeCommand.None -> result()
            is SwitchRuntimeCommand.EnterPauseFrame -> {
                pauseFrame = command
                result()
            }
            is SwitchRuntimeCommand.Converge -> {
                pauseFrame = null
                convergingNodeId = command.nodeId
                stateMachine.setDesired(command.target.toControlTarget())
                result()
            }
        }
    }

    fun confirmPauseFrame(nodeId: String) {
        runtime.confirmPauseFrame(nodeId)
        if (pauseFrame?.nodeId == nodeId) pauseFrame = null
    }

    fun clearRecognitionEvidence() {
        runtime.clearRecognitionEvidence()
        recentRoleUbEvents.clear()
    }

    fun reset(
        opening: SwitchAxisOpening?,
        nodes: List<SwitchAxisNode>,
        openingGraceSeconds: Int = 0
    ) {
        stateMachine.clearDesired()
        runtime = SwitchAxisRuntime(opening, nodes, openingGraceSeconds)
        convergingNodeId = null
        pauseFrame = null
        recentRoleUbEvents.clear()
    }

    private fun latchRoleUbEvents(frame: SwitchFrameInput) {
        frame.triggeredRoles.forEach { role -> recentRoleUbEvents[role] = frame.wallMs }
        recentRoleUbEvents.entries.removeIf { (_, atMs) -> frame.wallMs - atMs > ROLE_UB_EVENT_TTL_MS }
    }

    private fun SwitchFrameInput.withReplayedRoleUbEvents(): SwitchFrameInput =
        if (recentRoleUbEvents.isEmpty()) {
            this
        } else {
            copy(triggeredRoles = triggeredRoles + recentRoleUbEvents.keys)
        }

    private fun result(controlStep: ControlStep = stateMachine.snapshot()) = SwitchCoordinatorResult(
        controlStep = controlStep,
        activeNodeId = convergingNodeId ?: pauseFrame?.nodeId ?: runtime.pendingNodeId(),
        pauseFrame = pauseFrame,
        busy = convergingNodeId != null || pauseFrame != null || runtime.pendingNodeId() != null,
        runtime = runtime.snapshot()
    )

    private fun SwitchControlTarget.toControlTarget() = OpeningControlTarget(
        auto = auto?.toVisualState(),
        roles = roles.mapValues { (_, state) ->
            requireNotNull(state) { "开关轴 SET 目标必须完整" }.toVisualState()
        }
    )

    private fun AxisToggleState.toVisualState() = when (this) {
        AxisToggleState.ON -> VisualToggleState.ON
        AxisToggleState.OFF -> VisualToggleState.OFF
    }

    private companion object {
        const val ROLE_UB_EVENT_TTL_MS = 3000L
    }
}