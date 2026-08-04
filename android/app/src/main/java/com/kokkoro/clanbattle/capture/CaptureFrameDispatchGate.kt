package com.kokkoro.clanbattle.capture

/**
 * Coordinates the high-frequency TP path and the slower full-frame recognizer.
 *
 * Every captured frame may be sampled for TP, but only one full-frame recognition
 * may own an [android.media.Image] at a time. The interval is measured from the
 * last accepted slow frame so a slow recognizer cannot build an unbounded backlog.
 */
internal class CaptureFrameDispatchGate(
    private val slowFrameIntervalNanos: Long
) {
    private val lock = Any()
    private var nextLeaseId = 1L
    private var activeLeaseId: Long? = null
    private var lastSlowFrameNanos: Long? = null

    init {
        require(slowFrameIntervalNanos > 0L)
    }

    fun tryBeginSlowFrame(nowNanos: Long): Long? = synchronized(lock) {
        if (activeLeaseId != null) return@synchronized null
        val previous = lastSlowFrameNanos
        if (previous != null && nowNanos - previous < slowFrameIntervalNanos) {
            return@synchronized null
        }
        val leaseId = nextLeaseId++
        activeLeaseId = leaseId
        lastSlowFrameNanos = nowNanos
        leaseId
    }

    fun completeSlowFrame(leaseId: Long) = synchronized(lock) {
        if (activeLeaseId == leaseId) activeLeaseId = null
    }

    fun reset() = synchronized(lock) {
        activeLeaseId = null
        lastSlowFrameNanos = null
    }
}
