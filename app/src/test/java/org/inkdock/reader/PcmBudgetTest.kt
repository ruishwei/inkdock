package org.inkdock.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PcmBudgetTest {
    private fun reserve(budget: PcmBudget) = requireNotNull(budget.reserve { false })
    private fun buffer(budget: PcmBudget) = reserve(budget).accept(floatArrayOf(0f, 0.25f), 24_000)

    @Test fun twoReservationsExhaustCapacityAndWaitingCanBeCancelled() {
        val budget = PcmBudget()
        val first = reserve(budget)
        val second = reserve(budget)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val result = AtomicReference<PcmBudget.Reservation?>()
        val waiter = Thread {
            result.set(budget.reserve { entered.countDown(); cancelled.get() })
        }
        waiter.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(2, budget.inUse)
            cancelled.set(true)
            waiter.join(1_000)
            assertFalse("Cancellation must unblock a full budget", waiter.isAlive)
            assertNull(result.get())
            assertEquals(2, budget.inUse)
            assertEquals(2, budget.maxInUse)
        } finally {
            cancelled.set(true)
            first.release()
            second.release()
            waiter.interrupt()
            waiter.join(1_000)
            result.get()?.release()
        }
        assertEquals(0, budget.inUse)
    }

    @Test fun waitingReservationAcquiresOnlyAfterAnOwnerReleases() {
        val budget = PcmBudget(1)
        val first = reserve(budget)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val waiting = executor.submit<PcmBudget.Reservation?> {
                budget.reserve { entered.countDown(); false }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            first.release()
            val second = requireNotNull(waiting.get(1, TimeUnit.SECONDS))
            assertEquals(1, budget.inUse)
            second.release()
            assertEquals(0, budget.inUse)
            assertEquals(1, budget.maxInUse)
        } finally {
            first.release()
            executor.shutdownNow()
        }
    }

    @Test fun cancellationBeforeAcquireDoesNotConsumeCapacity() {
        val budget = PcmBudget()
        assertNull(budget.reserve { true })
        assertEquals(0, budget.inUse)
        assertEquals(0, budget.maxInUse)
    }

    @Test fun cancellationAfterAcquireReturnsThePermit() {
        val budget = PcmBudget()
        val checks = AtomicInteger()
        assertNull(budget.reserve { checks.incrementAndGet() > 1 })
        assertEquals(0, budget.inUse)
        assertEquals(1, budget.maxInUse)
    }

    @Test fun throwingCancellationCheckAfterAcquireReturnsThePermit() {
        val budget = PcmBudget()
        val checks = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            budget.reserve {
                if (checks.incrementAndGet() > 1) error("Failed cancellation check")
                false
            }
        }
        assertEquals(0, budget.inUse)
    }

    @Test fun interruptedWaitPreservesInterruptAndDoesNotStealAPermit() {
        val budget = PcmBudget(1)
        val held = reserve(budget)
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val result = AtomicReference<PcmBudget.Reservation?>()
        val waiter = Thread {
            result.set(budget.reserve { entered.countDown(); false })
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        waiter.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(1_000)
            assertFalse(waiter.isAlive)
            assertTrue(interrupted.get())
            assertNull(result.get())
            assertEquals(1, budget.inUse)
        } finally {
            held.release()
            waiter.interrupt()
            waiter.join(1_000)
            result.get()?.release()
        }
    }

    @Test fun invalidOutputsRejectAndReleaseTheirReservation() {
        val cases = listOf(
            floatArrayOf(0f) to 44_100,
            floatArrayOf() to 24_000,
            FloatArray(PcmBudget.MAX_SAMPLES + 1) to 24_000,
            floatArrayOf(Float.NaN) to 24_000,
            floatArrayOf(Float.POSITIVE_INFINITY) to 24_000,
            floatArrayOf(Float.NEGATIVE_INFINITY) to 24_000
        )
        cases.forEach { (samples, sampleRate) ->
            val budget = PcmBudget()
            val reservation = reserve(budget)
            assertThrows(IllegalArgumentException::class.java) { reservation.accept(samples, sampleRate) }
            assertEquals(0, budget.inUse)
            reservation.release()
            assertThrows(IllegalStateException::class.java) { reservation.accept(floatArrayOf(0f), 24_000) }
            assertEquals(0, budget.inUse)
        }
    }

    @Test fun exactSixtySecondBoundaryIsAcceptedWithoutCopying() {
        val budget = PcmBudget()
        val samples = FloatArray(1_440_000)
        val pcm = reserve(budget).accept(samples, 24_000)
        assertEquals(24_000, pcm.sampleRate)
        assertTrue(pcm.markPlaybackClaimed())
        assertSame(samples, pcm.samples)
        assertEquals(1, budget.inUse)
        pcm.finishPlayback()
        assertNull(pcm.samples)
        assertEquals(0, budget.inUse)
    }

    @Test fun successfulAcceptanceTransfersPermitExactlyOnce() {
        val budget = PcmBudget()
        val reservation = reserve(budget)
        val pcm = reservation.accept(floatArrayOf(0f), 24_000)
        reservation.release()
        reservation.release()
        assertThrows(IllegalStateException::class.java) { reservation.accept(floatArrayOf(0f), 24_000) }
        assertEquals(1, budget.inUse)
        pcm.discard()
        pcm.discard()
        pcm.finishPlayback()
        assertNull(pcm.samples)
        assertFalse(pcm.markPlaybackClaimed())
        assertEquals(0, budget.inUse)
    }

    @Test fun abandonedGenerationReservationCanBeReleasedRepeatedly() {
        val budget = PcmBudget()
        val reservation = reserve(budget)
        reservation.release()
        reservation.release()
        assertThrows(IllegalStateException::class.java) { reservation.accept(floatArrayOf(0f), 24_000) }
        assertEquals(0, budget.inUse)
    }

    @Test fun claimedDiscardRetainsSamplesAndPermitUntilPlaybackFinally() {
        val budget = PcmBudget()
        val pcm = buffer(budget)
        val pending = reserve(budget)
        assertTrue(pcm.markPlaybackClaimed())
        val playingSamples = pcm.samples
        pcm.discard()
        pcm.discard()
        assertFalse(pcm.markPlaybackClaimed())
        assertSame(playingSamples, pcm.samples)
        assertEquals(2, budget.inUse)
        val checks = AtomicInteger()
        assertNull(budget.reserve { checks.incrementAndGet() > 1 })
        assertEquals(2, budget.inUse)
        pcm.finishPlayback()
        pcm.finishPlayback()
        pcm.discard()
        assertNull(pcm.samples)
        assertEquals(1, budget.inUse)
        pending.release()
        assertEquals(0, budget.inUse)
        assertEquals(2, budget.maxInUse)
    }

    @Test fun finishingAnUnclaimedBufferDoesNotReleaseAnotherOwnersPermit() {
        val budget = PcmBudget()
        val pcm = buffer(budget)
        pcm.finishPlayback()
        assertEquals(1, budget.inUse)
        assertTrue(pcm.markPlaybackClaimed())
        pcm.finishPlayback()
        assertEquals(0, budget.inUse)
    }

    @Test(timeout = 10_000) fun concurrentClaimsAndDiscardHaveOneOwnerOrImmediateRelease() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            repeat(400) {
                val budget = PcmBudget()
                val pcm = buffer(budget)
                val start = CountDownLatch(1)
                val first = executor.submit<Boolean> { start.await(); pcm.markPlaybackClaimed() }
                val second = executor.submit<Boolean> { start.await(); pcm.markPlaybackClaimed() }
                val discard = executor.submit { start.await(); pcm.discard() }
                start.countDown()
                val claims = listOf(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS)).count { it }
                discard.get(1, TimeUnit.SECONDS)
                assertTrue(claims <= 1)
                assertEquals(claims, budget.inUse)
                if (claims == 1) {
                    assertNotNull(pcm.samples)
                    pcm.finishPlayback()
                }
                pcm.discard()
                pcm.finishPlayback()
                assertNull(pcm.samples)
                assertEquals(0, budget.inUse)
                assertEquals(1, budget.maxInUse)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000) fun racingAcceptanceAndReservationReleaseNeverDuplicatesPermit() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val budget = PcmBudget()
                val reservation = reserve(budget)
                val start = CountDownLatch(1)
                val accept = executor.submit<PcmBuffer?> {
                    start.await()
                    try { reservation.accept(floatArrayOf(0f), 24_000) }
                    catch (_: IllegalStateException) { null }
                }
                val release = executor.submit { start.await(); reservation.release() }
                start.countDown()
                val pcm = accept.get(1, TimeUnit.SECONDS)
                release.get(1, TimeUnit.SECONDS)
                assertEquals(if (pcm == null) 0 else 1, budget.inUse)
                pcm?.discard()
                assertEquals(0, budget.inUse)
                assertEquals(1, budget.maxInUse)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000) fun concurrentGenerationAndPlaybackStayWithinGlobalBudget() {
        val budget = PcmBudget()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val workers = (1..8).map {
                executor.submit {
                    repeat(200) {
                        val reservation = reserve(budget)
                        val pcm = try { reservation.accept(floatArrayOf(0f), 24_000) }
                        finally { reservation.release() }
                        assertTrue(pcm.markPlaybackClaimed())
                        try {
                            pcm.discard()
                            assertNotNull(pcm.samples)
                            assertTrue(budget.inUse in 1..2)
                        } finally {
                            pcm.finishPlayback()
                        }
                    }
                }
            }
            workers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(0, budget.inUse)
            assertTrue(budget.maxInUse in 1..2)
        } finally {
            executor.shutdownNow()
        }
    }
}
