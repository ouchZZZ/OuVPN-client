package com.example.myvpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bound blocking HTTP I/O as one attempt, including creation of its dedicated proxy. */
internal class TimedSpeedProbe(budgetMs: Long, ping: Boolean = false) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
    private val pool = if (ping) pings else bandwidth
    private var resource: AutoCloseable? = null
    private var closed = false
    @Volatile private var cleanupFailed = false

    @Synchronized fun own(value: AutoCloseable) {
        check(resource == null)
        resource = value
        if (closed) {
            closeResource()
            throw InterruptedException()
        }
    }

    fun <T> run(onTimeout: () -> T?, work: () -> T?): T? {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || !pool.slots.tryAcquire()) return onTimeout()
        val exited = CountDownLatch(1)
        val task = FutureTask<T?> {
            try { work() } finally { closeResource() }
        }
        pool.workers.execute {
            try { task.run() } finally {
                // Future.cancel() only marks the Future done, not the worker. Keep this slot
                // until work and cleanup actually exit; never reuse it after unconfirmed cleanup.
                if (!cleanupFailed) pool.slots.release()
                exited.countDown()
            }
        }
        return try {
            val result = task.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (System.nanoTime() >= deadline) onTimeout() else result
        } catch (_: TimeoutException) {
            onTimeout()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            task.cancel(true)
            try { closeResource() } finally { exited.await(CLEANUP_GRACE_MS, TimeUnit.MILLISECONDS) }
        }
    }

    @Synchronized private fun closeResource() {
        closed = true
        val owned = resource
        resource = null
        try { owned?.close() } catch (error: Exception) {
            cleanupFailed = true
            throw error
        }
    }

    private class ProbePool(capacity: Int) {
        // At most capacity pending/running tasks, including cancelled work still unwinding.
        val slots = Semaphore(capacity)
        val workers = Executors.newFixedThreadPool(capacity) { job ->
            Thread(job, "ouvpn-speed-probe").apply { isDaemon = true }
        }
    }

    companion object {
        private const val CLEANUP_GRACE_MS = 500L
        internal const val PARALLEL_PINGS = 5
        private val pings = ProbePool(PARALLEL_PINGS)
        private val bandwidth = ProbePool(1)
    }
}

internal class DownloadWindow(private val requestStart: Long, private val deadline: Long) {
    private var firstByte: Long? = null
    private var bytes = 0

    @Synchronized fun record(count: Int, now: Long) {
        if (count <= 0 || now >= deadline) return
        if (firstByte == null) firstByte = now
        bytes += count
    }

    @Synchronized fun sample(now: Long, complete: Boolean): NodeSpeed? {
        val first = firstByte ?: return null
        val elapsed = minOf(now, deadline) - first
        if (bytes < 16 * 1024 || (!complete && elapsed < TimeUnit.MILLISECONDS.toNanos(100))) return null
        val speed = bytes * 8000.0 / elapsed.coerceAtLeast(1_000_000)
        return NodeSpeed(speed, null, TimeUnit.NANOSECONDS.toMillis(first - requestStart))
    }
}
