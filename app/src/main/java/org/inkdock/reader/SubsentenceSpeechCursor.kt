package org.inkdock.reader

/**
 * Maps bounded synthesis units back to one original request's progress callbacks.
 *
 * Call on one serialized thread. [split] supplies the complete, bounded unit list;
 * this cursor never truncates it and retains at most one following original request.
 * Unit IDs are stable within a request ("requestId/partIndex"), not generation IDs.
 * If external IDs are reused, the adapter/core must reject stale generation tokens
 * before calling [unitEvent]; this cursor accepts the matching current unit ID.
 */
internal class SubsentenceSpeechCursor(
    private val split: (String) -> List<String>,
    private val sink: Sink
) {
    interface Sink {
        fun speakUnit(id: String, text: String)
        fun prefetchUnit(id: String, text: String)
        fun cancelUnits()
        fun event(id: String, event: String, code: Int? = null)
    }

    private class Request(val id: String, val text: String, val parts: List<String>) {
        var part = 0
        var started = false
        var terminalEvent: Pair<String, Int?>? = null
        fun unitId(index: Int = part) = "$id/$index"
    }

    private var current: Request? = null
    private var following: Request? = null

    /** Reject invalid input without interrupting an accepted current request. */
    fun speak(id: String, text: String): Boolean {
        if (text.isBlank()) return false
        val prepared = following?.takeIf { it.id == id && it.text == text }
        val request = prepared ?: request(id, text) ?: return false
        // A following request can reuse its prepared unit only after current ended.
        if (current != null || prepared == null) cancel()
        following = null
        request.part = 0
        request.started = false
        current = request
        // The outer service may defer promotion after the previous original done.
        // Preserve a prefetched-unit failure from that gap instead of retrying it.
        val terminal = request.terminalEvent
        if (terminal != null) unitEvent(request.unitId(), terminal.first, terminal.second)
        else playCurrent(request)
        return true
    }

    /** The first following request wins; callers may not build an unbounded queue. */
    fun prefetch(id: String, text: String) {
        val active = current ?: return
        if (following != null || text.isBlank()) return
        following = request(id, text) ?: return
        // Internal continuation always owns the only lookahead slot first.
        if (active.part + 1 == active.parts.size) prefetchNext(active)
    }

    fun unitEvent(internalId: String, event: String, code: Int? = null) {
        val active = current
        if (active == null) {
            following?.takeIf { it.unitId(0) == internalId && it.terminalEvent == null }
                ?.let { if (event == "error" || event == "stopped") it.terminalEvent = event to code }
            return
        }
        if (internalId != active.unitId()) return
        when (event) {
            "started" -> if (!active.started) {
                active.started = true
                sink.event(active.id, event, code)
            }
            "done" -> if (active.part + 1 < active.parts.size) {
                active.part++
                playCurrent(active)
            } else {
                // The outer completion callback may synchronously speak following.
                current = null
                sink.event(active.id, event, code)
            }
            "error", "stopped" -> {
                current = null
                following = null
                sink.event(active.id, event, code)
            }
        }
    }

    /** Invalidate every cursor reference before stopping units and their callbacks. */
    fun cancel(): String? {
        val id = current?.id
        current = null
        following = null
        sink.cancelUnits()
        return id
    }

    private fun request(id: String, text: String): Request? {
        val parts = split(text).toList()
        if (parts.isEmpty() || parts.any { it.isBlank() }) return null
        return Request(id, text, parts)
    }

    private fun playCurrent(active: Request) {
        val part = active.part
        sink.speakUnit(active.unitId(part), active.parts[part])
        // A sink may synchronously report completion, failure, or cancellation.
        if (current === active && active.part == part) prefetchNext(active)
    }

    private fun prefetchNext(active: Request) {
        if (current !== active) return
        val next = active.part + 1
        if (next < active.parts.size) {
            sink.prefetchUnit(active.unitId(next), active.parts[next])
        } else {
            following?.let { sink.prefetchUnit(it.unitId(0), it.parts[0]) }
        }
    }
}
