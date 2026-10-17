package org.inkdock.reader

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

/** The service is the only production owner of an Android TTS connection. */
internal interface OfflineSpeechEngine {
    val engines: List<TextToSpeech.EngineInfo>
    val defaultEngine: String
    val voices: Set<Voice>
    val voice: Voice?
    fun setVoice(voice: Voice): Int
    fun setSpeechRate(rate: Float): Int
    fun setAudioAttributes(attributes: AudioAttributes): Int
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int
    fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}
internal class AndroidOfflineSpeechEngine(private val tts: TextToSpeech) : OfflineSpeechEngine {
    override val engines get() = tts.engines.orEmpty()
    override val defaultEngine get() = tts.defaultEngine.orEmpty()
    override val voices get() = tts.voices.orEmpty()
    override val voice get() = tts.voice
    override fun setVoice(voice: Voice) = tts.setVoice(voice)
    override fun setSpeechRate(rate: Float) = tts.setSpeechRate(rate)
    override fun setAudioAttributes(attributes: AudioAttributes) = tts.setAudioAttributes(attributes)
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) = tts.setOnUtteranceProgressListener(listener)
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String) = tts.speak(text, queueMode, params, utteranceId)
    override fun stop() = tts.stop()
    override fun shutdown() = tts.shutdown()
}
