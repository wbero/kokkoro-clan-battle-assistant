package com.kokkoro.clanbattle.pauseframe

import com.kokkoro.clanbattle.recognition.CharacterRole

enum class PauseFrameState { IDLE, SOFT_PAUSED, ADVANCING, CONFIRMING, MANUAL_MENU, FAILED }

enum class PauseFrameMode { AXIS, MANUAL }

data class PauseFrameSnapshot(
    val state: PauseFrameState,
    val nodeId: String?,
    val role: CharacterRole?,
    val blocksScheduler: Boolean,
    val mode: PauseFrameMode? = null
)

data class PauseFrameResult(
    val accepted: Boolean,
    val state: PauseFrameState,
    val nodeId: String? = null,
    val confirmedRole: CharacterRole? = null,
    val readyForConvergence: Boolean = false,
    val mode: PauseFrameMode? = null
)

data class PauseFrameDiagnosticEvent(
    val nodeId: String?,
    val role: CharacterRole?,
    val action: String,
    val result: String,
    val mode: PauseFrameMode? = null
)

class PauseFrameSession(
    private val focusPort: OverlayFocusPort,
    private val scheduler: PauseFrameScheduler,
    private val perFrameMs: Long = 40,
    private val focusTransitionMs: Long = 1_000,
    private val menuSettleMs: Long = 700,
    private val tapGapMs: Long = 700,
    private val diagnosticCallback: (PauseFrameDiagnosticEvent) -> Unit = {}
) {
    private var state = PauseFrameState.IDLE
    private var nodeId: String? = null
    private var role: CharacterRole? = null
    private var mode: PauseFrameMode? = null
    private var generation = 0L

    fun enter(nodeId: String, role: CharacterRole): PauseFrameResult =
        enter(PauseFrameMode.AXIS, nodeId, role)

    fun enterManual(): PauseFrameResult = enter(PauseFrameMode.MANUAL, null, null)

    private fun enter(
        mode: PauseFrameMode,
        nodeId: String?,
        role: CharacterRole?
    ): PauseFrameResult {
        if (state != PauseFrameState.IDLE) return result(accepted = false)
        generation++
        this.nodeId = nodeId
        this.role = role
        this.mode = mode
        diagnose("enter", "requested")
        val acquired = focusPort.acquireFocus()
        diagnose("focus-acquire", if (acquired) "success" else "failed")
        state = if (acquired) PauseFrameState.SOFT_PAUSED else PauseFrameState.FAILED
        return result(accepted = state == PauseFrameState.SOFT_PAUSED)
    }

    /**
     * 释放 [frameCount] 帧：解除卡帧、运行 frameCount×[frameMs] 后重新卡住。frameCount<1 视为 1。
     * 默认按会话构造时的 [perFrameMs] 步进；也可传入独立帧率（如独立卡帧悬浮窗各档自己的 ms/帧）。
     */
    fun release(frameCount: Int, frameMs: Long = perFrameMs): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED) return result(accepted = false)
        diagnose("advance", "requested")
        state = PauseFrameState.ADVANCING
        val released = focusPort.releaseFocus()
        diagnose("focus-release", if (released) "success" else "failed")
        if (!released) return fail()
        val advanceGeneration = generation
        val releaseMs = frameMs.coerceAtLeast(1L) * frameCount.coerceAtLeast(1)
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
        if (state != PauseFrameState.SOFT_PAUSED || mode != PauseFrameMode.AXIS) {
            return result(accepted = false)
        }
        val confirmedNode = nodeId
        val confirmedRole = role ?: return fail()
        diagnose("confirm", "requested")
        state = PauseFrameState.CONFIRMING
        val released = focusPort.releaseFocus()
        diagnose("focus-release", if (released) "success" else "failed")
        if (!released) return fail()
        val confirmGeneration = generation
        // 软卡帧时暂停菜单已经打开；直接点菜单头像设置 SET，再点菜单外恢复战斗。
        scheduler.schedule(focusTransitionMs + menuSettleMs) tapAvatar@{
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
                mode = null
                onComplete(
                    PauseFrameResult(
                        accepted = true,
                        state = state,
                        nodeId = confirmedNode,
                        confirmedRole = confirmedRole,
                        readyForConvergence = true,
                        mode = PauseFrameMode.AXIS
                    )
                )
            }
        }
        return result(accepted = true)
    }

    /**
     * Manual confirmation only returns focus to the game, which exposes its pause
     * menu. The user owns every menu click; recognition remains blocked until
     * [resumeManual] is explicitly requested.
     */
    fun confirmManual(): PauseFrameResult {
        if (state != PauseFrameState.SOFT_PAUSED || mode != PauseFrameMode.MANUAL) {
            return result(accepted = false)
        }
        diagnose("manual-menu", "requested")
        val released = focusPort.releaseFocus()
        diagnose("focus-release", if (released) "success" else "failed")
        if (!released) return fail()
        state = PauseFrameState.MANUAL_MENU
        return result(accepted = true)
    }

    fun resumeManual(): PauseFrameResult {
        if (state != PauseFrameState.MANUAL_MENU || mode != PauseFrameMode.MANUAL) {
            return result(accepted = false)
        }
        diagnose("manual-resume", "requested")
        state = PauseFrameState.IDLE
        nodeId = null
        role = null
        mode = null
        return PauseFrameResult(
            accepted = true,
            state = PauseFrameState.IDLE,
            mode = PauseFrameMode.MANUAL
        )
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
        mode = null
    }

    fun snapshot() = PauseFrameSnapshot(
        state = state,
        nodeId = nodeId,
        role = role,
        blocksScheduler = state != PauseFrameState.IDLE,
        mode = mode
    )

    private fun fail(): PauseFrameResult {
        state = PauseFrameState.FAILED
        return result(accepted = false)
    }

    private fun result(accepted: Boolean) = PauseFrameResult(
        accepted = accepted,
        state = state,
        nodeId = nodeId,
        mode = mode
    )

    private fun diagnose(action: String, result: String) {
        diagnosticCallback(PauseFrameDiagnosticEvent(nodeId, role, action, result, mode))
    }
}
