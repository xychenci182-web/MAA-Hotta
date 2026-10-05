package com.aliothmoon.maahotta.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Login may wait for capture readiness; normal tasks keep their immediate unknown-result policy. */
internal class CaptureReadiness(
    private val now: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onWaiting: (Long) -> Unit = {},
    private val onWaitingWithoutDeadline: () -> Unit = {},
) {
    private var deadline: Long? = null
    private var awaitingCapture = false

    suspend fun <T> awaiting(action: suspend () -> T): T {
        val previous = awaitingCapture
        awaitingCapture = true
        return try { action() } finally { awaitingCapture = previous }
    }

    suspend fun <T> within(until: Long, action: suspend () -> T): T {
        val previous = deadline
        deadline = previous?.let { minOf(it, until) } ?: until
        return try { action() } finally { deadline = previous }
    }

    suspend fun <T : Any> read(capture: suspend () -> T?): T? {
        var reported = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val currentDeadline = deadline
            if (currentDeadline != null && now() >= currentDeadline) return null
            capture()?.let { return it }
            val remaining = currentDeadline?.minus(now())
            if (remaining != null) {
                if (remaining <= 0) return null
                if (!reported) { onWaiting(remaining); reported = true }
                wait(minOf(500, remaining))
            } else {
                if (!awaitingCapture) return null
                if (!reported) { onWaitingWithoutDeadline(); reported = true }
                wait(500)
            }
        }
    }
}
