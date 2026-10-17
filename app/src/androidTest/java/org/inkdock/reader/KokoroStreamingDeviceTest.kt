package org.inkdock.reader

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.assertRemains
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Real float-model Service tests plus explicitly synthetic Android AudioTrack/ring contract tests.
 * Playback-head advancement proves platform frame consumption, never physical speaker audibility.
 * The existing fixed-runtime float benchmark remains the callback-vs-returned PCM equality gate.
 */
@RunWith(AndroidJUnit4::class)
class KokoroStreamingDeviceTest {
    @Test fun realFloatHeadAdvancesBeforeWholeGenerationAndQueuedNextCompletes() = withFloatService("real_overlap") { speech, metrics, _, _, _ ->
        assertEquals("Keep multiple sentences inside one native unit", 1, KokoroOfflineSpeechEngine.split(MULTI_SENTENCE).size)
        val baseline = diagnostics(speech).toSet()
        onMain { speech.submitQueue(request("stream-real-overlap", listOf(MULTI_SENTENCE, MULTI_SENTENCE)), "synthetic-stream-only") }
        awaitHealthy("Both real streamed entries finish without queued-next deadlock", speech, 240_000) {
            it.optString("phase") == "done"
        }
        val lines = diagnostics(speech).filterNot { it in baseline }
        val generated = lines.filter { it.startsWith("pcm generated ") }
        assertEquals("Two whole real generations", 2, generated.size)
        val observations = JSONArray()
        for ((index, line) in generated.withIndex()) {
            val token = number(line, "token")
            val endAt = number(line, "at")
            val start = lines.first { it.startsWith("pcm output-start ") && number(it, "token") == token }
            val head = lines.first { it.startsWith("pcm head-progress ") && number(it, "token") == token }
            assertTrue("Real JNI must deliver multiple callbacks", number(line, "callbacks") >= 2)
            if (index == 0) {
                assertTrue("Current AudioTrack must receive PCM before whole generate returns", number(start, "firstWriteAt") < endAt)
                assertTrue("Current AudioTrack head must consume frames before whole generate returns", number(head, "at") < endAt)
            } // A fully prefetched next stream may legitimately finish generation before promotion.
            assertTrue(number(head, "frames") > 0)
            assertTrue("Fixed ring stays bounded", number(line, "queuedPeak") in 1..RING_CAPACITY)
            observations.put(JSONObject().put("token", token).put("firstWriteAtMs", number(start, "firstWriteAt"))
                .put("firstHeadAtMs", number(head, "at")).put("headFrames", number(head, "frames"))
                .put("generationEndAtMs", endAt).put("callbackCount", number(line, "callbacks"))
                .put("headBeforeGenerationReturn", number(head, "at") < endAt)
                .put("headLeadMs", endAt - number(head, "at")).put("generationMs", number(line, "genMs"))
                .put("producerWaitMs", number(line, "producerWaitMs")).put("queuedPeakSamples", number(line, "queuedPeak")))
        }
        assertEquals(2, lines.count { it.startsWith("pcm drained ") })
        await("Real streamed slots return after final drain") { onMain { speech.stateSnapshot().getInt("retainedAudioBuffers") == 0 } }
        metrics.put("observations", observations).put("queuedNextCompleted", true)
    }

