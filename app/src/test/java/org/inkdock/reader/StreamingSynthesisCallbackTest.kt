package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class StreamingSynthesisCallbackTest {
    @Test fun orderedCallbacksPreserveEverySampleAndValidateFinalCount() {
        val pipe = BoundedPcmStream(8); var time = 4L
        val callback = StreamingSynthesisCallback(pipe, { true }, { time++ })
        assertEquals(1, callback(floatArrayOf(0.1f, -0.2f)))
        assertEquals(1, callback(floatArrayOf(0.3f)))
        assertEquals(2, callback.callbackCount); assertEquals(4, callback.firstCallbackAt)
        assertTrue(callback.acceptsFinal(24000, 3)); assertFalse(callback.acceptsFinal(24000, 2))
        assertFalse(callback.acceptsFinal(16000, 3))
        pipe.finish(); val result = FloatArray(3); assertEquals(3, pipe.read(result))
        assertArrayEquals(floatArrayOf(0.1f, -0.2f, 0.3f), result, 0f)
    }
    @Test fun callbackNotificationExceptionNeverEscapesToJni() {
        val pipe = BoundedPcmStream(8, onFirstPcm = { throw IllegalStateException("synthetic") })
        val callback = StreamingSynthesisCallback(pipe, { true }, { 0 })
        assertEquals(0, callback(floatArrayOf(0.1f))); assertTrue(callback.failed)
        assertFalse(callback.acceptsFinal(24000, 1)); assertEquals(-1, pipe.read(FloatArray(1)))
    }
    @Test fun emptyGenerationCannotBecomeSuccessfulEof() {
        val callback = StreamingSynthesisCallback(BoundedPcmStream(8), { true }, { 0 })
        assertEquals(1, callback(FloatArray(0))); assertFalse(callback.acceptsFinal(24000, 0))
    }
    @Test fun invalidAndOversizedPcmAbortWithoutPlayingTruncatedResult() {
        for (samples in listOf(floatArrayOf(Float.NaN), floatArrayOf(1f, 2f, 3f))) {
            val pipe = BoundedPcmStream(8)
            val callback = StreamingSynthesisCallback(pipe, { true }, { 0 }, maxSamples = 2)
            assertEquals(0, callback(samples)); assertTrue(callback.failed)
            assertEquals(0, pipe.queuedSamples); assertFalse(callback.acceptsFinal(24000, samples.size))
        }
    }
    @Test fun generationCancellationDoesNotPublishOldPcm() {
        val pipe = BoundedPcmStream(8)
        val callback = StreamingSynthesisCallback(pipe, { false }, { 0 })
        assertEquals(0, callback(floatArrayOf(1f))); assertEquals(0, pipe.totalWritten)
    }
}
