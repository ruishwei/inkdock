package org.inkdock.reader;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-APK-only Android TTS engine. Emits zero PCM for synthetic fixture strings.
 * Exercises the real Android TTS Binder/callback chain, NOT speech or audible output.
 * This service runs in the test APK's own process, without the target APK classpath.
 * Use Android/JDK types only: AGP can omit target-provided Kotlin from the test APK.
 * No model, network client, real text, credentials, or production injection control.
 */
public final class SilentFixtureTtsService extends TextToSpeechService {
    public static final String VOICE_NAME = "inkdock-test-only-zh-CN-silent";
    public static final String TEXT_PREFIX = "SYNTHETIC OFFLINE FIXTURE";
    public static final long SEGMENT_MILLIS = 2_000L;
    private static final int SAMPLE_RATE = 8_000;
    private final AtomicInteger stopGeneration = new AtomicInteger();

    @Override
    protected String[] onGetLanguage() {
        return new String[] {"zho", "CHN", ""};
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return "zho".equals(lang) || "chi".equals(lang) || "zh".equals(lang)
                ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice(
                VOICE_NAME, Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant) >= TextToSpeech.LANG_AVAILABLE
                ? VOICE_NAME : null;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        return VOICE_NAME.equals(voiceName) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        return onIsValidVoiceName(voiceName);
    }

    @Override
    protected void onStop() {
        stopGeneration.incrementAndGet();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        // Reject arbitrary text, even though this fixture never contacts a server.
        CharSequence text = request.getCharSequenceText();
        if (text == null || !text.toString().startsWith(TEXT_PREFIX)) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
            callback.done();
            return;
        }
        int generation = stopGeneration.get();
        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            callback.done();
            return;
        }
        byte[] silence = new byte[Math.min(callback.getMaxBufferSize(), SAMPLE_RATE / 10 * 2)];
        long until = SystemClock.uptimeMillis() + SEGMENT_MILLIS;
        // Pace synthesis itself for an emulator using -no-audio. onStop interrupts it.
        while (SystemClock.uptimeMillis() < until && generation == stopGeneration.get()) {
            if (callback.audioAvailable(silence, 0, silence.length) != TextToSpeech.SUCCESS) break;
            SystemClock.sleep(100);
        }
        callback.done();
    }
}
