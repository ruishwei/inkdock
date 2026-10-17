package org.inkdock.reader

/** Serialized, bounded synthesis/playback state. Audio readiness is not queue acceptance. */
internal class TwoSlotSpeechPipeline<A>(private val port: Port<A>, private val now: () -> Long) {
    interface Port<A> {
        fun synthesize(token: Long, text: String)
        fun play(token: Long, audio: A)
        fun directSpeak(token: Long, text: String)
        fun cancelSynthesis()
        fun dispose(audio: A)
        fun event(id: String, event: String, code: Int? = null)
        fun diagnostic(value: String)
    }
    private enum class Phase { QUEUED, SYNTHESIZING, READY, PLAYING }
    private data class Slot<A>(val token: Long, val id: String, val text: String, val queuedAt: Long,
        var phase: Phase = Phase.QUEUED, var audio: A? = null, var readyAt: Long = 0)
    private var serial = 0L
    private var current: Slot<A>? = null
    private var next: Slot<A>? = null
    private var synthesizing: Long? = null
    private var direct: Slot<A>? = null
    private var previousDone: Long? = null
    var fallbackReason: String? = null
        private set
    val buffered get() = fallbackReason == null
    val slotCount get() = listOfNotNull(current, next, direct).size
    val readyAhead get() = next?.phase == Phase.READY
    val currentToken get() = current?.token ?: direct?.token

    fun speak(id: String, text: String) {
        if (!buffered) { startDirect(id, text); return }
        if (current?.id == id) return
        val prepared = next?.takeIf { it.id == id && it.text == text }
        if (prepared != null && current == null) { current = prepared; next = null }
        else {
            cancel()
            current = Slot(++serial, id, text, now())
        }
        port.diagnostic("buffer queued token=${current!!.token} chars=${text.length} at=${now()}")
        startIfReady()
        pump()
    }
    fun prefetch(id: String, text: String) {
        if (!buffered || current == null || next != null) return
        next = Slot(++serial, id, text, now())
        port.diagnostic("buffer queued-next token=${next!!.token} chars=${text.length} at=${now()}")
        pump()
    }
    private fun pump() {
        if (!buffered || synthesizing != null) return
        val slot = listOfNotNull(current, next).firstOrNull { it.phase == Phase.QUEUED } ?: return
        slot.phase = Phase.SYNTHESIZING; synthesizing = slot.token
        port.synthesize(slot.token, slot.text)
    }
    fun synthesisStarted(token: Long) {
        val slot = find(token) ?: return
        port.diagnostic("buffer synth-start token=$token waitMs=${now() - slot.queuedAt} at=${now()}")
    }
    fun synthesisDone(token: Long) {
        if (find(token) == null) return
        port.diagnostic("buffer synth-done token=$token at=${now()}")
    }
    /** Called only once complete audio is validated and adapter-specific readiness is met. */
    fun ready(token: Long, audio: A, durationMs: Long) {
        val slot = find(token)
        if (slot == null || slot.phase != Phase.SYNTHESIZING) { port.dispose(audio); return }
        slot.audio = audio; slot.readyAt = now(); slot.phase = Phase.READY
        if (synthesizing == token) synthesizing = null
        port.diagnostic("buffer ready token=$token durationMs=$durationMs elapsedMs=${now() - slot.queuedAt} at=${now()}")
        startIfReady()
        pump()
    }
    private fun startIfReady() {
        val slot = current ?: return
        if (slot.phase != Phase.READY) return
        slot.phase = Phase.PLAYING
        previousDone?.let { port.diagnostic("buffer boundary token=${slot.token} readyLeadMs=${it - slot.readyAt} gapMs=${now() - it}") }
        port.play(slot.token, requireNotNull(slot.audio))
    }
    fun playbackStarted(token: Long) {
        val slot = current?.takeIf { it.token == token && it.phase == Phase.PLAYING } ?: return
        val gap = previousDone?.let { " callbackGapMs=${now() - it}" }.orEmpty()
        port.diagnostic("buffer play-start token=$token$gap at=${now()}")
        port.event(slot.id, "started")
    }
    fun playbackDone(token: Long) {
        val slot = current?.takeIf { it.token == token && it.phase == Phase.PLAYING } ?: return
        current = null; previousDone = now()
        slot.audio?.let(port::dispose)
        port.diagnostic("buffer play-done token=$token readyAhead=$readyAhead at=${now()}")
        // This callback may synchronously promote next and enqueue exactly one replacement.
        port.event(slot.id, "done")
    }
    fun failed(token: Long, reason: String, code: Int) {
        val slot = find(token) ?: return
        fallbackReason = reason
        port.diagnostic("buffer fallback reason=$reason code=$code token=$token")
        if (next === slot) {
            next = null; synthesizing = null
            slot.audio?.let(port::dispose)
            port.cancelSynthesis()
            // Keep an already playing current sentence intact. The following speak uses direct TTS.
            if (current?.phase != Phase.PLAYING) current?.let { startDirect(it.id, it.text) }
        } else startDirect(slot.id, slot.text)
    }
    /** Native model/output failures are terminal; never silently select another voice/backend. */
    fun terminalFailure(token: Long, reason: String, code: Int) {
        val slot = find(token) ?: return
        val id = current?.id ?: slot.id
        port.diagnostic("buffer terminal-error reason=$reason code=$code token=$token")
        cancel()
        port.event(id, "error", code)
    }
    fun isSynthesizing(token: Long) = find(token)?.phase == Phase.SYNTHESIZING
    private fun startDirect(id: String, text: String) {
        cancel()
        val slot = Slot<A>(++serial, id, text, now())
        direct = slot
        port.directSpeak(slot.token, text)
    }
    fun isDirectQueued(token: Long) = direct?.let { it.token == token && it.phase == Phase.QUEUED } == true
    fun directEvent(token: Long, event: String, code: Int? = null) {
        val slot = direct?.takeIf { it.token == token } ?: return
        if (event == "started") slot.phase = Phase.PLAYING
        if (event in setOf("done", "error", "stopped")) direct = null
        port.event(slot.id, event, code)
    }
    fun cancel() {
        val old = listOfNotNull(current, next)
        current = null; next = null; direct = null; synthesizing = null; previousDone = null
        // Invalidate identities before stopping, so reentrant/late callbacks cannot restore audio.
        port.cancelSynthesis()
        old.forEach { it.audio?.let(port::dispose) }
    }
    private fun find(token: Long) = current?.takeIf { it.token == token } ?: next?.takeIf { it.token == token }
}
