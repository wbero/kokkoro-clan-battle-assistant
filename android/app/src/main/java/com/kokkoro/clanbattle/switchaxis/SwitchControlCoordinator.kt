package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.BattleControlState
import com.kokkoro.clanbattle.control.AuthoritativeControlState
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.control.actionsFrom
import com.kokkoro.clanbattle.recognition.CharacterRole

data class SwitchCoordinatorResult(
    val controlStep: ControlStep,
    val activeNodeId: String?,
    val pauseFrame: SwitchRuntimeCommand.EnterPauseFrame?,
    val busy: Boolean,
    val runtime: SwitchRuntimeSnapshot,
    val controlActions: List<ControlAction> = emptyList()
)

class SwitchControlCoordinator(
    private val stateMachine: BattleControlStateMachine,
    opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>,
    openingGraceSeconds: Int = 0,
    private val clickIntervalMs: Int = 100,
    private val authoritativeControls: AuthoritativeControlState = AuthoritativeControlState()
) {
    private var runtime = SwitchAxisRuntime(opening, nodes, openingGraceSeconds)
    private var nodesById = nodes.associateBy(SwitchAxisNode::id)
    private var convergingNodeId: String? = null
    private var convergingTarget: OpeningControlTarget? = null
    private var pauseFrame: SwitchRuntimeCommand.EnterPauseFrame? = null

    // Role UB triggers are single-frame pulses. If safety briefly leaves RUNNING
    // (e.g. a recognition-jitter false pause) during that exact frame, the pulse
    // would otherwise be dropped entirely before it ever reaches SwitchAxisRuntime.
    // Latch each observed role trigger with a wall-clock timestamp and keep
    // replaying it into the frame fed to the runtime until it expires or safety
    // recovers and consumes it.
    private data class RecentRoleUbEvent(
        val wallMs: Long,
        val clockSeconds: Int?
    )

    private val recentRoleUbEvents = mutableMapOf<CharacterRole, RecentRoleUbEvent>()

    init {
        require(clickIntervalMs >= 1)
        // Switch-axis convergence owns its own batch verification. Leaving a
        // state-machine desired target armed would make updateControls plan an
        // extra single click between two gestures in the batch.
        stateMachine.clearDesired()
    }

    fun update(
        frame: SwitchFrameInput,
        controlStep: ControlStep
    ): SwitchCoordinatorResult {
        latchRoleUbEvents(frame)

        // Freeze the switch runtime while safety is taking ownership of the game.
        // Returning the original step also keeps the real pause reason/action in
        // diagnostics instead of replacing it with a generic "snapshot".
        if (controlStep.safety != ControlSafetyState.RUNNING) {
            return result(controlStep)
        }

        if (convergingNodeId != null) {
            advanceConvergence(controlStep)?.let { return it }
        }

        val replayedFrame = frame.withReplayedRoleUbEvents()

        return when (val command = runtime.update(replayedFrame)) {
            SwitchRuntimeCommand.None -> result()
            is SwitchRuntimeCommand.EnterPauseFrame -> {
                pauseFrame = command
                result()
            }
            is SwitchRuntimeCommand.MissedCharacterUb -> {
                val reason =
                    "switch-character-ub-missed:${command.nodeId}:${command.role.name}:" +
                        "${command.expectedClockSeconds}->${command.observedClockSeconds}"
                stateMachine.forceSafety(reason)
                result(stateMachine.snapshot(reason))
            }
            is SwitchRuntimeCommand.Converge -> {
                pauseFrame = null
                convergingNodeId = command.nodeId
                convergingTarget = command.target.toControlTarget()
                stateMachine.clearDesired()
                if (nodesById[command.nodeId]?.trigger is CharacterUbTrigger) {
                    recentRoleUbEvents.clear()
                }
                advanceConvergence(controlStep) ?: result(controlStep)
            }
        }
    }

    fun seedControlState(state: BattleControlState?) {
        authoritativeControls.seedIfAbsent(state)
    }

    fun resetControlState() {
        authoritativeControls.reset()
    }

    fun confirmPauseFrame(nodeId: String) {
        runtime.confirmPauseFrame(nodeId)
        if (pauseFrame?.nodeId == nodeId) pauseFrame = null
    }

    fun clearRecognitionEvidence() {
        runtime.clearRecognitionEvidence()
        recentRoleUbEvents.clear()
    }

    fun isFinished(): Boolean =
        convergingNodeId == null && pauseFrame == null && runtime.isFinished()

    fun reset(
        opening: SwitchAxisOpening?,
        nodes: List<SwitchAxisNode>,
        openingGraceSeconds: Int = 0
    ) {
        stateMachine.clearDesired()
        authoritativeControls.reset()
        runtime = SwitchAxisRuntime(opening, nodes, openingGraceSeconds)
        nodesById = nodes.associateBy(SwitchAxisNode::id)
        clearConvergence()
        pauseFrame = null
        recentRoleUbEvents.clear()
    }

    private fun advanceConvergence(controlStep: ControlStep): SwitchCoordinatorResult? {
        val nodeId = convergingNodeId ?: return null
        val target = convergingTarget ?: return null
        val current = authoritativeControls.snapshot() ?: return result(controlStep)

        if (target.matches(current)) {
            runtime.confirmConvergence(nodeId)
            clearConvergence()
            return null
        }

        val actions = target.actionsFrom(current)
        if (actions.isEmpty()) return result(controlStep)
        authoritativeControls.apply(actions)
        // Internal state is the control truth after automatic toggles.  Do not
        // wait for animation-corrupted SET templates before releasing the node;
        // visual recognition is an independent desync audit in FrameProcessor.
        runtime.confirmConvergence(nodeId)
        clearConvergence()
        return result(controlStep, actions)
    }

    private fun clearConvergence() {
        convergingNodeId = null
        convergingTarget = null
    }

    private fun latchRoleUbEvents(frame: SwitchFrameInput) {
        frame.triggeredRoles.forEach { role ->
            recentRoleUbEvents[role] = RecentRoleUbEvent(
                wallMs = frame.wallMs,
                clockSeconds = frame.triggeredRoleClockSeconds[role] ?: frame.clockSeconds
            )
        }
        recentRoleUbEvents.entries.removeIf { (_, event) ->
            frame.wallMs - event.wallMs > ROLE_UB_EVENT_TTL_MS
        }
    }

    private fun SwitchFrameInput.withReplayedRoleUbEvents(): SwitchFrameInput {
        if (recentRoleUbEvents.isEmpty()) return this
        val activeNodeTime = runtime.snapshot().nodeId
            ?.let(nodesById::get)
            ?.timeSeconds
        val replayed = recentRoleUbEvents.filterValues { event ->
            event.clockSeconds == clockSeconds ||
                (activeNodeTime != null && event.clockSeconds == activeNodeTime)
        }
        if (replayed.isEmpty()) return this
        return copy(
            triggeredRoles = triggeredRoles + replayed.keys,
            triggeredRoleClockSeconds = triggeredRoleClockSeconds +
                replayed.mapValues { (_, event) -> event.clockSeconds }
        )
    }

    private fun result(
        controlStep: ControlStep = stateMachine.snapshot(),
        controlActions: List<ControlAction> = emptyList()
    ) = SwitchCoordinatorResult(
        controlStep = controlStep.copy(desired = convergingTarget),
        activeNodeId = convergingNodeId ?: pauseFrame?.nodeId ?: runtime.pendingNodeId(),
        pauseFrame = pauseFrame,
        busy = convergingNodeId != null || pauseFrame != null || runtime.pendingNodeId() != null,
        runtime = runtime.snapshot(),
        controlActions = controlActions
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

    private fun OpeningControlTarget.matches(current: BattleControlState): Boolean {
        auto?.let { wanted -> if (current.auto != wanted) return false }
        roles?.forEach { (role, wanted) ->
            if (current.roles.getValue(role) != wanted) return false
        }
        return true
    }

    private companion object {
        const val ROLE_UB_EVENT_TTL_MS = 3000L
    }
}