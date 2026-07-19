package com.kokkoro.clanbattle.sequenceaxis

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.scheduler.BossUbEvent
import java.util.ArrayDeque

data class SequenceFrameInput(
    val clockSeconds: Int?,
    val triggeredRoles: Set<CharacterRole>,
    val controlsTrustworthy: Boolean,
    val wallMs: Long,
    val schedulingAllowed: Boolean,
    val bossUbEvent: BossUbEvent? = null,
    val roleChainSchedulingAllowed: Boolean = schedulingAllowed
)

sealed interface SequenceRuntimeCommand {
    data object None : SequenceRuntimeCommand

    data class Dispatch(
        val event: AxisEvent,
        val rolesAlreadySet: Set<CharacterRole> = emptySet()
    ) : SequenceRuntimeCommand

    data class EnterPauseFrame(
        val nodeId: String,
        val role: CharacterRole
    ) : SequenceRuntimeCommand
}

data class SequenceRuntimeSnapshot(
    val activeEvent: AxisEvent? = null,
    val nextEvent: AxisEvent? = null,
    val phase: String? = null
)

class SequenceAxisRuntime(events: List<AxisEvent>) {
    private enum class ActivePhase {
        ARMED,
        TRIGGER_SATISFIED,
        PAUSE_FRAME_ENTERED,
        PAUSE_FRAME_CONFIRMED
    }

    private data class ActiveNode(
        val event: AxisEvent,
        val armedAtWallMs: Long,
        var phase: ActivePhase = ActivePhase.ARMED,
        var bossUbDetectedAtWallMs: Long? = null
    )

    private val remaining = events.toMutableList()
    private val crossed = ArrayDeque<AxisEvent>()
    private var active: ActiveNode? = null
    private var activeCharacterUbObserved = false
    // 上一个派发的是否为普通角色点击；只有为真时，才允许下一个普通角色点击提前于时钟链式放行。
    private var lastDispatchWasRole = false

    fun update(frame: SequenceFrameInput): SequenceRuntimeCommand {
        observeRoleUbEvents(frame.triggeredRoles)
        enqueueCrossed(frame.clockSeconds)
        var armedNow = false
        val current = active ?: (crossed.pollFirst() ?: pollChainedRole())?.let { event ->
            ActiveNode(event, frame.wallMs).also {
                active = it
                armedNow = true
            }
        } ?: return SequenceRuntimeCommand.None

        return when (val trigger = current.event.trigger) {
            TimedTrigger -> if (canDispatchTimed(current.event, frame)) {
                dispatch(current)
            } else {
                SequenceRuntimeCommand.None
            }
            is CharacterUbTrigger -> {
                if (
                    !armedNow &&
                    trigger.role != null &&
                    (activeCharacterUbObserved || trigger.role in frame.triggeredRoles)
                ) {
                    current.phase = ActivePhase.TRIGGER_SATISFIED
                }
                // A matching role UB is itself the synchronization point. Do not
                // wait for the animation detector to settle before dispatching
                // the configured follow-up (for example, closing AUTO).
                if (current.phase == ActivePhase.TRIGGER_SATISFIED && frame.roleChainSchedulingAllowed) {
                    dispatch(current)
                } else SequenceRuntimeCommand.None
            }
            is BossDelayTrigger -> {
                val delay = trigger.minimumDelayMs ?: 0L
                if (current.bossUbDetectedAtWallMs == null) {
                    frame.bossUbEvent
                        ?.takeIf {
                            it.isApplicableTo(current.event.timeSeconds) &&
                                (delay == 0L || !it.early)
                        }
                        ?.let { current.bossUbDetectedAtWallMs = it.detectedAtWallMs }
                }
                val detectedAt = current.bossUbDetectedAtWallMs
                val dispatchAllowed = if (delay == 0L) {
                    frame.roleChainSchedulingAllowed
                } else {
                    frame.schedulingAllowed
                }
                if (
                    dispatchAllowed &&
                    detectedAt != null &&
                    frame.wallMs - detectedAt >= delay
                ) {
                    dispatch(current)
                } else {
                    SequenceRuntimeCommand.None
                }
            }
            is PauseFrameTrigger -> pauseFrame(current, trigger, frame)
            else -> SequenceRuntimeCommand.None
        }
    }

