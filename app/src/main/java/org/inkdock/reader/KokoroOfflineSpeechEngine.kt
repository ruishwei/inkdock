package org.inkdock.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Service-owned local PCM engine. Only the fixed, no-espeak runtime may be bundled with this adapter. */
internal class KokoroOfflineSpeechEngine(private val store: KokoroModelStore,
    private val variant: KokoroManifest.Variant,
    systemEngines: List<TextToSpeech.EngineInfo>, onInit: (Int) -> Unit) : OfflineSpeechEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0)
    private val currentId = AtomicReference<String?>(null)
    @Volatile private var model: OfflineTts? = null
    private var leased = false
    @Volatile private var closed = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var listener: UtteranceProgressListener? = null
    @Volatile private var rate = 1f
    @Volatile private var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val info = TextToSpeech.EngineInfo().apply { name = ID; label = "Kokoro 中文（内置实验）" }
    override val engines = systemEngines.filter { it.name != ID } + info
    override val defaultEngine = ID
    @Volatile override var voices: Set<Voice> = emptySet(); private set
    @Volatile override var voice: Voice? = null; private set
    init {
        worker.execute {
            try {
                val dir = store.acquire(10000)
                leased=true
                fun f(name: String) = File(dir,name).absolutePath
                val instance = OfflineTts(config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                        model=f(variant.modelName),voices=f("voices.bin"),tokens=f("tokens.txt"),
                        lexicon=f("lexicon-zh.txt"),lang="zh"),numThreads=2,debug=false,provider="cpu"),
                    ruleFsts=listOf("phone-zh.fst","date-zh.fst","number-zh.fst").joinToString(",") { f(it) },maxNumSentences=1))
                model=instance
                check(instance.numSpeakers() == 103 && instance.sampleRate() == 24000) { "Unexpected model metadata" }
                model=instance
                // The verified v1.1-zh artifact has 3 English speakers followed by 100 Chinese speakers.
                // Keep exact native IDs instead of mislabeling v1.0 voices or promising style/emotion.
                voices=(3 until instance.numSpeakers()).map { id -> Voice("Kokoro-${KokoroManifest.speakers[id]}-${id.toString().padStart(3,'0')}",Locale.SIMPLIFIED_CHINESE,Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,emptySet()) }.toSet()
                voice=voices.first()
                if (!closed) onInit(TextToSpeech.SUCCESS)
            } catch (_: Throwable) {
                model?.release(); model=null; if(leased){store.release();leased=false}
                if (!closed) onInit(TextToSpeech.ERROR)
            }
        }
    }
    override fun setVoice(voice: Voice): Int {
        val match=voices.firstOrNull { it.name==voice.name } ?: return TextToSpeech.ERROR
        this.voice=match;return TextToSpeech.SUCCESS
    }
    override fun setSpeechRate(rate: Float): Int { if(!rate.isFinite()||rate !in 0.5f..1.5f)return TextToSpeech.ERROR;this.rate=rate;return TextToSpeech.SUCCESS }
    override fun setAudioAttributes(attributes: AudioAttributes): Int {this.attributes=attributes;return TextToSpeech.SUCCESS}
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {this.listener=listener;return TextToSpeech.SUCCESS}
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        if(closed||model==null||text.isBlank()||text.length>12000)return TextToSpeech.ERROR
        stop()
        val token=generation.incrementAndGet();currentId.set(utteranceId)
        val sid=voice?.name?.substringAfterLast('-')?.toIntOrNull() ?: return TextToSpeech.ERROR
        val speed=rate
        worker.execute {
            try {
                var started=false
                for(part in split(text)) {
                    if(token!=generation.get()||closed)break
                    val audio=model!!.generateWithCallback(part,sid,speed) { if(token==generation.get()&&!closed) 1 else 0 }
                    if(token!=generation.get()||closed)break
                    check(audio.samples.isNotEmpty()) { "Empty synthesized audio" }
                    val minBuffer=AudioTrack.getMinBufferSize(audio.sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT)
                    check(minBuffer>0)
                    val output=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(maxOf(minBuffer, audio.sampleRate / 5 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
                    try {
                        check(output.state==AudioTrack.STATE_INITIALIZED)
                        track=output
                        if(token!=generation.get()||closed)break
                        output.play()
                        if(!started){started=true;listener?.onStart(utteranceId)}
                        var written=0
                        while(written<audio.samples.size&&token==generation.get()&&!closed) {
                            val count=output.write(audio.samples,written,minOf(4096,audio.samples.size-written),AudioTrack.WRITE_BLOCKING)
                            check(count>0) { "Audio write failed" };written+=count
                        }
                        // onDone means the PCM has played, not just that synthesis/queueing finished.
                        val deadline=System.nanoTime()+((audio.samples.size.toDouble()/audio.sampleRate+10)*1e9).toLong()
                        while(token==generation.get()&&!closed&&(output.playbackHeadPosition.toLong() and 0xffffffffL)<written) {
                            check(System.nanoTime()<deadline) { "Audio playback timeout" };Thread.sleep(20)
                        }
                    } finally { if(track===output)track=null;try{output.stop()}catch(_:IllegalStateException){};output.release() }
                }
                if(token==generation.get()&&!closed&&currentId.compareAndSet(utteranceId,null))listener?.onDone(utteranceId)
            } catch (_: Throwable) {
                if(token==generation.get()&&!closed&&currentId.compareAndSet(utteranceId,null))listener?.onError(utteranceId,TextToSpeech.ERROR_SYNTHESIS)
            }
        }
        return TextToSpeech.SUCCESS
    }
    override fun stop(): Int {
        generation.incrementAndGet()
        track?.let { try{it.pause();it.flush()}catch(_:IllegalStateException){} }
        currentId.getAndSet(null)?.let { listener?.onStop(it,true) }
        return TextToSpeech.SUCCESS
    }
    override fun shutdown() {
        if(closed)return
        closed=true;stop()
        worker.execute { model?.release();model=null;if(leased){store.release();leased=false} }
        worker.shutdown()
    }
    companion object {
        const val ID="inkdock.kokoro.zh.v1_1"
        /** Bound intermediate PCM memory and preserve Unicode surrogate pairs. */
        internal fun split(text:String):List<String> {
            val parts=mutableListOf<String>();var start=0;var i=0
            while(i<text.length) {
                val cp=text.codePointAt(i);i+=Character.charCount(cp)
                if(i-start>=120 || cp in intArrayOf(0x3002,0xff01,0xff1f,10)) {
                    text.substring(start,i).trim().takeIf{it.isNotEmpty()}?.let{parts+=it};start=i
                }
            }
            text.substring(start).trim().takeIf{it.isNotEmpty()}?.let{parts+=it}
            return parts
        }
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
