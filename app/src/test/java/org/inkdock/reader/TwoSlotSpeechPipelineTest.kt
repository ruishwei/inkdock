package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class TwoSlotSpeechPipelineTest {
    private class Harness : TwoSlotSpeechPipeline.Port<String> {
        var clock = 0L
        val synth = mutableListOf<Pair<Long, String>>()
        val played = mutableListOf<Long>()
        val direct = mutableListOf<Long>()
        val disposed = mutableListOf<String>()
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        var cancelCalls = 0
        var immediateStartCallback = true
        val pipeline = TwoSlotSpeechPipeline(this) { clock }
        override fun synthesize(token: Long, text: String) { synth += token to text }
        override fun play(token: Long, audio: String) { played += token; if (immediateStartCallback) pipeline.playbackStarted(token) }
        override fun directSpeak(token: Long, text: String) { direct += token }
        override fun cancelSynthesis() { cancelCalls++ }
        override fun dispose(audio: String) { disposed += audio }
        override fun event(id: String, event: String, code: Int?) { events += "$id:$event" }
        override fun diagnostic(value: String) { diagnostics += value }
        fun ready(token: Long, asset: String = "audio-$token") { pipeline.synthesisDone(token); pipeline.ready(token, asset, 1000) }
    }
    @Test fun nextReallyReadyBeforeCurrentDoneAndNoThirdSlot() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "Synthetic A"); p.prefetch("b", "Synthetic B"); p.prefetch("c", "Synthetic C")
        assertEquals(2, p.slotCount); assertEquals(1, h.synth.size); assertFalse(p.readyAhead)
        val a = h.synth[0].first
        h.clock = 200; h.ready(a)
        assertEquals(2, h.synth.size); assertEquals(listOf(a), h.played)
        val b = h.synth[1].first
        h.clock = 400; h.ready(b)
        assertTrue(p.readyAhead); assertEquals(1, h.played.size)
        assertFalse(h.events.contains("a:done"))
        h.clock = 1200; p.playbackDone(a)
        assertEquals(listOf("a:started", "a:done"), h.events)
        p.speak("b", "Synthetic B"); p.prefetch("c", "Synthetic C")
        assertEquals(listOf(a, b), h.played); assertEquals(2, p.slotCount)
        assertTrue(h.diagnostics.any { it.contains("readyLeadMs=800 gapMs=0") })
    }
    @Test fun queuedNextIsNotReadyAndSlowSynthesisLeavesMeasuredGap() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.prefetch("b", "B")
        val a = h.synth[0].first; h.ready(a); val b = h.synth[1].first
        h.clock = 1000; p.playbackDone(a); p.speak("b", "B")
        assertFalse(p.readyAhead); assertEquals(1, h.played.size)
        h.clock = 1600; h.ready(b)
        assertEquals(2, h.played.size)
        assertTrue(h.diagnostics.any { it.contains("readyLeadMs=-600 gapMs=600") })
    }
    @Test fun synthesisDoneAloneDoesNotStartOrAdvancePlayback() {
        val h = Harness(); h.pipeline.speak("a", "A")
        h.pipeline.synthesisDone(h.synth.single().first)
        assertTrue(h.played.isEmpty()); assertTrue(h.events.isEmpty())
    }
    @Test fun cancellationDisposesReadyAudioAndRejectsAllLateCallbacks() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.prefetch("b", "B"); val a = h.synth[0].first
        h.ready(a); val b = h.synth[1].first; p.cancel()
        assertEquals(0, p.slotCount); assertTrue(h.disposed.contains("audio-$a"))
        p.playbackDone(a); h.ready(b)
        assertTrue(h.disposed.contains("audio-$b")); assertFalse(h.events.any { it.endsWith(":done") })
        assertEquals(1, h.played.size)
    }
    @Test fun newGenerationCannotPlayOldPreparedAudio() {
        val h = Harness(); val p = h.pipeline
        p.speak("same", "old"); val old = h.synth.single().first
        p.cancel(); p.speak("same", "new"); val fresh = h.synth.last().first
        h.ready(old); assertTrue(h.played.isEmpty()); h.ready(fresh)
        assertEquals(listOf(fresh), h.played)
    }
    @Test fun failureOfNextKeepsCurrentPlayingAndFallbackIsExplicit() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.prefetch("b", "B"); val a = h.synth[0].first; h.ready(a)
        val b = h.synth[1].first; p.failed(b, "file-request-rejected", -1)
        assertEquals("file-request-rejected", p.fallbackReason)
        assertTrue(h.direct.isEmpty()); assertFalse(h.disposed.contains("audio-$a"))
        p.playbackDone(a); p.speak("b", "B")
        assertEquals(1, h.direct.size); assertEquals(1, p.slotCount)
    }
    @Test fun initialFileFailureUsesDirectAndLateFileNeverPlays() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); val token = h.synth.single().first
        p.failed(token, "file-empty-or-oversize", -5)
        assertFalse(p.buffered); assertEquals(1, h.direct.size)
        h.ready(token); assertTrue(h.played.isEmpty())
        val direct = h.direct.single(); assertTrue(p.isDirectQueued(direct))
        p.directEvent(direct, "started"); assertFalse(p.isDirectQueued(direct))
        p.directEvent(direct, "done"); assertEquals(listOf("a:started", "a:done"), h.events)
    }
    @Test fun directFallbackFailureIsTerminalAndDoesNotLoop() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.failed(h.synth.single().first, "unsupported", -1)
        val token = h.direct.single(); p.directEvent(token, "error", -1); p.directEvent(token, "done")
        assertEquals(listOf("a:error"), h.events); assertEquals(1, h.direct.size)
        assertEquals(0, p.slotCount)
    }
    @Test fun repeatedStopIsSafeAndDoesNotReenableRejectedFileSupport() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.failed(h.synth.single().first, "unsupported", -1)
        p.cancel(); p.cancel(); p.speak("b", "B")
        assertFalse(p.buffered); assertEquals(2, h.direct.size); assertEquals(1, p.slotCount)
    }
    @Test fun manySegmentsNeverRetainMoreThanCurrentAndNext() {
        val h = Harness(); val p = h.pipeline
        p.speak("0", "0")
        for (i in 0 until 1000) {
            p.prefetch("${i + 1}", "${i + 1}")
            val token = p.currentToken!!
            if (!h.played.contains(token)) h.ready(token)
            h.synth.last().first.takeIf { it != token }?.let { h.ready(it) }
            assertTrue(p.slotCount <= 2)
            p.playbackDone(token); p.speak("${i + 1}", "${i + 1}")
            assertTrue(p.slotCount <= 2)
        }
        p.cancel(); assertEquals(0, p.slotCount)
    }
    @Test fun nativeNextFailureStopsWithoutSelectingADirectFallback() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.prefetch("b", "B")
        val a = h.synth[0].first; h.ready(a); val b = h.synth[1].first
        p.terminalFailure(b, "native-generation", -3)
        assertEquals(0, p.slotCount); assertTrue(h.direct.isEmpty())
        assertEquals(listOf("a:started", "a:error"), h.events)
        assertTrue(h.disposed.contains("audio-$a"))
        p.playbackDone(a); h.ready(b)
        assertFalse(h.events.any { it.endsWith(":done") })
    }
    @Test fun synthesisTimeoutGuardTurnsOffAtReadyAndAfterCancel() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); val a = h.synth.single().first
        assertTrue(p.isSynthesizing(a)); h.ready(a); assertFalse(p.isSynthesizing(a))
        p.cancel(); assertFalse(p.isSynthesizing(a))
        p.terminalFailure(a, "late-timeout", -3)
        assertEquals(listOf("a:started"), h.events)
    }
    @Test fun nativeCurrentFailureIsTerminalButExplicitNewRequestCanStart() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.prefetch("b", "B"); val a = h.synth.single().first
        p.terminalFailure(a, "native-generation", -3)
        assertEquals(listOf("a:error"), h.events)
        assertEquals(0, p.slotCount); assertTrue(h.direct.isEmpty())
        h.ready(a); p.playbackDone(a)
        assertTrue(h.played.isEmpty()); assertEquals(listOf("a:error"), h.events)
        p.speak("retry", "A"); val retry = h.synth.last().first
        h.ready(retry); assertEquals(listOf(retry), h.played)
    }
    @Test fun callbackGapIncludesOutputSetupAfterSchedulingHandoff() {
        val h = Harness(); val p = h.pipeline
        p.speak("a", "A"); p.prefetch("b", "B"); val a = h.synth.single().first
        h.ready(a); val b = h.synth.last().first
        h.clock = 200; h.ready(b)
        h.clock = 1000; p.playbackDone(a)
        h.immediateStartCallback = false
        p.speak("b", "B")
        h.clock = 1080; p.playbackStarted(b)
        assertTrue(h.diagnostics.any { it.contains("readyLeadMs=800 gapMs=0") })
        assertTrue(h.diagnostics.any { it.contains("play-start token=$b callbackGapMs=80") })
    }
    @Test fun diagnosticsContainNoSyntheticText() {
        val h = Harness(); h.pipeline.speak("opaque-id", "PRIVATE_SYNTHETIC_CONTENT")
        h.ready(h.synth.single().first)
        assertFalse(h.diagnostics.joinToString().contains("PRIVATE_SYNTHETIC_CONTENT"))
        assertFalse(h.diagnostics.joinToString().contains("opaque-id"))
    }
}