    @Test fun screenOffNotificationStopCancelsCurrentStreamAndQueuedNextWithoutRestart() = withFloatService("real_stop") { speech, metrics, scenario, activity, web ->
        assertFalse("Requires credential-free emulator", context.getSystemService(KeyguardManager::class.java)!!.isDeviceSecure)
        onMain { speech.submitQueue(request("stream-real-stop", listOf(LONG_MULTI_SENTENCE, LONG_MULTI_SENTENCE)), "synthetic-stream-only") }
        awaitHealthy("Actual streamed playback starts", speech, 150_000) { it.optString("phase") == "started" }
        onMain { web.pauseTimers() }
        systemHome()
        await("Actual Home lifecycle barrier") { readerIsStopped(activity) }
        sleepScreen()
        await("Actual screen-off barrier") { !context.getSystemService(PowerManager::class.java)!!.isInteractive }
        val before = onMain { speech.stateSnapshot() }
        assertTrue("Stop must encounter an active stream", before.getBoolean("active"))
        assertEquals("Current plus queued next remain bounded", 2, before.getInt("bufferedSlots"))
        assertTrue("An actual stream owns a PCM permit", before.getInt("retainedAudioBuffers") in 1..2)
        val stopAt = SystemClock.elapsedRealtime()
        context.getSystemService(NotificationManager::class.java)!!.activeNotifications
            .first { it.id == OfflineSpeechService.NOTIFICATION }.notification.actions
            .first { it.title.toString() == "停止" }.actionIntent.send()
        await("Notification Stop settles native callbacks and both slot owners", 150_000) {
            onMain { speech.stateSnapshot().let { it.optString("phase") == "stopped" &&
                it.getInt("bufferedSlots") == 0 && it.getInt("retainedAudioBuffers") == 0 } }
        }
        assertRemains("Late generation cannot restart stopped queue", 700) {
            onMain { speech.stateSnapshot().optString("phase") == "stopped" && !speech.hasActiveQueue() }
        }
        assertFalse(context.getSystemService(PowerManager::class.java)!!.isInteractive)
        metrics.put("stopRequestedAtMs", stopAt).put("beforeStop", before).put("settledState", onMain { speech.stateSnapshot() })
        wakeScreen(); onMain { web.resumeTimers() }; returnReaderToForeground(scenario, activity)
    }

    @Test fun syntheticProducerStarvationDoesNotBecomeEofOrPrematureDone() {
        val gate = CountDownLatch(1)
        val first = samples(12_000, 1)
        val second = samples(12_000, 2)
        val harness = StreamingAudioFixture(mapOf("a" to StreamPlan(listOf(first, second), gate)))
        val metrics = JSONObject().put("passed", false).put("syntheticProducer", true)
            .put("realAudioTrack", true).put("trackMuted", true)
        publish("synthetic_starvation", metrics)
        try {
            onMain { harness.start("a") }
            await("Real AudioTrack consumes the first synthetic chunk", 15_000) { harness.head() >= first.size }
            await("Consumer is waiting for next producer chunk, not EOF") { harness.streamFor("a")?.readWaitCount?.let { it > 0 } == true }
            assertRemains("Empty PCM queue is starvation, never done", 500) { "done:a" !in harness.events && harness.budget.inUse == 1 }
            assertEquals(0, harness.streamFor("a")!!.queuedSamples)
            gate.countDown()
            await("Explicit EOF and final AudioTrack drain produce done", 15_000) { "done:a" in harness.events }
            assertTrue("Ring transfer preserves exact float32 bits", harness.exactInput("a"))
            assertEquals(listOf("started:a", "done:a"), harness.events.toList())
            await("Starved stream releases both producer and player ownership") { harness.budget.inUse == 0 }
            metrics.put("consumerWaitCount", harness.streamFor("a")!!.readWaitCount)
                .put("inputSamples", first.size + second.size).put("exactRingInputMatch", true)
            harness.close(); metrics.put("passed", true)
        } finally { gate.countDown(); try { harness.close() } finally { publish("synthetic_starvation", metrics) } }
    }

    @Test fun syntheticQueuedNextRingCanFillPromoteAndCancelWithoutLeakingSlots() {
        val plans = mapOf("a" to StreamPlan(listOf(samples(60_000, 3))), "b" to StreamPlan(listOf(samples(96_000, 4))))
        val metrics = JSONObject().put("passed", false).put("syntheticProducer", true).put("realAudioTrack", true).put("trackMuted", true)
        publish("synthetic_next", metrics)
        val promoted = StreamingAudioFixture(plans, promoteNext = true)
        var cancelled: StreamingAudioFixture? = null
        try {
            // A queued incomplete next stream can be promoted and unblock its producer.
            try {
                onMain { promoted.start("a", "b") }
                await("Next ring is full with its producer blocked", 15_000) { promoted.nextProducerBlocked() }
                await("Promotion unblocks next producer and both AudioTracks drain", 25_000) { "done:b" in promoted.events }
                assertEquals(listOf("started:a", "done:a", "started:b", "done:b"), promoted.events.toList())
                assertTrue(promoted.exactInput("a") && promoted.exactInput("b"))
                await("Promotion releases all permits") { promoted.budget.inUse == 0 }
                assertEquals(2, promoted.budget.maxInUse)
                metrics.put("promotionExactRingInputMatch", true).put("promotionMaxSlots", promoted.budget.maxInUse)
            } finally { promoted.close() }
            // Cancel the same blocked-next state instead of permitting promotion.
            val fixture = StreamingAudioFixture(plans, promoteNext = true)
            cancelled = fixture
            onMain { fixture.start("a", "b") }
            await("Cancellation fixture holds current and blocked-next slots", 15_000) { fixture.nextProducerBlocked() }
            assertEquals(2, fixture.budget.inUse)
            onMain { fixture.cancel() }
            await("Cancellation wakes blocked producer/consumer and returns both permits") { fixture.budget.inUse == 0 }
            val atStop = fixture.events.toList()
            assertRemains("Cancelled callbacks cannot complete or restart", 400) { fixture.events.toList() == atStop }
            assertFalse(atStop.any { it.startsWith("done:") })
            fixture.close()
            metrics.put("cancelMaxSlots", fixture.budget.maxInUse).put("slotsAfterCancel", fixture.budget.inUse).put("passed", true)
        } finally {
            try { promoted.close(); cancelled?.close() } finally { publish("synthetic_next", metrics) }
        }
    }

