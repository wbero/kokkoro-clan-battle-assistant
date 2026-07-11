package com.kokkoro.clanbattle.capture

class ClockDebugSessionState<T : AutoCloseable>(private val factory: () -> T) : AutoCloseable {
    private var session: T? = null

    fun start() {
        val old = session
        session = null
        old?.close()
        session = factory()
    }

    fun current(): T? = session

    override fun close() {
        val old = session
        session = null
        old?.close()
    }
}
