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
    private val confirmationFrames: Int = 2,
    /**
     * 一帧内多个角色 SET 同时变化通常是识别抖动，但游戏本身也会同时清除多个
     * SET（例如全局 SET 被消耗）。因此这类跳变只是**需要更多**连续确认帧，
     * 不能永久拒绝：SET 徽标的呼吸动画会让稳定状态一次掉一个角色地退到全关，
     * 而恢复时两个角色同时出现，若永久拒绝就再也回不去，稳定状态被永久锁死。
     */
    private val implausibleConfirmationFrames: Int = 3
) {
    private var stable: BattleControlObservation? = null
    private var pending: BattleControlObservation? = null
    private var pendingFrames = 0
    private var implausible: BattleControlObservation? = null
    private var implausibleFrames = 0

    init {
        require(confirmationFrames >= 1)
        require(implausibleConfirmationFrames >= confirmationFrames)
    }

    fun reset() {
        stable = null
        clearPending()
        clearImplausible()
    }

    fun missing(): FilteredControlObservation {
        clearPending()
        clearImplausible()
        return FilteredControlObservation(stable, ControlObservationStatus.MISSING)
    }

    fun update(raw: BattleControlObservation): FilteredControlObservation {
        if (!raw.isTrustworthy()) {
            clearPending()
            clearImplausible()
            return FilteredControlObservation(stable, ControlObservationStatus.RAW_UNTRUSTWORTHY)
        }

        val current = stable
        if (current != null && raw.sameState(current)) {
            stable = raw
            clearPending()
            clearImplausible()
            return FilteredControlObservation(raw, ControlObservationStatus.TRUSTWORTHY)
        }

        if (current != null && !isPlausibleTransition(current, raw)) {
            clearPending()
            if (implausible?.sameState(raw) == true) {
                implausibleFrames++
            } else {
                implausible = raw
                implausibleFrames = 1
            }
            if (implausibleFrames < implausibleConfirmationFrames) {
                return FilteredControlObservation(current, ControlObservationStatus.IMPLAUSIBLE_TRANSITION)
            }
            // 同一个"不合理"状态已经连续出现足够多帧，画面确实变了，接受它。
            stable = raw
            clearImplausible()
            return FilteredControlObservation(raw, ControlObservationStatus.TRUSTWORTHY)
        }

        clearImplausible()
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

    private fun clearImplausible() {
        implausible = null
        implausibleFrames = 0
    }
}
