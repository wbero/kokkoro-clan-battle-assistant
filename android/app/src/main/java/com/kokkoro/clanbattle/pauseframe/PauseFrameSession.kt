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
    private val perFrameMs: Long = 40,
    private val focusTransitionMs: Long = 1_000,
    private val menuSettleMs: Long = 700,
    private val tapGapMs: Long = 400,
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

    /** 释放 [frameCount] 帧：解除卡帧、运行 frameCount×perFrameMs 后重新卡住。frameCount<1 视为 1。 */
    fun release(frameCount: Int): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED) return result(accepted = false)
        diagnose("advance", "requested")
        state = PauseFrameState.ADVANCING
        val released = focusPort.releaseFocus()
        diagnose("focus-release", if (released) "success" else "failed")
        if (!released) return fail()
        val advanceGeneration = generation
        val releaseMs = perFrameMs * frameCount.coerceAtLeast(1)
        scheduler.schedule(focusTransitionMs) outer@{
            if (generation != advanceGeneration || state != PauseFrameState.ADVANCING) return@outer
            val back = focusPort.sendBack()
            diagnose("back", if (back) "success" else "failed")
            if (!back) {
                fail()
                return@outer
            }
            scheduler.schedule(releaseMs) inner@{
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
        // 打开主菜单 → 等菜单渲染 → 点头像设 SET → 点菜单外关闭遮罩、恢复战斗。
        scheduler.schedule(focusTransitionMs) openMenu@{
            if (generation != confirmGeneration || state != PauseFrameState.CONFIRMING) return@openMenu
            val back = focusPort.sendBack()
            diagnose("back", if (back) "success" else "failed")
            if (!back) {
                onComplete(fail())
                return@openMenu
            }
            scheduler.schedule(menuSettleMs) tapAvatar@{
                if (generation != confirmGeneration || state != PauseFrameState.CONFIRMING) return@tapAvatar
                val tapped = focusPort.tapMenuRole(confirmedRole)
                diagnose("tap-role", if (tapped) "success" else "failed")
                if (!tapped) {
                    onComplete(fail())
                    return@tapAvatar
                }
                scheduler.schedule(tapGapMs) closeMenu@{
                    if (generation != confirmGeneration || state != PauseFrameState.CONFIRMING) return@closeMenu
                    val dismissed = focusPort.dismissMenu()
                    diagnose("dismiss", if (dismissed) "success" else "failed")
                    if (!dismissed) {
                        onComplete(fail())
                        return@closeMenu
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
            }
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
