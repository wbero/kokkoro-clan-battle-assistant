package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.CharacterRole

class BattleControlStateMachine {
    private var observed: BattleControlState? = null
    private var desired: OpeningControlTarget? = null
    private var expected: BattleControlState? = null
    private var pendingAction: ControlAction? = null
    private var actionStartedMs = 0L
    private var confirmationFrames = 0
    private var retryCount = 0
    private var safety = ControlSafetyState.RUNNING
    private var pauseReason: String? = null
    private var recoveryFrames = 0
    private var inconsistentFrames = 0

    fun setDesired(target: OpeningControlTarget?) {
        desired = target
    }

    fun clearDesired() {
        desired = null
    }

    fun requestToggle(action: ControlAction, nowMs: Long): ControlStep {
        require(action == ControlAction.TapAuto || action is ControlAction.TapRole) {
            "只支持需要图像确认的单项切换"
        }
        if (safety != ControlSafetyState.RUNNING) return step(ControlAction.None, "safety-paused")
        if (pendingAction != null) return step(ControlAction.None, "control-action-busy")
        val current = observed ?: return step(ControlAction.None, "waiting-trustworthy-state")
        if (action == ControlAction.TapAuto && current.auto == VisualToggleState.UNKNOWN) {
            return step(ControlAction.None, "waiting-trustworthy-state")
        }
        if (action is ControlAction.TapRole && current.roles.getValue(action.role) == VisualToggleState.UNKNOWN) {
            return step(ControlAction.None, "waiting-trustworthy-state")
        }
        desired = null
        return begin(action, current, nowMs)
    }

    fun requestRoleSet(role: CharacterRole, nowMs: Long): ControlStep {
        return requestRoleState(role, VisualToggleState.ON, nowMs)
    }

    fun requestRoleState(
        role: CharacterRole,
        wanted: VisualToggleState,
        nowMs: Long
    ): ControlStep {
        require(wanted != VisualToggleState.UNKNOWN) { "角色目标状态不能为未知" }
        if (safety != ControlSafetyState.RUNNING) return step(ControlAction.None, "safety-paused")
        if (pendingAction != null) return step(ControlAction.None, "control-action-busy")
        val current = observed ?: return step(ControlAction.None, "waiting-trustworthy-state")
        return when (current.roles.getValue(role)) {
            VisualToggleState.UNKNOWN -> step(ControlAction.None, "waiting-trustworthy-state")
            wanted -> step(
                ControlAction.None,
                if (wanted == VisualToggleState.ON) "role-set-already-on" else "role-set-already-off",
                confirmed = true
            )
            else -> {
                desired = null
                begin(ControlAction.TapRole(role), current, nowMs)
            }
        }
    }

    /**
     * A matching TP drop proves that a pending role-SET click took effect even when the
     * breathing badge disappears under the UB animation. Seed the expected ON state so
     * the coordinator can immediately issue the cleanup OFF click.
     */
    fun acknowledgeRoleSetByUb(role: CharacterRole): ControlStep {
        val pending = pendingAction
        val target = expected
        if (
            pending != ControlAction.TapRole(role) ||
            target?.roles?.get(role) != VisualToggleState.ON
        ) {
            return step(ControlAction.None, "no-pending-role-set")
        }
        observed = target
        clearPending()
        return step(ControlAction.None, "role-set-confirmed-by-ub", confirmed = true)
    }

    /** A TP drop proves the role has just used UB. Treat its SET as ON before cleanup so
     * an animation-obscured OFF observation cannot suppress the required closing tap. */
    fun assumeRoleSetOnFromUb(role: CharacterRole): ControlStep {
        if (pendingAction != null) return step(ControlAction.None, "control-action-busy")
        val current = observed ?: return step(ControlAction.None, "waiting-trustworthy-state")
        val roles = current.roles.toMutableMap().apply { this[role] = VisualToggleState.ON }
        observed = current.copy(
            globalSet = if (roles.values.all { it == VisualToggleState.ON }) {
                VisualToggleState.ON
            } else {
                VisualToggleState.OFF
            },
            roles = roles
        )
        return step(ControlAction.None, "role-set-assumed-on-from-ub", confirmed = true)
    }

    /** Stops waiting for an ON badge after the axis time has passed. [observed] keeps the
     * latest trustworthy visual state, so the caller can turn the role off only when needed. */
    fun cancelPendingRoleSetConfirmation(role: CharacterRole): ControlStep {
        val pending = pendingAction
        val target = expected
        if (
            pending != ControlAction.TapRole(role) ||
            target?.roles?.get(role) != VisualToggleState.ON
        ) {
            return step(ControlAction.None, "no-pending-role-set")
        }
        clearPending()
        return step(ControlAction.None, "role-set-confirmation-cancelled", confirmed = true)
    }

