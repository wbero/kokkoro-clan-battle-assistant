package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState

class ControlCropSampler(
    private val unknownIntervalMs: Long = 1_000L
) {
    private var lastUnknownSaveMs: Long? = null
    private var lastSafety = ControlSafetyState.RUNNING

    fun shouldSave(
        wallMs: Long,
        unknown: Boolean,
        action: ControlAction,
        safety: ControlSafetyState
    ): Boolean {
        val safetyTransition = safety != lastSafety
        lastSafety = safety
        val unknownDue = unknown && (lastUnknownSaveMs?.let { wallMs - it >= unknownIntervalMs } != false)
        if (unknownDue) lastUnknownSaveMs = wallMs
        return unknownDue || action != ControlAction.None || safetyTransition
    }
}
