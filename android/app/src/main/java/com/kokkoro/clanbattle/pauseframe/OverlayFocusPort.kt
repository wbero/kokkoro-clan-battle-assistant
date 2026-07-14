package com.kokkoro.clanbattle.pauseframe

import com.kokkoro.clanbattle.recognition.CharacterRole

interface OverlayFocusPort {
    fun acquireFocus(): Boolean
    fun releaseFocus(): Boolean
    fun sendBack(): Boolean
    fun tapRole(role: CharacterRole): Boolean
}

fun interface PauseFrameScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
}
