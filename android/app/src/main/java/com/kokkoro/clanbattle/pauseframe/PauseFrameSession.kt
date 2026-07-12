package com.kokkoro.clanbattle.pauseframe

import com.kokkoro.clanbattle.recognition.CharacterRole

enum class PauseFrameState { IDLE, SOFT_PAUSED, ADVANCING, CONFIRMING, FAILED }

data class PauseFrameSnapshot(
    val state: PauseFrameState,
    val nodeId: String?,
    val role: CharacterRole?,
    val blocksScheduler: Boolean
)

data class PauseFrameResult(
    val accepted: Boolean,
    val state: PauseFrameState,
    val nodeId: String? = null,
    val confirmedRole: CharacterRole? = null,
    val readyForConvergence: Boolean = false
)

class PauseFrameSession(
    private val focusPort: OverlayFocusPort,
    private val scheduler: PauseFrameScheduler,
    private val frameIntervalMs: Long = 40,
    private val focusTransitionMs: Long = 1_000
) {
    private var state = PauseFrameState.IDLE
    private var nodeId: String? = null
    private var role: CharacterRole? = null

    fun enter(nodeId: String, role: CharacterRole): PauseFrameResult {
        if (state != PauseFrameState.IDLE) return result(accepted = false)
        this.nodeId = nodeId
        this.role = role
        state = if (focusPort.acquireFocus()) PauseFrameState.SOFT_PAUSED else PauseFrameState.FAILED
        return result(accepted = state == PauseFrameState.SOFT_PAUSED)
    }

    fun advance(): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED) return result(accepted = false)
        state = PauseFrameState.ADVANCING
        if (!focusPort.releaseFocus()) return fail()
        scheduler.schedule(focusTransitionMs) {
            if (!focusPort.sendBack()) {
                fail()
                return@schedule
            }
            scheduler.schedule(frameIntervalMs) {
                state = if (focusPort.acquireFocus()) {
                    PauseFrameState.SOFT_PAUSED
                } else {
                    PauseFrameState.FAILED
                }
            }
        }
        return result(accepted = true)
    }

    fun confirm(): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED) return result(accepted = false)
        val confirmedNode = nodeId
        val confirmedRole = role ?: return fail()
        state = PauseFrameState.CONFIRMING
        if (!focusPort.tapRole(confirmedRole)) return fail()
        if (!focusPort.releaseFocus()) return fail()
        state = PauseFrameState.IDLE
        nodeId = null
        role = null
        return PauseFrameResult(
            accepted = true,
            state = state,
            nodeId = confirmedNode,
            confirmedRole = confirmedRole,
            readyForConvergence = true
        )
    }

    fun reset() {
        if (state != PauseFrameState.IDLE) focusPort.releaseFocus()
        state = PauseFrameState.IDLE
        nodeId = null
        role = null
    }

    fun snapshot() = PauseFrameSnapshot(
        state = state,
        nodeId = nodeId,
        role = role,
        blocksScheduler = state != PauseFrameState.IDLE
    )

    private fun fail(): PauseFrameResult {
        state = PauseFrameState.FAILED
        return result(accepted = false)
    }

    private fun result(accepted: Boolean) = PauseFrameResult(
        accepted = accepted,
        state = state,
        nodeId = nodeId
    )
}