    fun update(observation: BattleControlObservation, nowMs: Long): ControlStep {
        if (safety != ControlSafetyState.RUNNING) return step(ControlAction.None, pauseReason ?: "safety-paused")
        if (!observation.consistent) {
            inconsistentFrames++
            if (inconsistentFrames < INCONSISTENT_CONFIRM_FRAMES) {
                return step(ControlAction.None, "confirming-inconsistent-state")
            }
            forceSafety(observation.reason ?: "inconsistent-control-state")
            return step(ControlAction.None, observation.reason ?: "inconsistent-control-state")
        }
        inconsistentFrames = 0
        val current = observation.toState()
        // Keep the freshest trustworthy state even while a click is pending. This lets
        // axis-time fallback inspect the actual SET badge before deciding whether to tap.
        observed = current
        val pending = pendingAction
        if (pending != null) {
            if (matchesExpected(current, pending, requireNotNull(expected))) {
                confirmationFrames++
                if (confirmationFrames < CONFIRM_FRAMES) {
                    return step(ControlAction.None, "confirming-click")
                }
                clearPending()
                return plan(current, nowMs)
            }
            confirmationFrames = 0
            val confirmingRoleSetOn = pending is ControlAction.TapRole &&
                expected?.roles?.get(pending.role) == VisualToggleState.ON
            // UB animations can hide the ON badge for several seconds. The sequence
            // coordinator resolves this pending action from TP drop or axis-time progress.
            if (confirmingRoleSetOn) {
                return step(ControlAction.None, "waiting-role-set-ub-or-clock")
            }
            if (nowMs - actionStartedMs >= CONFIRM_TIMEOUT_MS) {
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    actionStartedMs = nowMs
                    return step(pending, "retry-click")
                }
                forceSafety("click-confirmation-failed")
                return step(ControlAction.None, "click-confirmation-failed")
            }
            return step(ControlAction.None, "waiting-click-confirmation")
        }

