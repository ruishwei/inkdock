package org.inkdock.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class BoundedPcmStreamTest {
    private fun await(latch: CountDownLatch) {
        assertTrue("Worker did not reach the expected state", latch.await(2, TimeUnit.SECONDS))
    }

    private fun assertExact(expected: FloatArray, actual: FloatArray) {
        assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
    }

    @Test fun validatesCapacityAndReadTarget() {
        assertThrows(IllegalArgumentException::class.java) { BoundedPcmStream(0) }
        assertThrows(IllegalArgumentException::class.java) { BoundedPcmStream(-1) }
        val stream = BoundedPcmStream(1)
        assertThrows(IllegalArgumentException::class.java) { stream.read(FloatArray(0)) }
        stream.cancel()
        assertThrows(IllegalArgumentException::class.java) { stream.read(FloatArray(0)) }
    }

    @Test fun defaultCapacityIsEightSecondsAtTwentyFourKhz() {
        val stream = BoundedPcmStream()
        assertTrue(stream.write(FloatArray(192_000)))
        assertEquals(192_000, stream.queuedSamples)
        assertEquals(192_000, stream.maxQueuedSamples)
        stream.cancel()
    }

    @Test fun cancelledStreamObjectDoesNotRetainItsPcmStorage() {
        val stream = BoundedPcmStream(8)
        assertTrue(stream.write(floatArrayOf(1f)))
        stream.cancel()
        val storage = BoundedPcmStream::class.java.getDeclaredField("ring").apply { isAccessible = true }
        assertNull(storage.get(stream))
        assertEquals(-1, stream.read(FloatArray(1)))
        assertFalse(stream.write(floatArrayOf(2f)))
    }

    @Test fun emptyWritesDoNotSignalOrChangeCounters() {
        val calls = AtomicInteger()
        val stream = BoundedPcmStream(2, onFirstPcm = { calls.incrementAndGet() })
        repeat(3) { assertTrue(stream.write(FloatArray(0))) }
        assertEquals(0, calls.get())
        assertEquals(0, stream.queuedSamples)
        assertEquals(0, stream.maxQueuedSamples)
        assertEquals(0L, stream.totalWritten)
        assertEquals(0L, stream.totalRead)
        assertEquals(0L, stream.writeWaitNanos)
        assertEquals(0L, stream.readWaitNanos)
        assertEquals(0L, stream.readWaitCount)
        assertTrue(stream.write(floatArrayOf(0.5f)))
        assertEquals(1, stream.read(FloatArray(1)))
        assertTrue(stream.write(floatArrayOf(-0.5f)))
        assertEquals(1, calls.get())
        stream.finish()
        assertFalse(stream.write(FloatArray(0)))
        assertEquals(0L, stream.writeWaitNanos)
        assertEquals(0L, stream.readWaitNanos)
        assertEquals(0L, stream.readWaitCount)
    }

    @Test fun copiesCallerStorageWithoutClippingOrChangingFloatBits() {
        // The engine validates finite samples; the transport itself must preserve every bit.
        val expected = floatArrayOf(
            0f, -0f, 3.5f, -8.25f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.fromBits(0x7fc01234)
        )
        val source = expected.copyOf()
        val stream = BoundedPcmStream(source.size)
        assertTrue(stream.write(source))
        source.fill(42f)
        val actual = FloatArray(expected.size + 2) { 99f }
        assertEquals(expected.size, stream.read(actual))
        assertExact(expected, actual.copyOf(expected.size))
        assertEquals(99f, actual[expected.size], 0f)
        assertEquals(99f, actual[expected.size + 1], 0f)
        stream.finish()
        assertEquals(0, stream.read(actual))
        assertEquals(expected.size.toLong(), stream.totalWritten)
        assertEquals(expected.size.toLong(), stream.totalRead)
    }

    @Test(timeout = 10_000) fun firstSignalPrecedesOversizedWriteCompletionAndOccupancyStaysBounded() {
        val firstPcm = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val signals = AtomicInteger()
        val source = FloatArray(101) { (it - 50) / 16f }
        val stream = BoundedPcmStream(4, onFirstPcm = {
            signals.incrementAndGet()
            firstPcm.countDown()
            await(releaseCallback)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val producer = executor.submit<Boolean> { stream.write(source) }
            await(firstPcm)
            assertFalse("First PCM must be reported before the oversized write finishes", producer.isDone)
            assertEquals(4, stream.queuedSamples)
            assertEquals(4L, stream.totalWritten)
            val consumer = executor.submit<FloatArray> {
                val output = ArrayList<Float>()
                val target = FloatArray(3)
                while (true) {
                    val count = stream.read(target)
                    assertTrue(count >= 0)
                    if (count == 0) break
                    repeat(count) { output.add(target[it]) }
                    assertTrue(stream.queuedSamples in 0..4)
                }
                output.toFloatArray()
            }
            releaseCallback.countDown()
            assertTrue(producer.get(2, TimeUnit.SECONDS))
            stream.finish()
            assertExact(source, consumer.get(2, TimeUnit.SECONDS))
            assertEquals(1, signals.get())
            assertEquals(4, stream.maxQueuedSamples)
            assertEquals(0, stream.queuedSamples)
            assertEquals(source.size.toLong(), stream.totalWritten)
            assertEquals(source.size.toLong(), stream.totalRead)
        } finally {
            releaseCallback.countDown()
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000) fun varyingCallbacksAndReadSizesPreserveWaveformAcrossManyWraps() {
        val source = FloatArray(2_003) { Float.fromBits(0x38000000 + it * 733) }
        val stream = BoundedPcmStream(7)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val producer = executor.submit<Boolean> {
                var offset = 0
                while (offset < source.size) {
                    val count = minOf(1 + offset % 23, source.size - offset)
                    val callback = source.copyOfRange(offset, offset + count)
                    if (!stream.write(callback)) return@submit false
                    callback.fill(-123f)
                    offset += count
                }
                stream.finish()
                true
            }
            val output = ArrayList<Float>()
            var readNumber = 0
            while (true) {
                val target = FloatArray(1 + readNumber++ % 11)
                val count = stream.read(target)
                assertTrue(count >= 0)
                if (count == 0) break
                repeat(count) { output.add(target[it]) }
                assertTrue(stream.queuedSamples in 0..7)
            }
            assertTrue(producer.get(2, TimeUnit.SECONDS))
            assertExact(source, output.toFloatArray())
            assertTrue(stream.maxQueuedSamples in 1..7)
            assertEquals(source.size.toLong(), stream.totalWritten)
            assertEquals(source.size.toLong(), stream.totalRead)
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun finishPreservesQueuedSamplesUntilAllHaveBeenRead() {
        val stream = BoundedPcmStream(5)
        assertTrue(stream.write(floatArrayOf(1f, 2f, 3f, 4f, 5f)))
        stream.finish()
        stream.finish()
        assertFalse(stream.write(floatArrayOf(6f)))
        assertEquals(5, stream.queuedSamples)
        val target = FloatArray(2)
        assertEquals(2, stream.read(target))
        assertExact(floatArrayOf(1f, 2f), target)
        assertEquals(2, stream.read(target))
        assertExact(floatArrayOf(3f, 4f), target)
        assertEquals(1, stream.read(target))
        assertEquals(5f, target[0], 0f)
        repeat(2) { assertEquals(0, stream.read(target)) }
        assertEquals(5L, stream.totalWritten)
        assertEquals(5L, stream.totalRead)
    }

    @Test(timeout = 5_000) fun emptyReaderWaitsUntilFinishInsteadOfReturningPrematureEof() {
        val polls = CountDownLatch(2)
        val stream = BoundedPcmStream(2, isCancelled = { polls.countDown(); false })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<Int> { stream.read(FloatArray(1)) }
            await(polls)
            assertFalse(reader.isDone)
            stream.finish()
            assertEquals(0, reader.get(2, TimeUnit.SECONDS).toInt())
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000) fun finishReleasesFullWriterButKeepsAlreadyAcceptedSamples() {
        val fullPolls = CountDownLatch(2)
        lateinit var stream: BoundedPcmStream
        stream = BoundedPcmStream(2, isCancelled = {
            if (stream.queuedSamples == 2) fullPolls.countDown()
            false
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit<Boolean> { stream.write(floatArrayOf(1f, 2f, 3f)) }
            await(fullPolls)
            assertFalse(writer.isDone)
            stream.finish()
            assertFalse(writer.get(2, TimeUnit.SECONDS))
            val target = FloatArray(4)
            assertEquals(2, stream.read(target))
            assertExact(floatArrayOf(1f, 2f), target.copyOf(2))
            assertEquals(0, stream.read(target))
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000) fun cancelReleasesBlockedWriterAndRepeatedCancelNeverReopensStream() {
        val fullPolls = CountDownLatch(2)
        lateinit var stream: BoundedPcmStream
        stream = BoundedPcmStream(2, isCancelled = {
            if (stream.queuedSamples == 2) fullPolls.countDown()
            false
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit<Boolean> { stream.write(floatArrayOf(1f, 2f, 3f)) }
            await(fullPolls)
            assertFalse(writer.isDone)
            repeat(3) { stream.cancel() }
            assertFalse(writer.get(2, TimeUnit.SECONDS))
            stream.finish()
            stream.cancel()
            assertEquals(-1, stream.read(FloatArray(2)))
            assertFalse(stream.write(floatArrayOf(9f)))
            assertFalse(stream.write(FloatArray(0)))
            assertEquals(0, stream.queuedSamples)
            assertEquals(2, stream.maxQueuedSamples)
            assertEquals(2L, stream.totalWritten)
            assertEquals(0L, stream.totalRead)
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000) fun cancelReleasesBlockedReaderWithoutTouchingItsTarget() {
        val polls = CountDownLatch(2)
        val stream = BoundedPcmStream(2, isCancelled = { polls.countDown(); false })
        val executor = Executors.newSingleThreadExecutor()
        val target = floatArrayOf(7f, 8f)
        try {
            val reader = executor.submit<Int> { stream.read(target) }
            await(polls)
            assertFalse(reader.isDone)
            stream.cancel()
            assertEquals(-1, reader.get(2, TimeUnit.SECONDS).toInt())
            assertExact(floatArrayOf(7f, 8f), target)
            assertEquals(0L, stream.totalRead)
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000) fun cancellationPredicateReleasesEitherWaitWithoutAnExplicitCancelCall() {
        for (writing in listOf(false, true)) {
            val externalCancellation = AtomicBoolean()
            val blockedPolls = CountDownLatch(2)
            lateinit var stream: BoundedPcmStream
            stream = BoundedPcmStream(1, isCancelled = {
                if (!writing || stream.queuedSamples == 1) blockedPolls.countDown()
                externalCancellation.get()
            })
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Int> {
                    if (writing) {
                        if (stream.write(floatArrayOf(1f, 2f))) 1 else -1
                    } else stream.read(FloatArray(1))
                }
                await(blockedPolls)
                assertFalse(result.isDone)
                externalCancellation.set(true)
                assertEquals(-1, result.get(2, TimeUnit.SECONDS).toInt())
                assertEquals(0, stream.queuedSamples)
                assertEquals(-1, stream.read(FloatArray(1)))
            } finally {
                stream.cancel()
                executor.shutdownNow()
            }
        }
    }

    @Test(timeout = 10_000) fun interruptedWaitCancelsStreamAndPreservesInterruptStatus() {
        for (writing in listOf(false, true)) {
            val blockedPolls = CountDownLatch(2)
            val worker = AtomicReference<Thread>()
            lateinit var stream: BoundedPcmStream
            stream = BoundedPcmStream(1, isCancelled = {
                if (!writing || stream.queuedSamples == 1) blockedPolls.countDown()
                false
            })
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Pair<Int, Boolean>> {
                    worker.set(Thread.currentThread())
                    val count = if (writing) {
                        if (stream.write(floatArrayOf(1f, 2f))) 1 else -1
                    } else stream.read(FloatArray(1))
                    count to Thread.currentThread().isInterrupted
                }
                await(blockedPolls)
                assertFalse(result.isDone)
                worker.get().interrupt()
                assertEquals(-1 to true, result.get(2, TimeUnit.SECONDS))
                assertEquals(0, stream.queuedSamples)
                assertEquals(-1, stream.read(FloatArray(1)))
            } finally {
                stream.cancel()
                executor.shutdownNow()
            }
        }
    }

    @Test(timeout = 10_000) fun waitMetricsSeparateProducerBackpressureFromConsumerStarvation() {
        val emptyPolls = CountDownLatch(2)
        val fullPolls = CountDownLatch(2)
        val observeFull = AtomicBoolean()
        lateinit var stream: BoundedPcmStream
        stream = BoundedPcmStream(1, isCancelled = {
            if (observeFull.get()) {
                if (stream.queuedSamples == 1) fullPolls.countDown()
            } else emptyPolls.countDown()
            false
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<Int> { stream.read(FloatArray(1)) }
            await(emptyPolls)
            assertTrue(stream.write(floatArrayOf(1f)))
            assertEquals(1, reader.get(2, TimeUnit.SECONDS).toInt())
            val readWaitNanos = stream.readWaitNanos
            val readWaitCount = stream.readWaitCount
            assertTrue(readWaitNanos > 0L)
            assertTrue(readWaitCount > 0L)
            assertEquals(0L, stream.writeWaitNanos)

            observeFull.set(true)
            val writer = executor.submit<Boolean> { stream.write(floatArrayOf(2f, 3f)) }
            await(fullPolls)
            val initialWriteWaitNanos = stream.writeWaitNanos
            assertTrue(initialWriteWaitNanos > 0L)
            assertEquals(1, stream.read(FloatArray(1)))
            assertTrue(writer.get(2, TimeUnit.SECONDS))
            assertEquals(1, stream.read(FloatArray(1)))
            stream.finish()
            assertEquals(0, stream.read(FloatArray(1)))
            assertTrue(stream.writeWaitNanos >= initialWriteWaitNanos)
            assertEquals(readWaitNanos, stream.readWaitNanos)
            assertEquals(readWaitCount, stream.readWaitCount)
            val writeWaitNanos = stream.writeWaitNanos
            stream.cancel()
            stream.cancel()
            assertEquals(writeWaitNanos, stream.writeWaitNanos)
            assertEquals(readWaitNanos, stream.readWaitNanos)
            assertEquals(readWaitCount, stream.readWaitCount)
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000) fun firstCallbackRunsOutsideMonitorAndCanReenterFinish() {
        val executor = Executors.newSingleThreadExecutor()
        val signals = AtomicInteger()
        lateinit var stream: BoundedPcmStream
        stream = BoundedPcmStream(3, onFirstPcm = {
            signals.incrementAndGet()
            // Another thread must be able to enter read while this callback is still running.
            val target = executor.submit<FloatArray> {
                val samples = FloatArray(3)
                assertEquals(3, stream.read(samples))
                samples
            }.get(2, TimeUnit.SECONDS)
            assertExact(floatArrayOf(1f, 2f, 3f), target)
            assertEquals(3L, stream.totalRead)
            stream.finish()
        })
        try {
            assertTrue(stream.write(floatArrayOf(1f, 2f, 3f)))
            assertEquals(1, signals.get())
            assertEquals(0, stream.read(FloatArray(1)))
        } finally {
            stream.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun firstCallbackCanCancelOversizedWriteSynchronously() {
        val signals = AtomicInteger()
        lateinit var stream: BoundedPcmStream
        stream = BoundedPcmStream(2, onFirstPcm = {
            signals.incrementAndGet()
            assertEquals(2, stream.queuedSamples)
            stream.cancel()
        })
        assertFalse(stream.write(floatArrayOf(1f, 2f, 3f)))
        assertEquals(1, signals.get())
        assertEquals(0, stream.queuedSamples)
        assertEquals(2L, stream.totalWritten)
        assertEquals(-1, stream.read(FloatArray(1)))
    }

    @Test fun firstCallbackCanReadSynchronously() {
        val target = FloatArray(2)
        lateinit var stream: BoundedPcmStream
        stream = BoundedPcmStream(2, onFirstPcm = { assertEquals(2, stream.read(target)) })
        assertTrue(stream.write(floatArrayOf(0.5f, -0.5f)))
        assertExact(floatArrayOf(0.5f, -0.5f), target)
        assertEquals(0, stream.queuedSamples)
        assertEquals(2L, stream.totalRead)
    }

    @Test fun failingFirstCallbackCancelsRatherThanLeavingAFullStreamBehind() {
        val failure = IllegalStateException("Playback could not start")
        val stream = BoundedPcmStream(1, onFirstPcm = { throw failure })
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            stream.write(floatArrayOf(1f, 2f))
        })
        assertEquals(0, stream.queuedSamples)
        assertFalse(stream.write(floatArrayOf(3f)))
        assertEquals(-1, stream.read(FloatArray(1)))
    }
}
