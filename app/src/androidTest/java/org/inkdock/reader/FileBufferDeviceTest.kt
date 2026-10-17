package org.inkdock.reader

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.instrumentation
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real TTS-to-file, private filesystem and MediaPlayer; synthetic zero PCM is not voice-quality proof. */
@RunWith(AndroidJUnit4::class)
class FileBufferDeviceTest {
    @Test fun fileAudioIsPreparedBeforePreviousPlaybackFinishesAndFilesStayBounded() {
        val diagnostics = CopyOnWriteArrayList<String>()
        withRealFixture(diagnostics) { engine ->
            val events = CopyOnWriteArrayList<String>()
            onMain {
                engine.setOnUtteranceProgressListener(listener(events) { id ->
                    if (id == "a") engine.speak(fixture("B"), TextToSpeech.QUEUE_FLUSH, null, "b")
                })
                engine.speak(fixture("A"), TextToSpeech.QUEUE_FLUSH, null, "a")
                engine.prefetch(fixture("B"), "b")
            }
            await("Current plays with next audio already prepared", 15_000) {
                onMain { engine.nextAudioReady && engine.bufferedSlots == 2 } && "start:a" in events && "done:a" !in events
            }
            val files = directory().listFiles().orEmpty().filter { it.isFile }
            assertEquals(2, files.size)
            assertTrue(files.all { it.length() in 1..FileBufferedOfflineSpeechEngine.MAX_FILE_BYTES })
            assertTrue(diagnostics.any { it.contains("synth-done") })
            assertFalse(events.contains("done:a"))
            await("Both synthetic files finish actual playback", 15_000) { "done:b" in events }
            assertEquals(listOf("start:a", "done:a", "start:b", "done:b"), events.toList())
            assertTrue(diagnostics.any { line -> line.contains("boundary") &&
                Regex("readyLeadMs=(\\d+)").find(line)?.groupValues?.get(1)?.toLong()?.let { it > 0 } == true })
            assertTrue(directory().listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun internalShortUnitsKeepOneExternalRequestUntilAllAudioCompletes() {
        withRealFixture(CopyOnWriteArrayList()) { engine ->
            val events = CopyOnWriteArrayList<String>()
            val prefix = "${SilentFixtureTtsService.TEXT_PREFIX} FAST BUFFER "
            fun unit(char: Char) = prefix + char.toString().repeat(119 - prefix.length) + "\n"
            val text = unit('A') + unit('B')
            assertEquals(2, SpeechSentenceChunks.split(text).size)
            onMain {
                engine.setOnUtteranceProgressListener(listener(events))
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "outer")
            }
            await("Internal next unit prepared without finishing original request", 15_000) {
                onMain { engine.nextAudioReady } && events.toList() == listOf("start:outer")
            }
            await("Original request finishes only after both units play", 15_000) { "done:outer" in events }
            assertEquals(listOf("start:outer", "done:outer"), events.toList())
            assertTrue(directory().listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun stopDeletesPreparedFilesAndLateCallbacksCannotRestart() {
        withRealFixture(CopyOnWriteArrayList()) { engine ->
            val events = CopyOnWriteArrayList<String>()
            onMain {
                engine.setOnUtteranceProgressListener(listener(events))
                engine.speak(fixture("C"), TextToSpeech.QUEUE_FLUSH, null, "c")
                engine.prefetch(fixture("D"), "d")
            }
            await("One next synthetic audio is ready", 15_000) { onMain { engine.nextAudioReady } }
            onMain { engine.stop() }
            assertEquals(0, onMain { engine.bufferedSlots })
            assertTrue(directory().listFiles().orEmpty().isEmpty())
            val atStop = events.toList()
            Thread.sleep(SilentFixtureTtsService.SEGMENT_MILLIS + 300)
            assertEquals(atStop, events.toList())
            assertTrue(directory().listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun fileRejectionFallsBackExplicitlyWithoutCallingItPrefetched() {
        val events = CopyOnWriteArrayList<String>()
        val directCalls = AtomicInteger()
        var progress: UtteranceProgressListener? = null
        val fake = object : OfflineSpeechEngine {
            override val engines = emptyList<TextToSpeech.EngineInfo>()
            override val defaultEngine = "test-only"
            override val voices = emptySet<Voice>()
            override val voice: Voice? = null
            override fun setVoice(voice: Voice) = TextToSpeech.SUCCESS
            override fun setSpeechRate(rate: Float) = TextToSpeech.SUCCESS
            override fun setAudioAttributes(attributes: AudioAttributes) = TextToSpeech.SUCCESS
            override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int { progress = listener; return TextToSpeech.SUCCESS }
            override fun synthesizeToFile(text: String, params: Bundle, file: File, utteranceId: String) = TextToSpeech.ERROR
            override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
                directCalls.incrementAndGet(); progress?.onStart(utteranceId); progress?.onDone(utteranceId)
                return TextToSpeech.SUCCESS
            }
            override fun stop() = TextToSpeech.SUCCESS
            override fun shutdown() = Unit
        }
        val engine = onMain { FileBufferedOfflineSpeechEngine(instrumentation.targetContext, fake) {} }
        try {
            onMain { engine.setOnUtteranceProgressListener(listener(events)); engine.speak("Synthetic fixture", 0, null, "a") }
            await("Rejected file synthesis falls back once") { "done:a" in events }
            assertEquals(1, directCalls.get())
            assertTrue(onMain { engine.bufferingMode.startsWith("speak-fallback:") })
            assertFalse(onMain { engine.nextAudioReady })
            assertTrue(directory().listFiles().orEmpty().isEmpty())
        } finally { onMain { engine.shutdown() } }
    }
    private fun fixture(suffix: String) = "${SilentFixtureTtsService.TEXT_PREFIX} FAST BUFFER $suffix"
    private fun directory() = File(instrumentation.targetContext.noBackupFilesDir, "speech-prefetch")
    private fun listener(events: MutableList<String>, done: (String) -> Unit = {}) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) { events += "start:$utteranceId" }
        override fun onDone(utteranceId: String) { events += "done:$utteranceId"; done(utteranceId) }
        @Deprecated("Required legacy callback") override fun onError(utteranceId: String) { events += "error:$utteranceId" }
        override fun onError(utteranceId: String, errorCode: Int) { events += "error:$utteranceId:$errorCode" }
    }
    private fun withRealFixture(diagnostics: MutableList<String>, test: (FileBufferedOfflineSpeechEngine) -> Unit) {
        val ready = CountDownLatch(1); val status = AtomicInteger(TextToSpeech.ERROR)
        val raw = onMain { TextToSpeech(instrumentation.targetContext, {
            status.set(it); ready.countDown()
        }, instrumentation.context.packageName) }
        var wrapped: FileBufferedOfflineSpeechEngine? = null
        try {
            assertTrue(ready.await(12, TimeUnit.SECONDS)); assertEquals(TextToSpeech.SUCCESS, status.get())
            val engine = onMain {
                assertEquals(TextToSpeech.SUCCESS, raw.setVoice(raw.voices.first { it.name == SilentFixtureTtsService.VOICE_NAME }))
                FileBufferedOfflineSpeechEngine(instrumentation.targetContext, AndroidOfflineSpeechEngine(raw), diagnostics::add)
            }
            wrapped = engine; test(engine)
        } finally { onMain { wrapped?.shutdown() ?: raw.shutdown() } }
    }
}
