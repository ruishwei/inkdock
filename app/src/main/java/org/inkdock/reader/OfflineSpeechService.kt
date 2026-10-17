package org.inkdock.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Owns offline synthesis and the entire reading queue independently of Activity/WebView timers. */
class OfflineSpeechService : Service() {
    interface Listener {
        fun onQueueStatus(state: JSONObject)
        fun onVoiceSettings(state: JSONObject)
        fun onSpeechStatus(state: JSONObject)
    }
    inner class LocalBinder : Binder() { val service: OfflineSpeechService get() = this@OfflineSpeechService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val speechTimers = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val manager by lazy { requireNotNull(getSystemService(NotificationManager::class.java)) }
    private val audio by lazy { requireNotNull(getSystemService(AudioManager::class.java)) }
    private var listener: Listener? = null
    private var tts: OfflineSpeechEngine? = null
    private var engineGeneration = 0
    private var requestedEngine = ""
    private var engineLoading = false
    private var installedEngines: List<TextToSpeech.EngineInfo> = emptyList()
    private var ttsReady = false
    private var ttsInitialized = false
    private var destroyed = false
    private var request: OfflineSpeechRequest? = null
    private var documentId = ""
    private var queueIndex = 0
    private var phase = "stopped"
    private var message = ""
    private var lastState = JSONObject().put("phase", "stopped").put("active", false)
    private var sequence = 0L
    private var utteranceId: String? = null
    private val speech = SpeechTracker()
    private val diagnostics = ArrayDeque<String>()
    private lateinit var session: MediaSession
    private lateinit var focusRequest: AudioFocusRequest
    private var focused = false
    private var foregroundService = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val wakeLock by lazy {
        requireNotNull(getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InkDock:OfflineSpeech")
            .apply { setReferenceCounted(false) }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback("音频设备已断开，朗读已暂停。")
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "离线朗读", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "后台离线朗读和暂停、继续、停止控制"; setShowBadge(false) })
        session = MediaSession(this, "InkDockOfflineSpeech").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
            }, main)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "InkDock 离线朗读")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "当前文档").build())
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pausePlayback("其他应用需要音频，朗读已暂停；点继续可从当前片段重读。")
            }, main).build()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        switchEngine(prefs.getString("voice.engine", "").orEmpty())
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean {
        listener = null
        if (request == null) stopSelf()
        return false
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { pausePlayback(); if (request == null) stopSelf(startId) }
            ACTION_RESUME -> { if (request != null) resumePlayback() else { ensureForeground(); finishForeground(); stopSelf(startId) } }
            ACTION_STOP -> stopPlayback()
            ACTION_START -> if (request != null && phase != "paused") ensureForeground() else {
                // A fast cancellation can overtake startForegroundService's asynchronous delivery.
                ensureForeground()
                if (request == null) { finishForeground(); stopSelf(startId) }
                else { stopForeground(STOP_FOREGROUND_DETACH); foregroundService = false; updateMedia() }
            }
        }
        // Never restart/replay private text after process death or a user Task Manager stop.
        return START_NOT_STICKY
    }
    internal fun setListener(value: Listener?) { listener = value; if (value != null) sendVoiceSettings() }
    internal fun hasActiveQueue() = request != null
    internal fun isReadingDocument(id: String) = request != null && documentId == id
    internal fun stateSnapshot(restore: Boolean = false): JSONObject {
        val current = request?.entries?.let { it.getOrNull(queueIndex.coerceAtMost(it.lastIndex)) }
        val state = if (request == null) JSONObject(lastState.toString()) else JSONObject()
            .put("queueId", request!!.queueId).put("phase", phase).put("block", current?.block ?: 0)
            .put("requestId", current?.requestId.orEmpty()).put("queueIndex", queueIndex)
            .put("queueSize", request!!.entries.size).put("active", true).put("message", message)
        return state.put("source", "offline").put("restore", restore).put("documentId", documentId)
    }
    internal fun sendSnapshot() { listener?.onQueueStatus(stateSnapshot(true)) }
    private fun publishState() { listener?.onQueueStatus(stateSnapshot()); updateMedia() }
    internal fun rejectQueue(queueId: String, reason: String) {
        listener?.onQueueStatus(JSONObject().put("queueId", queueId).put("phase", "error").put("active", false)
            .put("restore", false).put("source", "offline").put("message", reason))
    }
    internal fun submitQueue(value: OfflineSpeechRequest, id: String) {
        stopPlayback("已替换朗读队列。")
        request = value; documentId = id; queueIndex = 0; phase = "queued"
        message = "已接收完整离线队列，正在准备朗读。"
        try {
            // Only called by a visible Activity after a user gesture.
            startForegroundService(Intent(this, OfflineSpeechService::class.java).setAction(ACTION_START))
            ensureForeground()
        } catch (_: Exception) { failQueue("无法启动后台朗读服务，请回到应用前台重试。"); return }
        publishState()
        if (engineLoading) return
        playCurrent()
    }
    internal fun pausePlayback(reason: String = "朗读已暂停；继续时将从当前片段开头重读。") {
        if (request == null || phase == "paused") return
        invalidateSpeech()
        phase = "paused"; message = reason
        releaseAudio()
        publishState()
        if (foregroundService) { stopForeground(STOP_FOREGROUND_DETACH); foregroundService = false }
        manager.notify(NOTIFICATION, notification())
    }
    internal fun resumePlayback() {
        if (request == null || phase != "paused") return
        try {
            startForegroundService(Intent(this, OfflineSpeechService::class.java).setAction(ACTION_START))
            ensureForeground()
        } catch (_: Exception) { failQueue("无法恢复后台朗读，请打开应用后重试。"); return }
        phase = "queued"; message = "从当前片段开头继续。"; publishState()
        if (!engineLoading) playCurrent()
    }
    internal fun stopPlayback(reason: String = "朗读已停止。") {
        val hadQueue = request != null
        invalidateSpeech()
        if (hadQueue) endQueue("stopped", reason) else { releaseAudio(); finishForeground(); stopSelf() }
    }
    private fun invalidateSpeech() {
        speechTimers.removeCallbacksAndMessages(null)
        utteranceId = null
        speech.cancel(SystemClock.elapsedRealtime())
        tts?.stop()
    }
    private fun playCurrent() {
        if (request == null || phase == "paused" || destroyed) return
        val entry = request?.entries?.getOrNull(queueIndex) ?: run { endQueue("done", "朗读队列已完成。"); return }
        val engine = tts
        if (!ttsReady || engine == null || offlineVoices().none { it.name == engine.voice?.name }) {
            failQueue(if (!ttsInitialized) "语音引擎尚未初始化完成或初始化失败，请检查系统 TTS。" else "没有可用的已安装离线中文音色，请打开声音设置检查。")
            return
        }
        ensureForeground()
        if (!focused) {
            focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focused) { pausePlayback("暂时无法获得音频焦点，已暂停；请稍后点继续。"); return }
        }
        wakeLock.acquire(MAX_SEGMENT_TIME_MS)
        phase = "queued"; message = "已提交当前片段，等待离线引擎开始。"
        val token = "offline-${++sequence}"
        utteranceId = token
        emitSpeech(speech.begin(token, entry.requestId, entry.text.length, SystemClock.elapsedRealtime()), message)
        publishState()
        val result = try { engine.speak(entry.text, TextToSpeech.QUEUE_FLUSH, null, token) } catch (_: Exception) { TextToSpeech.ERROR_SERVICE }
        if (result != TextToSpeech.SUCCESS) speechEvent(token, "error", result)
        else {
            speechTimers.postDelayed({ if (utteranceId == token) speech.queuedTimeout(token, SystemClock.elapsedRealtime())?.let {
                utteranceId = null; engine.stop(); emitSpeech(it, speechError(it.errorCode)); failQueue(speechError(it.errorCode))
            } }, 12000)
            speechTimers.postDelayed({ if (utteranceId == token) { failQueue("当前片段超过 20 分钟仍未完成，已停止以释放后台资源。") } }, MAX_SEGMENT_TIME_MS)
        }
    }
    private fun speechEvent(id: String?, event: String, code: Int? = null) {
        if (id == null || id != utteranceId || request == null || phase == "paused") return
        val update = speech.event(id, event, SystemClock.elapsedRealtime(), code) ?: return
        val eventMessage = when (event) { "started" -> "离线引擎报告开始朗读。"; "done" -> "当前片段完成。"; "error" -> speechError(code); else -> "语音引擎中断，朗读已停止。" }
        emitSpeech(update, eventMessage)
        when (event) {
            "started" -> { phase = "started"; message = eventMessage; publishState() }
            "done" -> {
                speechTimers.removeCallbacksAndMessages(null)
                utteranceId = null
                request?.entries?.getOrNull(queueIndex)?.takeIf { it.block >= 0 }?.let { prefs.edit().putInt("anchor.$documentId", it.block).apply() }
                queueIndex++
                if (queueIndex >= (request?.entries?.size ?: 0)) endQueue("done", "朗读队列已完成。") else playCurrent()
            }
            "error", "stopped" -> { utteranceId = null; failQueue(eventMessage) }
        }
    }
    private fun failQueue(reason: String) { invalidateSpeech(); endQueue("error", reason) }
    private fun endQueue(endPhase: String, reason: String) {
        phase = endPhase; message = reason
        lastState = stateSnapshot().put("active", false).put("phase", endPhase)
        request = null // Do not retain document text after completion/cancel/error.
        releaseAudio(); publishState(); finishForeground(); stopSelf()
    }
    private fun releaseAudio() {
        if (focused) { audio.abandonAudioFocusRequest(focusRequest); focused = false }
        if (wakeLock.isHeld) wakeLock.release()
    }
    private fun ensureForeground() {
        if (foregroundService) return
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification())
        foregroundService = true
    }
    private fun finishForeground() {
        if (::session.isInitialized) session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE); foregroundService = false; manager.cancel(NOTIFICATION)
    }
    private fun notification(): Notification {
        val paused = phase == "paused"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, code: Int, foreground: Boolean = false): PendingIntent {
            val intent = Intent(this, OfflineSpeechService::class.java).setAction(name)
            return if (foreground) PendingIntent.getForegroundService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val current = request?.entries?.getOrNull(queueIndex)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("InkDock 离线朗读")
            .setContentText(if (paused) "已暂停 · 当前片段将从头继续" else "${queueIndex + 1}/${request?.entries?.size ?: 0} · 第 ${(current?.block ?: 0) + 1} 段")
            .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(!paused).setDeleteIntent(action(ACTION_STOP, 3))
            .addAction(Notification.Action.Builder(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "继续" else "暂停", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1, paused)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 2)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }
    private fun updateMedia() {
        val state = when { request == null -> PlaybackState.STATE_STOPPED; phase == "paused" -> PlaybackState.STATE_PAUSED; phase == "started" -> PlaybackState.STATE_PLAYING; else -> PlaybackState.STATE_BUFFERING }
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (phase == "started") voiceRate() else 0f).build())
        if (request != null) { session.isActive = true; manager.notify(NOTIFICATION, notification()) }
    }
    private fun emitSpeech(update: SpeechTracker.Update, reason: String) {
        val line = "${update.phase} chars=${update.characters} elapsed=${update.elapsedMs}ms error=${update.errorCode ?: "none"}"
        diagnostics.addLast(line); while (diagnostics.size > 12) diagnostics.removeFirst()
        Log.i("InkDockTTS", line) // No text, document title, or credential logging.
        listener?.onSpeechStatus(JSONObject().put("source", "offline").put("requestId", update.requestId).put("phase", update.phase)
            .put("characters", update.characters).put("elapsedMs", update.elapsedMs).put("message", reason)
            .put("errorCode", update.errorCode ?: JSONObject.NULL))
    }
    private fun offlineVoices(): List<Voice> = if (!ttsInitialized) emptyList() else tts?.voices.orEmpty().filter {
        it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired &&
            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
    }.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
    private fun voiceRate() = prefs.getFloat("voice.rate", 1f).coerceIn(0.5f, 1.5f)
    internal fun voiceSettings(reason: String = ""): JSONObject {
        installedEngines = tts?.engines.orEmpty().sortedBy { it.label.toString() }
        val engines = JSONArray(); installedEngines.forEach { engines.put(JSONObject().put("name", it.name).put("label", it.label)) }
        val voices = JSONArray(); offlineVoices().forEach { voices.put(JSONObject().put("name", it.name).put("language", it.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE))) }
        val current = if (ttsReady) tts?.voice else null
        return JSONObject().put("engine", installedEngines.firstOrNull { it.name == requestedEngine }?.label ?: requestedEngine)
            .put("enginePackage", requestedEngine).put("engines", engines).put("source", "offline")
            .put("loading", engineLoading).put("initialized", ttsInitialized).put("current", current?.name.orEmpty())
            .put("language", current?.locale?.getDisplayName(Locale.SIMPLIFIED_CHINESE).orEmpty())
            .put("voices", voices).put("rate", voiceRate()).put("message", reason).put("diagnostics", diagnostics.joinToString("\n"))
    }
    internal fun sendVoiceSettings(reason: String = "") { listener?.onVoiceSettings(voiceSettings(reason)) }
    internal fun selectEngine(name: String) {
        if (name.isBlank() || installedEngines.any { it.name == name }) switchEngine(name)
        else sendVoiceSettings("未找到该引擎，请刷新列表后重试。")
    }
    internal fun configureVoice(name: String, rate: Float, stopCurrent: Boolean = true): Boolean {
        val engine = tts ?: return false
        val voice = offlineVoices().firstOrNull { it.name == name } ?: return false
        if (stopCurrent) stopPlayback("更改音色，朗读已停止。")
        ttsReady = false
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS || engine.setSpeechRate(rate) != TextToSpeech.SUCCESS || engine.setAudioAttributes(attributes) != TextToSpeech.SUCCESS) return false
        val actual = engine.voice ?: return false
        if (actual.name != voice.name || actual.isNetworkConnectionRequired || actual.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) return false
        ttsReady = true
        prefs.edit().putString("voice.name.$requestedEngine", voice.name).putFloat("voice.rate", rate).apply()
        return true
    }
    private fun switchEngine(packageName: String) {
        stopPlayback("切换语音引擎，朗读已停止。")
        val generation = ++engineGeneration
        tts?.shutdown(); tts = null; ttsReady = false; ttsInitialized = false
        engineLoading = true; requestedEngine = packageName; sendVoiceSettings("正在初始化所选引擎…")
        try {
            val init = TextToSpeech.OnInitListener { status -> finishEngineInit(generation, status) }
            tts = AndroidOfflineSpeechEngine(if (packageName.isBlank()) TextToSpeech(applicationContext, init) else TextToSpeech(applicationContext, init, packageName))
            installedEngines = tts?.engines.orEmpty().sortedBy { it.label.toString() }
            if (packageName.isNotBlank() && installedEngines.none { it.name == packageName }) { switchEngine(""); return }
        } catch (_: Exception) {
            ++engineGeneration; engineLoading = false; tts?.shutdown(); tts = null
            sendVoiceSettings("引擎服务无法启动，请选择其他已安装引擎。")
            if (request != null) failQueue("引擎服务无法启动。")
            return
        }
        main.postDelayed({ if (generation == engineGeneration && engineLoading && !destroyed) {
            ++engineGeneration; tts?.shutdown(); tts = null; engineLoading = false
            sendVoiceSettings("引擎初始化超时，请重试或切换其他已安装引擎。")
            if (request != null) failQueue("引擎初始化超时，朗读已停止。")
        } }, 12000)
    }
    private fun finishEngineInit(generation: Int, status: Int) {
        main.post {
            if (destroyed || generation != engineGeneration) return@post
            engineLoading = false; installedEngines = tts?.engines.orEmpty().sortedBy { it.label.toString() }
            if (requestedEngine.isBlank()) requestedEngine = tts?.defaultEngine.orEmpty()
            ttsInitialized = status == TextToSpeech.SUCCESS
            if (ttsInitialized) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { main.post { if (generation == engineGeneration) speechEvent(utteranceId, "started") } }
                    override fun onDone(utteranceId: String?) { main.post { if (generation == engineGeneration) speechEvent(utteranceId, "done") } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { if (generation == engineGeneration) speechEvent(utteranceId, "stopped") } }
                    @Deprecated("Required legacy callback") override fun onError(utteranceId: String?) { main.post { if (generation == engineGeneration) speechEvent(utteranceId, "error") } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { if (generation == engineGeneration) speechEvent(utteranceId, "error", errorCode) } }
                })
                prefs.edit().putString("voice.engine", requestedEngine).apply()
                val saved = prefs.getString("voice.name.$requestedEngine", null)
                val voices = offlineVoices()
                val choice = voices.firstOrNull { it.name == saved } ?: voices.firstOrNull()
                if (choice != null) configureVoice(choice.name, voiceRate(), false)
            }
            sendVoiceSettings(if (ttsReady) "" else if (!ttsInitialized) "引擎初始化失败，请重试或选择其他已安装引擎。" else "未找到可用的离线中文语音，请在系统 TTS 设置检查语音包。")
            if (request != null && phase != "paused") playCurrent()
        }
    }
    private fun speechError(code: Int?): String = when (code) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "语音包尚未安装完成，请在系统 TTS 设置检查离线中文语音包。"
        TextToSpeech.ERROR_OUTPUT -> "语音输出失败，请检查媒体音量、蓝牙或音频设备。"
        TextToSpeech.ERROR_SYNTHESIS -> "系统引擎合成失败，请换一个已安装的离线中文音色。"
        TextToSpeech.ERROR_SERVICE -> "系统语音服务不可用，请检查 TTS 引擎或重启应用。"
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "引擎报告网络错误；本应用仅请求离线音色，请检查语音包。"
        -1000 -> "语音请求已提交，但 12 秒内没有收到开始回调。请检查系统 TTS 设置。"
        else -> "语音引擎未能完成朗读（错误码 ${code ?: "未知"}）。"
    }
    override fun onDestroy() {
        destroyed = true; ++engineGeneration; listener = null; invalidateSpeech(); request = null
        main.removeCallbacksAndMessages(null); speechTimers.removeCallbacksAndMessages(null); releaseAudio(); finishForeground()
        unregisterReceiver(noisy); session.release(); tts?.shutdown(); tts = null
        super.onDestroy()
    }
    companion object {
        internal const val ACTION_START = "org.inkdock.reader.speech.START"
        internal const val ACTION_PAUSE = "org.inkdock.reader.speech.PAUSE"
        internal const val ACTION_RESUME = "org.inkdock.reader.speech.RESUME"
        internal const val ACTION_STOP = "org.inkdock.reader.speech.STOP"
        private const val CHANNEL = "offline-reading"
        internal const val NOTIFICATION = 41
        private const val MAX_SEGMENT_TIME_MS = 20 * 60 * 1000L
    }
}
