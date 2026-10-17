package org.inkdock.reader

import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.inkdock.reader.BackgroundReaderTestSupport.readerIsStopped
import org.inkdock.reader.BackgroundReaderTestSupport.returnReaderToForeground
import org.inkdock.reader.BackgroundReaderTestSupport.systemHome
import org.inkdock.reader.BackgroundReaderTestSupport.withReader
import org.inkdock.reader.KokoroDeviceTestSupport.TEXT
import org.inkdock.reader.KokoroDeviceTestSupport.NEXT_TEXT
import org.inkdock.reader.KokoroDeviceTestSupport.artifacts
import org.inkdock.reader.KokoroDeviceTestSupport.awaitService
import org.inkdock.reader.KokoroDeviceTestSupport.context
import org.inkdock.reader.KokoroDeviceTestSupport.elapsed
import org.inkdock.reader.KokoroDeviceTestSupport.request
import org.inkdock.reader.KokoroDeviceTestSupport.service
import org.inkdock.reader.KokoroDeviceTestSupport.variants
import org.inkdock.reader.KokoroDeviceTestSupport.withPreferencesRestored
import org.inkdock.reader.KokoroDeviceTestSupport.writeReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Genuine fixed-hash model + x86_64 JNI. Missing staged data/runtime fails instead of skipping. */
@RunWith(AndroidJUnit4::class)
class KokoroNativeDeviceTest {
    @Test fun x86JniLoadsBothVariantsAndExportsChineseForSpeaker3And53() {
        assertTrue("This CI proof requires the x86_64 Android ABI", "x86_64" in Build.SUPPORTED_ABIS)
        assertTrue("JNI process must be 64 bit", Process.is64Bit())
        assertTrue("Audited JNI library must load", KokoroRepository.runtimeAvailable())
        val provenance = JSONObject(context.assets.open("kokoro-native-build.json").bufferedReader().use { it.readText() })
        assertEquals(KokoroManifest.RUNTIME_COMMIT, provenance.getString("sourceCommit"))
        assertEquals("zf_001", KokoroManifest.speakers[3])
        assertEquals("zf_090", KokoroManifest.speakers[53])
        val metrics = JSONObject().put("passed", false).put("syntheticTextOnly", true).put("textFixture", TEXT)
            .put("abi", "x86_64").put("androidApi", Build.VERSION.SDK_INT)
            .put("runtimeCommit", KokoroManifest.RUNTIME_COMMIT)
            .put("timingBasis", "elapsed realtime; RTF is observation, not a pass threshold")
        val results = JSONArray(); metrics.put("variants", results)
        try {
            for (variant in variants()) {
                val row = JSONObject().put("variant", variant.id).put("modelSha256", variant.files.first().sha256)
                    .put("manifestFiles", variant.files.size)
                results.put(row)
                val store = KokoroRepository.get(context, variant)
                val hashStart = SystemClock.elapsedRealtime()
                val directory = store.acquire(10_000)
                row.put("verifyMs", elapsed(hashStart))
                var tts: OfflineTts? = null
                try {
                    fun path(name: String) = File(directory, name).absolutePath
                    val initStart = SystemClock.elapsedRealtime()
                    val model = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(model = path(variant.modelName), voices = path("voices.bin"),
                            tokens = path("tokens.txt"), lexicon = path("lexicon-zh.txt"), lang = "zh"),
                        numThreads = 2, debug = false, provider = "cpu"),
                        ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst").joinToString(",", transform = ::path),
                        maxNumSentences = 1))
                    tts = model
                    row.put("initMs", elapsed(initStart)).put("sampleRate", model.sampleRate())
                        .put("speakerCount", model.numSpeakers()).put("languageMetadata", model.lang())
                    assertEquals(24_000, model.sampleRate())
                    assertEquals(103, model.numSpeakers())
                    assertEquals(8, variant.files.size)
                    val runs = JSONArray(); row.put("synthesis", runs)
                    for (sid in listOf(3, 53)) {
                        val callbacks = AtomicLong()
                        val callbackSamples = AtomicLong()
                        val start = SystemClock.elapsedRealtime()
                        val audio = model.generateWithCallback(TEXT, sid, 1f) { samples ->
                            callbacks.incrementAndGet(); callbackSamples.addAndGet(samples.size.toLong()); 1
                        }
                        val synthesisMs = elapsed(start)
                        assertEquals(24_000, audio.sampleRate)
                        assertTrue("Real Chinese synthesis must return bounded nonempty PCM", audio.samples.size in 1..(24_000 * 15))
                        assertTrue("Native samples must be finite", audio.samples.all { it.isFinite() })
                        assertTrue("Real model output must not be silent synthetic PCM", audio.samples.any { abs(it) > 0.0001f })
                        assertTrue("Production callback JNI path must be exercised", callbacks.get() > 0)
                        val wav = File(artifacts, "${variant.id}-speaker-$sid.wav")
                        assertTrue("Native WAV export succeeds", audio.save(wav.absolutePath))
                        verifyWav(wav, audio.samples.size)
                        val durationMs = audio.samples.size * 1000.0 / audio.sampleRate
                        runs.put(JSONObject().put("speakerId", sid).put("speakerName", KokoroManifest.speakers[sid])
                            .put("generationMs", synthesisMs).put("audioMs", durationMs).put("rtf", synthesisMs / durationMs)
                            .put("callbackCount", callbacks.get()).put("callbackSamples", callbackSamples.get())
                            .put("samples", audio.samples.size).put("wav", wav.name).put("wavBytes", wav.length()))
                        writeReport("real-model-metrics.json", "jni", metrics)
                    }
                    row.put("passed", true)
                } finally { try { tts?.release() } finally { store.release() } }
            }
            metrics.put("passed", true)
        } finally { writeReport("real-model-metrics.json", "jni", metrics) }
    }

    @Test fun realNativeAdapterContinuesInBackgroundAndReportsObservedPrefetchTimings() = withPreferencesRestored {
        val variant = variants().firstOrNull { it.id == "int8" } ?: variants().first()
        // Exercise the real production Service/adapter as well as raw JNI export above.
        KokoroRepository.select(context, variant)
        val metrics = JSONObject().put("passed", false).put("variant", variant.id).put("realModel", true)
            .put("syntheticTextOnly", true).put("requiresRtfBelowOne", false)
        try {
            withReader { scenario, activity, web ->
                awaitService(activity)
                val speech = service(activity)
                try {
                    onMain { activity.Bridge().selectVoiceEngine(KokoroOfflineSpeechEngine.ID) }
                    await("Real native engine initialized", 150_000) {
                        onMain { speech.voiceSettings().let { !it.getBoolean("loading") && it.getBoolean("initialized") } }
                    }
                    assertTrue(onMain { speech.voiceSettings().getBoolean("engineBindingVerified") })
                    assertEquals("native-pcm-prefetch", onMain { speech.stateSnapshot().getString("bufferingMode") })
                    assertTrue(onMain { speech.configureVoice("Kokoro-zf_001-003", 1f) })
                    val began = SystemClock.elapsedRealtime()
                    onMain { speech.submitQueue(request("real-native-synthetic", listOf("这是一段公开的合成测试文字，请安静地读完。", NEXT_TEXT, TEXT)), "synthetic-native-only") }
                    await("Actual model begins first AudioTrack", 150_000) {
                        onMain { speech.stateSnapshot().optString("phase") == "started" }
                    }
                    onMain { web.pauseTimers() }
                    systemHome()
                    await("Real model reader moves to background") { readerIsStopped(activity) }
                    val backgroundAt = SystemClock.elapsedRealtime()
                    assertTrue("Queue must still be active after Android STOPPED barrier", onMain { speech.stateSnapshot().getBoolean("active") })
                    val completedAtBackground = onMain { speech.voiceSettings().getString("diagnostics").lines().count { it.contains("buffer play-done") } }
                    var readyObserved = false
                    await("Real native queue drains all synthetic entries in background", 260_000) {
                        val state = onMain { speech.stateSnapshot() }
                        readyObserved = readyObserved || state.getBoolean("nextAudioReady")
                        assertFalse("Real native path must not fail or pause: $state", state.optString("phase") in setOf("error", "paused"))
                        state.optString("phase") == "done"
                    }
                    assertTrue(readerIsStopped(activity))
                    val lines = onMain { speech.voiceSettings().getString("diagnostics").lines() }
                    assertTrue("Real adapter generated PCM", lines.any { it.startsWith("pcm generated") })
                    assertTrue("A real AudioTrack must drain after the Android STOPPED barrier",
                        lines.count { it.contains("buffer play-done") } > completedAtBackground)
                    metrics.put("queueElapsedMs", elapsed(began)).put("nextReadyObserved", readyObserved).put("backgroundAtMs", backgroundAt)
                        .put("completedBeforeBackground", completedAtBackground)
                        .put("diagnostics", JSONArray(lines)).put("finalState", onMain { speech.stateSnapshot() })

                    onMain { web.resumeTimers() }
                    returnReaderToForeground(scenario, activity)
                } finally {
                    onMain {
                        web.resumeTimers()
                        metrics.put("finalState", speech.stateSnapshot())
                            .put("diagnostics", JSONArray(speech.voiceSettings().getString("diagnostics").lines()))
                        speech.releaseKokoroForMaintenance()
                    }
                    // Wait for serialized model release before later tests select another variant.
                    val store = KokoroRepository.get(context, variant)
                    store.acquire(10_000); store.release()
                }
            }
            metrics.put("passed", true)
        } finally { writeReport("real-model-metrics.json", "adapter", metrics) }
    }

    private fun verifyWav(file: File, samples: Int) {
        assertTrue("Export remains a small synthetic fixture", file.length() in 44..1_000_000)
        val data = file.readBytes()
        assertEquals("RIFF", String(data, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(data, 8, 4, Charsets.US_ASCII))
        val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(data.size - 8, bytes.getInt(4))
        var cursor = 12; var formatSeen = false; var dataSeen = false
        while (cursor + 8 <= data.size) {
            val name = String(data, cursor, 4, Charsets.US_ASCII)
            val size = bytes.getInt(cursor + 4)
            assertTrue(size >= 0 && cursor.toLong() + 8 + size <= data.size)
            if (name == "fmt ") {
                assertTrue(size >= 16)
                assertEquals(1, bytes.getShort(cursor + 8).toInt()) // Native export: PCM16.
                assertEquals(1, bytes.getShort(cursor + 10).toInt())
                assertEquals(24_000, bytes.getInt(cursor + 12))
                assertEquals(16, bytes.getShort(cursor + 22).toInt())
                formatSeen = true
            }
            if (name == "data") { assertEquals(samples * 2, size); dataSeen = true }
            cursor += 8 + size + (size and 1)
        }
        assertTrue(formatSeen && dataSeen)
    }
}
