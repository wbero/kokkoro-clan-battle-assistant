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

    fun setDesired(target: OpeningControlTarget?) {
        desired = target
    }

    fun update(observation: BattleControlObservation, nowMs: Long): ControlStep {
        if (safety != ControlSafetyState.RUNNING) return step(ControlAction.None, pauseReason ?: "safety-paused")
        if (!observation.consistent) {
            forceSafety(observation.reason ?: "inconsistent-control-state")
            return step(ControlAction.None, observation.reason ?: "inconsistent-control-state")
        }
        val current = observation.toState()
        val pending = pendingAction
        if (pending != null) {
            if (current == expected) {
                confirmationFrames++
                if (confirmationFrames < CONFIRM_FRAMES) {
                    return step(ControlAction.None, "confirming-click")
                }
                observed = current
                clearPending()
                return plan(current, nowMs)
            }
            confirmationFrames = 0
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

    fun snapshot(): ControlStep = step(ControlAction.None, "snapshot")

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
        const val CONFIRM_TIMEOUT_MS = 500L
        const val MAX_RETRIES = 1
        const val MENU_MIN_SCORE = 0.70
        const val RECOVERY_FRAMES = 2
    }
}
