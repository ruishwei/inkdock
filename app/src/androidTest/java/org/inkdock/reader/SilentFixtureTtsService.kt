package org.inkdock.reader

import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-APK-only Android TTS engine. Emits zero PCM for synthetic fixture strings.
 * Exercises the real Android TTS Binder/callback chain, NOT speech or audible output.
 * No model, network client, real text, credentials, or production injection control.
 */
class SilentFixtureTtsService : TextToSpeechService() {
    private val stopGeneration = AtomicInteger()

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CHN", "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (lang == "zho" || lang == "chi" || lang == "zh") TextToSpeech.LANG_COUNTRY_AVAILABLE
        else TextToSpeech.LANG_NOT_SUPPORTED

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): List<Voice> = listOf(Voice(
        VOICE_NAME, Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL,
        false, emptySet()
    ))

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (onIsLanguageAvailable(lang, country, variant) >= TextToSpeech.LANG_AVAILABLE) VOICE_NAME else null

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == VOICE_NAME) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onStop() { stopGeneration.incrementAndGet() }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // Reject arbitrary text, even though this test fixture never contacts a server.
        if (!request.charSequenceText.toString().startsWith(TEXT_PREFIX)) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            callback.done()
            return
        }
        val generation = stopGeneration.get()
        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            callback.done()
            return
        }
        val silence = ByteArray(minOf(callback.maxBufferSize, SAMPLE_RATE / 10 * 2))
        val until = SystemClock.uptimeMillis() + SEGMENT_MILLIS
        // Pace synthesis itself: tests remain deterministic with an emulator using -no-audio.
        // onStop can interrupt between buffers; no request waits on an external test controller.
        while (SystemClock.uptimeMillis() < until && generation == stopGeneration.get()) {
            if (callback.audioAvailable(silence, 0, silence.size) != TextToSpeech.SUCCESS) break
            SystemClock.sleep(100)
        }
        callback.done()
    }

    companion object {
        const val VOICE_NAME = "inkdock-test-only-zh-CN-silent"
        const val TEXT_PREFIX = "SYNTHETIC OFFLINE FIXTURE"
        const val SEGMENT_MILLIS = 2_000L
        private const val SAMPLE_RATE = 8_000
    }
}
