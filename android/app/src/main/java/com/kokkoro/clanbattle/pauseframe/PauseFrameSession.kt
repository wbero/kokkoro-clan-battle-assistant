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

data class PauseFrameDiagnosticEvent(
    val nodeId: String?,
    val role: CharacterRole?,
    val action: String,
    val result: String
)

class PauseFrameSession(
    private val focusPort: OverlayFocusPort,
    private val scheduler: PauseFrameScheduler,
    private val frameIntervalMs: Long = 40,
    private val focusTransitionMs: Long = 1_000,
    private val diagnosticCallback: (PauseFrameDiagnosticEvent) -> Unit = {}
) {
    private var state = PauseFrameState.IDLE
    private var nodeId: String? = null
    private var role: CharacterRole? = null
    private var generation = 0L

    fun enter(nodeId: String, role: CharacterRole): PauseFrameResult {
        if (state != PauseFrameState.IDLE) return result(accepted = false)
        generation++
        this.nodeId = nodeId
        this.role = role
        diagnose("enter", "requested")
        val acquired = focusPort.acquireFocus()
        diagnose("focus-acquire", if (acquired) "success" else "failed")
        state = if (acquired) PauseFrameState.SOFT_PAUSED else PauseFrameState.FAILED
        return result(accepted = state == PauseFrameState.SOFT_PAUSED)
    }

    fun advance(): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED) return result(accepted = false)
        diagnose("advance", "requested")
        state = PauseFrameState.ADVANCING
        val released = focusPort.releaseFocus()
        diagnose("focus-release", if (released) "success" else "failed")
        if (!released) return fail()
        val advanceGeneration = generation
        scheduler.schedule(focusTransitionMs) outer@{
            if (generation != advanceGeneration || state != PauseFrameState.ADVANCING) return@outer
            val back = focusPort.sendBack()
            diagnose("back", if (back) "success" else "failed")
            if (!back) {
                fail()
                return@outer
            }
            scheduler.schedule(frameIntervalMs) inner@{
                if (generation != advanceGeneration || state != PauseFrameState.ADVANCING) return@inner
                val acquired = focusPort.acquireFocus()
                diagnose("focus-acquire", if (acquired) "success" else "failed")
                state = if (acquired) {
                    PauseFrameState.SOFT_PAUSED
                } else {
                    PauseFrameState.FAILED
                }
            }
        }
        return result(accepted = true)
    }

    fun confirm(onComplete: (PauseFrameResult) -> Unit): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED) return result(accepted = false)
        val confirmedNode = nodeId
        val confirmedRole = role ?: return fail()
        diagnose("confirm", "requested")
        state = PauseFrameState.CONFIRMING
        val released = focusPort.releaseFocus()
        diagnose("focus-release", if (released) "success" else "failed")
        if (!released) return fail()
        val confirmGeneration = generation
        scheduler.schedule(focusTransitionMs) confirmation@{
            if (generation != confirmGeneration || state != PauseFrameState.CONFIRMING) return@confirmation
            val back = focusPort.sendBack()
            diagnose("back", if (back) "success" else "failed")
            if (!back) {
                onComplete(fail())
                return@confirmation
            }
            val tapped = focusPort.tapRole(confirmedRole)
            diagnose("tap-role", if (tapped) "success" else "failed")
            if (!tapped) {
                onComplete(fail())
                return@confirmation
            }
            state = PauseFrameState.IDLE
            nodeId = null
            role = null
            onComplete(
                PauseFrameResult(
                    accepted = true,
                    state = state,
                    nodeId = confirmedNode,
                    confirmedRole = confirmedRole,
                    readyForConvergence = true
                )
            )
        }
        return result(accepted = true)
    }

    fun reset() {
        generation++
        if (state != PauseFrameState.IDLE) {
            diagnose("reset", "requested")
            val released = focusPort.releaseFocus()
            diagnose("focus-release", if (released) "success" else "failed")
        }
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

    private fun diagnose(action: String, result: String) {
        diagnosticCallback(PauseFrameDiagnosticEvent(nodeId, role, action, result))
    }
}
