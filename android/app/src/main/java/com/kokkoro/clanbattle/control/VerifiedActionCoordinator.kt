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
    private var activeRoleTpBelowThresholdFrames = 0
    private var activeRoleTpObservedAtMs: Long? = null
    private var activeRoleTpFullObserved = false
    private var activeRoleSetVisualOnObserved = false
    private var activeRoleSetRequestedByCoordinator = false
    private val recentRoleUbAtMs = mutableMapOf<CharacterRole, Long>()
    private val recentRoleUbClockSeconds = mutableMapOf<CharacterRole, Int>()
    private val recoveredRoleUbActionIds = mutableSetOf<String>()
    private var roleAliases: Map<String, CharacterRole> = defaultRoleAliases()

    fun configureRoleSetFallbackGraceMs(value: Long) {
        roleSetFallbackWatchdog.configureGraceMs(value)
    }

    fun observeFrame(
        triggeredRoles: Set<CharacterRole>,
        clockSeconds: Int?,
        nowMs: Long,
        tpBelowThresholdRoles: Set<CharacterRole> = emptySet(),
        tpFullRoles: Set<CharacterRole> = emptySet()
    ) {
        roleSetFallbackWatchdog.observeClock(clockSeconds, nowMs)
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
        val role = roleFromName(action.role)
        if (
            action.type == ActionType.CLICK_ROLE &&
            activePhase in ROLE_UB_OBSERVING_PHASES &&
            activeRoleTpObservedAtMs != nowMs
        ) {
            activeRoleTpObservedAtMs = nowMs
            activeRoleTpBelowThresholdFrames = when {
                role in tpFullRoles -> {
                    // Start a fresh, node-local baseline. A low TP reading from
                    // an earlier node can never satisfy this lifecycle.
                    activeRoleTpFullObserved = true
                    0
                }
                activeRoleTpFullObserved && role in tpBelowThresholdRoles ->
                    activeRoleTpBelowThresholdFrames + 1
                else -> 0
            }
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
        clockSeconds: Int? = null,
        tpBelowThresholdRoles: Set<CharacterRole> = emptySet(),
        tpFullRoles: Set<CharacterRole> = emptySet()
    ): CoordinatedActionStep {
        observeFrame(triggeredRoles, clockSeconds, nowMs, tpBelowThresholdRoles, tpFullRoles)
        if (latest.safety != ControlSafetyState.RUNNING) {
            if (activeControl != null) {
                activePhase = ActivePhase.STARTING
                activeRoleUbObserved = false
                resetFallbackTpEvidence()
                activeRoleSetVisualOnObserved = false
                activeRoleSetRequestedByCoordinator = false
                roleSetFallbackWatchdog.cancel()
            }
            return result(latest, emptyList(), busy = true)
        }

        val active = activeControl
        if (active != null) {
            return advance(active, latest, nowMs, triggeredRoles, tpBelowThresholdRoles, tpFullRoles, emptyList())
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
            resetFallbackTpEvidence()
            activeRoleSetVisualOnObserved = false
            activeRoleSetRequestedByCoordinator = false
            return advance(next, latest, nowMs, triggeredRoles, tpBelowThresholdRoles, tpFullRoles, immediate)
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
        resetFallbackTpEvidence()
        activeRoleSetVisualOnObserved = false
        activeRoleSetRequestedByCoordinator = false
        recentRoleUbAtMs.clear()
        recentRoleUbClockSeconds.clear()
        recoveredRoleUbActionIds.clear()
        roleSetFallbackWatchdog.reset()
    }

    private fun advance(
        event: AxisEvent,
        latest: ControlStep,
        nowMs: Long,
        triggeredRoles: Set<CharacterRole>,
        tpBelowThresholdRoles: Set<CharacterRole>,
        tpFullRoles: Set<CharacterRole>,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep = when (event.actions.single().type) {
        ActionType.CLICK_ROLE -> advanceRole(
            event, latest, nowMs, triggeredRoles, tpBelowThresholdRoles, tpFullRoles, immediate
        )
        else -> advanceGeneric(event, latest, nowMs, immediate)
    }

    private fun advanceRole(
        event: AxisEvent,
        latest: ControlStep,
        nowMs: Long,
        triggeredRoles: Set<CharacterRole>,
        tpBelowThresholdRoles: Set<CharacterRole>,
        tpFullRoles: Set<CharacterRole>,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val action = event.actions.single()
        val role = requireNotNull(roleFromName(action.role)) { "非法角色：${action.role}" }
        if (latest.observed?.roles?.get(role) == VisualToggleState.ON) {
            activeRoleSetVisualOnObserved = true
        }
        return when (activePhase ?: ActivePhase.STARTING) {
            ActivePhase.STARTING -> {
                if (alreadySetActionIds.remove(event.id)) {
                    activePhase = ActivePhase.WAITING_ROLE_UB
                    roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                    // If the role is already full TP at the moment the lifecycle
                    // starts, mark the full observation immediately so that a
                    // subsequent drop can be recognised as a valid UB.
                    if (role in tpFullRoles) {
                        activeRoleTpFullObserved = true
                    }
                    val ubDetected = recoveredRoleUbActionIds.remove(event.id) || hasRoleUb(role, triggeredRoles)
                    val fallbackDue = isRoleSetFallbackDue(nowMs)
                    if (ubDetected || fallbackDue) {
                        return requestRoleOffAfterRelease(role, nowMs, immediate)
                    }
                    return result(latest, immediate, busy = true)
                }
                val step = stateMachine.requestRoleSet(role, nowMs)
                when {
                    step.confirmed -> {
                        activeRoleSetRequestedByCoordinator = true
                        activePhase = ActivePhase.WAITING_ROLE_UB
                        roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                        // Same as above: initialise the full-TP baseline if the
                        // role is already full when SET is first confirmed.
                        if (role in tpFullRoles) {
                            activeRoleTpFullObserved = true
                        }
                        val ubDetected = hasRoleUb(role, triggeredRoles)
                        if (ubDetected || isRoleSetFallbackDue(nowMs)) {
                            return requestRoleOffAfterRelease(role, nowMs, immediate)
                        }
                    }
                    step.action != ControlAction.None -> {
                        activeRoleSetRequestedByCoordinator = true
                        activePhase = ActivePhase.CONFIRMING_ROLE_ON
                        roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                    }
                }
                result(step, immediate, busy = true, newControlAction = step.action)
            }
            ActivePhase.CONFIRMING_ROLE_ON -> {
                val ubDetected = hasRoleUb(role, triggeredRoles)
                val fallbackDue = isRoleSetFallbackDue(nowMs)
                if (latest.confirmed) {
                    activePhase = ActivePhase.WAITING_ROLE_UB
                    roleSetFallbackWatchdog.arm(event.timeSeconds, nowMs)
                    if (ubDetected || fallbackDue) {
                        requestRoleOffAfterRelease(role, nowMs, immediate)
                    } else {
                        result(latest, immediate, busy = true)
                    }
                } else if (ubDetected || fallbackDue) {
                    val acknowledged = stateMachine.acknowledgeRoleSetByUb(role)
                    if (acknowledged.confirmed) {
                        requestRoleOffAfterRelease(role, nowMs, immediate)
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
                requestRoleOffAfterRelease(role, nowMs, immediate)
            }
            ActivePhase.CONFIRMING_ROLE_OFF -> {
                if (latest.confirmed) completeActive(immediate)
                else result(latest, immediate, busy = true)
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
        resetFallbackTpEvidence()
        activeRoleSetVisualOnObserved = false
        activeRoleSetRequestedByCoordinator = false
        recentRoleUbAtMs.clear()
        recentRoleUbClockSeconds.clear()
        recoveredRoleUbActionIds.clear()
        roleSetFallbackWatchdog.cancel()
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

    private fun requestRoleOffAfterRelease(
        role: CharacterRole,
        nowMs: Long,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        activeRoleUbObserved = false
        // The SET badge can look OFF under a UB animation. A confirmed SET lifecycle plus
        // stable low TP still requires a physical OFF tap instead of trusting that frame.
        stateMachine.assumeRoleSetOnFromUb(role)
        return requestRoleOff(role, nowMs, immediate)
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
        resetFallbackTpEvidence()
        activeRoleSetVisualOnObserved = false
        activeRoleSetRequestedByCoordinator = false
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

    /**
     * A role UB is recognised only when either:
     * 1. The coordinator itself has already observed the UB via [observeFrame],
     *    or
     * 2. The energy detector reports the role in [triggeredRoles] **and** the
     *    coordinator has independently observed that the role's TP was full
     *    during the current lifecycle ([activeRoleTpFullObserved]).
     *
     * The second condition prevents a false trigger caused by a single-frame
     * energy spike followed by an immediate drop (e.g. recognition noise or a
     * brief animation flash).  The role must genuinely have been at full TP
     * before the drop can count as a UB.
     */
    private fun hasRoleUb(role: CharacterRole, triggeredRoles: Set<CharacterRole>): Boolean =
        activeRoleUbObserved || (role in triggeredRoles && activeRoleTpFullObserved)

    private fun isRoleSetFallbackDue(nowMs: Long): Boolean =
        (activeRoleSetRequestedByCoordinator || activeRoleSetVisualOnObserved) &&
            activeRoleTpFullObserved &&
            activeRoleTpBelowThresholdFrames >= FALLBACK_TP_CONFIRM_FRAMES &&
            roleSetFallbackWatchdog.isDue(nowMs)

    private fun resetFallbackTpEvidence() {
        activeRoleTpBelowThresholdFrames = 0
        activeRoleTpObservedAtMs = null
        activeRoleTpFullObserved = false
    }

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
        const val FALLBACK_TP_CONFIRM_FRAMES = 3
        val ROLE_UB_OBSERVING_PHASES = setOf(
            ActivePhase.CONFIRMING_ROLE_ON,
            ActivePhase.WAITING_ROLE_UB
        )
    }
}