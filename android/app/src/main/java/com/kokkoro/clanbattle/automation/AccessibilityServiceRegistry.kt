package com.kokkoro.clanbattle.automation

/**
 * Tracks the currently connected accessibility-service instance.
 *
 * Android may bind a replacement service before the old instance receives
 * [android.app.Service.onUnbind]. Disconnecting the old instance must therefore
 * never clear the newer connection.
 */
internal class AccessibilityServiceRegistry<T : Any> {
    private var current: T? = null

    @Synchronized
    fun connect(service: T) {
        current = service
    }

    @Synchronized
    fun disconnect(service: T) {
        if (current === service) current = null
    }

    @Synchronized
    fun current(): T? = current
}
