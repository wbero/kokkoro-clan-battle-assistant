package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlState
import com.kokkoro.clanbattle.control.ControlAction
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
    val runtime: SwitchRuntimeSnapshot,
    val controlActions: List<ControlAction> = emptyList()
)

class SwitchControlCoordinator(
    private val stateMachine: BattleControlStateMachine,
    opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>,
    openingGraceSeconds: Int = 0,
    private val clickIntervalMs: Int = 100
) {
    private var runtime = SwitchAxisRuntime(opening, nodes, openingGraceSeconds)
    private var nodesById = nodes.associateBy(SwitchAxisNode::id)
    private var convergingNodeId: String? = null
    private var convergingTarget: OpeningControlTarget? = null
    private var batchDispatchedAtMs: Long? = null
    private var batchActionCount = 0
    private var batchRetryCount = 0
    private val dispatchedControlActions = mutableSetOf<ControlAction>()
    private val confirmedControlActions = mutableSetOf<ControlAction>()
    private val confirmedActionMismatchFrames = mutableMapOf<ControlAction, Int>()
    private var pauseFrame: SwitchRuntimeCommand.EnterPauseFrame? = null

    // Role UB triggers are single-frame pulses. If safety briefly leaves RUNNING
    // (e.g. a recognition-jitter false pause) during that exact frame, the pulse
    // would otherwise be dropped entirely before it ever reaches SwitchAxisRuntime.
    // Latch each observed role trigger with a wall-clock timestamp and keep
    // replaying it into the frame fed to the runtime until it expires or safety
    // recovers and consumes it.
    private val recentRoleUbEvents = mutableMapOf<CharacterRole, Long>()

    init {
        require(clickIntervalMs >= 1)
        // Switch-axis convergence owns its own batch verification. Leaving a
        // state-machine desired target armed would make updateControls plan an
        // extra single click between two gestures in the batch.
        stateMachine.clearDesired()
    }

    fun update(
        frame: SwitchFrameInput,
        controlStep: ControlStep,
        trustworthyObservation: BattleControlObservation? = null
    ): SwitchCoordinatorResult {
        latchRoleUbEvents(frame)

        // Freeze the switch runtime while safety is taking ownership of the game.
        // Returning the original step also keeps the real pause reason/action in
        // diagnostics instead of replacing it with a generic "snapshot".
        if (controlStep.safety != ControlSafetyState.RUNNING) {
            return result(controlStep)
        }

        if (convergingNodeId != null) {
            advanceConvergence(frame, controlStep, trustworthyObservation)?.let { return it }
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
                convergingTarget = command.target.toControlTarget()
                batchDispatchedAtMs = null
                batchActionCount = 0
                batchRetryCount = 0
                stateMachine.clearDesired()
                if (nodesById[command.nodeId]?.trigger is CharacterUbTrigger) {
                    recentRoleUbEvents.clear()
                }
                advanceConvergence(frame, controlStep, trustworthyObservation) ?: result(controlStep)
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
        nodesById = nodes.associateBy(SwitchAxisNode::id)
        clearConvergence()
        pauseFrame = null
        recentRoleUbEvents.clear()
    }

    private fun advanceConvergence(
        frame: SwitchFrameInput,
        controlStep: ControlStep,
        trustworthyObservation: BattleControlObservation?
    ): SwitchCoordinatorResult? {
        val nodeId = convergingNodeId ?: return null
        val target = convergingTarget ?: return null
        val current = controlStep.observed

        if (current != null && target.matches(current)) {
            runtime.confirmConvergence(nodeId)
            clearConvergence()
            return null
        }

        observeDispatchedActionResults(
            observation = trustworthyObservation,
            target = target,
            allowMismatchConfirmation = controlStep.reason == "no-control-target"
        )

        if (!frame.controlsTrustworthy || current == null) {
            return result(controlStep)
        }

        val dispatchedAt = batchDispatchedAtMs
        if (dispatchedAt == null) {
            val actions = target.actionsFrom(current)
            if (actions.isEmpty()) return result(controlStep)
            markBatchDispatched(frame.wallMs, actions)
            return result(controlStep, actions)
        }

        val confirmationDeadline = dispatchedAt + BATCH_CONFIRM_TIMEOUT_MS +
            (batchActionCount - 1).coerceAtLeast(0) * clickIntervalMs.toLong()
        if (frame.wallMs < confirmationDeadline) {
            return result(controlStep)
        }

        // A UB banner/tail can leave the last trustworthy control snapshot stale.
        // Initial clicks are allowed immediately at the trigger synchronization
        // point, but retries must wait for a normal fresh observation frame.
        if (controlStep.reason != "no-control-target") {
            return result(controlStep)
        }

        val remaining = target.actionsFrom(current)
        val retryable = remaining.filterNot { it in confirmedControlActions }
        if (remaining.isEmpty()) return result(controlStep)
        if (retryable.isEmpty()) return result(controlStep)
        if (batchRetryCount >= BATCH_MAX_RETRIES) {
            val reason = "switch-batch-confirmation-failed:$nodeId"
            stateMachine.forceSafety(reason)
            return result(stateMachine.snapshot(reason))
        }

        batchRetryCount++
        markBatchDispatched(frame.wallMs, retryable)
        return result(controlStep, retryable)
    }

    private fun markBatchDispatched(nowMs: Long, actions: List<ControlAction>) {
        batchDispatchedAtMs = nowMs
        batchActionCount = actions.size
        dispatchedControlActions += actions
    }

    /**
     * Switch batches bypass the sequential state-machine click confirmation, so a UB
     * visual hold can keep [ControlStep.observed] on the pre-click state even after a
     * control has visibly reached its target. Remember that stronger per-control
     * evidence. A brief animation-obscured reverse read must not cause a second tap
     * that would undo the successful first tap.
     *
     * A remembered success is released only after the opposite state is seen on
     * several consecutive normal (non-hold) trustworthy frames. That keeps genuine
     * click failures recoverable without reacting to a one-frame SET badge occlusion.
     */
    private fun observeDispatchedActionResults(
        observation: BattleControlObservation?,
        target: OpeningControlTarget,
        allowMismatchConfirmation: Boolean
    ) {
        val state = observation?.toState() ?: return
        dispatchedControlActions.forEach { action ->
            if (target.actionMatches(action, state)) {
                confirmedControlActions += action
                confirmedActionMismatchFrames.remove(action)
            } else if (action in confirmedControlActions) {
                if (!allowMismatchConfirmation) {
                    confirmedActionMismatchFrames.remove(action)
                    return@forEach
                }
                val frames = (confirmedActionMismatchFrames[action] ?: 0) + 1
                if (frames >= CONFIRMED_ACTION_MISMATCH_FRAMES) {
                    confirmedControlActions -= action
                    confirmedActionMismatchFrames.remove(action)
                } else {
                    confirmedActionMismatchFrames[action] = frames
                }
            }
        }
    }

    private fun clearConvergence() {
        convergingNodeId = null
        convergingTarget = null
        batchDispatchedAtMs = null
        batchActionCount = 0
        batchRetryCount = 0
        dispatchedControlActions.clear()
        confirmedControlActions.clear()
        confirmedActionMismatchFrames.clear()
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

    private fun OpeningControlTarget.actionsFrom(current: BattleControlState): List<ControlAction> {
        val actions = mutableListOf<ControlAction>()
        roles?.let { wantedRoles ->
            val allWantedOn = wantedRoles.values.all { it == VisualToggleState.ON }
            val allWantedOff = wantedRoles.values.all { it == VisualToggleState.OFF }
            when {
                allWantedOn && current.globalSet == VisualToggleState.OFF ->
                    actions += ControlAction.TapGlobalSet
                allWantedOff && current.globalSet == VisualToggleState.ON ->
                    actions += ControlAction.TapGlobalSet
                else -> CharacterRole.entries.forEach { role ->
                    if (current.roles.getValue(role) != wantedRoles.getValue(role)) {
                        actions += ControlAction.TapRole(role)
                    }
                }
            }
        }
        auto?.let { wanted ->
            if (current.auto != wanted) actions += ControlAction.TapAuto
        }
        return actions
    }

    private fun OpeningControlTarget.actionMatches(
        action: ControlAction,
        current: BattleControlState
    ): Boolean {
        return when (action) {
            ControlAction.TapAuto -> auto == null || current.auto == auto
            ControlAction.TapGlobalSet -> {
                val wantedRoles = roles ?: return true
                val wantedGlobal = when {
                    wantedRoles.values.all { it == VisualToggleState.ON } -> VisualToggleState.ON
                    wantedRoles.values.all { it == VisualToggleState.OFF } -> VisualToggleState.OFF
                    else -> return false
                }
                current.globalSet == wantedGlobal &&
                    CharacterRole.entries.all { role -> current.roles.getValue(role) == wantedRoles.getValue(role) }
            }
            is ControlAction.TapRole -> roles?.get(action.role) == current.roles.getValue(action.role)
            ControlAction.None,
            ControlAction.TapMenu -> true
        }
    }

    private fun BattleControlObservation.toState() = BattleControlState(
        auto = auto.state,
        globalSet = globalSet.state,
        roles = roles.mapValues { it.value.state }
    )

    private companion object {
        const val ROLE_UB_EVENT_TTL_MS = 3000L
        const val BATCH_CONFIRM_TIMEOUT_MS = 1000L
        const val BATCH_MAX_RETRIES = 1
        const val CONFIRMED_ACTION_MISMATCH_FRAMES = 3
    }
}