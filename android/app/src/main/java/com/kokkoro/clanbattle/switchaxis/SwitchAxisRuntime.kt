package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import java.util.ArrayDeque

data class SwitchFrameInput(
    val clockSeconds: Int?,
    val triggeredRoles: Set<CharacterRole>,
    val controlsTrustworthy: Boolean,
    val wallMs: Long
)

sealed interface SwitchRuntimeCommand {
    data object None : SwitchRuntimeCommand

    data class Converge(
        val nodeId: String,
        val target: SwitchControlTarget
    ) : SwitchRuntimeCommand

    data class EnterPauseFrame(
        val nodeId: String,
        val role: CharacterRole
    ) : SwitchRuntimeCommand
}

data class SwitchRuntimeSnapshot(
    val nodeId: String? = null,
    val sourceLine: Int? = null,
    val triggerType: String? = null,
    val runtimeState: String? = null,
    val eligibleWallMs: Long? = null,
    val deadlineWallMs: Long? = null
)

class SwitchAxisRuntime(
    private val opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>
) {
    private sealed interface ActiveState {
        data object Armed : ActiveState
        data object PauseFrameEntered : ActiveState
        data object PauseFrameConfirmed : ActiveState
        data object Converging : ActiveState
    }

    private data class ActiveNode(
        val node: SwitchAxisNode,
        var state: ActiveState = ActiveState.Armed,
        val armedAtWallMs: Long
    )

    private val remainingNodes = nodes.toMutableList()
    private val crossedNodes = ArrayDeque<SwitchAxisNode>()
    private var openingPending = opening != null
    private var openingConverging = false
    private var active: ActiveNode? = null

    fun update(frame: SwitchFrameInput): SwitchRuntimeCommand {
        if (openingPending || openingConverging) {
            return updateOpening(frame)
        }

        enqueueCrossedNodes(frame.clockSeconds)
        var armedNow = false
        val current = active ?: crossedNodes.pollFirst()?.let {
            ActiveNode(node = it, armedAtWallMs = frame.wallMs).also { armed ->
                active = armed
                armedNow = true
            }
        } ?: return SwitchRuntimeCommand.None

        return commandFor(current, frame, armedNow)
    }

    fun confirmPauseFrame(nodeId: String) {
        val current = active ?: return
        if (current.node.id == nodeId && current.state == ActiveState.PauseFrameEntered) {
            current.state = ActiveState.PauseFrameConfirmed
        }
    }

    fun confirmConvergence(nodeId: String) {
        if (openingConverging && nodeId == OPENING_NODE_ID) {
            openingConverging = false
            openingPending = false
            return
        }

        val current = active ?: return
        if (current.node.id == nodeId && current.state == ActiveState.Converging) {
            active = null
        }
    }

    fun pendingNodeId(): String? = when {
        openingConverging -> OPENING_NODE_ID
        active != null -> active?.node?.id
        else -> null
    }

    fun snapshot(): SwitchRuntimeSnapshot {
        if (openingConverging) {
            return SwitchRuntimeSnapshot(
                nodeId = OPENING_NODE_ID,
                sourceLine = opening?.sourceLine,
                triggerType = "OPENING",
                runtimeState = "Converging"
            )
        }
        val current = active ?: return SwitchRuntimeSnapshot()
        val trigger = current.node.trigger
        return SwitchRuntimeSnapshot(
            nodeId = current.node.id,
            sourceLine = current.node.sourceLine,
            triggerType = when (trigger) {
                TimedTrigger -> "TIMED"
                is CharacterUbTrigger -> "CHARACTER_UB"
                is BossDelayTrigger -> "BOSS_DELAY"
                is PauseFrameTrigger -> "PAUSE_FRAME"
                else -> "INVALID"
            },
            runtimeState = when (current.state) {
                ActiveState.Armed -> "Armed"
                ActiveState.PauseFrameEntered -> "PauseFrameEntered"
                ActiveState.PauseFrameConfirmed -> "PauseFrameConfirmed"
                ActiveState.Converging -> "Converging"
            },
            eligibleWallMs = current.armedAtWallMs,
            deadlineWallMs = (trigger as? BossDelayTrigger)?.minimumDelayMs?.let {
                current.armedAtWallMs + it
            }
        )
    }

    private fun updateOpening(frame: SwitchFrameInput): SwitchRuntimeCommand {
        val target = opening?.target ?: return SwitchRuntimeCommand.None
        if (openingConverging) {
            return SwitchRuntimeCommand.Converge(OPENING_NODE_ID, target)
        }
        if (frame.clockSeconds !in OPENING_WINDOW || !frame.controlsTrustworthy) {
            return SwitchRuntimeCommand.None
        }
        openingConverging = true
        return SwitchRuntimeCommand.Converge(OPENING_NODE_ID, target)
    }

    private fun enqueueCrossedNodes(clockSeconds: Int?) {
        if (clockSeconds == null) return
        val crossed = remainingNodes.filter { clockSeconds <= it.timeSeconds }
        if (crossed.isEmpty()) return
        crossed.forEach(crossedNodes::addLast)
        remainingNodes.removeAll(crossed.toSet())
    }

    private fun commandFor(
        active: ActiveNode,
        frame: SwitchFrameInput,
        armedNow: Boolean
    ): SwitchRuntimeCommand {
        if (active.state == ActiveState.Converging) {
            return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
        }

        return when (val trigger = active.node.trigger) {
            TimedTrigger -> convergeWhenTrustworthy(active, frame)
            is CharacterUbTrigger -> {
                val role = trigger.role
                if (!armedNow && role != null && role in frame.triggeredRoles) {
                    convergeWhenTrustworthy(active, frame)
                } else {
                    SwitchRuntimeCommand.None
                }
            }
            is BossDelayTrigger -> {
                val delayMs = trigger.minimumDelayMs
                if (delayMs != null && frame.wallMs - active.armedAtWallMs >= delayMs) {
                    convergeWhenTrustworthy(active, frame)
                } else {
                    SwitchRuntimeCommand.None
                }
            }
            is PauseFrameTrigger -> if (frame.controlsTrustworthy) {
                pauseFrameCommand(active, trigger)
            } else {
                SwitchRuntimeCommand.None
            }
            else -> SwitchRuntimeCommand.None
        }
    }

    private fun convergeWhenTrustworthy(
        active: ActiveNode,
        frame: SwitchFrameInput
    ): SwitchRuntimeCommand {
        if (!frame.controlsTrustworthy) return SwitchRuntimeCommand.None
        active.state = ActiveState.Converging
        return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
    }

    private fun pauseFrameCommand(
        active: ActiveNode,
        trigger: PauseFrameTrigger
    ): SwitchRuntimeCommand {
        if (active.state == ActiveState.PauseFrameConfirmed) {
            active.state = ActiveState.Converging
            return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
        }
        if (active.state == ActiveState.PauseFrameEntered) return SwitchRuntimeCommand.None
        val role = trigger.role ?: return SwitchRuntimeCommand.None
        active.state = ActiveState.PauseFrameEntered
        return SwitchRuntimeCommand.EnterPauseFrame(active.node.id, role)
    }

    private companion object {
        const val OPENING_NODE_ID = "opening-1"
        val OPENING_WINDOW = 88..90
    }
}
