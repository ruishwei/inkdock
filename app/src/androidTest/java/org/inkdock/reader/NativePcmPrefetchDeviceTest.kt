package org.inkdock.reader

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.instrumentation
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.inkdock.reader.BackgroundReaderTestSupport.readerIsStopped
import org.inkdock.reader.BackgroundReaderTestSupport.returnReaderToForeground
import org.inkdock.reader.BackgroundReaderTestSupport.sleepScreen
import org.inkdock.reader.BackgroundReaderTestSupport.systemHome
import org.inkdock.reader.BackgroundReaderTestSupport.wakeScreen
import org.inkdock.reader.BackgroundReaderTestSupport.withReader
import org.inkdock.reader.KokoroDeviceTestSupport.awaitService
import org.inkdock.reader.KokoroDeviceTestSupport.context
import org.inkdock.reader.KokoroDeviceTestSupport.request
import org.inkdock.reader.KokoroDeviceTestSupport.service
import org.inkdock.reader.KokoroDeviceTestSupport.withPreferencesRestored
import org.inkdock.reader.KokoroDeviceTestSupport.writeReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A deliberately fake fast PCM generator exercises production two-slot/budget logic, real
 * AudioTrack drain and the foreground Service with Home/screen-off. It proves platform scheduling,
 * not Kokoro speed, JNI generation, intelligible audio, or sample-accurate gaplessness.
 */
@RunWith(AndroidJUnit4::class)
class NativePcmPrefetchDeviceTest {
    @Test fun nextPcmBecomesReadyInBackgroundBeforeCurrentDrainAndStopReclaimsBothSlots() = withPreferencesRestored {
        assertFalse("Screen-off test requires a credential-free test device", context.getSystemService(KeyguardManager::class.java)!!.isDeviceSecure)
        val engine = FastSilentPcmEngine()
        val metrics = JSONObject().put("passed", false).put("syntheticGenerator", true).put("audio", "zero PCM; no speech quality evidence")
            .put("realAndroidAudioTrack", true).put("productionPipelineAndBudget", true)
        try {
            withReader { scenario, activity, web ->
                awaitService(activity)
                val speech = service(activity)
                onMain { installTestOnlyEngine(speech, engine) }
                await("Test-only PCM adapter attaches through real Service initialization") {
                    onMain { speech.voiceSettings().optBoolean("initialized") }
                }
                onMain { speech.submitQueue(request("synthetic-pcm-prefetch", listOf("SYNTHETIC PCM A", "SYNTHETIC PCM B", "SYNTHETIC PCM C")), "synthetic-pcm-only") }
                await("Real AudioTrack begins synthetic current buffer") { onMain { speech.stateSnapshot().optString("phase") == "started" } }
                onMain { web.pauseTimers() }
                systemHome()
                await("Reader actually stopped behind Home") { readerIsStopped(activity) }
                sleepScreen()
                await("Device screen is noninteractive") { !context.getSystemService(PowerManager::class.java)!!.isInteractive }
                // The next synthetic generation is deliberately held until both lifecycle barriers pass.
                engine.allowNextGeneration.countDown()
                await("Next PCM exists while first AudioTrack has not drained") {
                    onMain { speech.stateSnapshot().getBoolean("nextAudioReady") } && engine.doneCount.get() == 0
                }
                val readyAt = SystemClock.elapsedRealtime()
                assertTrue(readerIsStopped(activity))
                assertFalse(context.getSystemService(PowerManager::class.java)!!.isInteractive)
                assertEquals(2, onMain { speech.stateSnapshot().getInt("retainedAudioBuffers") })
                assertEquals(2, engine.budget.maxInUse)
                assertEquals(1, engine.maxGenerationWorkers.get())
                await("Current drains and prepared next starts while screen remains off", 25_000) {
                    onMain { speech.stateSnapshot().let { it.optInt("queueIndex") == 1 && it.optString("phase") == "started" && it.getBoolean("nextAudioReady") } }
                }
                assertTrue("READY preceded current drain", engine.firstDoneAt.get() > readyAt)
                assertTrue(engine.diagnostics.any { line -> line.contains("boundary") &&
                    Regex("readyLeadMs=(\\d+)").find(line)?.groupValues?.get(1)?.toLong()?.let { it > 0 } == true })
                assertFalse(context.getSystemService(PowerManager::class.java)!!.isInteractive)
                assertEquals("Stop must encounter current plus ready next PCM", 2, engine.budget.inUse)
                val notification = context.getSystemService(NotificationManager::class.java)!!.activeNotifications
                    .first { it.id == OfflineSpeechService.NOTIFICATION }.notification
                notification.actions.first { it.title.toString() == "停止" }.actionIntent.send()
                await("Notification Stop ends queue and reclaims claimed plus prefetched PCM") {
                    onMain { speech.stateSnapshot().optString("phase") == "stopped" } && engine.budget.inUse == 0
                }
                val stopped = onMain { speech.stateSnapshot() }
                val doneAtStop = engine.doneCount.get()
                Thread.sleep(350)
                assertEquals(doneAtStop, engine.doneCount.get())
                assertEquals(0, onMain { speech.stateSnapshot().getInt("bufferedSlots") })
                metrics.put("nextReadyObservedAtMs", readyAt).put("firstDrainedAtMs", engine.firstDoneAt.get())
                    .put("readyLeadObservedMs", engine.firstDoneAt.get() - readyAt)
                    .put("maxPcmSlots", engine.budget.maxInUse).put("maxGenerationWorkers", engine.maxGenerationWorkers.get())
                    .put("finalState", stopped).put("diagnostics", JSONArray(engine.diagnostics))
                wakeScreen()
                onMain { web.resumeTimers() }
                returnReaderToForeground(scenario, activity)
            }
            onMain { engine.shutdown() }
            metrics.put("passed", true)
        } finally {
            onMain { engine.shutdown() }
            metrics.put("diagnostics", JSONArray(engine.diagnostics))
                .put("maxPcmSlots", engine.budget.maxInUse).put("retainedAtCleanup", engine.budget.inUse)
            writeReport("synthetic-prefetch-metrics.json", "background", metrics)
        }
    }

