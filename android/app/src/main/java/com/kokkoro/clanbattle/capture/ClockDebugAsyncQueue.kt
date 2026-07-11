package com.kokkoro.clanbattle.capture

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

class ClockDebugAsyncQueue(capacity: Int, private val onError: (Throwable) -> Unit = {}) : AutoCloseable {
    private sealed interface Task {
        class Run(val action: () -> Unit) : Task
        data object Stop : Task
    }

    private val lock = Any()
    private val tasks = ArrayBlockingQueue<Task>(capacity)
    private var accepting = true
    private val errors = AtomicLong()
    val errorCount: Long get() = errors.get()
    private val worker = Thread {
        while (true) when (val task = tasks.take()) {
            Task.Stop -> return@Thread
            is Task.Run -> try { task.action() } catch (error: Throwable) {
                errors.incrementAndGet()
                runCatching { onError(error) }
            }
        }
    }.apply { name = "clock-debug-recorder"; start() }

    fun submit(action: () -> Unit): Boolean = synchronized(lock) {
        accepting && tasks.offer(Task.Run(action))
    }

    fun control(action: () -> Unit): Boolean = synchronized(lock) {
        if (!accepting) return@synchronized false
        tasks.put(Task.Run(action))
        true
    }

    override fun close() {
        synchronized(lock) {
            if (!accepting) return
            accepting = false
            tasks.put(Task.Stop)
        }
        worker.join()
    }
}

class ClockDebugFrameSampler {
    private var lastSpecial: Long? = null
    private var lastFailure: Long? = null
    private var lastBaseline: Long? = null

    fun shouldSaveFrame(elapsedMs: Long, recognitionOk: Boolean, ambiguousOrOverridden: Boolean): Boolean {
        val interval = when { ambiguousOrOverridden -> 100L; !recognitionOk -> 250L; else -> 1_000L }
        val previous = when { ambiguousOrOverridden -> lastSpecial; !recognitionOk -> lastFailure; else -> lastBaseline }
        if (previous != null && elapsedMs - previous < interval) return false
        when { ambiguousOrOverridden -> lastSpecial = elapsedMs; !recognitionOk -> lastFailure = elapsedMs; else -> lastBaseline = elapsedMs }
        return true
    }

    fun cropFileNames(frameId: Long, slots: List<String>, saveFrame: Boolean): List<String> =
        if (saveFrame) slots.map { "frame-$frameId-${it.lowercase()}.png" } else emptyList()
}
