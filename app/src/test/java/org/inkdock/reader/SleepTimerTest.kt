package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun presetsAndCustomUseMonotonicDeadline() {
        for (minutes in listOf(1, 15, 30, 60, 137, 720)) {
            val timer = SleepTimer()
            assertTrue(timer.set(minutes, 100))
            assertEquals(minutes * 60_000L, timer.snapshot(100).remainingMs)
            assertFalse(timer.expire(100 + minutes * 60_000L - 1))
            assertTrue(timer.expire(100 + minutes * 60_000L))
            assertTrue(timer.snapshot(Long.MAX_VALUE).expired)
            assertFalse(timer.snapshot(Long.MAX_VALUE).active)
            assertFalse(timer.expire(Long.MAX_VALUE))
        }
    }
    @Test fun invalidDurationDoesNotReplaceExistingTimer() {
        val timer = SleepTimer(); assertTrue(timer.set(15, 500))
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 721, Int.MAX_VALUE)) assertFalse(timer.set(minutes, 500))
        assertFalse(timer.set(1, -1)); assertFalse(timer.set(1, Long.MAX_VALUE))
        assertEquals(15 * 60_000L, timer.snapshot(500).remainingMs)
    }
    @Test fun pauseAndNewPlaybackDoNotExtendDeadline() {
        val timer = SleepTimer(); timer.set(30, 0)
        timer.beginUserPlayback()
        assertEquals(10 * 60_000L, timer.snapshot(20 * 60_000L).remainingMs)
        // Resume after deep sleep is checked against elapsed realtime, not delayed Handler uptime.
        assertTrue(timer.expire(35 * 60_000L))
        assertTrue(timer.snapshot(35 * 60_000L).expired)
    }
    @Test fun cancellationAndRearmInvalidateOldDeadline() {
        val timer = SleepTimer(); timer.set(15, 0); timer.cancel()
        assertFalse(timer.expire(15 * 60_000L)); assertFalse(timer.snapshot(0).expired)
        timer.set(15, 0); timer.set(60, 100)
        assertFalse(timer.expire(15 * 60_000L)); assertEquals(60, timer.snapshot(100).durationMinutes)
        assertTrue(timer.expire(60 * 60_000L + 100))
    }
    @Test fun expiryRemainsTerminalUntilExplicitUserGesture() {
        val timer = SleepTimer(); timer.set(1, 0); assertTrue(timer.expire(60_000))
        assertTrue(timer.snapshot(70_000).expired); assertFalse(timer.expire(70_000))
        timer.beginUserPlayback()
        assertFalse(timer.snapshot(70_000).expired); assertFalse(timer.snapshot(70_000).active)
    }
    @Test fun restartingProcessNeverRestoresTimerOrPlayback() {
        val previous = SleepTimer(); previous.set(60, 0)
        val restarted = SleepTimer()
        assertFalse(restarted.snapshot(100).active); assertFalse(restarted.snapshot(100).expired)
    }
}
