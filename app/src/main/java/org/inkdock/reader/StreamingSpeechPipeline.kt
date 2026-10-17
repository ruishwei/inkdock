package org.inkdock.reader

/** One model producer and current + next streams. First PCM is not generation completion. */
internal class StreamingSpeechPipeline<A>(private val port: Port<A>, private val now: () -> Long) {
    interface Port<A> {
        fun synthesize(token: Long, text: String)
        fun play(token: Long, audio: A)
        fun cancelSynthesis()
        fun dispose(audio: A)
        fun event(id: String, event: String, code: Int? = null)
        fun diagnostic(value: String)
    }
    private data class Slot<A>(val token: Long, val id: String, val text: String, val queuedAt: Long,
        var generating: Boolean = false, var generated: Boolean = false, var audio: A? = null,
        var firstPcmAt: Long = 0, var playing: Boolean = false, var drained: Boolean = false)
    private var serial = 0L
    private var current: Slot<A>? = null
    private var next: Slot<A>? = null
    private var synthesizing: Long? = null
    private var previousDone: Long? = null
    val slotCount get() = listOfNotNull(current, next).size
    val readyAhead get() = next?.let { it.generated && it.audio != null } == true
    val prefixAhead get() = next?.audio != null
    val currentToken get() = current?.token

    fun speak(id: String, text: String) {
        if (current?.id == id) return
        val prepared = next?.takeIf { it.id == id && it.text == text }
        if (prepared != null && current == null) { current = prepared; next = null }
        else { cancel(); current = Slot(++serial, id, text, now()) }
        port.diagnostic("buffer queued token=${current!!.token} chars=${text.length} at=${now()}")
        startIfReady(); pump()
    }
    fun prefetch(id: String, text: String) {
        if (current == null || next != null) return
        next = Slot(++serial, id, text, now())
        port.diagnostic("buffer queued-next token=${next!!.token} chars=${text.length} at=${now()}")
        pump()
    }
    private fun pump() {
        if (synthesizing != null) return
        val slot = listOfNotNull(current, next).firstOrNull { !it.generating && !it.generated } ?: return
        slot.generating = true; synthesizing = slot.token
        port.synthesize(slot.token, slot.text)
    }
    fun synthesisStarted(token: Long) {
        val slot = find(token) ?: return
        port.diagnostic("buffer synth-start token=$token waitMs=${now() - slot.queuedAt} at=${now()}")
    }
    fun streamReady(token: Long, audio: A) {
        val slot = find(token)
        if (slot == null || !slot.generating || slot.audio != null) { port.dispose(audio); return }
        slot.audio = audio; slot.firstPcmAt = now()
        port.diagnostic("buffer stream-ready token=$token elapsedMs=${now() - slot.queuedAt} at=${now()}")
        startIfReady()
        // The JNI call still owns the producer. Only synthesisFinished can pump next.
    }
    fun synthesisFinished(token: Long) {
        val slot = find(token) ?: return
        if (!slot.generating || slot.generated) return
        slot.generated = true
        if (synthesizing == token) synthesizing = null
        port.diagnostic("buffer synth-done token=$token at=${now()}")
        completeIfDrained(slot)
        pump()
    }
    fun isSynthesizing(token: Long) = find(token)?.let { it.generating && !it.generated } == true
    private fun startIfReady() {
        val slot = current ?: return
        val audio = slot.audio ?: return
        if (slot.playing) return
        slot.playing = true
        previousDone?.let { port.diagnostic("buffer stream-boundary token=${slot.token} prefixLeadMs=${it - slot.firstPcmAt} gapMs=${now() - it}") }
        port.play(slot.token, audio)
    }
    fun playbackStarted(token: Long) {
        val slot = current?.takeIf { it.token == token && it.playing && !it.drained } ?: return
        val gap = previousDone?.let { " callbackGapMs=${now() - it}" }.orEmpty()
        port.diagnostic("buffer play-start token=$token$gap at=${now()}")
        port.event(slot.id, "started")
    }
    fun playbackDone(token: Long) {
        val slot = current?.takeIf { it.token == token && it.playing && !it.drained } ?: return
        slot.drained = true
        completeIfDrained(slot)
    }
    private fun completeIfDrained(slot: Slot<A>) {
        if (current !== slot || !slot.generated || !slot.drained) return
        current = null; previousDone = now()
        slot.audio?.let(port::dispose)
        port.diagnostic("buffer play-done token=${slot.token} readyAhead=$readyAhead prefixAhead=$prefixAhead at=${now()}")
        port.event(slot.id, "done")
    }
    fun terminalFailure(token: Long, reason: String, code: Int) {
        val slot = find(token) ?: return
        val id = current?.id ?: slot.id
        port.diagnostic("buffer terminal-error reason=$reason code=$code token=$token")
        cancel(); port.event(id, "error", code)
    }
    fun cancel() {
        val old = listOfNotNull(current, next)
        current = null; next = null; synthesizing = null; previousDone = null
        port.cancelSynthesis()
        old.forEach { it.audio?.let(port::dispose) }
    }
    private fun find(token: Long) = current?.takeIf { it.token == token } ?: next?.takeIf { it.token == token }
}
