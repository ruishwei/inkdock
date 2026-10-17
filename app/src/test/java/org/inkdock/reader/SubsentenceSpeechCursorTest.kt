package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class SubsentenceSpeechCursorTest {
    private data class UnitCall(val id: String, val text: String)
    private data class Event(val id: String, val event: String, val code: Int?)
    private class FakeSink : SubsentenceSpeechCursor.Sink {
        val spoken = mutableListOf<UnitCall>()
        val prefetched = mutableListOf<UnitCall>()
        val events = mutableListOf<Event>()
        val actions = mutableListOf<String>()
        var cancellations = 0
        var onSpeak: ((String) -> Unit)? = null
        var onCancel: (() -> Unit)? = null
        var onEvent: ((Event) -> Unit)? = null
        override fun speakUnit(id: String, text: String) {
            spoken += UnitCall(id, text)
            actions += "speak:$id"
            onSpeak?.invoke(id)
        }
        override fun prefetchUnit(id: String, text: String) {
            prefetched += UnitCall(id, text)
            actions += "prefetch:$id"
        }
        override fun cancelUnits() {
            cancellations++
            actions += "cancel"
            onCancel?.invoke()
        }
        override fun event(id: String, event: String, code: Int?) {
            val value = Event(id, event, code)
            events += value
            onEvent?.invoke(value)
        }
    }

    private fun cursor(sink: FakeSink) = SubsentenceSpeechCursor({ it.split('|') }, sink)

    @Test fun preservesEveryBoundedPartAndEmitsOuterDoneOnlyAtTheEnd() {
        val sink = FakeSink()
        val cursor = SubsentenceSpeechCursor({ it.chunked(120) }, sink)
        val text = "甲乙丙丁戊".repeat(125)
        val parts = text.chunked(120)
        assertTrue(cursor.speak("original", text))
        parts.indices.forEach { index ->
            assertEquals(UnitCall("original/$index", parts[index]), sink.spoken.last())
            cursor.unitEvent("original/$index", "started")
            cursor.unitEvent("original/$index", "done")
            assertEquals(if (index == parts.lastIndex) 1 else 0,
                sink.events.count { it.event == "done" })
        }
        assertEquals(text, sink.spoken.joinToString("") { it.text })
        assertTrue(sink.spoken.all { it.text.length <= 120 })
        assertEquals(parts.size, sink.spoken.size)
        assertEquals(listOf(Event("original", "started", null), Event("original", "done", null)), sink.events)
    }

    @Test fun emitsStartedOnlyOnceAndIgnoresOtherOrCompletedUnits() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("request", "one|two")
        cursor.unitEvent("another/0", "started")
        cursor.unitEvent("request/1", "done")
        cursor.unitEvent("request/0", "started")
        cursor.unitEvent("request/0", "started")
        cursor.unitEvent("request/0", "done")
        cursor.unitEvent("request/0", "done")
        cursor.unitEvent("request/0", "error", -7)
        cursor.unitEvent("request/1", "started")
        cursor.unitEvent("request/1", "done")
        cursor.unitEvent("request/1", "done")
        assertEquals(2, sink.spoken.size)
        assertEquals(listOf(Event("request", "started", null), Event("request", "done", null)), sink.events)
    }

    @Test fun internalContinuationHasPriorityOverFollowingOriginal() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one|two|three")
        cursor.prefetch("second", "four|five")
        assertEquals(listOf(UnitCall("first/1", "two")), sink.prefetched)
        cursor.unitEvent("first/0", "done")
        assertEquals(UnitCall("first/2", "three"), sink.prefetched.last())
        assertFalse(sink.prefetched.any { it.id.startsWith("second/") })
        cursor.unitEvent("first/1", "done")
        assertEquals(UnitCall("second/0", "four"), sink.prefetched.last())
        assertFalse(sink.prefetched.any { it.id == "second/1" })
    }

    @Test fun matchingFollowingReusesSplitAndPreparedUnitAcrossOriginalBoundary() {
        val sink = FakeSink()
        val splits = mutableListOf<String>()
        val cursor = SubsentenceSpeechCursor({ splits += it; it.split('|') }, sink)
        cursor.speak("first", "one")
        cursor.prefetch("second", "two|three")
        cursor.unitEvent("first/0", "done")
        val cancellations = sink.cancellations
        assertTrue(cursor.speak("second", "two|three"))
        assertEquals(cancellations, sink.cancellations)
        assertEquals(listOf("one", "two|three"), splits)
        assertEquals(UnitCall("second/0", "two"), sink.spoken.last())
        assertEquals(UnitCall("second/1", "three"), sink.prefetched.last())
        cursor.unitEvent("second/0", "started")
        assertEquals(Event("second", "started", null), sink.events.last())
    }

    @Test fun followingWithChangedTextCancelsInsteadOfReusing() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one")
        cursor.prefetch("second", "old")
        cursor.unitEvent("first/0", "done")
        val cancellations = sink.cancellations
        cursor.speak("second", "new")
        assertEquals(cancellations + 1, sink.cancellations)
        assertEquals(listOf("cancel", "speak:second/0"), sink.actions.takeLast(2))
        assertEquals(UnitCall("second/0", "new"), sink.spoken.last())
    }

    @Test fun followingFailureBetweenOuterCompletionAndPromotionNeverRetries() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one")
        cursor.prefetch("second", "two|three")
        cursor.unitEvent("first/0", "done")
        cursor.unitEvent("second/0", "error", -7)
        cursor.unitEvent("second/0", "error", -8)
        cursor.unitEvent("second/0", "done")
        assertEquals(listOf(Event("first", "done", null)), sink.events)
        assertTrue(cursor.speak("second", "two|three"))
        assertEquals(listOf(UnitCall("first/0", "one")), sink.spoken)
        assertEquals(listOf(Event("first", "done", null), Event("second", "error", -7)), sink.events)
        cursor.unitEvent("second/0", "started")
        cursor.unitEvent("second/0", "done")
        assertEquals(2, sink.events.size)
    }

    @Test fun explicitCancelClearsLatchedFollowingFailureForNewRequest() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one")
        cursor.prefetch("second", "two")
        cursor.unitEvent("first/0", "done")
        cursor.unitEvent("second/0", "error", -7)
        cursor.cancel()
        assertTrue(cursor.speak("second", "two"))
        assertEquals(UnitCall("second/0", "two"), sink.spoken.last())
        assertEquals(listOf(Event("first", "done", null)), sink.events)
    }

    @Test fun replacingCurrentCancelsBeforeSpeakingEvenWhenFollowingMatches() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one")
        cursor.prefetch("second", "two")
        sink.onCancel = { cursor.unitEvent("first/0", "done") }
        val cancellations = sink.cancellations
        cursor.speak("second", "two")
        assertEquals(cancellations + 1, sink.cancellations)
        assertEquals(listOf("cancel", "speak:second/0"), sink.actions.takeLast(2))
        assertTrue(sink.events.isEmpty())
    }

    @Test fun retainsOnlyFirstFollowingRequest() {
        val sink = FakeSink()
        val splits = mutableListOf<String>()
        val cursor = SubsentenceSpeechCursor({ splits += it; it.split('|') }, sink)
        cursor.speak("first", "one|two")
        cursor.prefetch("second", "three|four")
        cursor.prefetch("third", "five")
        cursor.prefetch("fourth", "six")
        cursor.unitEvent("first/0", "done")
        assertEquals(listOf("one|two", "three|four"), splits)
        assertEquals(UnitCall("second/0", "three"), sink.prefetched.last())
        cursor.unitEvent("first/1", "done")
        val cancellations = sink.cancellations
        cursor.speak("second", "three|four")
        assertEquals(cancellations, sink.cancellations)
    }

    @Test fun cancelClearsPartsAndFollowingBeforeReentrantOrLateCallbacks() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one|two")
        cursor.prefetch("second", "three")
        sink.onCancel = {
            cursor.unitEvent("first/0", "stopped")
            cursor.unitEvent("first/0", "done")
            cursor.unitEvent("second/0", "started")
        }
        assertEquals("first", cursor.cancel())
        cursor.unitEvent("first/1", "done")
        cursor.prefetch("third", "four")
        assertNull(cursor.cancel())
        assertEquals(1, sink.spoken.size)
        assertEquals(1, sink.prefetched.size)
        assertTrue(sink.events.isEmpty())
        val cancellations = sink.cancellations
        cursor.speak("second", "three")
        assertEquals(cancellations + 1, sink.cancellations)
    }

    @Test fun rejectsBlankOrEmptySplitWithoutInterruptingCurrent() {
        val sink = FakeSink()
        val cursor = SubsentenceSpeechCursor({ if (it == "empty") emptyList() else it.split('|') }, sink)
        cursor.speak("first", "one")
        val cancellations = sink.cancellations
        assertFalse(cursor.speak("blank", " \n\t"))
        assertFalse(cursor.speak("blank", ""))
        assertFalse(cursor.speak("invalid", "empty"))
        assertFalse(cursor.speak("invalid", "two||three"))
        cursor.prefetch("blank", " \n")
        cursor.prefetch("empty", "empty")
        cursor.prefetch("valid", "two")
        assertEquals(cancellations, sink.cancellations)
        assertEquals(UnitCall("valid/0", "two"), sink.prefetched.single())
        cursor.unitEvent("first/0", "done")
        assertEquals(Event("first", "done", null), sink.events.single())
    }

    @Test fun ignoresPrefetchWithoutCurrentAndDoesNotSplitIt() {
        val sink = FakeSink()
        var splitCalls = 0
        val cursor = SubsentenceSpeechCursor({ splitCalls++; listOf(it) }, sink)
        cursor.prefetch("unused", "text")
        assertEquals(0, splitCalls)
        assertTrue(sink.actions.isEmpty())
    }

    @Test fun outerCompletionMayReentrantlyPromoteFollowing() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        cursor.speak("first", "one|two")
        cursor.prefetch("second", "three|four")
        sink.onEvent = {
            if (it == Event("first", "done", null)) assertTrue(cursor.speak("second", "three|four"))
        }
        cursor.unitEvent("first/0", "done")
        val cancellations = sink.cancellations
        cursor.unitEvent("first/1", "done")
        assertEquals(cancellations, sink.cancellations)
        assertEquals(UnitCall("second/0", "three"), sink.spoken.last())
        assertEquals(UnitCall("second/1", "four"), sink.prefetched.last())
        cursor.unitEvent("second/0", "done")
        cursor.unitEvent("second/1", "done")
        assertEquals(listOf(Event("first", "done", null), Event("second", "done", null)), sink.events)
    }

    @Test fun synchronousUnitCompletionDoesNotPrefetchObsoleteUnits() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        sink.onSpeak = { cursor.unitEvent(it, "done") }
        cursor.speak("first", "one|two|three")
        assertEquals(listOf("one", "two", "three"), sink.spoken.map { it.text })
        assertTrue(sink.prefetched.isEmpty())
        assertEquals(Event("first", "done", null), sink.events.single())
    }

    @Test fun synchronousCancelDuringSpeakCannotEnqueueLookahead() {
        val sink = FakeSink()
        val cursor = cursor(sink)
        sink.onSpeak = { cursor.cancel() }
        cursor.speak("first", "one|two")
        assertTrue(sink.prefetched.isEmpty())
        assertNull(cursor.cancel())
    }

    @Test fun terminalErrorAndStopUseOriginalIdAndClearPendingParts() {
        listOf("error", "stopped").forEach { terminal ->
            val sink = FakeSink()
            val cursor = cursor(sink)
            cursor.speak("first", "one|two")
            cursor.prefetch("second", "three")
            cursor.unitEvent("first/0", terminal, -42)
            cursor.unitEvent("first/0", "done")
            cursor.unitEvent("first/1", "started")
            assertEquals(listOf(Event("first", terminal, -42)), sink.events)
            assertEquals(1, sink.spoken.size)
            assertNull(cursor.cancel())
        }
    }
}
