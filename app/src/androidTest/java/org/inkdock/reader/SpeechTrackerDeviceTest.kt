package org.inkdock.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs production native state tracking on Android; never asserts audible output. */
@RunWith(AndroidJUnit4::class)
class SpeechTrackerDeviceTest {
    @Test fun queueAcceptanceAndEngineStartAreDistinct() {
        val tracker = SpeechTracker()
        val queued = tracker.begin("utterance-1", "request-1", 42, 100)
        assertEquals("queued", queued.phase)
        assertEquals(0L, queued.elapsedMs)
        assertTrue(tracker.isActive())
        val started = tracker.event("utterance-1", "started", 125)
        assertEquals("started", started?.phase)
        assertEquals(25L, started?.elapsedMs)
        assertNull(tracker.event("utterance-1", "started", 130))
        assertEquals("done", tracker.event("utterance-1", "done", 200)?.phase)
        assertFalse(tracker.isActive())
        assertNull(tracker.event("utterance-1", "done", 210))
    }

    @Test fun staleIdsCannotAffectReplacementAndErrorIsTerminal() {
        val tracker = SpeechTracker()
        tracker.begin("old", "old-request", 5, 0)
        tracker.begin("new", "new-request", 8, 10)
        assertNull(tracker.event("old", "started", 20))
        assertNull(tracker.event("old", "error", 30, -5))
        assertNull(tracker.event(null, "done", 40))
        val error = tracker.event("new", "error", 50, -5)
        assertEquals("new-request", error?.requestId)
        assertEquals("error", error?.phase)
        assertEquals(-5, error?.errorCode)
        assertEquals(40L, error?.elapsedMs)
        assertFalse(tracker.isActive())
        assertNull(tracker.event("new", "started", 60))
    }

    @Test fun queuedTimeoutHasBoundaryAndDoesNotFailStartedSpeech() {
        val tracker = SpeechTracker()
        tracker.begin("queued", "request", 20, 100)
        assertNull(tracker.queuedTimeout("stale", 12_100))
        assertNull(tracker.queuedTimeout("queued", 12_099))
        val timeout = tracker.queuedTimeout("queued", 12_100)
        assertEquals("error", timeout?.phase)
        assertEquals(-1000, timeout?.errorCode)
        assertFalse(tracker.isActive())
        assertNull(tracker.event("queued", "started", 12_101))
        tracker.begin("started", "request-2", 30, 13_000)
        tracker.event("started", "started", 13_010)
        assertNull(tracker.queuedTimeout("started", 30_000))
        assertTrue(tracker.isActive())
        assertEquals("stopped", tracker.cancel(30_001)?.phase)
        assertFalse(tracker.isActive())
        assertNull(tracker.event("started", "done", 30_002))
    }
}
