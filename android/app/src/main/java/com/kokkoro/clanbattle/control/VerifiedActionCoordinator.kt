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
    roleSetFallbackGraceMs: Long = 0
) {
    private enum class ActivePhase {
        STARTING,
        CONFIRMING_GENERIC,
        CONFIRMING_ROLE_ON,
        WAITING_ROLE_UB,
        CONFIRMING_ROLE_OFF
    }

    private val queue = ArrayDeque<AxisEvent>()
    private val alreadySetActionIds = mutableSetOf<String>()
    private var activeControl: AxisEvent? = null
    private var activePhase: ActivePhase? = null
    private val roleSetFallbackWatchdog = RoleSetFallbackWatchdog(roleSetFallbackGraceMs)
    private var activeRoleUbObserved = false
    private var activeRoleTpBelowThresholdObserved = false
    private val recentRoleUbAtMs = mutableMapOf<CharacterRole, Long>()
    private val recoveredRoleUbActionIds = mutableSetOf<String>()
    private var roleAliases: Map<String, CharacterRole> = defaultRoleAliases()

    fun configureRoleSetFallbackGraceMs(value: Long) {
        roleSetFallbackWatchdog.configureGraceMs(value)
    }

    fun observeFrame(
        triggeredRoles: Set<CharacterRole>,
        clockSeconds: Int?,
        nowMs: Long,
        tpBelowThresholdRoles: Set<CharacterRole> = emptySet()
    ) {
        roleSetFallbackWatchdog.observeClock(clockSeconds, nowMs)
        triggeredRoles.forEach { recentRoleUbAtMs[it] = nowMs }
        val event = activeControl ?: return
        val action = event.actions.single()
        if (
            action.type == ActionType.CLICK_ROLE &&
            activePhase in ROLE_UB_OBSERVING_PHASES &&
            roleFromName(action.role) in triggeredRoles
        ) {
            activeRoleUbObserved = true
        }
        if (
            action.type == ActionType.CLICK_ROLE &&
            activePhase in ROLE_UB_OBSERVING_PHASES &&
            roleFromName(action.role) in tpBelowThresholdRoles
        ) {
            activeRoleTpBelowThresholdObserved = true
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
                    if (
                        role != null &&
                        recentUbAt != null &&
                        nowMs != null &&
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
        clockSeconds: Int? = null,
        tpBelowThresholdRoles: Set<CharacterRole> = emptySet()
    ): CoordinatedActionStep {
        observeFrame(triggeredRoles, clockSeconds, nowMs, tpBelowThresholdRoles)
        if (latest.safety != ControlSafetyState.RUNNING) {
            if (activeControl != null) {
                activePhase = ActivePhase.STARTING
                activeRoleUbObserved = false
                activeRoleTpBelowThresholdObserved = false
                roleSetFallbackWatchdog.cancel()
            }
            return result(latest, emptyList(), busy = true)
        }

        val active = activeControl
        if (active != null) {
            return advance(active, latest, nowMs, triggeredRoles, emptyList())
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
            activeRoleTpBelowThresholdObserved = false
            return advance(next, latest, nowMs, triggeredRoles, immediate)
        }
        return result(latest, immediate, busy = false)
    }

    fun isBusy(): Boolean = activeControl != null || queue.isNotEmpty()

    fun reset() {
        queue.clear()
        alreadySetActionIds.clear()
        activeControl = null
        activePhase = null
        activeRoleUbObserved = false
        activeRoleTpBelowThresholdObserved = false
        recentRoleUbAtMs.clear()
        recoveredRoleUbActionIds.clear()
        roleSetFallbackWatchdog.reset()
    }

    private fun advance(
        event: AxisEvent,
        latest: ControlStep,
        nowMs: Long,
        triggeredRoles: Set<CharacterRole>,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep = when (event.actions.single().type) {
        ActionType.CLICK_ROLE -> advanceRole(event, latest, nowMs, triggeredRoles, immediate)
        else -> advanceGeneric(event, latest, nowMs, immediate)
    }

    private fun advanceRole(
        event: AxisEvent,
        latest: ControlStep,
        nowMs: Long,
        triggeredRoles: Set<CharacterRole>,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val action = event.actions.single()
        val role = requireNotNull(roleFromName(action.role)) { "非法角色：${action.role}" }
        return when (activePhase ?: ActivePhase.STARTING) {
            ActivePhase.STARTING -> {
                if (alreadySetActionIds.remove(event.id)) {
                    activePhase = ActivePhase.WAITING_ROLE_UB
                    roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                    val ubDetected = recoveredRoleUbActionIds.remove(event.id) || hasRoleUb(role, triggeredRoles)
                    val fallbackDue = isRoleSetFallbackDue(nowMs)
                    if (ubDetected) {
                        activeRoleUbObserved = false
                        stateMachine.assumeRoleSetOnFromUb(role)
                        return requestRoleOff(role, nowMs, immediate)
                    }
                    if (fallbackDue) return requestRoleOff(role, nowMs, immediate)
                    return result(latest, immediate, busy = true)
                }
                val step = stateMachine.requestRoleState(role, VisualToggleState.ON, nowMs)
                when {
                    step.confirmed -> {
                        activePhase = ActivePhase.WAITING_ROLE_UB
                        roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                        val ubDetected = hasRoleUb(role, triggeredRoles)
                        if (ubDetected) {
                            activeRoleUbObserved = false
                            stateMachine.assumeRoleSetOnFromUb(role)
                            return requestRoleOff(role, nowMs, immediate)
                        }
                        if (isRoleSetFallbackDue(nowMs)) {
                            return requestRoleOff(role, nowMs, immediate)
                        }
                    }
                    step.action != ControlAction.None -> {
                        activePhase = ActivePhase.CONFIRMING_ROLE_ON
                    }
                }
                result(step, immediate, busy = true, newControlAction = step.action)
            }
            ActivePhase.CONFIRMING_ROLE_ON -> {
                val ubDetected = hasRoleUb(role, triggeredRoles)
                if (latest.confirmed) {
                    activePhase = ActivePhase.WAITING_ROLE_UB
                    roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                    val fallbackDue = isRoleSetFallbackDue(nowMs)
                    if (ubDetected || fallbackDue) {
                        if (ubDetected) activeRoleUbObserved = false
                        requestRoleOff(role, nowMs, immediate)
                    } else {
                        result(latest, immediate, busy = true)
                    }
                } else if (ubDetected) {
                    val acknowledged = stateMachine.acknowledgeRoleSetByUb(role)
                    if (acknowledged.confirmed) {
                        activeRoleUbObserved = false
                        requestRoleOff(role, nowMs, immediate)
                    } else {
                        result(latest, immediate, busy = true)
                    }
                } else {
                    result(latest, immediate, busy = true)
                }
            }
            ActivePhase.WAITING_ROLE_UB -> {
                val ubDetected = hasRoleUb(role, triggeredRoles)
                val fallbackDue = isRoleSetFallbackDue(nowMs)
                if (!ubDetected && !fallbackDue) return result(latest, immediate, busy = true)
                if (ubDetected) {
                    activeRoleUbObserved = false
                    stateMachine.assumeRoleSetOnFromUb(role)
                }
                requestRoleOff(role, nowMs, immediate)
            }
            ActivePhase.CONFIRMING_ROLE_OFF -> {
                if (latest.confirmed) completeActive(immediate)
                else result(latest, immediate, busy = true)
            }
            ActivePhase.CONFIRMING_GENERIC -> error("角色动作进入了非法阶段")
        }
    }

    private fun requestRoleOff(
        role: CharacterRole,
        nowMs: Long,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        roleSetFallbackWatchdog.cancel()
        val step = stateMachine.requestRoleState(role, VisualToggleState.OFF, nowMs)
        return if (step.confirmed) {
            completeActive(immediate)
        } else {
            if (step.action != ControlAction.None) activePhase = ActivePhase.CONFIRMING_ROLE_OFF
            result(step, immediate, busy = true, newControlAction = step.action)
        }
    }

    private fun advanceGeneric(
        event: AxisEvent,
        latest: ControlStep,
        nowMs: Long,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val action = event.actions.single()
        if (activePhase == ActivePhase.CONFIRMING_GENERIC) {
            return if (latest.confirmed) completeActive(immediate)
            else result(latest, immediate, busy = true)
        }

        val step = when (action.type) {
            ActionType.CLICK_AUTO -> stateMachine.requestToggle(ControlAction.TapAuto, nowMs)
            ActionType.TOGGLE_AUTO, ActionType.SET_ROLES -> {
                stateMachine.setDesired(requireNotNull(OpeningControlTarget.fromAction(action)))
                stateMachine.snapshot()
            }
            else -> latest
        }
        activePhase = if (action.type == ActionType.CLICK_AUTO && step.action == ControlAction.None) {
            ActivePhase.STARTING
        } else {
            ActivePhase.CONFIRMING_GENERIC
        }
        return result(step, immediate, busy = true, newControlAction = step.action)
    }

    private fun completeActive(immediate: List<AxisEvent>): CoordinatedActionStep {
        val completed = activeControl
        if (completed?.actions?.single()?.type?.isTargetAction() == true) stateMachine.clearDesired()
        activeControl = null
        activePhase = null
        activeRoleUbObserved = false
        activeRoleTpBelowThresholdObserved = false
        completed?.id?.let(recoveredRoleUbActionIds::remove)
        roleSetFallbackWatchdog.cancel()
        return result(stateMachine.snapshot(), immediate, busy = queue.isNotEmpty())
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

    private fun hasRoleUb(role: CharacterRole, triggeredRoles: Set<CharacterRole>): Boolean =
        activeRoleUbObserved || role in triggeredRoles

    private fun isRoleSetFallbackDue(nowMs: Long): Boolean =
        activeRoleTpBelowThresholdObserved && roleSetFallbackWatchdog.isDue(nowMs)

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
        val ROLE_UB_OBSERVING_PHASES = setOf(
            ActivePhase.CONFIRMING_ROLE_ON,
            ActivePhase.WAITING_ROLE_UB
        )
    }
}
