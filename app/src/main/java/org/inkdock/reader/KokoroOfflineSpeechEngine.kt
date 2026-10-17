package org.inkdock.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One leased model, one generation worker, one audio-output worker, and two reserved/retained PCM slots. */
internal class KokoroOfflineSpeechEngine(private val store: KokoroModelStore,
    private val variant: KokoroManifest.Variant, systemEngines: List<TextToSpeech.EngineInfo>,
    private val diagnostic: (String) -> Unit = {}, onInit: (Int) -> Unit) : OfflineSpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val outputWorker = Executors.newSingleThreadExecutor()
    private val pendingGeneration = AtomicReference<Runnable?>(null)
    private val generation = AtomicLong(0)
    private val budget = PcmBudget()
    private val trackLock = Any()
    @Volatile private var model: OfflineTts? = null
    private var leased = false // Only the generation worker touches the lease or model lifecycle.
    @Volatile private var closed = false
    private var track: AudioTrack? = null // Registered/released under trackLock.
    @Volatile private var listener: UtteranceProgressListener? = null
    @Volatile private var rate = 1f
    @Volatile private var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val info = TextToSpeech.EngineInfo().apply { name = ID; label = "Kokoro 中文（内置实验）" }
    override val engines = systemEngines.filter { it.name != ID } + info
    override val defaultEngine = ID
    @Volatile override var voices: Set<Voice> = emptySet(); private set
    @Volatile override var voice: Voice? = null; private set
    override val bufferingMode get() = "native-pcm-prefetch"
    override val bufferedSlots get() = pipeline.slotCount
    override val nextAudioReady get() = pipeline.readyAhead
    override val retainedAudioBuffers get() = budget.inUse

    private val pipeline: TwoSlotSpeechPipeline<PcmBuffer> = TwoSlotSpeechPipeline(object : TwoSlotSpeechPipeline.Port<PcmBuffer> {
        override fun synthesize(token: Long, text: String) = generatePcm(token, text)
        override fun play(token: Long, audio: PcmBuffer) = playPcm(token, audio)
        override fun directSpeak(token: Long, text: String) {
            // Native failures must not switch engines, voices, or enter an unbounded retry.
            nativeDirectError(token)
        }
        override fun cancelSynthesis() = cancelWork()
        override fun dispose(audio: PcmBuffer) = audio.discard()
        override fun event(id: String, event: String, code: Int?) = innerEvent(id, event, code)
        override fun diagnostic(value: String) = diagnostic.invoke(value)
    }, SystemClock::elapsedRealtime)
    private val cursor = SubsentenceSpeechCursor(::split, object : SubsentenceSpeechCursor.Sink {
        override fun speakUnit(id: String, text: String) = pipeline.speak(id, text)
        override fun prefetchUnit(id: String, text: String) = pipeline.prefetch(id, text)
        override fun cancelUnits() = pipeline.cancel()
        override fun event(id: String, event: String, code: Int?) {
            when (event) {
                "started" -> listener?.onStart(id)
                "done" -> listener?.onDone(id)
                "error" -> listener?.onError(id, code ?: TextToSpeech.ERROR_SYNTHESIS)
                "stopped" -> listener?.onStop(id, true)
            }
        }
    })

    init {
        worker.execute {
            try {
                val dir = store.acquire(10000); leased = true
                if (closed) { releaseModel(); return@execute }
                fun f(name: String) = File(dir, name).absolutePath
                val instance = OfflineTts(config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                        model = f(variant.modelName), voices = f("voices.bin"), tokens = f("tokens.txt"),
                        lexicon = f("lexicon-zh.txt"), lang = "zh"), numThreads = 2, debug = false, provider = "cpu"),
                    ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst").joinToString(",") { f(it) }, maxNumSentences = 1))
                model = instance
                check(instance.numSpeakers() == 103 && instance.sampleRate() == PcmBudget.SAMPLE_RATE)
                voices = (3 until instance.numSpeakers()).map { id ->
                    Voice("Kokoro-${KokoroManifest.speakers[id]}-${id.toString().padStart(3, '0')}",
                        Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
                }.toSet()
                voice = voices.first()
                if (!closed) onInit(TextToSpeech.SUCCESS)
            } catch (_: Throwable) {
                releaseModel()
                if (!closed) onInit(TextToSpeech.ERROR)
            }
        }
    }
    override fun setVoice(voice: Voice): Int {
        val match = voices.firstOrNull { it.name == voice.name } ?: return TextToSpeech.ERROR
        if (this.voice?.name != match.name) stop()
        this.voice = match
        return TextToSpeech.SUCCESS
    }
    override fun setSpeechRate(rate: Float): Int {
        if (!rate.isFinite() || rate !in 0.5f..1.5f) return TextToSpeech.ERROR
        if (this.rate != rate) stop()
        this.rate = rate
        return TextToSpeech.SUCCESS
    }
    override fun setAudioAttributes(attributes: AudioAttributes): Int { this.attributes = attributes; return TextToSpeech.SUCCESS }
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        if (closed || model == null || text.isBlank() || text.length > 12000) return TextToSpeech.ERROR
        return if (cursor.speak(utteranceId, text)) TextToSpeech.SUCCESS else TextToSpeech.ERROR_INVALID_REQUEST
    }
    override fun prefetch(text: String, utteranceId: String) {
        if (!closed && model != null && text.isNotBlank() && text.length <= 12000) cursor.prefetch(utteranceId, text)
    }
    private fun alive(epoch: Long) = !closed && epoch == generation.get()
    private fun generatePcm(token: Long, text: String) {
        val epoch = generation.get()
        val sid = voice?.name?.substringAfterLast('-')?.toIntOrNull()
        if (sid == null) { pipeline.terminalFailure(token, "voice-unavailable", TextToSpeech.ERROR_SYNTHESIS); return }
        val speed = rate
        lateinit var job: Runnable
        job = Runnable {
            pendingGeneration.compareAndSet(job, null)
            if (!alive(epoch)) return@Runnable
            var reservation: PcmBudget.Reservation? = null
            try {
                reservation = budget.reserve { !alive(epoch) } ?: return@Runnable
                if (!alive(epoch)) return@Runnable
                val began = SystemClock.elapsedRealtime()
                main.post { if (alive(epoch)) pipeline.synthesisStarted(token) }
                val callbackSamples = AtomicLong(0)
                val audio = requireNotNull(model).generateWithCallback(text, sid, speed, SherpaTtsCallback { samples ->
                    val count = callbackSamples.addAndGet(samples.size.toLong())
                    if (alive(epoch) && count <= PcmBudget.MAX_SAMPLES) 1 else 0
                })
                if (!alive(epoch)) return@Runnable
                check(callbackSamples.get() <= PcmBudget.MAX_SAMPLES) { "PCM callback limit" }
                val durationMs = audio.samples.size.toLong() * 1000 / audio.sampleRate
                val pcm = reservation.accept(audio.samples, audio.sampleRate)
                val elapsed = SystemClock.elapsedRealtime() - began
                if (!main.post {
                    if (!alive(epoch)) pcm.discard()
                    else {
                        diagnostic("pcm generated token=$token genMs=$elapsed durationMs=$durationMs held=${budget.inUse}")
                        pipeline.synthesisDone(token)
                        pipeline.ready(token, pcm, durationMs)
                    }
                }) pcm.discard()
            } catch (_: Throwable) {
                main.post { if (alive(epoch)) pipeline.terminalFailure(token, "native-generation", TextToSpeech.ERROR_SYNTHESIS) }
            } finally { reservation?.release(); if (reservation != null) reportPcmRetention() }
        }
        pendingGeneration.set(job)
        try { worker.execute(job) }
        catch (_: RuntimeException) {
            pendingGeneration.compareAndSet(job, null)
            pipeline.terminalFailure(token, "generation-worker-unavailable", TextToSpeech.ERROR_SERVICE)
        }
        main.postDelayed({ if (alive(epoch) && pipeline.isSynthesizing(token))
            pipeline.terminalFailure(token, "native-synthesis-timeout", TextToSpeech.ERROR_SYNTHESIS)
        }, SYNTHESIS_TIMEOUT_MS)
    }
    private fun playPcm(token: Long, pcm: PcmBuffer) {
        val epoch = generation.get()
        if (!pcm.markPlaybackClaimed()) { pipeline.terminalFailure(token, "pcm-released", TextToSpeech.ERROR_OUTPUT); return }
        val playbackAttributes = attributes
        try {
            outputWorker.execute {
                var output: AudioTrack? = null
                var completed = false
                var failed = false
                try {
                    if (!alive(epoch)) return@execute
                    val samples = requireNotNull(pcm.samples)
                    val minBuffer = AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(minBuffer > 0)
                    val player = AudioTrack.Builder().setAudioAttributes(playbackAttributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(pcm.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(maxOf(minBuffer, pcm.sampleRate / 5 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    output = player
                    check(player.state == AudioTrack.STATE_INITIALIZED)
                    synchronized(trackLock) { if (alive(epoch)) track = player }
                    if (!alive(epoch)) return@execute
                    // Preload a small bounded portion, then start. Nonblocking writes keep cancellation responsive.
                    var written = player.write(samples, 0, minOf(4096, samples.size), AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0)
                    synchronized(trackLock) { if (alive(epoch)) player.play() }
                    if (!alive(epoch)) return@execute
                    main.post { if (alive(epoch)) pipeline.playbackStarted(token) }
                    var progressAt = SystemClock.elapsedRealtime()
                    while (written < samples.size && alive(epoch)) {
                        val count = player.write(samples, written, minOf(4096, samples.size - written), AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0)
                        if (count > 0) { written += count; progressAt = SystemClock.elapsedRealtime() }
                        else { check(SystemClock.elapsedRealtime() - progressAt < 5_000); Thread.sleep(5) }
                    }
                    val deadline = SystemClock.elapsedRealtime() + samples.size.toLong() * 1000 / pcm.sampleRate + 10_000
                    while (alive(epoch) && (player.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                        check(SystemClock.elapsedRealtime() < deadline); Thread.sleep(10)
                    }
                    completed = alive(epoch) && written == samples.size
                } catch (_: Throwable) { failed = alive(epoch) }
                finally {
                    synchronized(trackLock) {
                        try { output?.pause(); output?.flush(); output?.stop() } catch (_: RuntimeException) { }
                        if (track === output) track = null
                    }
                    try { output?.release() } catch (_: RuntimeException) { completed = false; failed = alive(epoch) }
                    finally { pcm.finishPlayback(); reportPcmRetention() }
                }
                if (completed) main.post { if (alive(epoch)) pipeline.playbackDone(token) }
                else if (failed) main.post { if (alive(epoch)) pipeline.terminalFailure(token, "native-playback", TextToSpeech.ERROR_OUTPUT) }
            }
        } catch (_: RuntimeException) {
            pcm.finishPlayback()
            pipeline.terminalFailure(token, "output-worker-unavailable", TextToSpeech.ERROR_SERVICE)
        }
    }
    private fun cancelWork() {
        synchronized(trackLock) {
            generation.incrementAndGet()
            try { track?.pause(); track?.flush() } catch (_: RuntimeException) { }
        }
        pendingGeneration.getAndSet(null)?.let { worker.remove(it) }
        diagnostic("pcm cancelled held=${budget.inUse}")
    }
    private fun reportPcmRetention() {
        // A canceled output/generation can release later without a playback callback.
        main.post { if (!closed) diagnostic("pcm retention held=${budget.inUse}") }
    }
    private fun nativeDirectError(token: Long) = pipeline.directEvent(token, "error", TextToSpeech.ERROR_SYNTHESIS)
    private fun innerEvent(id: String, event: String, code: Int?) = cursor.unitEvent(id, event, code)
    override fun stop(): Int {
        cursor.cancel()?.let { listener?.onStop(it, true) }
        return TextToSpeech.SUCCESS
    }
    private fun releaseModel() {
        try { model?.release() } finally { model = null; if (leased) { store.release(); leased = false } }
    }
    override fun shutdown() {
        if (closed) return
        closed = true; stop()
        // Release only after active generation exits. Never release a JNI model under a running call.
        worker.execute { releaseModel() }; worker.shutdown()
        // Claimed-but-cancelled PCM jobs must run their finally blocks to return the two-slot permits.
        outputWorker.shutdown()
    }
    companion object {
        const val ID = "inkdock.kokoro.zh.v1_1"
        const val SYNTHESIS_TIMEOUT_MS = 120_000L
        internal fun split(text: String): List<String> = SpeechSentenceChunks.split(text)
    }
}
/** Shared app-private storage lock for setup UI and service. No Activity is retained. */
object KokoroRepository {
    private val lease=java.util.concurrent.atomic.AtomicBoolean(false)
    private val stores=mutableMapOf<String,KokoroModelStore>()
    fun runtimeAvailable(): Boolean = try { System.loadLibrary("sherpa-onnx-jni"); true } catch (_: UnsatisfiedLinkError) { false }
    fun selected(context:Context)=KokoroManifest.variant(context.applicationContext.getSharedPreferences("kokoro",Context.MODE_PRIVATE).getString("variant","float") ?: "float")
    @Synchronized fun select(context:Context,variant:KokoroManifest.Variant) {
        check(!lease.get() && stores.values.none{it.isDownloading()}) { "请等待朗读引擎释放或取消下载后再切换" }
        context.applicationContext.getSharedPreferences("kokoro",Context.MODE_PRIVATE).edit().putString("variant",variant.id).apply()
    }
    @Synchronized fun get(context: Context,variant:KokoroManifest.Variant=selected(context)): KokoroModelStore = stores.getOrPut(variant.id) {
        KokoroModelStore(File(context.applicationContext.filesDir,"neural-models"),variant.files,"kokoro-zh-v1_1-${variant.id}",lease)
    }
}
