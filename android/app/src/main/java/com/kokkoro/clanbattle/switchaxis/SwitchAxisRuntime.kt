package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.scheduler.BossUbEvent
import java.util.ArrayDeque

data class SwitchFrameInput(
    val clockSeconds: Int?,
    val triggeredRoles: Set<CharacterRole>,
    val controlsTrustworthy: Boolean,
    val wallMs: Long,
    val bossUbEvent: BossUbEvent? = null,
    val triggeredRoleClockSeconds: Map<CharacterRole, Int?> =
        triggeredRoles.associateWith { clockSeconds }
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

    data class MissedCharacterUb(
        val nodeId: String,
        val role: CharacterRole,
        val expectedClockSeconds: Int,
        val observedClockSeconds: Int
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
    nodes: List<SwitchAxisNode>,
    openingGraceSeconds: Int = 0
) {
    private val openingEarliestSeconds = (OPENING_MIN_SECONDS - openingGraceSeconds).coerceAtLeast(0)

    init {
        require(openingGraceSeconds >= 0)
    }
    private sealed interface ActiveState {
        data object Armed : ActiveState
        data object PauseFrameEntered : ActiveState
        data object PauseFrameConfirmed : ActiveState
        data object Converging : ActiveState
    }

    private data class ActiveNode(
        val node: SwitchAxisNode,
        var state: ActiveState = ActiveState.Armed,
        val armedAtWallMs: Long,
        var bossUbDetectedAtWallMs: Long? = null,
        var characterUbObserved: Boolean = false
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
        val current = active ?: crossedNodes.pollFirst()?.let {
            ActiveNode(node = it, armedAtWallMs = frame.wallMs).also { armed ->
                active = armed
            }
        } ?: return SwitchRuntimeCommand.None

        return commandFor(current, frame)
    }

    fun confirmPauseFrame(nodeId: String) {
        val current = active ?: return
        if (current.node.id == nodeId && current.state == ActiveState.PauseFrameEntered) {
            current.state = ActiveState.PauseFrameConfirmed
        }
    }

    /** Drop UB/delay evidence that predates a user-owned menu interaction. */
    fun clearRecognitionEvidence() {
        active?.bossUbDetectedAtWallMs = null
        active?.characterUbObserved = false
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
                current.bossUbDetectedAtWallMs?.plus(it)
            }
        )
    }

    private fun updateOpening(frame: SwitchFrameInput): SwitchRuntimeCommand {
        val target = opening?.target ?: return SwitchRuntimeCommand.None
        if (openingConverging) {
            return SwitchRuntimeCommand.Converge(OPENING_NODE_ID, target)
        }
        val clockSeconds = frame.clockSeconds ?: return SwitchRuntimeCommand.None
        // The first readable 1:30 frame can still belong to the battle-entry
        // transition: the SET/AUTO badges are visible and trustworthy, but taps may
        // not be accepted yet. Wait until the countdown has visibly started before
        // issuing the first opening click. Opening grace still lets slower devices
        // begin later than the normal 1:29..1:28 window.
        if (
            clockSeconds !in openingEarliestSeconds..OPENING_MAX_SECONDS ||
            !frame.controlsTrustworthy
        ) {
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
        frame: SwitchFrameInput
    ): SwitchRuntimeCommand {
        if (active.state == ActiveState.Converging) {
            return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
        }

        return when (val trigger = active.node.trigger) {
            TimedTrigger -> convergeWhenTrustworthy(active, frame)
            is CharacterUbTrigger -> {
                val role = trigger.role
                val clockSeconds = frame.clockSeconds
                val matchingEventClock = role?.let(frame.triggeredRoleClockSeconds::get)
                if (
                    role != null &&
                    role in frame.triggeredRoles &&
                    matchingEventClock == active.node.timeSeconds
                ) {
                    active.characterUbObserved = true
                }
                if (
                    !active.characterUbObserved &&
                    role != null &&
                    clockSeconds != null &&
                    clockSeconds < active.node.timeSeconds
                ) {
                    return SwitchRuntimeCommand.MissedCharacterUb(
                        nodeId = active.node.id,
                        role = role,
                        expectedClockSeconds = active.node.timeSeconds,
                        observedClockSeconds = clockSeconds
                    )
                }
                // “1:03 | UB后=角色3” means the ROLE_3 UB captured at 1:03,
                // not an arbitrary later/earlier ROLE_3. The event carries its
                // capture-time game clock so it can survive a temporary safety /
                // control hold without becoming valid for another node second.
                if (active.characterUbObserved) {
                    // A matched character skill name is the synchronization
                    // point.  SET/AUTO must be changed inside the UB animation,
                    // so do not block this transition on animation-corrupted
                    // control templates.
                    active.state = ActiveState.Converging
                    SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
                } else {
                    SwitchRuntimeCommand.None
                }
            }
            is BossDelayTrigger -> {
                if (active.bossUbDetectedAtWallMs == null) {
                    frame.bossUbEvent
                        ?.takeIf {
                            it.isApplicableTo(active.node.timeSeconds) &&
                                (trigger.minimumDelayMs == null || trigger.minimumDelayMs == 0L || !it.early)
                        }
                        ?.let { active.bossUbDetectedAtWallMs = it.detectedAtWallMs }
                }
                val delayMs = trigger.minimumDelayMs ?: 0L
                if (
                    active.bossUbDetectedAtWallMs != null &&
                    frame.wallMs - active.bossUbDetectedAtWallMs!! >= delayMs
                ) {
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
        const val OPENING_MIN_SECONDS = 88
        const val OPENING_MAX_SECONDS = 89
    }

    private fun BossUbEvent.isApplicableTo(nodeTimeSeconds: Int): Boolean =
        heldClockSeconds <= nodeTimeSeconds && nodeTimeSeconds - heldClockSeconds <= 2
}
