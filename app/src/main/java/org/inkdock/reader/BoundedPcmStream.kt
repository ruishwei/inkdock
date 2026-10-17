package org.inkdock.reader

/**
 * A fixed-size mono PCM channel for one callback producer and one playback consumer.
 *
 * Writes copy into owned storage and apply backpressure instead of retaining callback arrays.
 * [onFirstPcm] runs once, outside the monitor, as soon as the first samples are readable. It
 * must start playback without waiting for generation to finish: a single callback can exceed
 * the entire ring. Samples are copied unchanged; format/output validation belongs to the engine.
 */
class BoundedPcmStream(
    capacitySamples: Int = 192_000,
    private val isCancelled: () -> Boolean = { false },
    private val onFirstPcm: () -> Unit = {}
) {
    companion object {
        private const val CANCEL_POLL_MILLIS = 25L
    }

    init { require(capacitySamples > 0) { "PCM stream capacity must be positive." } }

    private val lock = Object()
    private var ring: FloatArray? = FloatArray(capacitySamples)
    private var readIndex = 0
    private var writeIndex = 0
    private var queued = 0
    private var highWater = 0
    private var written = 0L
    private var consumed = 0L
    private var producerWaitNanos = 0L
    private var consumerWaitNanos = 0L
    private var consumerWaitCount = 0L
    private var firstPcmSignalled = false
    private var finished = false
    private var cancelled = false

    val queuedSamples: Int get() = synchronized(lock) { queued }
    val maxQueuedSamples: Int get() = synchronized(lock) { highWater }
    val totalWritten: Long get() = synchronized(lock) { written }
    val totalRead: Long get() = synchronized(lock) { consumed }
    val writeWaitNanos: Long get() = synchronized(lock) { producerWaitNanos }
    val readWaitNanos: Long get() = synchronized(lock) { consumerWaitNanos }
    val readWaitCount: Long get() = synchronized(lock) { consumerWaitCount }

    /**
     * Copies all samples in order, waiting only when the ring is full. Returns false if closed
     * before the whole write is accepted, cancelled, or interrupted. The caller may reuse its
     * array after this method returns. Empty writes never signal first PCM.
     */
    fun write(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return synchronized(lock) { !cancelledLocked() && !finished }
        var offset = 0
        while (offset < samples.size) {
            var signalFirstPcm = false
            synchronized(lock) {
                if (cancelledLocked() || finished) return false
                val storage = requireNotNull(ring)
                if (queued == storage.size) {
                    if (!awaitChangeLocked(reading = false)) return false
                } else {
                    // A contiguous copy keeps every allocation bounded by the fixed ring.
                    val count = minOf(samples.size - offset, storage.size - queued, storage.size - writeIndex)
                    samples.copyInto(storage, writeIndex, offset, offset + count)
                    offset += count
                    writeIndex += count
                    if (writeIndex == storage.size) writeIndex = 0
                    queued += count
                    written += count
                    highWater = maxOf(highWater, queued)
                    if (!firstPcmSignalled) {
                        firstPcmSignalled = true
                        signalFirstPcm = true
                    }
                    lock.notifyAll()
                }
            }
            // This must happen before trying to copy/wait for an oversized callback's remainder.
            // Playback startup may reenter read(), finish(), or cancel(), or use another thread.
            if (signalFirstPcm) {
                try {
                    onFirstPcm()
                } catch (failure: Throwable) {
                    cancel()
                    throw failure
                }
            }
        }
        return synchronized(lock) { !cancelledLocked() }
    }

    /** Returns a positive sample count, 0 only after EOF has drained, or -1 on cancellation. */
    fun read(target: FloatArray): Int {
        require(target.isNotEmpty()) { "PCM read target must not be empty." }
        synchronized(lock) {
            while (true) {
                if (cancelledLocked()) return -1
                if (queued > 0) {
                    val storage = requireNotNull(ring)
                    val count = minOf(target.size, queued)
                    val beforeWrap = minOf(count, storage.size - readIndex)
                    storage.copyInto(target, 0, readIndex, readIndex + beforeWrap)
                    readIndex += beforeWrap
                    if (readIndex == storage.size) readIndex = 0
                    if (count > beforeWrap) {
                        storage.copyInto(target, beforeWrap, 0, count - beforeWrap)
                        readIndex = count - beforeWrap
                    }
                    queued -= count
                    consumed += count
                    lock.notifyAll()
                    return count
                }
                if (finished) return 0
                if (!awaitChangeLocked(reading = true)) return -1
            }
        }
    }

    /** Marks generation EOF. Already queued samples remain readable. */
    fun finish() = synchronized(lock) {
        finished = true
        lock.notifyAll()
    }

    /** Invalidates this stream, discards queued samples, and releases both waits. Idempotent. */
    fun cancel() = synchronized(lock) { cancelLocked() }

    private fun cancelLocked() {
        cancelled = true
        ring = null // Late posted callbacks may retain the stream object, never its released PCM storage.
        queued = 0
        readIndex = 0
        writeIndex = 0
        lock.notifyAll()
    }

    private fun cancelledLocked(): Boolean {
        if (!cancelled && isCancelled()) cancelLocked()
        return cancelled
    }

    private fun awaitChangeLocked(reading: Boolean): Boolean {
        val started = System.nanoTime()
        try {
            lock.wait(CANCEL_POLL_MILLIS)
            return true
        } catch (_: InterruptedException) {
            cancelLocked()
            Thread.currentThread().interrupt()
            return false
        } finally {
            // Includes reacquiring the monitor after wait; excludes copying and callback work.
            val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
            if (reading) {
                consumerWaitNanos += elapsed
                consumerWaitCount++
            } else {
                producerWaitNanos += elapsed
            }
        }
    }
}