    /** Reflection stays wholly in the test APK; no production flag, exported service or intent hook. */
    private fun installTestOnlyEngine(service: OfflineSpeechService, fake: FastSilentPcmEngine) {
        fun field(name: String) = OfflineSpeechService::class.java.getDeclaredField(name).apply { isAccessible = true }
        service.stopPlayback()
        (field("tts").get(service) as? OfflineSpeechEngine)?.shutdown()
        val generation = field("engineGeneration").getInt(service) + 1
        field("engineGeneration").setInt(service, generation)
        field("tts").set(service, fake)
        field("requestedEngine").set(service, instrumentation.context.packageName)
        field("engineLoading").setBoolean(service, true)
        field("engineInitRequestedAt").setLong(service, SystemClock.elapsedRealtime())
        OfflineSpeechService::class.java.getDeclaredMethod("finishEngineInit", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, generation, TextToSpeech.SUCCESS)
    }
}

/** Deterministic synthetic generator, actual Android output; all state callbacks return to main. */
private class FastSilentPcmEngine : OfflineSpeechEngine {
    val budget = PcmBudget()
    val allowNextGeneration = CountDownLatch(1)
    val doneCount = AtomicInteger()
    val firstDoneAt = AtomicLong()
    val maxGenerationWorkers = AtomicInteger()
    val diagnostics = CopyOnWriteArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private val generator = Executors.newSingleThreadExecutor()
    private val output = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val calls = AtomicInteger()
    private val generating = AtomicInteger()
    @Volatile private var closed = false
    private var listener: UtteranceProgressListener? = null
    private var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    override val engines = emptyList<TextToSpeech.EngineInfo>()
    override val defaultEngine = "test-only-fast-silent-pcm"
    override val voice = Voice("test-only-fast-silent-zh", Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
    override val voices = setOf(voice)
    override val bufferingMode = "test-only-pcm-prefetch"
    override val bufferedSlots get() = pipeline.slotCount
    override val nextAudioReady get() = pipeline.readyAhead
    override val retainedAudioBuffers get() = budget.inUse
    private fun alive(value: Long) = !closed && epoch.get() == value
    private val pipeline: TwoSlotSpeechPipeline<PcmBuffer> = TwoSlotSpeechPipeline(object : TwoSlotSpeechPipeline.Port<PcmBuffer> {
        override fun synthesize(token: Long, text: String) {
            check(text.startsWith("SYNTHETIC PCM"))
            val generation = epoch.get()
            val index = calls.incrementAndGet()
            generator.execute {
                var reserved: PcmBudget.Reservation? = null
                try {
                    val workers = generating.incrementAndGet()
                    maxGenerationWorkers.updateAndGet { maxOf(it, workers) }
                    if (index > 1 && !allowNextGeneration.await(20, TimeUnit.SECONDS)) error("Synthetic gate timed out")
                    if (!alive(generation)) return@execute
                    reserved = budget.reserve { !alive(generation) } ?: return@execute
                    main.post { if (alive(generation)) pipeline.synthesisStarted(token) }
                    val pcm = reserved.accept(FloatArray(PcmBudget.SAMPLE_RATE * 10), PcmBudget.SAMPLE_RATE)
                    main.post {
                        if (!alive(generation)) pcm.discard()
                        else { pipeline.synthesisDone(token); pipeline.ready(token, pcm, 10_000) }
                    }
                } catch (_: Throwable) {
                    main.post { if (alive(generation)) pipeline.terminalFailure(token, "synthetic-generation", TextToSpeech.ERROR_SYNTHESIS) }
                } finally { reserved?.release(); generating.decrementAndGet() }
            }
        }
        override fun play(token: Long, audio: PcmBuffer) {
            check(audio.markPlaybackClaimed())
            val generation = epoch.get()
            output.execute {
                var track: AudioTrack? = null
                var drained = false
                try {
                    if (!alive(generation)) return@execute
                    val samples = requireNotNull(audio.samples)
                    val player = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(samples.size * 4).setTransferMode(AudioTrack.MODE_STATIC).build()
                    track = player
                    check(player.state != AudioTrack.STATE_UNINITIALIZED)
                    check(player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size)
                    check(player.state == AudioTrack.STATE_INITIALIZED)
                    if (!alive(generation)) return@execute
                    player.play()
                    main.post { if (alive(generation)) pipeline.playbackStarted(token) }
                    val deadline = SystemClock.elapsedRealtime() + 25_000
                    while (alive(generation) && (player.playbackHeadPosition.toLong() and 0xffffffffL) < samples.size) {
                        check(SystemClock.elapsedRealtime() < deadline); Thread.sleep(10)
                    }
                    drained = alive(generation)
                } catch (_: Throwable) {
                    main.post { if (alive(generation)) pipeline.terminalFailure(token, "synthetic-audiotrack", TextToSpeech.ERROR_OUTPUT) }
                } finally {
                    try { track?.stop() } catch (_: RuntimeException) { }
                    try { track?.release() } finally { audio.finishPlayback() }
                }
                if (drained) main.post { if (alive(generation)) pipeline.playbackDone(token) }
            }
        }
        override fun directSpeak(token: Long, text: String) = error("Synthetic PCM cannot silently fallback")
        override fun cancelSynthesis() { epoch.incrementAndGet() }
        override fun dispose(audio: PcmBuffer) = audio.discard()
        override fun diagnostic(value: String) { diagnostics += value }
        override fun event(id: String, event: String, code: Int?) {
            when (event) {
                "started" -> listener?.onStart(id)
                "done" -> { doneCount.incrementAndGet(); firstDoneAt.compareAndSet(0, SystemClock.elapsedRealtime()); listener?.onDone(id) }
                "error" -> listener?.onError(id, code ?: TextToSpeech.ERROR)
            }
        }
    }, SystemClock::elapsedRealtime)
    override fun setVoice(voice: Voice) = if (voice.name == this.voice.name) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun setSpeechRate(rate: Float) = TextToSpeech.SUCCESS
    override fun setAudioAttributes(attributes: AudioAttributes): Int { this.attributes = attributes; return TextToSpeech.SUCCESS }
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        pipeline.speak(utteranceId, text); return TextToSpeech.SUCCESS
    }
    override fun prefetch(text: String, utteranceId: String) = pipeline.prefetch(utteranceId, text)
    override fun stop(): Int { pipeline.cancel(); return TextToSpeech.SUCCESS }
    override fun shutdown() {
        if (closed) return
        closed = true; stop(); allowNextGeneration.countDown(); generator.shutdown(); output.shutdown()
    }
}
