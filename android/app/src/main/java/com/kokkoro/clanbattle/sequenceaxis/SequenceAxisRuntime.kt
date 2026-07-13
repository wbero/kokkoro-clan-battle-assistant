package com.kokkoro.clanbattle.sequenceaxis

import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import java.util.ArrayDeque

data class SequenceFrameInput(
    val clockSeconds: Int?,
    val triggeredRoles: Set<CharacterRole>,
    val controlsTrustworthy: Boolean,
    val wallMs: Long,
    val schedulingAllowed: Boolean
)

sealed interface SequenceRuntimeCommand {
    data object None : SequenceRuntimeCommand

    data class Dispatch(val event: AxisEvent) : SequenceRuntimeCommand

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
        var phase: ActivePhase = ActivePhase.ARMED
    )

    private val remaining = events.toMutableList()
    private val crossed = ArrayDeque<AxisEvent>()
    private var active: ActiveNode? = null

    fun update(frame: SequenceFrameInput): SequenceRuntimeCommand {
        enqueueCrossed(frame.clockSeconds)
        var armedNow = false
        val current = active ?: crossed.pollFirst()?.let { event ->
            ActiveNode(event, frame.wallMs).also {
                active = it
                armedNow = true
            }
        } ?: return SequenceRuntimeCommand.None

        return when (val trigger = current.event.trigger) {
            TimedTrigger -> if (frame.schedulingAllowed) dispatch(current) else SequenceRuntimeCommand.None
            is CharacterUbTrigger -> {
                if (!armedNow && trigger.role != null && trigger.role in frame.triggeredRoles) {
                    current.phase = ActivePhase.TRIGGER_SATISFIED
                }
                if (current.phase == ActivePhase.TRIGGER_SATISFIED && frame.schedulingAllowed) {
                    dispatch(current)
                } else SequenceRuntimeCommand.None
            }
            is BossDelayTrigger -> {
                val delay = trigger.minimumDelayMs
                if (frame.schedulingAllowed && delay != null && frame.wallMs - current.armedAtWallMs >= delay) {
                    dispatch(current)
                } else {
                    SequenceRuntimeCommand.None
                }
            }
            is PauseFrameTrigger -> pauseFrame(current, trigger, frame)
            else -> SequenceRuntimeCommand.None
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
        active = null
        return SequenceRuntimeCommand.Dispatch(current.event)
    }

    private fun pauseFrame(
        current: ActiveNode,
        trigger: PauseFrameTrigger,
        frame: SequenceFrameInput
    ): SequenceRuntimeCommand {
        if (current.phase == ActivePhase.PAUSE_FRAME_CONFIRMED) {
            active = null
            return if (current.event.actions.isEmpty()) {
                SequenceRuntimeCommand.None
            } else {
                SequenceRuntimeCommand.Dispatch(current.event)
            }
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
}
