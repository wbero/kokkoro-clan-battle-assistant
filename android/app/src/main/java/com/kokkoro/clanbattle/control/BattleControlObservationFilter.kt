package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.CharacterRole

enum class ControlObservationStatus {
    TRUSTWORTHY,
    PENDING_CONFIRMATION,
    RAW_UNTRUSTWORTHY,
    IMPLAUSIBLE_TRANSITION,
    MISSING
}

data class FilteredControlObservation(
    val observation: BattleControlObservation?,
    val status: ControlObservationStatus
) {
    val trustworthy: Boolean get() = status == ControlObservationStatus.TRUSTWORTHY
}

class BattleControlObservationFilter(
    private val confirmationFrames: Int = 2
) {
    private var stable: BattleControlObservation? = null
    private var pending: BattleControlObservation? = null
    private var pendingFrames = 0

    init {
        require(confirmationFrames >= 1)
    }

    fun reset() {
        stable = null
        clearPending()
    }

    fun missing(): FilteredControlObservation {
        clearPending()
        return FilteredControlObservation(stable, ControlObservationStatus.MISSING)
    }

    fun update(raw: BattleControlObservation): FilteredControlObservation {
        if (!raw.isTrustworthy()) {
            clearPending()
            return FilteredControlObservation(stable, ControlObservationStatus.RAW_UNTRUSTWORTHY)
        }

        val current = stable
        if (current != null && raw.sameState(current)) {
            stable = raw
            clearPending()
            return FilteredControlObservation(raw, ControlObservationStatus.TRUSTWORTHY)
        }

        if (current != null && !isPlausibleTransition(current, raw)) {
            clearPending()
            return FilteredControlObservation(current, ControlObservationStatus.IMPLAUSIBLE_TRANSITION)
        }

        if (pending?.sameState(raw) == true) {
            pendingFrames++
        } else {
            pending = raw
            pendingFrames = 1
        }

        if (pendingFrames < confirmationFrames) {
            return FilteredControlObservation(current, ControlObservationStatus.PENDING_CONFIRMATION)
        }

        stable = raw
        clearPending()
        return FilteredControlObservation(raw, ControlObservationStatus.TRUSTWORTHY)
    }

    private fun isPlausibleTransition(
        previous: BattleControlObservation,
        next: BattleControlObservation
    ): Boolean {
        val changedRoles = CharacterRole.entries.count { role ->
            previous.roles.getValue(role).state != next.roles.getValue(role).state
        }
        if (changedRoles <= 1) return true
        if (previous.globalSet.state == next.globalSet.state) return false
        return next.roles.values.all { it.state == next.globalSet.state }
    }

    private fun BattleControlObservation.sameState(other: BattleControlObservation): Boolean =
        auto.state == other.auto.state &&
            globalSet.state == other.globalSet.state &&
            CharacterRole.entries.all { role ->
                roles.getValue(role).state == other.roles.getValue(role).state
            }

    private fun BattleControlObservation.isTrustworthy(): Boolean =
        consistent &&
            auto.state != VisualToggleState.UNKNOWN &&
            globalSet.state != VisualToggleState.UNKNOWN &&
            roles.keys == CharacterRole.entries.toSet() &&
            roles.values.none { it.state == VisualToggleState.UNKNOWN }

    private fun clearPending() {
        pending = null
        pendingFrames = 0
    }
}