    fun observeRoleUbEvents(triggeredRoles: Set<CharacterRole>) {
        val trigger = active?.event?.trigger as? CharacterUbTrigger ?: return
        if (trigger.role != null && trigger.role in triggeredRoles) {
            activeCharacterUbObserved = true
        }
    }

    fun confirmPauseFrame(nodeId: String) {
        val current = active ?: return
        if (current.event.id == nodeId && current.phase == ActivePhase.PAUSE_FRAME_ENTERED) {
            current.phase = ActivePhase.PAUSE_FRAME_CONFIRMED
        }
    }

    fun snapshot(): SequenceRuntimeSnapshot {
        val current = active
        return SequenceRuntimeSnapshot(
            activeEvent = current?.event,
            nextEvent = crossed.firstOrNull() ?: remaining.firstOrNull(),
            phase = current?.phase?.name
        )
    }

    private fun enqueueCrossed(clockSeconds: Int?) {
        if (clockSeconds == null) return
        val due = remaining.filter { clockSeconds <= it.timeSeconds }
        if (due.isEmpty()) return
        due.forEach(crossed::addLast)
        remaining.removeAll(due.toSet())
    }

    private fun dispatch(current: ActiveNode): SequenceRuntimeCommand.Dispatch {
        lastDispatchWasRole = current.event.isPlainRoleClick()
        active = null
        activeCharacterUbObserved = false
        return SequenceRuntimeCommand.Dispatch(current.event)
    }

    private fun canDispatchTimed(event: AxisEvent, frame: SequenceFrameInput): Boolean =
        frame.schedulingAllowed ||
            (lastDispatchWasRole && event.isPlainRoleClick() && frame.roleChainSchedulingAllowed)

    // 上一个派发是普通角色点击时，把待执行队列的队首角色提前于时钟放行，实现 UB 完成即链式点击下一个。
    private fun pollChainedRole(): AxisEvent? {
        if (!lastDispatchWasRole) return null
        val head = remaining.firstOrNull()?.takeIf { it.isPlainRoleClick() } ?: return null
        remaining.removeAt(0)
        return head
    }

    // 普通角色点击：无特殊触发，动作里含角色点击且只含角色点击或提示（排除混入 AUTO/SET/BOSS 的行）。
    private fun AxisEvent.isPlainRoleClick(): Boolean =
        trigger == TimedTrigger &&
            actions.any { it.type == ActionType.CLICK_ROLE } &&
            actions.all { it.type == ActionType.CLICK_ROLE || it.type == ActionType.NOTIFY }

    private fun pauseFrame(
        current: ActiveNode,
        trigger: PauseFrameTrigger,
        frame: SequenceFrameInput
    ): SequenceRuntimeCommand {
        if (
            current.phase == ActivePhase.PAUSE_FRAME_CONFIRMED &&
            frame.roleChainSchedulingAllowed &&
            frame.controlsTrustworthy
        ) {
            active = null
            activeCharacterUbObserved = false
            lastDispatchWasRole = false
            val role = trigger.role ?: return SequenceRuntimeCommand.None
            return SequenceRuntimeCommand.Dispatch(
                current.event.withPauseFrameLifecycle(role),
                rolesAlreadySet = setOf(role)
            )
        }
        if (
            current.phase == ActivePhase.PAUSE_FRAME_ENTERED ||
            !frame.controlsTrustworthy ||
            !frame.schedulingAllowed
        ) {
            return SequenceRuntimeCommand.None
        }
        val role = trigger.role ?: return SequenceRuntimeCommand.None
        current.phase = ActivePhase.PAUSE_FRAME_ENTERED
        return SequenceRuntimeCommand.EnterPauseFrame(current.event.id, role)
    }

    private fun AxisEvent.withPauseFrameLifecycle(role: CharacterRole): AxisEvent {
        val canonicalName = "角色${role.ordinal + 1}"
        val remainingActions = actions.filterNot { action ->
            action.type == ActionType.CLICK_ROLE && action.role == canonicalName
        }
        return copy(
            actions = listOf(AxisAction(ActionType.CLICK_ROLE, role = canonicalName)) + remainingActions
        )
    }

    private fun BossUbEvent.isApplicableTo(nodeTimeSeconds: Int): Boolean =
        heldClockSeconds <= nodeTimeSeconds && nodeTimeSeconds - heldClockSeconds <= 2
}