    private fun withFloatService(key: String, test: (OfflineSpeechService, JSONObject, androidx.test.core.app.ActivityScenario<MainActivity>, MainActivity, android.webkit.WebView) -> Unit) = withPreferencesRestored {
        val metrics = JSONObject().put("passed", false).put("realFloatModel", true).put("syntheticTextOnly", true)
            .put("runtimeCommit", KokoroManifest.RUNTIME_COMMIT).put("modelSha256", KokoroManifest.float.files.first().sha256)
            .put("speakerId", 3).put("speakerEvidence", "requested fixed model SID3; no physical speaker identity or audible output claim")
        publish(key, metrics)
        assertEquals("acc4adc175b9d9986106cd20060329673ad5a2e12ef3c557d2d3745b694f8b38", KokoroManifest.float.files.first().sha256)
        val provenance = JSONObject(context.assets.open("kokoro-native-build.json").bufferedReader().use { it.readText() })
        assertEquals(KokoroManifest.RUNTIME_COMMIT, provenance.getString("sourceCommit"))
        KokoroRepository.select(context, KokoroManifest.float)
        try {
            withReader { scenario, activity, web ->
                awaitService(activity)
                val speech = service(activity)
                var initialised = false
                var primaryFailure: Throwable? = null
                try {
                    onMain { activity.Bridge().selectVoiceEngine(KokoroOfflineSpeechEngine.ID) }
                    await("Actual fixed float model initialized", 150_000) { onMain { speech.voiceSettings().optBoolean("initialized") } }
                    assertEquals("native-pcm-stream-prefetch", onMain { speech.stateSnapshot().getString("bufferingMode") })
                    assertTrue(onMain { speech.voiceSettings().getBoolean("engineBindingVerified") })
                    assertTrue(onMain { speech.configureVoice("Kokoro-zf_001-003", 1f) })
                    initialised = true
                    test(speech, metrics, scenario, activity, web)
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    throw failure
                } finally {
                    try {
                        metrics.put("diagnostics", JSONArray(diagnostics(speech)))
                            .put("stateBeforeCleanup", onMain { speech.stateSnapshot() })
                        onMain { web.resumeTimers(); speech.stopPlayback() }
                        // Observe the live engine's ownership, not the zero fallback after tts=null.
                        if (initialised) {
                            await("Cooperative producer and player settle before engine detach", 150_000) {
                                onMain { speech.stateSnapshot().let {
                                    it.getString("bufferingMode") == "native-pcm-stream-prefetch" && it.getInt("retainedAudioBuffers") == 0
                                } }
                            }
                            metrics.put("settledBeforeEngineDetach", onMain { speech.stateSnapshot() })
                        }
                        onMain { speech.releaseKokoroForMaintenance() }
                        val store = KokoroRepository.get(context, KokoroManifest.float)
                        store.acquire(10_000); store.release()
                        metrics.put("modelLeaseReleased", true).put("diagnostics", JSONArray(diagnostics(speech)))
                    } catch (cleanupFailure: Throwable) {
                        metrics.put("cleanupFailureType", cleanupFailure.javaClass.simpleName)
                        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
                    }
                }
            }
            metrics.put("passed", true)
        } finally { publish(key, metrics) }
    }
    private fun diagnostics(speech: OfflineSpeechService) = onMain { speech.voiceSettings().getString("diagnostics").lines() }
    private fun awaitHealthy(label: String, speech: OfflineSpeechService, timeout: Long, condition: (JSONObject) -> Boolean) = await(label, timeout) {
        val state = onMain { speech.stateSnapshot() }
        assertFalse("Native stream failed or paused: $state", state.optString("phase") in setOf("error", "paused"))
        condition(state)
    }
    private fun number(line: String, key: String): Long = Regex("(?:^| )${Regex.escape(key)}=(\\d+)(?: |$)")
        .find(line)?.groupValues?.get(1)?.toLong() ?: error("Missing numeric diagnostic $key in $line")
    private fun publish(key: String, value: JSONObject) {
        val target = File(context.filesDir, "native-test-artifacts/$REPORT_FILE")
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val report = if (target.isFile) JSONObject(target.readText()) else JSONObject().put("schemaVersion", 1)
        report.put(key, value)
        val partial = File(target.parentFile, "$REPORT_FILE.part")
        partial.writeText(report.toString(2)); check(partial.renameTo(target))
    }
    private fun samples(count: Int, offset: Int) = FloatArray(count) { ((it + offset) % 257 - 128) / 131072f }
    companion object {
        const val REPORT_FILE = "streaming-device-metrics.json"
        private const val RING_CAPACITY = 192_000L
        private const val MULTI_SENTENCE = "今天是一个适合阅读的好日子。我们一起读一本有趣的书。读完以后休息一会儿。"
        private const val LONG_MULTI_SENTENCE = "这是一段公开的合成测试文字，请安静地读完。今天我们一起阅读这篇简单的文章，学习新的知识。阅读结束以后，可以到窗外看看蓝色的天空。"
    }
}

