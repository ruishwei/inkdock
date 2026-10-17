package org.inkdock.reader

import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Nine bounded, real-model generations: first/warm same sentence and a three-sentence callback
 * probe for each CPU thread count. No audio playback, network, model download or preference edit.
 * "First" means first generation on a newly constructed model, NOT cold filesystem/OS caches.
 * Thread order is fixed (1,2,4), not a randomized statistical benchmark or a Y300t speed claim.
 *
 * A six-minute cooperative resource budget stops later calls and requests cancellation from the
 * next callback. It is not an RTF threshold. JNI initialization/a stalled native call cannot be
 * safely force-killed here; the external runner still needs a whole-process timeout. Models are
 * always released on the calling thread after generation returns, never under an active JNI call.
 */
@RunWith(AndroidJUnit4::class)
class KokoroFloatLatencyBenchmarkDeviceTest {
    @Test fun fixedFloatThreadsOneTwoFourMeasureFirstWarmAndIncrementalCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val deadlineNs = startedNs + RESOURCE_BUDGET_NS
        val configurations = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("passed", false)
            .put("syntheticTextOnly", true).put("variant", "float").put("speakerId", SPEAKER_ID)
            .put("speakerName", "zf_001").put("speed", 1.0).put("provider", "cpu")
            .put("maxNumSentences", 1).put("threadOrder", JSONArray(listOf(1, 2, 4)))
            .put("androidApi", Build.VERSION.SDK_INT).put("deviceModel", Build.MODEL)
            .put("deviceHardware", Build.HARDWARE).put("deviceName", Build.DEVICE).put("manufacturer", Build.MANUFACTURER)
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("processArch", System.getProperty("os.arch")).put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("runtimeCommit", KokoroManifest.RUNTIME_COMMIT)
            .put("singleSentenceFixture", SINGLE_SENTENCE).put("multiSentenceFixture", MULTI_SENTENCE)
            .put("firstRunMeaning", "first generation on newly constructed model; file hashes already read; OS caches not cleared")
            .put("timingBasis", "elapsedRealtimeNanos; callback entry relative to generate call; excludes output playback")
            .put("callbackMeaning", "callback PCM availability; not audible first sound or proof of a streaming playback implementation")
            .put("noRtfPassThreshold", true).put("resourceBudgetMs", RESOURCE_BUDGET_NS / 1_000_000)
            .put("configurations", configurations)
        val target = File(context.filesDir, "native-test-artifacts/$REPORT_FILE")
        var ownedModels = 0
        var ownedModelsPeak = 0
        var releases = 0
        try {
            assertEquals("Run this benchmark on the reviewed API35 environment", 35, Build.VERSION.SDK_INT)
            assertTrue("Reviewed runtime is 64-bit", Process.is64Bit())
            assertEquals("Named JNI callback must expose the exact typed signature", java.lang.Integer::class.java,
                SherpaTtsCallback::class.java.getMethod("invoke", FloatArray::class.java).returnType)
            assertTrue("Audited JNI must load; no fallback", KokoroRepository.runtimeAvailable())
            val provenance = JSONObject(context.assets.open("kokoro-native-build.json").bufferedReader().use { it.readText() })
            assertEquals(KokoroManifest.RUNTIME_COMMIT, provenance.getString("sourceCommit"))
            val variant = KokoroManifest.float
            assertEquals("model.onnx", variant.modelName)
            assertEquals(FLOAT_MODEL_SHA256, variant.files.first().sha256)
            assertEquals(8, variant.files.size)
            assertEquals("zf_001", KokoroManifest.speakers[SPEAKER_ID])
            report.put("modelSha256", FLOAT_MODEL_SHA256).put("manifest", JSONArray(variant.files.map {
                JSONObject().put("file", it.path).put("bytes", it.size).put("sha256", it.sha256)
            }))
            publish(target, report)
            for (threads in listOf(1, 2, 4)) {
                checkBudget(deadlineNs)
                assertEquals("Never construct a second model while owning the previous one", 0, ownedModels)
                val row = JSONObject().put("threads", threads).put("passed", false)
                val runs = JSONArray(); row.put("runs", runs); configurations.put(row)
                val store = KokoroRepository.get(context, variant)
                val verifyStartedNs = SystemClock.elapsedRealtimeNanos()
                // Full fixed-manifest hash verification for every configuration; no marker-only trust.
                val directory = store.acquire(10_000)
                row.put("verifyMs", ms(SystemClock.elapsedRealtimeNanos() - verifyStartedNs))
                var model: OfflineTts? = null
                try {
                    checkBudget(deadlineNs)
                    fun path(name: String) = File(directory, name).absolutePath
                    val initStartedNs = SystemClock.elapsedRealtimeNanos()
                    model = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(model = path(variant.modelName), voices = path("voices.bin"),
                            tokens = path("tokens.txt"), lexicon = path("lexicon-zh.txt"), lang = "zh"),
                        numThreads = threads, debug = false, provider = "cpu"),
                        ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst").joinToString(",", transform = ::path),
                        maxNumSentences = 1))
                    row.put("initMs", ms(SystemClock.elapsedRealtimeNanos() - initStartedNs))
                    ownedModels++; ownedModelsPeak = maxOf(ownedModelsPeak, ownedModels)
                    assertEquals(1, ownedModels)
                    assertEquals(SAMPLE_RATE, model.sampleRate())
                    assertEquals(103, model.numSpeakers())
                    row.put("sampleRate", model.sampleRate()).put("speakerCount", model.numSpeakers())
                        .put("languageMetadata", model.lang())
                    publish(target, report)
                    for ((label, fixture) in listOf("first" to SINGLE_SENTENCE, "warm" to SINGLE_SENTENCE, "multi_sentence" to MULTI_SENTENCE)) {
                        checkBudget(deadlineNs)
                        val run = JSONObject().put("kind", label).put("passed", false).put("textChars", fixture.length)
                        runs.put(run)
                        try { measure(model, fixture, label == "multi_sentence", deadlineNs, run) }
                        finally { publish(target, report) }
                    }
                } finally {
                    val releaseStartedNs = SystemClock.elapsedRealtimeNanos()
                    try {
                        if (model != null) { model.release(); ownedModels--; releases++ }
                    } finally {
                        store.release()
                        row.put("releaseMs", ms(SystemClock.elapsedRealtimeNanos() - releaseStartedNs))
                            .put("modelReleased", model == null || ownedModels == 0)
                        publish(target, report)
                    }
                }
                row.put("passed", true)
                publish(target, report)
            }
            assertEquals(3, releases)
            assertEquals(0, ownedModels)
            report.put("passed", true)
        } catch (failure: Throwable) {
            // Only class names and fixed benchmark status, never documents or arbitrary exception text.
            report.put("failureType", failure.javaClass.simpleName)
                .put("resourceBudgetExceeded", SystemClock.elapsedRealtimeNanos() >= deadlineNs)
            throw failure
        } finally {
            report.put("elapsedMs", ms(SystemClock.elapsedRealtimeNanos() - startedNs))
                .put("ownedModelsPeak", ownedModelsPeak).put("releasedModels", releases).put("ownedModelsAtExit", ownedModels)
            publish(target, report)
        }
    }

    private fun measure(model: OfflineTts, text: String, multiSentence: Boolean, deadlineNs: Long, row: JSONObject) {
        val records = ArrayList<CallbackRecord>()
        // Only the multi-sentence probe retains callback PCM, capped at60seconds/5.76MB.
        // Compare its logical concatenation without allocating a second concatenated array.
        val callbackPcm = ArrayList<FloatArray>()
        val lock = Any()
        val cancelled = AtomicBoolean(false)
        val invalidCallback = AtomicBoolean(false)
        var callbackSamples = 0L
        var callbackCalls = 0
        val startNs = SystemClock.elapsedRealtimeNanos()
        val callback = SherpaTtsCallback { samples ->
            val enteredNs = SystemClock.elapsedRealtimeNanos()
            synchronized(lock) {
                callbackSamples += samples.size
                callbackCalls++
                val valid = samples.isNotEmpty() && samples.size <= MAX_PCM_SAMPLES && samples.all { it.isFinite() }
                if (!valid) invalidCallback.set(true)
                val stop = !valid || callbackCalls > MAX_CALLBACKS || callbackSamples > MAX_PCM_SAMPLES || enteredNs >= deadlineNs
                if (stop) cancelled.set(true)
                if (multiSentence && !stop) callbackPcm += samples.copyOf()
                if (records.size < MAX_CALLBACKS) records += CallbackRecord(enteredNs - startNs, samples.size, callbackSamples,
                    SystemClock.elapsedRealtimeNanos() - enteredNs)
                if (stop) 0 else 1
            }
        }
        try {
            val audio = model.generateWithCallback(text, SPEAKER_ID, 1f, callback)
            val returnedNs = SystemClock.elapsedRealtimeNanos()
            val generationNs = returnedNs - startNs
            val callbacks = synchronized(lock) { records.toList() }
            val durationMs = audio.samples.size * 1000.0 / audio.sampleRate
            row.put("generationMs", ms(generationNs)).put("wholeGenerateMs", ms(generationNs)).put("audioMs", durationMs)
                .put("samples", audio.samples.size).put("sampleRate", audio.sampleRate)
                .put("rtf", if (durationMs > 0) ms(generationNs) / durationMs else JSONObject.NULL)
                .put("cancelRequested", cancelled.get()).put("invalidCallback", invalidCallback.get())
            appendCallbacks(row, callbacks)
            assertFalse("Cooperative safety/resource limit interrupted this measurement", cancelled.get())
            assertFalse("Callbacks must contain valid finite PCM", invalidCallback.get())
            assertEquals(SAMPLE_RATE, audio.sampleRate)
            assertTrue("Bounded real PCM must be nonempty", audio.samples.size in 1..MAX_PCM_SAMPLES)
            assertTrue("Returned PCM must be finite", audio.samples.all { it.isFinite() })
            assertTrue("Real voice output must not be all silent", audio.samples.any { abs(it) > 0.0001f })
            assertTrue("JNI callback must deliver audio", callbacks.isNotEmpty())
            assertTrue("Callback times must be monotonic and inside generate", callbacks.zipWithNext().all { (a, b) -> a.offsetNs <= b.offsetNs } &&
                callbacks.first().offsetNs >= 0 && callbacks.last().offsetNs <= generationNs)
            row.put("firstCallbackMs", ms(callbacks.first().offsetNs))
                .put("lastCallbackMs", ms(callbacks.last().offsetNs))
                .put("firstCallbackLeadToReturnMs", ms(generationNs - callbacks.first().offsetNs))
                .put("lastCallbackLeadToReturnMs", ms(generationNs - callbacks.last().offsetNs))
                .put("callbackSampleTotalEqualsReturned", callbacks.last().cumulativeSamples == audio.samples.size.toLong())
            val partialBeforeReturn = callbacks.size > 1 && callbacks.first().samples < audio.samples.size && callbacks.first().offsetNs < generationNs
            row.put("partialPcmBeforeWholeGenerateReturned", partialBeforeReturn)
            if (multiSentence) {
                val comparison = synchronized(lock) { comparePcm(callbackPcm, audio.samples) }
                row.put("callbackAndReturnComparison", comparison)
                assertTrue("Pinned callback PCM must exactly reconstruct returned PCM before streaming is safe", comparison.getBoolean("exactMatch"))
                assertTrue("Three-sentence maxNumSentences=1 fixture must expose multiple callbacks", callbacks.size >= 2)
                assertTrue("First callback must contain a proper prefix before whole generation returns", partialBeforeReturn)
            }
            row.put("passed", true)
        } finally {
            // Persist any callbacks even if JNI throws or an assertion rejects output.
            synchronized(lock) {
                appendCallbacks(row, records.toList())
                row.put("callbackCallsObserved", callbackCalls).put("allCallbackSamplesObserved", callbackSamples)
                    .put("callbackRecordsTruncated", callbackCalls > records.size)
            }
        }
    }

    /** Bitwise float32 equality, including signed zero; a mismatch is a compatibility failure, not a speed gate. */
    private fun comparePcm(chunks: List<FloatArray>, returned: FloatArray): JSONObject {
        val callbackCount = chunks.sumOf { it.size }
        var offset = 0
        var firstMismatch = -1
        for (chunk in chunks) for (sample in chunk) {
            if (offset < returned.size && firstMismatch < 0 && sample.toRawBits() != returned[offset].toRawBits()) firstMismatch = offset
            offset++
        }
        val sameLength = callbackCount == returned.size
        if (!sameLength && firstMismatch < 0) firstMismatch = minOf(callbackCount, returned.size)
        return JSONObject().put("comparisonPerformed", true).put("comparison", "logical concatenation, exact float32 raw bits")
            .put("callbackPcmSamples", callbackCount).put("returnedPcmSamples", returned.size)
            .put("comparedSamples", minOf(callbackCount, returned.size))
            .put("returnedMinusCallbackSamples", returned.size - callbackCount)
            .put("exactMatch", sameLength && firstMismatch < 0)
            .put("firstMismatchIndex", if (firstMismatch < 0) JSONObject.NULL else firstMismatch)
            .put("fixturePcmReconstructionMatches", sameLength && firstMismatch < 0)
    }

    private data class CallbackRecord(val offsetNs: Long, val samples: Int, val cumulativeSamples: Long, val inspectionNs: Long)
    private fun appendCallbacks(row: JSONObject, records: List<CallbackRecord>) {
        var previousNs = 0L
        val callbacks = JSONArray()
        records.forEachIndexed { index, record ->
            callbacks.put(JSONObject().put("index", index).put("atMs", ms(record.offsetNs))
                .put("sincePreviousMs", ms(record.offsetNs - previousNs)).put("samples", record.samples)
                .put("cumulativeSamples", record.cumulativeSamples).put("inspectionMs", ms(record.inspectionNs)))
            previousNs = record.offsetNs
        }
        row.put("callbackCount", records.size).put("callbackSamples", records.lastOrNull()?.cumulativeSamples ?: 0)
            .put("callbacks", callbacks)
    }
    private fun checkBudget(deadlineNs: Long) {
        check(SystemClock.elapsedRealtimeNanos() < deadlineNs) { "Cooperative benchmark resource budget exhausted" }
    }
    private fun publish(target: File, report: JSONObject) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val partial = File(target.parentFile, "$REPORT_FILE.part")
        partial.writeText(report.toString(2))
        check(partial.renameTo(target)) { "Cannot publish benchmark metrics" }
    }
    private fun ms(nanos: Long) = nanos / 1_000_000.0

    companion object {
        const val REPORT_FILE = "float-latency-benchmark.json"
        private const val FLOAT_MODEL_SHA256 = "acc4adc175b9d9986106cd20060329673ad5a2e12ef3c557d2d3745b694f8b38"
        private const val SPEAKER_ID = 3
        private const val SAMPLE_RATE = 24_000
        private const val MAX_PCM_SAMPLES = SAMPLE_RATE * 60
        private const val MAX_CALLBACKS = 128
        private const val RESOURCE_BUDGET_NS = 6L * 60 * 1_000_000_000
        private const val SINGLE_SENTENCE = "今天是一个适合阅读的好日子。"
        private const val MULTI_SENTENCE = "你好，世界。今天天气很好。我们一起读书。"
    }
}
