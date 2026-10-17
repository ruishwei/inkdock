package org.inkdock.reader

/** State only: queue acceptance never proves audio was produced. No text is retained. */
class SpeechTracker {
    data class Update(val id: String, val requestId: String, val phase: String, val characters: Int, val elapsedMs: Long, val errorCode: Int? = null)
    private data class Active(val id: String, val requestId: String, val characters: Int, val since: Long, var phase: String)
    private var active: Active? = null
    fun begin(id: String, requestId: String, characters: Int, now: Long): Update {
        active = Active(id, requestId, characters, now, "queued")
        return Update(id, requestId, "queued", characters, 0)
    }
    fun event(id: String?, phase: String, now: Long, errorCode: Int? = null): Update? {
        val item = active ?: return null
        if (id == null || item.id != id) return null
        if (phase == "started" && item.phase != "queued") return null
        val update = Update(item.id, item.requestId, phase, item.characters, (now - item.since).coerceAtLeast(0), errorCode)
        if (phase in setOf("done", "error", "stopped")) active = null else item.phase = phase
        return update
    }
    fun queuedTimeout(id: String, now: Long): Update? {
        val item = active ?: return null
        if (item.id != id || item.phase != "queued" || now - item.since < 12000) return null
        return event(id, "error", now, -1000)
    }
    fun cancel(now: Long): Update? = active?.let { event(it.id, "stopped", now) }
    fun isActive() = active != null
}
