package org.inkdock.reader
import org.junit.Assert.*
import org.junit.Test

class SpeechTrackerTest {
    @Test fun acceptedRequestIsQueuedNotProofOfPlayback() {
        val state=SpeechTracker()
        assertEquals("queued",state.begin("a","reader-1",100,0).phase)
        assertEquals("started",state.event("a","started",20)?.phase)
        assertNull(state.event("a","started",30))
        assertEquals("done",state.event("a","done",500)?.phase)
        assertFalse(state.isActive())
    }
    @Test fun staleAndDuplicateCallbacksCannotAdvanceNewRequest() {
        val state=SpeechTracker();state.begin("a","old",20,0);state.begin("b","new",50,10)
        assertNull(state.event("a","done",20));assertNull(state.event(null,"done",20))
        assertEquals("new",state.event("b","error",30,-5)?.requestId)
        assertNull(state.event("b","done",40))
    }
    @Test fun queueTimeoutAndCancellationAreTerminal() {
        val state=SpeechTracker();state.begin("a","r",50,0)
        assertNull(state.queuedTimeout("a",11999));assertEquals(-1000,state.queuedTimeout("a",12000)?.errorCode)
        state.begin("b","r2",40,13000);state.event("b","started",14000)
        assertNull(state.queuedTimeout("b",50000));assertEquals("stopped",state.cancel(50001)?.phase)
        assertNull(state.event("b","done",50002))
    }
    @Test fun quickCompletionRemainsEngineReportNotAudibilityProof() {
        val state=SpeechTracker();state.begin("a","r",100,100)
        val done=state.event("a","done",110)
        assertEquals(10L,done?.elapsedMs);assertEquals("done",done?.phase)
    }
}
