package org.inkdock.reader

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts both in-flight generation reservations and retained mono PCM buffers.
 * Reserve before generation, then accept its output or release in the generator's finally block.
 * The model's internal/transient allocations are deliberately outside this ownership budget.
 */
class PcmBudget(val capacity: Int = 2) {
    companion object {
        const val SAMPLE_RATE = 24_000
        const val MAX_SAMPLES = SAMPLE_RATE * 60
        private const val CANCEL_POLL_MILLIS = 25L
    }

    init { require(capacity > 0) { "PCM capacity must be positive." } }

    private val permits = Semaphore(capacity, true)
    private val used = AtomicInteger()
    private val highWater = AtomicInteger()

    val inUse: Int get() = used.get()
    val maxInUse: Int get() = highWater.get()

    /** Returns null on cancellation/interruption; waiting never allocates another PCM buffer. */
    fun reserve(isCancelled: () -> Boolean): Reservation? {
        while (!isCancelled()) {
            val acquired = try {
                permits.tryAcquire(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (!acquired) continue
            val count = used.incrementAndGet()
            highWater.updateAndGet { previous -> maxOf(previous, count) }
            var transferred = false
            try {
                // Cancellation can race with a permit becoming available.
                if (isCancelled()) return null
                val reservation = Reservation(this)
                transferred = true
                return reservation
            } finally {
                if (!transferred) releaseSlot()
            }
        }
        return null
    }

    private fun releaseSlot() {
        used.decrementAndGet()
        permits.release()
    }

    /** Single-use ownership of a permit, until it is released or transferred to a [PcmBuffer]. */
    class Reservation internal constructor(private val budget: PcmBudget) {
        private val lock = Any()
        private var open = true

        /**
         * Takes ownership of [samples] without copying. The caller must stop retaining/using it.
         * Invalid output consumes and releases this reservation before throwing.
         * A concurrent release either wins before acceptance or becomes a harmless no-op.
         */
        fun accept(samples: FloatArray, sampleRate: Int): PcmBuffer = synchronized(lock) {
            check(open) { "PCM reservation has already been consumed." }
            var transferred = false
            try {
                require(sampleRate == SAMPLE_RATE) { "PCM must be 24 kHz mono." }
                require(samples.isNotEmpty() && samples.size <= MAX_SAMPLES) {
                    "PCM must contain between 1 and $MAX_SAMPLES mono samples (at most 60 seconds)."
                }
                require(samples.all { it.isFinite() }) { "PCM contains a non-finite sample." }
                val buffer = PcmBuffer(samples, sampleRate, budget::releaseSlot)
                transferred = true
                buffer
            } finally {
                open = false
                if (!transferred) budget.releaseSlot()
            }
        }

        /** Call only once generation has stopped using its output; safe after accept and on retry. */
        fun release() = synchronized(lock) {
            if (open) {
                open = false
                budget.releaseSlot()
            }
        }
    }
}

/**
 * A retained PCM array and its budget permit have exactly the same lifetime.
 * Claim before queueing a playback task. Every successful claimant must finish in its worker's
 * finally block, including when cancellation happens while that task is still queued.
 */
class PcmBuffer internal constructor(
    samples: FloatArray,
    val sampleRate: Int,
    private val releaseSlot: () -> Unit
) {
    private enum class State { AVAILABLE, CLAIMED, RELEASED }
    private val lock = Any()
    private var state = State.AVAILABLE
    private var storage: FloatArray? = samples

    /** Access only while holding a successful playback claim; never retain after finishPlayback. */
    val samples: FloatArray? get() = synchronized(lock) { storage }

    /** Atomically prevents discard from releasing samples that a queued/running worker will use. */
    fun markPlaybackClaimed(): Boolean = synchronized(lock) {
        if (state != State.AVAILABLE) false else {
            state = State.CLAIMED
            true
        }
    }

    /** Unclaimed buffers release immediately. Claimed ones remain owned by their playback worker. */
    fun discard() = synchronized(lock) {
        if (state == State.AVAILABLE) releaseLocked()
    }

    /** Only the successful playback claimant may call this, after it stops using the array. */
    fun finishPlayback() = synchronized(lock) {
        if (state == State.CLAIMED) releaseLocked()
    }

    private fun releaseLocked() {
        state = State.RELEASED
        storage = null
        releaseSlot()
    }
}
