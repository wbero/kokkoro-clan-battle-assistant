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
    val newControlAction: ControlAction = ControlAction.None
)

class VerifiedActionCoordinator(
    private val stateMachine: BattleControlStateMachine
) {
    private val queue = ArrayDeque<AxisEvent>()
    private var activeControl: AxisEvent? = null
    private var activeStarted = false

    fun enqueue(events: List<AxisEvent>) {
        events.forEach { event ->
            event.actions.forEachIndexed { index, action ->
                queue.addLast(event.copy(id = "${event.id}:$index", actions = listOf(action)))
            }
        }
    }

    fun update(latest: ControlStep, nowMs: Long): CoordinatedActionStep {
        if (latest.safety != ControlSafetyState.RUNNING) {
            if (activeControl != null) activeStarted = false
            return CoordinatedActionStep(latest, emptyList(), busy = true)
        }

        val active = activeControl
        if (active != null) {
            if (!activeStarted) return start(active, latest, nowMs, emptyList())
            if (latest.confirmed) {
                if (active.actions.single().type.isTargetAction()) stateMachine.clearDesired()
                activeControl = null
                activeStarted = false
                return CoordinatedActionStep(stateMachine.snapshot(), emptyList(), busy = queue.isNotEmpty())
            }
            return CoordinatedActionStep(latest, emptyList(), busy = true)
        }

        val immediate = mutableListOf<AxisEvent>()
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!next.actions.single().type.isVerifiedControlAction()) {
                immediate += next
                continue
            }
            activeControl = next
            return start(next, latest, nowMs, immediate)
        }
        return CoordinatedActionStep(latest, immediate, busy = false)
    }

    fun isBusy(): Boolean = activeControl != null || queue.isNotEmpty()

    fun reset() {
        queue.clear()
        activeControl = null
        activeStarted = false
    }

    private fun start(
        event: AxisEvent,
        latest: ControlStep,
        nowMs: Long,
        immediate: List<AxisEvent>
    ): CoordinatedActionStep {
        val action = event.actions.single()
        val step = when (action.type) {
            ActionType.CLICK_AUTO -> stateMachine.requestToggle(ControlAction.TapAuto, nowMs)
            ActionType.CLICK_ROLE -> stateMachine.requestToggle(
                ControlAction.TapRole(requireNotNull(roleFromName(action.role)) { "非法角色：${action.role}" }),
                nowMs
            )
            ActionType.TOGGLE_AUTO, ActionType.SET_ROLES -> {
                stateMachine.setDesired(requireNotNull(OpeningControlTarget.fromAction(action)))
                activeStarted = true
                stateMachine.snapshot()
            }
            else -> latest
        }
        if (step.action != ControlAction.None) activeStarted = true
        return CoordinatedActionStep(
            controlStep = step,
            immediateEvents = immediate,
            busy = true,
            newControlAction = step.action
        )
    }

    private fun roleFromName(name: String?): CharacterRole? = when (name) {
        "角色1" -> CharacterRole.ROLE_1
        "角色2" -> CharacterRole.ROLE_2
        "角色3" -> CharacterRole.ROLE_3
        "角色4" -> CharacterRole.ROLE_4
        "角色5" -> CharacterRole.ROLE_5
        else -> null
    }

    private fun ActionType.isVerifiedControlAction(): Boolean = when (this) {
        ActionType.CLICK_ROLE, ActionType.CLICK_AUTO, ActionType.TOGGLE_AUTO, ActionType.SET_ROLES -> true
        ActionType.NOTIFY, ActionType.BOSS -> false
    }

    private fun ActionType.isTargetAction(): Boolean =
        this == ActionType.TOGGLE_AUTO || this == ActionType.SET_ROLES
}
