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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One leased model, one producer, one audio worker and two bounded streaming PCM rings. */
internal class KokoroOfflineSpeechEngine(private val store: KokoroModelStore,
    private val variant: KokoroManifest.Variant, systemEngines: List<TextToSpeech.EngineInfo>,
    private val diagnostic: (String) -> Unit = {}, onInit: (Int) -> Unit) : OfflineSpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val outputWorker = Executors.newSingleThreadExecutor()
    private val pendingGeneration = AtomicReference<Runnable?>(null)
    private val generation = AtomicLong(0)
    private val budget = PcmBudget()
    private val streams = ConcurrentHashMap<Long, StreamingPcmSlot>()
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
    override val bufferingMode get() = "native-pcm-stream-prefetch"
    override val bufferedSlots get() = pipeline.slotCount
    override val nextAudioReady get() = pipeline.readyAhead
    override val retainedAudioBuffers get() = budget.inUse

    private val pipeline: StreamingSpeechPipeline<StreamingPcmSlot> = StreamingSpeechPipeline(object : StreamingSpeechPipeline.Port<StreamingPcmSlot> {
        override fun synthesize(token: Long, text: String) = generatePcm(token, text)
        override fun play(token: Long, audio: StreamingPcmSlot) = playPcm(token, audio)
        override fun cancelSynthesis() = cancelWork()
        override fun dispose(audio: StreamingPcmSlot) = audio.discard()
        override fun event(id: String, event: String, code: Int?) = innerEvent(id, event, code)
        override fun diagnostic(value: String) = diagnostic.invoke(value)
    }, SystemClock::elapsedRealtime)
    private val cursor: SubsentenceSpeechCursor = SubsentenceSpeechCursor(::split, object : SubsentenceSpeechCursor.Sink {
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
            var slot: StreamingPcmSlot? = null
            var successful = false
            try {
                val budgetStart = SystemClock.elapsedRealtime()
                reservation = budget.reserve { !alive(epoch) } ?: return@Runnable
                if (!alive(epoch)) return@Runnable
                val began = SystemClock.elapsedRealtime()
                lateinit var callback: StreamingSynthesisCallback
                lateinit var audioSlot: StreamingPcmSlot
                val pipe = BoundedPcmStream(isCancelled = { !alive(epoch) }, onFirstPcm = {
                    val firstPcmAt = SystemClock.elapsedRealtime()
                    if (!main.post {
                        if (!alive(epoch)) audioSlot.discard()
                        else {
                            diagnostic("pcm first token=$token firstCallbackMs=${callback.firstCallbackAt - began} firstPcmMs=${firstPcmAt - began} at=$firstPcmAt")
                            pipeline.streamReady(token, audioSlot)
                        }
                    }) audioSlot.discard()
                })
                audioSlot = StreamingPcmSlot(pipe, reservation) { streams.remove(token) }
                slot = audioSlot; reservation = null
                streams[token] = audioSlot
                if (!alive(epoch)) return@Runnable
                main.post {
                    if (alive(epoch)) {
                        diagnostic("pcm producer token=$token budgetWaitMs=${began - budgetStart}")
                        pipeline.synthesisStarted(token)
                        main.postDelayed({ if (alive(epoch) && pipeline.isSynthesizing(token))
                            pipeline.terminalFailure(token, "native-synthesis-timeout", TextToSpeech.ERROR_SYNTHESIS)
                        }, SYNTHESIS_TIMEOUT_MS)
                    }
                }
                callback = StreamingSynthesisCallback(pipe, { alive(epoch) }, SystemClock::elapsedRealtime)
                val audio = requireNotNull(model).generateWithCallback(text, sid, speed, SherpaTtsCallback(callback))
                if (!alive(epoch)) return@Runnable
                // Audited Kokoro appends every exact callback vector without postprocessing.
                check(callback.acceptsFinal(audio.sampleRate, audio.samples.size))
                val ended = SystemClock.elapsedRealtime()
                val durationMs = pipe.totalWritten * 1000 / PcmBudget.SAMPLE_RATE
                if (!main.post {
                    if (alive(epoch)) {
                        diagnostic("pcm generated token=$token genMs=${ended - began} durationMs=$durationMs callbacks=${callback.callbackCount} callbackSamples=${callback.samplesReceived} producerWaitMs=${pipe.writeWaitNanos / 1_000_000} queuedPeak=${pipe.maxQueuedSamples} held=${budget.inUse} at=$ended")
                        pipeline.synthesisFinished(token)
                    } else audioSlot.discard()
                }) { audioSlot.discard(); return@Runnable }
                successful = true
                // EOF is explicit. An empty queue between callbacks is never completion.
                pipe.finish()
            } catch (_: Throwable) {
                main.post { if (alive(epoch)) pipeline.terminalFailure(token, "native-stream-generation", TextToSpeech.ERROR_SYNTHESIS) }
            } finally {
                if (!successful) slot?.discard()
                slot?.producerDone(); reservation?.release(); reportPcmRetention()
            }
        }
        pendingGeneration.set(job)
        try { worker.execute(job) }
        catch (_: RuntimeException) {
            pendingGeneration.compareAndSet(job, null)
            pipeline.terminalFailure(token, "generation-worker-unavailable", TextToSpeech.ERROR_SERVICE)
        }
    }
    private fun playPcm(token: Long, pcm: StreamingPcmSlot) {
        val epoch = generation.get()
        if (!pcm.claimPlayback()) { pipeline.terminalFailure(token, "pcm-released", TextToSpeech.ERROR_OUTPUT); return }
        val playbackAttributes = attributes
        try {
            outputWorker.execute {
                var output: AudioTrack? = null
                var completed = false
                var failed = false
                var written = 0L
                var underruns = 0
                try {
                    if (!alive(epoch)) return@execute
                    val minBuffer = AudioTrack.getMinBufferSize(PcmBudget.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(minBuffer > 0)
                    val player = AudioTrack.Builder().setAudioAttributes(playbackAttributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(PcmBudget.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(maxOf(minBuffer, PcmBudget.SAMPLE_RATE / 5 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    output = player
                    check(player.state == AudioTrack.STATE_INITIALIZED)
                    synchronized(trackLock) { if (alive(epoch)) track = player }
                    if (!alive(epoch)) return@execute
                    val samples = FloatArray(4096)
                    var started = false
                    var progressAt = SystemClock.elapsedRealtime()
                    var count = pcm.stream.read(samples)
                    while (count > 0 && alive(epoch)) {
                        var offset = 0
                        while (offset < count && alive(epoch)) {
                            val amount = player.write(samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(amount >= 0)
                            if (amount > 0) {
                                offset += amount; written += amount; progressAt = SystemClock.elapsedRealtime()
                                if (!started) {
                                    val firstWriteAt = progressAt
                                    synchronized(trackLock) { if (alive(epoch)) player.play() }
                                    val playAt = SystemClock.elapsedRealtime()
                                    started = true
                                    main.post { if (alive(epoch)) {
                                        diagnostic("pcm output-start token=$token firstWriteAt=$firstWriteAt playCallAt=$playAt (not_audible_output)")
                                        pipeline.playbackStarted(token)
                                        reportFirstHeadProgress(token, epoch, player)
                                    } }
                                }
                            } else { check(SystemClock.elapsedRealtime() - progressAt < 5_000); Thread.sleep(5) }
                        }
                        count = if (alive(epoch)) pcm.stream.read(samples) else -1
                        // Time waiting for the producer must not count against the output-write watchdog.
                        progressAt = SystemClock.elapsedRealtime()
                    }
                    if (!alive(epoch)) return@execute
                    check(count == 0 && started && written > 0)
                    val deadline = SystemClock.elapsedRealtime() + written * 1000 / PcmBudget.SAMPLE_RATE + 10_000
                    while (alive(epoch) && (player.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                        check(SystemClock.elapsedRealtime() < deadline); Thread.sleep(10)
                    }
                    underruns = player.underrunCount
                    completed = alive(epoch)
                } catch (_: Throwable) { failed = alive(epoch) }
                finally {
                    synchronized(trackLock) {
                        try { output?.pause(); output?.flush(); output?.stop() } catch (_: RuntimeException) { }
                        if (track === output) track = null
                    }
                    try { output?.release() } catch (_: RuntimeException) { completed = false; failed = alive(epoch) }
                    finally { pcm.finishPlayback(); reportPcmRetention() }
                }
                if (completed) main.post { if (alive(epoch)) {
                    diagnostic("pcm drained token=$token samples=$written consumerWaitMs=${pcm.stream.readWaitNanos / 1_000_000} waitCount=${pcm.stream.readWaitCount} underruns=$underruns")
                    pipeline.playbackDone(token)
                } }
                else if (failed) main.post { if (alive(epoch)) pipeline.terminalFailure(token, "native-stream-playback", TextToSpeech.ERROR_OUTPUT) }
            }
        } catch (_: RuntimeException) {
            pcm.finishPlayback()
            pipeline.terminalFailure(token, "output-worker-unavailable", TextToSpeech.ERROR_SERVICE)
        }
    }
    private fun reportFirstHeadProgress(token: Long, epoch: Long, player: AudioTrack) {
        val probe = object : Runnable {
            override fun run() {
                if (!alive(epoch)) return
                val frames = synchronized(trackLock) {
                    if (track !== player) return
                    try { player.playbackHeadPosition.toLong() and 0xffffffffL }
                    catch (_: RuntimeException) { return }
                }
                if (frames > 0) diagnostic("pcm head-progress token=$token frames=$frames at=${SystemClock.elapsedRealtime()} generationActive=${pipeline.isSynthesizing(token)}")
                else main.postDelayed(this, 25)
            }
        }
        main.post(probe)
    }
    private fun cancelWork() {
        synchronized(trackLock) {
            generation.incrementAndGet()
            try { track?.pause(); track?.flush() } catch (_: RuntimeException) { }
        }
        pendingGeneration.getAndSet(null)?.let { worker.remove(it) }
        streams.values.forEach { it.discard() }
        diagnostic("pcm cancelled held=${budget.inUse}")
    }
    private fun reportPcmRetention() {
        // A canceled output/generation can release later without a playback callback.
        main.post { if (!closed) diagnostic("pcm retention held=${budget.inUse}") }
    }
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
