package org.inkdock.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.UUID

/** Current plus one next private audio file. No public files, network, model, or text diagnostics. */
internal class FileBufferedOfflineSpeechEngine(
    context: Context,
    private val backend: OfflineSpeechEngine,
    private val diagnostic: (String) -> Unit
) : OfflineSpeechEngine by backend {
    private val main = Handler(Looper.getMainLooper())
    private val directory = File(context.noBackupFilesDir, "speech-prefetch")
    private val prefix = UUID.randomUUID().toString()
    private var listener: UtteranceProgressListener? = null
    private var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private data class Original(val id: String, val text: String, val parts: List<String>)
    private var original: Original? = null
    private var following: Original? = null
    private var part = 0
    private var started = false
    private data class Audio(val file: File, var player: MediaPlayer? = null, var owned: Boolean = false, var synthesisFinished: Boolean = false)
    private val work = LinkedHashMap<Long, Audio>()
    private var closed = false
    private var storageCleanupFailed = false
    private val pipeline: TwoSlotSpeechPipeline<Audio> = TwoSlotSpeechPipeline(object : TwoSlotSpeechPipeline.Port<Audio> {
        override fun synthesize(token: Long, text: String) = synthesizeFile(token, text)
        override fun play(token: Long, audio: Audio) {
            try { requireNotNull(audio.player).start(); pipelinePlaybackStarted(token) }
            catch (_: Exception) { pipelineFailure(token, "player-start", TextToSpeech.ERROR_OUTPUT) }
        }
        override fun directSpeak(token: Long, text: String) {
            val result = try { backend.speak(text, TextToSpeech.QUEUE_FLUSH, null, "direct-$token") }
                catch (_: Exception) { TextToSpeech.ERROR_SERVICE }
            if (result != TextToSpeech.SUCCESS) main.post { pipelineDirectEvent(token, "error", result) }
            else main.postDelayed({ if (pipelineDirectQueued(token)) {
                pipelineDirectEvent(token, "error", -1000); backend.stop()
            } }, 12_000)
        }
        override fun cancelSynthesis() {
            backend.stop()
            work.filterValues { !it.owned }.keys.toList().forEach { token -> work.remove(token)?.let(::disposeAudio) }
        }
        override fun dispose(audio: Audio) = disposeAudio(audio)
        override fun event(id: String, event: String, code: Int?) = innerEvent(id, event, code)
        override fun diagnostic(value: String) = diagnostic.invoke(value)
    }, SystemClock::elapsedRealtime)
    override val bufferingMode get() = if (pipeline.buffered) "file-prefetch" else "speak-fallback:${pipeline.fallbackReason}"
    override val bufferedSlots get() = pipeline.slotCount
    override val nextAudioReady get() = pipeline.readyAhead

    init {
        // A previous process cannot resume this private audio. Remove only this feature's stale directory contents.
        directory.mkdirs()
        val staleFiles = directory.listFiles()
        if (staleFiles == null) { storageCleanupFailed = true; diagnostic("buffer cleanup-unavailable") }
        staleFiles?.filter { it.isFile }?.forEach {
            if (!it.delete()) { storageCleanupFailed = true; diagnostic("buffer cleanup-failed") }
        }
        backend.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = route(utteranceId, "started")
            override fun onDone(utteranceId: String?) = route(utteranceId, "done")
            override fun onStop(utteranceId: String?, interrupted: Boolean) = route(utteranceId, "stopped")
            @Deprecated("Required legacy callback") override fun onError(utteranceId: String?) = route(utteranceId, "error", TextToSpeech.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) = route(utteranceId, "error", errorCode)
        })
    }
    override fun setAudioAttributes(attributes: AudioAttributes): Int {
        this.attributes = attributes
        return backend.setAudioAttributes(attributes)
    }
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {
        this.listener = listener
        return TextToSpeech.SUCCESS
    }
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        if (closed) return TextToSpeech.ERROR_SERVICE
        val reuse = following?.takeIf { it.id == utteranceId && it.text == text }
        if (original != null || (following != null && reuse == null)) stop()
        original = reuse ?: Original(utteranceId, text, SpeechSentenceChunks.split(text))
        following = null; part = 0; started = false
        if (original!!.parts.isEmpty()) return TextToSpeech.ERROR_INVALID_REQUEST
        playPart()
        return TextToSpeech.SUCCESS
    }
    override fun prefetch(text: String, utteranceId: String) {
        if (closed || original == null || following != null) return
        following = Original(utteranceId, text, SpeechSentenceChunks.split(text))
        prefetchPart()
    }
    private fun partId(value: Original, index: Int) = "${value.id}/$index"
    private fun playPart() {
        val value = original ?: return
        pipeline.speak(partId(value, part), value.parts[part])
        prefetchPart()
    }
    private fun prefetchPart() {
        val value = original ?: return
        if (part + 1 < value.parts.size) pipeline.prefetch(partId(value, part + 1), value.parts[part + 1])
        else following?.takeIf { it.parts.isNotEmpty() }?.let { pipeline.prefetch(partId(it, 0), it.parts[0]) }
    }
    private fun innerEvent(id: String, event: String, code: Int?) {
        val value = original ?: return
        if (id != partId(value, part)) return
        when (event) {
            "started" -> if (!started) { started = true; listener?.onStart(value.id) }
            "done" -> if (part + 1 < value.parts.size) { part++; playPart() }
                else { original = null; listener?.onDone(value.id) }
            "error" -> listener?.onError(value.id, code ?: TextToSpeech.ERROR)
            "stopped" -> listener?.onStop(value.id, true)
        }
    }
    private fun route(id: String?, event: String, code: Int? = null) {
        main.post {
            if (closed || id == null) return@post
            if (id.startsWith("direct-")) {
                id.removePrefix("direct-").toLongOrNull()?.let { pipeline.directEvent(it, event, code) }
                return@post
            }
            val token = id.removePrefix("file-").toLongOrNull() ?: return@post
            val audio = work[token] ?: return@post
            // A completed file is now owned by the player. Vendor late synthesis callbacks
            // cannot invalidate ready/playing audio or replay the current sentence.
            if (audio.synthesisFinished) return@post
            when (event) {
                "started" -> pipeline.synthesisStarted(token)
                "done" -> { audio.synthesisFinished = true; pipeline.synthesisDone(token); prepareFile(token) }
                else -> pipeline.failed(token, "synthesis-$event", code ?: TextToSpeech.ERROR_SYNTHESIS)
            }
        }
    }
    private fun synthesizeFile(token: Long, text: String) {
        // Never accumulate new audio when an earlier private file could not be removed.
        if (storageCleanupFailed) {
            main.post { pipeline.failed(token, "cleanup-failed", TextToSpeech.ERROR_OUTPUT) }; return
        }
        val file = File(directory, "$prefix-$token.wav")
        val audio = Audio(file); work[token] = audio
        val result = try {
            if (!directory.isDirectory && !directory.mkdirs()) TextToSpeech.ERROR_OUTPUT
            else backend.synthesizeToFile(text, Bundle(), file, "file-$token")
        } catch (_: Exception) { TextToSpeech.ERROR_OUTPUT }
        if (result != TextToSpeech.SUCCESS) { main.post { pipeline.failed(token, "file-request-rejected", result) }; return }
        // Vendor file writers cannot be forcibly quota-limited by this API. Accepted audio is capped;
        // poll cancels oversized in-flight output and unlinks it, but a vendor can transiently overshoot.
        val begin = SystemClock.elapsedRealtime()
        fun poll() {
            if (work[token] !== audio || audio.owned) return
            if (file.length() > MAX_FILE_BYTES) pipeline.failed(token, "file-size-limit", TextToSpeech.ERROR_OUTPUT)
            else if (SystemClock.elapsedRealtime() - begin >= SYNTHESIS_TIMEOUT_MS)
                pipeline.failed(token, "file-timeout", TextToSpeech.ERROR_SYNTHESIS)
            else main.postDelayed({ poll() }, 100)
        }
        main.postDelayed({ poll() }, 100)
    }
    private fun prepareFile(token: Long) {
        val audio = work[token] ?: return
        if (audio.player != null) return
        if (!audio.file.isFile || audio.file.length() !in 1..MAX_FILE_BYTES) {
            pipeline.failed(token, "file-empty-or-oversize", TextToSpeech.ERROR_OUTPUT); return
        }
        try {
            val player = MediaPlayer(); audio.player = player
            player.setAudioAttributes(attributes)
            player.setDataSource(audio.file.absolutePath)
            player.setOnPreparedListener {
                if (work[token] !== audio) return@setOnPreparedListener
                val duration = it.duration.toLong()
                if (duration !in 1..MAX_AUDIO_MS || audio.file.length() > MAX_FILE_BYTES) {
                    pipeline.failed(token, "audio-duration-or-size-limit", TextToSpeech.ERROR_OUTPUT)
                } else {
                    audio.owned = true
                    pipeline.ready(token, audio, duration)
                }
            }
            player.setOnCompletionListener { pipeline.playbackDone(token) }
            player.setOnErrorListener { _, _, _ ->
                pipeline.failed(token, "player-error", TextToSpeech.ERROR_OUTPUT); true
            }
            player.prepareAsync()
        } catch (_: Exception) { pipeline.failed(token, "file-prepare", TextToSpeech.ERROR_OUTPUT) }
    }
    private fun disposeAudio(audio: Audio) {
        work.entries.filter { it.value === audio }.map { it.key }.forEach(work::remove)
        try { audio.player?.release() } catch (_: Exception) { }
        audio.player = null
        if (audio.file.exists() && !audio.file.delete()) {
            storageCleanupFailed = true
            diagnostic("buffer cleanup-failed")
        }
    }
    // Named bridges avoid Kotlin initializer self-reference inference.
    private fun pipelineDirectQueued(token: Long) = pipeline.isDirectQueued(token)
    private fun pipelinePlaybackStarted(token: Long) = pipeline.playbackStarted(token)
    private fun pipelineFailure(token: Long, reason: String, code: Int) = pipeline.failed(token, reason, code)
    private fun pipelineDirectEvent(token: Long, event: String, code: Int) = pipeline.directEvent(token, event, code)
    override fun stop(): Int {
        original = null; following = null; started = false; part = 0
        pipeline.cancel()
        main.removeCallbacksAndMessages(null)
        return TextToSpeech.SUCCESS
    }
    override fun shutdown() {
        stop(); closed = true; backend.shutdown()
    }
    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        // At the slowest supported speed a 120-character sentence can exceed 30 seconds.
        const val MAX_AUDIO_MS = 60_000L
        const val SYNTHESIS_TIMEOUT_MS = 90_000L
    }
}
