package org.inkdock.reader

/** Process-local sleep timer. All times must use a monotonic elapsed-realtime clock.
 * Pausing never changes the deadline. Replacement playback inherits it; explicit stop/completion
 * cancels it. Expiry is sticky until an explicit user playback gesture, cancellation or new timer.
 */
internal class SleepTimer {
    data class Snapshot(val active: Boolean, val remainingMs: Long, val durationMinutes: Int, val expired: Boolean)
    private var deadlineMs: Long? = null
    private var minutes = 0
    private var expired = false

    fun set(durationMinutes: Int, nowMs: Long): Boolean {
        if (durationMinutes !in 1..MAX_MINUTES || nowMs < 0) return false
        val durationMs = durationMinutes * 60_000L
        if (nowMs > Long.MAX_VALUE - durationMs) return false
        minutes = durationMinutes
        deadlineMs = nowMs + durationMs
        expired = false
        return true
    }
    fun cancel() { deadlineMs = null; minutes = 0; expired = false }
    /** Consumes expiry once; caller must invalidate playback before accepting further callbacks. */
    fun expire(nowMs: Long): Boolean {
        val deadline = deadlineMs ?: return false
        if (nowMs < deadline) return false
        deadlineMs = null; expired = true
        return true
    }
    fun beginUserPlayback() { if (expired) cancel() }
    fun snapshot(nowMs: Long): Snapshot = Snapshot(deadlineMs != null,
        deadlineMs?.let { (it - nowMs).coerceAtLeast(0) } ?: 0, minutes, expired)
    companion object { const val MAX_MINUTES = 720 }
}