        observed = current
        return plan(current, nowMs)
    }

    fun snapshot(reason: String = "snapshot"): ControlStep = step(ControlAction.None, reason)

    /** Updates the displayed observation without planning clicks or changing safety state. */
    fun observeOnly(observation: BattleControlObservation): ControlStep {
        if (observation.consistent) observed = observation.toState()
        return step(
            ControlAction.None,
            if (observation.consistent) "observation-only" else observation.reason ?: "inconsistent-observation"
        )
    }

    fun forceSafety(reason: String) {
        safety = ControlSafetyState.SAFETY_PAUSING
        pauseReason = reason
        recoveryFrames = 0
    }

    fun updateMenu(menuScore: Double): ControlStep {
        if (safety == ControlSafetyState.SAFETY_PAUSED) {
            return step(ControlAction.None, pauseReason ?: "safety-paused")
        }
        if (safety != ControlSafetyState.SAFETY_PAUSING) {
            return step(ControlAction.None, "safety-not-requested")
        }
        if (menuScore < MENU_MIN_SCORE) {
            return step(ControlAction.None, "menu-button-untrusted")
        }
        safety = ControlSafetyState.SAFETY_PAUSED
        return step(ControlAction.TapMenu, pauseReason ?: "safety-pause")
    }

    fun updateRecovery(
        menuButtonScore: Double,
        observation: BattleControlObservation,
        nowMs: Long
    ): ControlStep {
        if (safety != ControlSafetyState.SAFETY_PAUSED) {
            return step(ControlAction.None, "not-safety-paused")
        }
        if (menuButtonScore < MENU_MIN_SCORE || !observation.isTrustworthy()) {
            recoveryFrames = 0
            return step(ControlAction.None, pauseReason ?: "waiting-manual-recovery")
        }
        recoveryFrames++
        if (recoveryFrames < RECOVERY_FRAMES) {
            return step(ControlAction.None, "confirming-manual-recovery")
        }

        safety = ControlSafetyState.RUNNING
        pauseReason = null
        recoveryFrames = 0
        inconsistentFrames = 0
        clearPending()
        val current = observation.toState()
        observed = current
        return plan(current, nowMs)
    }

    fun reset() {
        observed = null
        desired = null
        expected = null
        pendingAction = null
        actionStartedMs = 0L
        confirmationFrames = 0
        retryCount = 0
        safety = ControlSafetyState.RUNNING
        pauseReason = null
        recoveryFrames = 0
        inconsistentFrames = 0
    }

    private fun plan(current: BattleControlState, nowMs: Long): ControlStep {
        val target = desired ?: return step(ControlAction.None, "no-control-target", confirmed = true)
        target.auto?.let { wanted ->
            if (current.auto == VisualToggleState.UNKNOWN) {
                return step(ControlAction.None, "waiting-trustworthy-state")
            }
            if (current.auto != wanted) return begin(ControlAction.TapAuto, current, nowMs)
        }

        target.roles?.let { wantedRoles ->
            require(wantedRoles.keys == CharacterRole.entries.toSet())
            if (current.globalSet == VisualToggleState.UNKNOWN ||
                current.roles.values.any { it == VisualToggleState.UNKNOWN }
            ) return step(ControlAction.None, "waiting-trustworthy-state")

            val allWantedOn = wantedRoles.values.all { it == VisualToggleState.ON }
            val allWantedOff = wantedRoles.values.all { it == VisualToggleState.OFF }
            when {
                allWantedOn && current.globalSet != VisualToggleState.ON ->
                    return begin(ControlAction.TapGlobalSet, current, nowMs)

                allWantedOff && current.globalSet == VisualToggleState.ON ->
                    return begin(ControlAction.TapGlobalSet, current, nowMs)

                else -> {
                    val mismatch = CharacterRole.entries.firstOrNull { role ->
                        current.roles.getValue(role) != wantedRoles.getValue(role)
                    }
                    if (mismatch != null) return begin(ControlAction.TapRole(mismatch), current, nowMs)
                }
            }
        }
        return step(ControlAction.None, "control-target-confirmed", confirmed = true)
    }

    private fun begin(action: ControlAction, current: BattleControlState, nowMs: Long): ControlStep {
        pendingAction = action
        expected = expectedAfter(current, action)
        actionStartedMs = nowMs
        confirmationFrames = 0
        retryCount = 0
        return step(action, "control-click")
    }

    private fun expectedAfter(current: BattleControlState, action: ControlAction): BattleControlState = when (action) {
        ControlAction.TapAuto -> current.copy(auto = current.auto.toggled())
        ControlAction.TapGlobalSet -> {
            val turnOn = current.globalSet == VisualToggleState.OFF
            val state = if (turnOn) VisualToggleState.ON else VisualToggleState.OFF
            current.copy(globalSet = state, roles = CharacterRole.entries.associateWith { state })
        }
        is ControlAction.TapRole -> {
            val newRoles = current.roles.toMutableMap().apply {
                this[action.role] = getValue(action.role).toggled()
            }
            current.copy(
                globalSet = if (newRoles.values.all { it == VisualToggleState.ON }) {
                    VisualToggleState.ON
                } else {
                    VisualToggleState.OFF
                },
                roles = newRoles
            )
        }
        ControlAction.None, ControlAction.TapMenu -> current
    }

    private fun matchesExpected(
        current: BattleControlState,
        action: ControlAction,
        target: BattleControlState
    ): Boolean = when (action) {
        ControlAction.TapAuto -> current.auto == target.auto
        ControlAction.TapGlobalSet ->
            current.globalSet == target.globalSet && current.roles == target.roles
        is ControlAction.TapRole -> current.roles.getValue(action.role) == target.roles.getValue(action.role)
        ControlAction.None, ControlAction.TapMenu -> false
    }

    private fun clearPending() {
        expected = null
        pendingAction = null
        confirmationFrames = 0
        retryCount = 0
    }

    private fun step(action: ControlAction, reason: String, confirmed: Boolean = false) = ControlStep(
        action = action,
        reason = reason,
        observed = observed,
        desired = desired,
        expected = expected,
        safety = safety,
        retryCount = retryCount,
        confirmed = confirmed
    )

    private fun BattleControlObservation.toState() = BattleControlState(
        auto = auto.state,
        globalSet = globalSet.state,
        roles = roles.mapValues { it.value.state }
    )

    private fun BattleControlObservation.isTrustworthy(): Boolean =
        consistent && auto.state != VisualToggleState.UNKNOWN &&
            globalSet.state != VisualToggleState.UNKNOWN &&
            roles.values.none { it.state == VisualToggleState.UNKNOWN }

    private fun VisualToggleState.toggled(): VisualToggleState = when (this) {
        VisualToggleState.ON -> VisualToggleState.OFF
        VisualToggleState.OFF -> VisualToggleState.ON
        VisualToggleState.UNKNOWN -> error("不能切换未知状态")
    }

    private companion object {
        const val CONFIRM_FRAMES = 2
        const val CONFIRM_TIMEOUT_MS = 1_000L
        const val MAX_RETRIES = 1
        const val MENU_MIN_SCORE = 0.70
        const val RECOVERY_FRAMES = 2
        const val INCONSISTENT_CONFIRM_FRAMES = 3
    }
}
