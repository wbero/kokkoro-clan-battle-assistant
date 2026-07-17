package com.kokkoro.clanbattle.pauseframe

import com.kokkoro.clanbattle.recognition.CharacterRole

interface OverlayFocusPort {
    fun acquireFocus(): Boolean
    fun releaseFocus(): Boolean
    fun sendBack(): Boolean

    /** 卡帧确定：在主菜单“队伍情况”里点击该角色头像，设置其 SET。 */
    fun tapMenuRole(role: CharacterRole): Boolean

    /** 点击菜单外空白处，关闭菜单遮罩、恢复战斗。 */
    fun dismissMenu(): Boolean
}

fun interface PauseFrameScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
}
