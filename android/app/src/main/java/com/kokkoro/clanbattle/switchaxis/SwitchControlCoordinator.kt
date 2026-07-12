package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VisualToggleState

data class SwitchCoordinatorResult(
    val controlStep: ControlStep,
    val activeNodeId: String?,
    val pauseFrame: SwitchRuntimeCommand.EnterPauseFrame?,
    val busy: Boolean
)

class SwitchControlCoordinator(
    private val stateMachine: BattleControlStateMachine,
    opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>
) {
    private var runtime = SwitchAxisRuntime(opening, nodes)
    private var convergingNodeId: String? = null
    private var pauseFrame: SwitchRuntimeCommand.EnterPauseFrame? = null

    fun update(frame: SwitchFrameInput, controlStep: ControlStep): SwitchCoordinatorResult {
        val currentNodeId = convergingNodeId
        if (currentNodeId != null && controlStep.confirmed) {
            runtime.confirmConvergence(currentNodeId)
            convergingNodeId = null
            stateMachine.clearDesired()
        }

        return when (val command = runtime.update(frame)) {
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

    fun reset(opening: SwitchAxisOpening?, nodes: List<SwitchAxisNode>) {
        stateMachine.clearDesired()
        runtime = SwitchAxisRuntime(opening, nodes)
        convergingNodeId = null
        pauseFrame = null
    }

    private fun result() = SwitchCoordinatorResult(
        controlStep = stateMachine.snapshot(),
        activeNodeId = convergingNodeId ?: pauseFrame?.nodeId ?: runtime.pendingNodeId(),
        pauseFrame = pauseFrame,
        busy = convergingNodeId != null || pauseFrame != null || runtime.pendingNodeId() != null
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
}
