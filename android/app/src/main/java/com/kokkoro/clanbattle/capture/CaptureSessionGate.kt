package com.kokkoro.clanbattle.capture

internal class CaptureSessionGate {
    private var activeSessionId: Long? = null
    private var pendingSessionId: Long? = null

    @Synchronized
    fun begin(sessionId: Long): Boolean {
        require(sessionId > 0L)
        if (sessionId == activeSessionId || sessionId == pendingSessionId) return false
        pendingSessionId = sessionId
        return true
    }

    @Synchronized
    fun activate(sessionId: Long): Boolean {
        if (pendingSessionId != sessionId) return false
        activeSessionId = sessionId
        pendingSessionId = null
        return true
    }

    @Synchronized
    fun fail(sessionId: Long) {
        if (pendingSessionId == sessionId) pendingSessionId = null
    }

    @Synchronized
    fun clear() {
        activeSessionId = null
        pendingSessionId = null
    }
}
