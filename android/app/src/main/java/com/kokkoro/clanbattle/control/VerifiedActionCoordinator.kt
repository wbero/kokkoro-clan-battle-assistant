package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.recognition.CharacterRole
import java.util.ArrayDeque

data class CoordinatedActionStep(
    val controlStep: ControlStep,
    val immediateEvents: List<AxisEvent>,
    val busy: Boolean,
    val newControlAction: ControlAction = ControlAction.None,
    val phase: String? = null,
    val activeEvent: AxisEvent? = null,
    val nextEvent: AxisEvent? = null
)

class VerifiedActionCoordinator(
    private val stateMachine: BattleControlStateMachine,
    private val authoritativeControls: AuthoritativeControlState = AuthoritativeControlState()
) {
    private enum class ActivePhase {
        STARTING,
        CONFIRMING_GENERIC,
        WAITING_ROLE_UB
    }

    private val queue = ArrayDeque<AxisEvent>()
    private val alreadySetActionIds = mutableSetOf<String>()
    private var activeControl: AxisEvent? = null
    private var activePhase: ActivePhase? = null
    private var activeRoleUbObserved = false
    private val pendingTargetActions = ArrayDeque<ControlAction>()
    private val recentRoleUbAtMs = mutableMapOf<CharacterRole, Long>()
    private val recentRoleUbClockSeconds = mutableMapOf<CharacterRole, Int>()
    private val recoveredRoleUbActionIds = mutableSetOf<String>()
    private var roleAliases: Map<String, CharacterRole> = defaultRoleAliases()

    fun observeFrame(
        triggeredRoles: Set<CharacterRole>,
        clockSeconds: Int?,
        nowMs: Long
    ) {
        triggeredRoles.forEach { role ->
            recentRoleUbAtMs[role] = nowMs
            clockSeconds?.let { recentRoleUbClockSeconds[role] = it }
        }
        val event = activeControl ?: return
        val action = event.actions.single()
        if (
            action.type == ActionType.CLICK_ROLE &&
            activePhase in ROLE_UB_OBSERVING_PHASES &&
            roleFromName(action.role) in triggeredRoles
        ) {
            activeRoleUbObserved = true
        }
    }

    fun configureRoleAliases(header: Map<String, String>) {
        roleAliases = buildMap {
            putAll(defaultRoleAliases())
            CharacterRole.entries.forEach { role ->
                header["角色${role.ordinal + 1}"]
                    ?.takeIf(String::isNotBlank)
                    ?.let { alias -> put(alias, role) }
            }
        }
    }

    fun enqueue(
        events: List<AxisEvent>,
        rolesAlreadySet: Set<CharacterRole> = emptySet(),
        nowMs: Long? = null
    ) {
        events.forEach { event ->
            event.actions.forEachIndexed { index, action ->
                val actionEvent = event.copy(id = "${event.id}:$index", actions = listOf(action))
                queue.addLast(actionEvent)
                if (
                    action.type == ActionType.CLICK_ROLE &&
                    roleFromName(action.role) in rolesAlreadySet
                ) {
                    alreadySetActionIds += actionEvent.id
                    val role = roleFromName(action.role)
                    val recentUbAt = role?.let(recentRoleUbAtMs::get)
                    val recentUbClock = role?.let(recentRoleUbClockSeconds::get)
                    if (
                        role != null &&
                        recentUbAt != null &&
                        nowMs != null &&
                        (recentUbClock == null || recentUbClock == event.timeSeconds) &&
                        nowMs - recentUbAt in 0..RECENT_ROLE_UB_WINDOW_MS
                    ) {
                        recoveredRoleUbActionIds += actionEvent.id
                    }
                }
            }
        }
    }

    fun update(
        latest: ControlStep,
        nowMs: Long,
        triggeredRoles: Set<CharacterRole> = emptySet(),
        clockSeconds: Int? = null
    ): CoordinatedActionStep {
        authoritativeControls.seedIfAbsent(latest.observed)
        observeFrame(triggeredRoles, clockSeconds, nowMs)
        if (latest.safety != ControlSafetyState.RUNNING) {
            if (activeControl != null) {
                activePhase = ActivePhase.STARTING
                activeRoleUbObserved = false
                pendingTargetActions.clear()
            }
            return result(latest, emptyList(), busy = true)
        }

        val active = activeControl
        if (active != null) {
            return advance(active, latest, triggeredRoles, emptyList())
        }

        val immediate = mutableListOf<AxisEvent>()
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!next.actions.single().type.isVerifiedControlAction()) {
                immediate += next
                continue
            }
            activeControl = next
            activePhase = ActivePhase.STARTING
            activeRoleUbObserved = false
            return advance(next, latest, triggeredRoles, immediate)
        }
        return result(latest, immediate, busy = false)
    }

    fun isBusy(): Boolean = activeControl != null || queue.isNotEmpty()

    fun isRoleLifecycleBusy(): Boolean =
        activeControl?.actions?.singleOrNull()?.type == ActionType.CLICK_ROLE

    fun reset() {
        queue.clear()
        alreadySetActionIds.clear()
        activeControl = null
        activePhase = null
        activeRoleUbObserved = false
        pendingTargetActions.clear()
        recentRoleUbAtMs.clear()
        recentRoleUbClockSeconds.clear()
        recoveredRoleUbActionIds.clear()
    }

    private fun advance(
        event: AxisEvent,
        latest: ControlStep,
        triggeredRoles: Set<CharacterRole>,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep = when (event.actions.single().type) {
        ActionType.CLICK_ROLE -> advanceRole(
            event, latest, triggeredRoles, immediate
        )
        else -> advanceGeneric(event, latest, immediate)
    }

    private fun advanceRole(
        event: AxisEvent,
        latest: ControlStep,
        triggeredRoles: Set<CharacterRole>,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val action = event.actions.single()
        val role = requireNotNull(roleFromName(action.role)) { "非法角色：${action.role}" }
        return when (activePhase ?: ActivePhase.STARTING) {
            ActivePhase.STARTING -> {
                if (alreadySetActionIds.remove(event.id)) {
                    authoritativeControls.assumeRole(role, VisualToggleState.ON)
                    activePhase = ActivePhase.WAITING_ROLE_UB
                    val ubDetected = recoveredRoleUbActionIds.remove(event.id) || hasRoleUb(role, triggeredRoles)
                    if (ubDetected) {
                        return requestRoleOffAfterRelease(role, immediate)
                    }
                    return result(latest, immediate, busy = true)
                }
                val internal = authoritativeControls.snapshot()
                    ?: return result(latest, immediate, busy = true)
                activePhase = ActivePhase.WAITING_ROLE_UB

                if (internal.roles.getValue(role) == VisualToggleState.ON) {
                    val ubDetected = hasRoleUb(role, triggeredRoles)
                    return if (ubDetected) {
                        requestRoleOffAfterRelease(role, immediate)
                    } else {
                        result(latest, immediate, busy = true)
                    }
                }

                val tapOn = ControlAction.TapRole(role)
                authoritativeControls.apply(tapOn)
                val ubDetected = hasRoleUb(role, triggeredRoles)
                if (ubDetected) {
                    // The ON tap is already queued first.  Keep waiting one frame
                    // before cleanup so the action executor preserves ON -> OFF.
                    activeRoleUbObserved = true
                }
                result(latest, immediate, busy = true, newControlAction = tapOn)
            }
            ActivePhase.WAITING_ROLE_UB -> {
                val ubDetected = hasRoleUb(role, triggeredRoles)
                if (!ubDetected) return result(latest, immediate, busy = true)
                requestRoleOffAfterRelease(role, immediate)
            }
            ActivePhase.CONFIRMING_GENERIC -> error("角色动作进入了非法阶段")
        }
    }

    /** Discard UB evidence captured before a pause-frame menu interaction. */
    fun clearRecentRoleUb(role: CharacterRole) {
        recentRoleUbAtMs.remove(role)
        recentRoleUbClockSeconds.remove(role)
    }

    /** Re-arm the current lifecycle after recognition was intentionally paused. */
    fun restartAfterRecognitionPause() {
        if (activeControl != null) activePhase = ActivePhase.STARTING
        activeRoleUbObserved = false
        recentRoleUbAtMs.clear()
        recentRoleUbClockSeconds.clear()
        recoveredRoleUbActionIds.clear()
        pendingTargetActions.clear()
    }

    private fun requestRoleOff(
        role: CharacterRole,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val internal = authoritativeControls.snapshot()
            ?: return result(stateMachine.snapshot(), immediate, busy = true)
        if (internal.roles.getValue(role) == VisualToggleState.OFF) {
            return completeActive(immediate)
        }
        val tapOff = ControlAction.TapRole(role)
        authoritativeControls.apply(tapOff)
        // The physical OFF tap is queued now.  The lifecycle may complete
        // immediately because subsequent automatic taps share one ordered action
        // queue; visual templates only audit the predicted state afterwards.
        return completeActive(immediate, newControlAction = tapOff)
    }

    private fun requestRoleOffAfterRelease(
        role: CharacterRole,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        activeRoleUbObserved = false
        // Skill-name recognition proves the role released UB.  Its SET must have
        // been ON for that lifecycle even if the animation hides the badge.
        authoritativeControls.assumeRole(role, VisualToggleState.ON)
        return requestRoleOff(role, immediate)
    }

    private fun advanceGeneric(
        event: AxisEvent,
        latest: ControlStep,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val action = event.actions.single()
        if (activePhase == ActivePhase.CONFIRMING_GENERIC) {
            val next = if (pendingTargetActions.isEmpty()) null else pendingTargetActions.removeFirst()
            if (next == null) return completeActive(immediate)
            authoritativeControls.apply(next)
            return if (pendingTargetActions.isEmpty()) {
                completeActive(immediate, newControlAction = next)
            } else {
                result(latest, immediate, busy = true, newControlAction = next)
            }
        }

        val internal = authoritativeControls.snapshot()
            ?: return result(latest, immediate, busy = true)
        return when (action.type) {
            ActionType.CLICK_AUTO -> {
                val tap = ControlAction.TapAuto
                authoritativeControls.apply(tap)
                completeActive(immediate, newControlAction = tap)
            }
            ActionType.TOGGLE_AUTO, ActionType.SET_ROLES -> {
                pendingTargetActions.clear()
                pendingTargetActions.addAll(
                    requireNotNull(OpeningControlTarget.fromAction(action)).actionsFrom(internal)
                )
                val first = if (pendingTargetActions.isEmpty()) null else pendingTargetActions.removeFirst()
                if (first == null) {
                    completeActive(immediate)
                } else {
                    authoritativeControls.apply(first)
                    if (pendingTargetActions.isEmpty()) {
                        completeActive(immediate, newControlAction = first)
                    } else {
                        activePhase = ActivePhase.CONFIRMING_GENERIC
                        result(latest, immediate, busy = true, newControlAction = first)
                    }
                }
            }
            else -> result(latest, immediate, busy = true)
        }
    }

    private fun completeActive(
        immediate: List<AxisEvent>,
        newControlAction: ControlAction = ControlAction.None
    ): CoordinatedActionStep {
        val completed = activeControl
        if (completed?.actions?.single()?.type?.isTargetAction() == true) stateMachine.clearDesired()
        activeControl = null
        activePhase = null
        activeRoleUbObserved = false
        completed?.id?.let(recoveredRoleUbActionIds::remove)
        return result(
            stateMachine.snapshot(),
            immediate,
            busy = queue.isNotEmpty(),
            newControlAction = newControlAction
        )
    }

    fun seedControlState(state: BattleControlState?) {
        authoritativeControls.seedIfAbsent(state)
    }

    private fun result(
        step: ControlStep,
        immediate: List<AxisEvent>,
        busy: Boolean,
        newControlAction: ControlAction = ControlAction.None
    ) = CoordinatedActionStep(
        controlStep = step,
        immediateEvents = immediate,
        busy = busy,
        newControlAction = newControlAction,
        phase = activePhase?.name,
        activeEvent = activeControl,
        nextEvent = queue.firstOrNull()
    )

    private fun roleFromName(name: String?): CharacterRole? = roleAliases[name]

    /** Skill-name recognition is authoritative for sequence role identity. */
    private fun hasRoleUb(role: CharacterRole, triggeredRoles: Set<CharacterRole>): Boolean =
        activeRoleUbObserved || role in triggeredRoles

    private fun defaultRoleAliases(): Map<String, CharacterRole> =
        CharacterRole.entries.associateBy { role -> "角色${role.ordinal + 1}" }

    private fun ActionType.isVerifiedControlAction(): Boolean = when (this) {
        ActionType.CLICK_ROLE, ActionType.CLICK_AUTO, ActionType.TOGGLE_AUTO, ActionType.SET_ROLES -> true
        ActionType.NOTIFY, ActionType.BOSS -> false
    }

    private fun ActionType.isTargetAction(): Boolean =
        this == ActionType.TOGGLE_AUTO || this == ActionType.SET_ROLES

    private companion object {
        const val RECENT_ROLE_UB_WINDOW_MS = 3_000L
        val ROLE_UB_OBSERVING_PHASES = setOf(ActivePhase.WAITING_ROLE_UB)
    }
}