private data class StreamPlan(val chunks: List<FloatArray>, val pauseAfterFirst: CountDownLatch? = null)

/** Synthetic component fixture, not the JNI adapter. Production ring/slot/pipeline with a deliberate 2-second stress ring; actual muted AudioTrack. */
private class StreamingAudioFixture(private val plans: Map<String, StreamPlan>, private val promoteNext: Boolean = false) {
    val budget = PcmBudget()
    val events = CopyOnWriteArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private val producer = Executors.newSingleThreadExecutor()
    private val output = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val streams = ConcurrentHashMap<String, StreamingPcmSlot>()
    private val received = ConcurrentHashMap<String, FloatArray>()
    private val receivedCounts = ConcurrentHashMap<String, Int>()
    private val names = ConcurrentHashMap<Long, String>()
    private val trackLock = Any()
    private var track: AudioTrack? = null
    @Volatile private var closed = false
    private fun alive(value: Long) = !closed && epoch.get() == value
    private val pipeline: StreamingSpeechPipeline<StreamingPcmSlot> = StreamingSpeechPipeline(object : StreamingSpeechPipeline.Port<StreamingPcmSlot> {
        override fun synthesize(token: Long, text: String) {
            val generation = epoch.get(); names[token] = text
            producer.execute {
                var slot: StreamingPcmSlot? = null
                var reservation: PcmBudget.Reservation? = null
                var successful = false
                try {
                    reservation = budget.reserve { !alive(generation) } ?: return@execute
                    lateinit var created: StreamingPcmSlot
                    val ring = BoundedPcmStream(capacitySamples = 48_000, isCancelled = { !alive(generation) }, onFirstPcm = {
                        main.post { if (alive(generation)) pipeline.streamReady(token, created) else created.discard() }
                    })
                    created = StreamingPcmSlot(ring, reservation); reservation = null
                    slot = created; streams[text] = created
                    main.post { if (alive(generation)) pipeline.synthesisStarted(token) }
                    val plan = plans.getValue(text)
                    for ((index, chunk) in plan.chunks.withIndex()) {
                        if (!ring.write(chunk)) return@execute
                        if (index == 0) plan.pauseAfterFirst?.let { gate ->
                            while (alive(generation) && !gate.await(25, TimeUnit.MILLISECONDS)) { }
                        }
                        if (!alive(generation)) return@execute
                    }
                    main.post { if (alive(generation)) pipeline.synthesisFinished(token) }
                    successful = true; ring.finish()
                } catch (_: Throwable) { main.post { if (alive(generation)) pipeline.terminalFailure(token, "synthetic-producer", -1) } }
                finally { if (!successful) slot?.discard(); slot?.producerDone(); reservation?.release() }
            }
        }
        override fun play(token: Long, audio: StreamingPcmSlot) {
            check(audio.claimPlayback())
            val generation = epoch.get()
            val name = names.getValue(token)
            output.execute {
                var player: AudioTrack? = null
                var complete = false
                try {
                    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    val min = AudioTrack.getMinBufferSize(PcmBudget.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(min > 0)
                    val outputTrack = AudioTrack.Builder().setAudioAttributes(attrs)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(PcmBudget.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(maxOf(min, 19_200)).setTransferMode(AudioTrack.MODE_STREAM).build()
                    player = outputTrack; check(outputTrack.state == AudioTrack.STATE_INITIALIZED)
                    check(outputTrack.setVolume(0f) == AudioTrack.SUCCESS) // Per-track mute, never global settings.
                    synchronized(trackLock) { if (alive(generation)) track = outputTrack }
                    val input = FloatArray(plans.getValue(name).chunks.sumOf { it.size })
                    received[name] = input
                    val chunk = FloatArray(4096)
                    var written = 0; var started = false
                    var count = audio.stream.read(chunk)
                    while (count > 0 && alive(generation)) {
                        chunk.copyInto(input, written, 0, count)
                        var offset = 0; var progressedAt = SystemClock.elapsedRealtime()
                        while (offset < count && alive(generation)) {
                            val amount = outputTrack.write(chunk, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(amount >= 0)
                            if (amount > 0) {
                                offset += amount; written += amount; receivedCounts[name] = written
                                progressedAt = SystemClock.elapsedRealtime()
                                if (!started) { outputTrack.play(); started = true; main.post { if (alive(generation)) pipeline.playbackStarted(token) } }
                            } else { check(SystemClock.elapsedRealtime() - progressedAt < 5_000); Thread.sleep(5) }
                        }
                        count = if (alive(generation)) audio.stream.read(chunk) else -1
                    }
                    if (!alive(generation)) return@execute
                    check(count == 0 && started)
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while (alive(generation) && (outputTrack.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                        check(SystemClock.elapsedRealtime() < deadline); Thread.sleep(10)
                    }
                    complete = alive(generation)
                } catch (_: Throwable) { main.post { if (alive(generation)) pipeline.terminalFailure(token, "synthetic-output", -1) } }
                finally {
                    synchronized(trackLock) { if (track === player) track = null }
                    try { player?.stop() } catch (_: RuntimeException) { }
                    try { player?.release() } finally { audio.finishPlayback() }
                }
                if (complete) main.post { if (alive(generation)) pipeline.playbackDone(token) }
            }
        }
        override fun cancelSynthesis() { epoch.incrementAndGet(); streams.values.forEach { it.discard() } }
        override fun dispose(audio: StreamingPcmSlot) = audio.discard()
        override fun diagnostic(value: String) { }
        override fun event(id: String, event: String, code: Int?) {
            events += "$event:$id"
            if (event == "done" && id == "a" && promoteNext) pipeline.speak("b", "b")
        }
    }, SystemClock::elapsedRealtime)
    fun start(current: String, next: String? = null) { pipeline.speak(current, current); next?.let { pipeline.prefetch(it, it) } }
    fun cancel() = pipeline.cancel()
    fun streamFor(name: String) = streams[name]?.stream
    fun head(): Long = synchronized(trackLock) { track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L }
    fun nextProducerBlocked() = onMain { pipeline.prefixAhead && !pipeline.readyAhead } && budget.inUse == 2 &&
        streamFor("b")?.let { it.queuedSamples == 48_000 && it.writeWaitNanos > 0 } == true
    fun exactInput(name: String): Boolean {
        val actual = received[name] ?: return false
        if (receivedCounts[name] != actual.size) return false
        var index = 0
        for (part in plans.getValue(name).chunks) for (sample in part) if (sample.toRawBits() != actual[index++].toRawBits()) return false
        return index == actual.size
    }
    fun close() {
        if (closed) return
        onMain { if (!closed) { closed = true; pipeline.cancel(); producer.shutdown(); output.shutdown() } }
        check(producer.awaitTermination(10, TimeUnit.SECONDS))
        check(output.awaitTermination(10, TimeUnit.SECONDS))
        check(budget.inUse == 0)
    }
}
