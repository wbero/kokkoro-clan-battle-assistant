package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult

/**
 * 独立 TP 采样通道与完整识别之间的缓冲。
 *
 * 采样线程按显示帧率写入（[submit]），识别线程约每 60ms 读取一次（[take]）。
 * 两条不变式：
 * - **不丢事件**：两次读取之间发生的所有 UB 都会累积下来，一次性交付。
 * - **不重复交付**：同一次 UB 只会被交付一次；快照自带的 triggeredRoles 不参与交付，
 *   否则同一份快照会在后续每个完整帧里把同一次 UB 反复上报。
 */
class EnergySampleBuffer {
    private val lock = Any()
    private var latest: EnergyDetectionResult? = null
    private val pending = mutableMapOf<CharacterRole, Long>()
    private var pendingVisualObstruction = false

    fun submit(result: EnergyDetectionResult, sampleTimestampNanos: Long) = synchronized(lock) {
        latest = result
        result.triggeredRoles.forEach { role ->
            pending[role] = result.triggeredRoleTimesNanos[role] ?: sampleTimestampNanos
        }
        pendingVisualObstruction = pendingVisualObstruction || result.visualObstruction
    }

    /** 取出最新比例快照，并附上自上次取用以来累积的全部 UB 事件。 */
    fun take(): EnergyDetectionResult? = synchronized(lock) {
        val snapshot = latest ?: return@synchronized null
        val mergedTimes = pending.toMap()
        val merged = mergedTimes.keys
        val visualObstruction = pendingVisualObstruction
        pending.clear()
        pendingVisualObstruction = false
        snapshot.copy(
            triggeredRoles = merged,
            triggeredRoleTimesNanos = mergedTimes,
            visualObstruction = visualObstruction,
            characters = snapshot.characters.mapValues { (role, state) ->
                val triggered = role in merged
                if (state.triggered == triggered) state else state.copy(triggered = triggered)
            }
        )
    }

    fun reset() = synchronized(lock) {
        latest = null
        pending.clear()
        pendingVisualObstruction = false
    }
}
