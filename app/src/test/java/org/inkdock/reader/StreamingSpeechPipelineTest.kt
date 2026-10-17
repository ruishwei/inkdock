package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class StreamingSpeechPipelineTest {
    private class Harness : StreamingSpeechPipeline.Port<String> {
        var time = 0L
        val generated = mutableListOf<Long>()
        val played = mutableListOf<Long>()
        val disposed = mutableListOf<String>()
        val events = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var onEvent: ((String, String) -> Unit)? = null
        val p = StreamingSpeechPipeline(this) { time }
        override fun synthesize(token: Long, text: String) { generated += token }
        override fun play(token: Long, audio: String) { played += token }
        override fun cancelSynthesis() { }
        override fun dispose(audio: String) { disposed += audio }
        override fun event(id: String, event: String, code: Int?) { events += "$id:$event"; onEvent?.invoke(id, event) }
        override fun diagnostic(value: String) { lines += value }
    }
    @Test fun prefixStartsPlaybackButDoesNotFreeTheModelProducer() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single(); p.prefetch("b", "B")
        p.streamReady(a, "A"); p.playbackStarted(a)
        assertEquals(listOf(a), h.played); assertTrue(p.isSynthesizing(a))
        assertEquals(1, h.generated.size); assertFalse(p.readyAhead)
        p.synthesisFinished(a)
        assertEquals(2, h.generated.size); assertFalse(p.isSynthesizing(a))
        assertFalse(h.events.contains("a:done"))
    }
    @Test fun nextCanGenerateAndBufferBeforeCurrentDrains() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single(); p.prefetch("b", "B")
        p.streamReady(a, "A"); p.synthesisFinished(a)
        val b = h.generated.last(); p.streamReady(b, "B")
        assertTrue(p.prefixAhead); assertFalse(p.readyAhead); assertEquals(1, h.played.size)
        p.synthesisFinished(b); assertTrue(p.readyAhead)
        p.playbackDone(a); p.speak("b", "B")
        assertEquals(listOf(a, b), h.played); assertEquals(1, p.slotCount)
    }
    @Test fun playbackDrainBeforeGenerationNoticeDoesNotAdvanceEarly() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single(); p.streamReady(a, "A")
        p.playbackDone(a); assertTrue(h.events.isEmpty())
        p.synthesisFinished(a); assertEquals(listOf("a:done"), h.events)
        p.playbackDone(a); p.synthesisFinished(a); assertEquals(1, h.events.size)
    }
    @Test fun generationCompletionIsNotPlaybackCompletion() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single(); p.streamReady(a, "A")
        p.synthesisFinished(a); assertTrue(h.events.isEmpty())
        p.playbackDone(a); assertEquals(listOf("a:done"), h.events)
    }
    @Test fun reentrantPromotionRetainsNextAndEnqueuesOnlyOneReplacement() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single(); p.prefetch("b", "B")
        p.streamReady(a, "A"); p.synthesisFinished(a)
        val b = h.generated.last(); p.streamReady(b, "B")
        h.onEvent = { id, event -> if (id == "a" && event == "done") { p.speak("b", "B"); p.prefetch("c", "C") } }
        p.playbackDone(a)
        assertEquals(listOf(a, b), h.played); assertEquals(2, p.slotCount)
        assertEquals(2, h.generated.size)
        p.synthesisFinished(b); assertEquals(3, h.generated.size)
    }
    @Test fun cancellationRejectsLateStreamsAndAllOldCompletions() {
        val h = Harness(); val p = h.p
        p.speak("same", "old"); val old = h.generated.single(); p.cancel()
        p.speak("same", "new"); val fresh = h.generated.last()
        p.streamReady(old, "old"); p.synthesisFinished(old); p.playbackDone(old)
        assertEquals(listOf("old"), h.disposed); assertTrue(h.events.isEmpty())
        p.streamReady(fresh, "new"); assertEquals(listOf(fresh), h.played)
    }
    @Test fun terminalNextErrorStopsCurrentWithoutAdvancing() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single(); p.prefetch("b", "B")
        p.streamReady(a, "A"); p.synthesisFinished(a); val b = h.generated.last()
        p.terminalFailure(b, "test", -1)
        assertEquals(listOf("a:error"), h.events); assertEquals(0, p.slotCount)
        p.playbackDone(a); p.synthesisFinished(b); assertEquals(1, h.events.size)
    }
    @Test fun duplicatePrefixIsDisposedWithoutReplacingPlayingAudio() {
        val h = Harness(); val p = h.p
        p.speak("a", "A"); val a = h.generated.single()
        p.streamReady(a, "first"); p.streamReady(a, "duplicate")
        assertEquals(listOf("duplicate"), h.disposed); assertEquals(1, h.played.size)
    }
    @Test fun diagnosticsKeepPrefixAndCompletionDistinctAndExcludePrivateText() {
        val h = Harness(); val p = h.p
        p.speak("PRIVATE_ID", "PRIVATE_TEXT"); val a = h.generated.single()
        p.streamReady(a, "A"); p.playbackStarted(a)
        assertTrue(h.lines.any { it.startsWith("buffer stream-ready") })
        assertFalse(h.lines.any { it.startsWith("buffer synth-done") })
        assertFalse(h.lines.joinToString().contains("PRIVATE"))
    }
}
