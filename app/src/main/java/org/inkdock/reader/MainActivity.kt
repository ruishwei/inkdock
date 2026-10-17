package org.inkdock.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowInsetsController
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/** Only packaged UI reaches this bridge. Cloud requests require native per-segment consent. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var appliedDark: Boolean? = null
    private var speechSequence = 0
    private val speech = SpeechTracker()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val speechDiagnostics = ArrayDeque<String>()
    private var panelOpen = false
    private var backCallback: OnBackInvokedCallback? = null
    private var selectedCloud: CloudVoiceProvider? = null // Every launch defaults to offline.
    private var cloudUtterance = ""
    private var cloudRequest = ""
    private var cloudCharacters = 0
    private val cloud by lazy { CloudSpeechUi(this, { foreground && !isDestroyed },
        { message -> sendVoiceSettings(message) }, { phase, message ->
            if (phase == "queued") emitSpeech(speech.begin(cloudUtterance, cloudRequest, cloudCharacters, SystemClock.elapsedRealtime()), message)
            else {
                val update = speech.event(cloudUtterance, phase, SystemClock.elapsedRealtime(), if (phase == "error") -2000 else null)
                    ?: if (phase == "stopped" || phase == "error") SpeechTracker.Update(cloudUtterance, cloudRequest, phase, cloudCharacters, 0) else null
                if (update != null) emitSpeech(update, message)
            }
        }) }
    private var tts: TextToSpeech? = null
    private var engineGeneration = 0
    private var requestedEngine = ""
    private var engineLoading = false
    private var installedEngines: List<TextToSpeech.EngineInfo> = emptyList()
    private var ttsReady = false
    private var ttsInitialized = false
    private var ready = false
    private var foreground = false
    private var text = ""
    private var documentId = "sample"
    private var importGeneration = 0
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val activeFile by lazy { File(filesDir, "active.md") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xfffaf7ef.toInt())
        // Android 15 enforces edge-to-edge. Keep controls outside system bars.
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemWindowInsetTop
            view.setPadding(insets.systemWindowInsetLeft, bars, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        applyReaderTheme(prefs.getBoolean("dark", false))
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.settings.domStorageEnabled = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Bridge(), "Native")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return blocked()
                return if (uri.scheme == "file" && uri.path?.startsWith("/android_asset/") == true) null else blocked()
            }
        }
        text = if (activeFile.exists()) activeFile.readText(Charsets.UTF_8) else assets.open("sample.md").bufferedReader().use { it.readText() }
        documentId = hash(text)
        web.loadUrl("file:///android_asset/index.html")
        switchEngine(prefs.getString("voice.engine", "").orEmpty())
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun applyReaderTheme(dark: Boolean) {
        if (appliedDark == dark) return
        appliedDark = dark
        val color = Color.parseColor(if (dark) "#181b20" else "#faf7ef")
        root.setBackgroundColor(color)
        web.setBackgroundColor(color)
        window.setBackgroundDrawable(ColorDrawable(color))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // The first onCreate call can precede view attachment.
            root.post { window.insetsController?.setSystemBarsAppearance(if (appliedDark == true) 0 else appearance, appearance) }
        } else {
            val lightIcons = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (dark) window.decorView.systemUiVisibility and lightIcons.inv()
                else window.decorView.systemUiVisibility or lightIcons
        }
    }
    private fun setPanelOpen(open: Boolean) {
        panelOpen = open
        if (Build.VERSION.SDK_INT >= 33) {
            if (open && backCallback == null) {
                val callback = OnBackInvokedCallback { closePanelFromBack() }
                backCallback = callback
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            } else if (!open) {
                backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
                backCallback = null
            }
        }
    }
    private fun closePanelFromBack() {
        if (!ready || isDestroyed) return
        web.evaluateJavascript("window.handleNativeBack ? window.handleNativeBack() : false", null)
    }
    @Deprecated("Legacy Back fallback for Android 8–12")
    override fun onBackPressed() {
        if (panelOpen) closePanelFromBack() else super.onBackPressed()
    }
    private fun emitSpeech(update: SpeechTracker.Update, message: String = "") {
        val event = JSONObject().put("requestId", update.requestId).put("phase", update.phase)
            .put("characters", update.characters).put("elapsedMs", update.elapsedMs).put("message", message)
            .put("errorCode", update.errorCode ?: JSONObject.NULL)
        val line = "${update.phase} chars=${update.characters} elapsed=${update.elapsedMs}ms error=${update.errorCode ?: "none"}"
        speechDiagnostics.addLast(line)
        while (speechDiagnostics.size > 12) speechDiagnostics.removeFirst()
        Log.i("InkDockTTS", line) // Never log document text or selection.
        if (ready && !isDestroyed) web.evaluateJavascript("window.onSpeechStatus($event)", null)
        if (update.phase == "error") toast(message)
    }
    private fun speechError(code: Int?): String = when (code) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "语音包尚未安装完成，请在系统 TTS 设置检查离线中文语音包。"
        TextToSpeech.ERROR_OUTPUT -> "语音输出失败，请检查媒体音量、蓝牙或音频设备。"
        TextToSpeech.ERROR_SYNTHESIS -> "系统引擎合成失败，请换一个已安装的离线中文音色。"
        TextToSpeech.ERROR_SERVICE -> "系统语音服务不可用，请检查 TTS 引擎或重启应用。"
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "引擎报告网络错误；本应用仅请求离线音色，请检查语音包是否真的可离线使用。"
        TextToSpeech.ERROR_INVALID_REQUEST -> "系统语音引擎拒绝了朗读请求。"
        -1000 -> "语音请求已提交，但12秒内没有收到开始回调。请检查系统 TTS 设置。"
        else -> "语音引擎未能完成朗读（错误码 ${code ?: "未知"}），请在声音设置查看诊断。"
    }
    private fun speechEvent(id: String?, phase: String, code: Int? = null) {
        val update = speech.event(id, phase, SystemClock.elapsedRealtime(), code) ?: return
        val message = when (phase) {
            "started" -> "引擎报告开始朗读；是否有声音仍取决于媒体音量和输出设备。"
            "done" -> if (update.elapsedMs < 120 && update.characters > 30) "引擎很快报告完成；若未听到声音，请检查系统 TTS 和媒体输出。" else "当前语音片段完成。"
            "error" -> speechError(code)
            else -> "朗读已停止。"
        }
        emitSpeech(update, message)
    }
    private fun stopPlayback(reason: String = "朗读已停止。") {
        cloud.stop()
        val update = speech.cancel(SystemClock.elapsedRealtime())
        tts?.stop()
        if (update != null) emitSpeech(update, reason)
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showDocument() {
        if (!ready) return
        val anchor = prefs.getInt("anchor.$documentId", 0)
        val font = prefs.getInt("font", 20)
        val dark = prefs.getBoolean("dark", false)
        web.evaluateJavascript("window.loadDocument(${JSONObject.quote(text)},$anchor,$font,$dark)", null)
    }
    private fun importText(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) { toast("文件过大：当前预览版最多支持 8 MiB。"); return }
        val generation = ++importGeneration
        io.execute {
            try {
                // Atomic replacement keeps the previous import if writing fails.
                val temp = File(filesDir, "import.tmp")
                temp.writeText(value, Charsets.UTF_8)
                check(temp.renameTo(activeFile)) { "保存失败" }
                val id = hash(value)
                runOnUiThread { if (generation == importGeneration && !isDestroyed) { text = value; documentId = id; showDocument() } }
            } catch (_: Exception) { toast("无法保存文档，原文档未更改。") }
        }
    }
    private fun importUri(uri: Uri) {
        if (uri.scheme != "content") { toast("请从系统文件选择器导入文档。"); return }
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; if (output.size() + n > MAX_BYTES) throw IllegalArgumentException(); output.write(buffer, 0, n) }
                    output.toByteArray()
                } ?: throw IllegalArgumentException()
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                val value = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                runOnUiThread { importText(value) }
            } catch (_: Exception) { toast("无法导入：请选择 8 MiB 以内的 UTF-8 Markdown / 文本文件。"); }
        }
    }
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) importUri(uri) else intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::importText)
            }
            Intent.ACTION_VIEW -> intent.data?.let(::importUri)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    @Deprecated("Platform activity result API retained to keep the shell dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN && resultCode == RESULT_OK) data?.data?.let(::importUri)
    }
    private fun offlineVoices(): List<Voice> = if (!ttsInitialized) emptyList() else
        tts?.voices.orEmpty().filter {
            it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))

    private fun voiceRate() = prefs.getFloat("voice.rate", 1f).coerceIn(0.5f, 1.5f)
    private fun sendVoiceSettings(message: String = "") {
        if (!ready || isDestroyed) return
        val engine = tts
        val packageName = requestedEngine
        val label = installedEngines.firstOrNull { it.name == packageName }?.label ?: packageName
        val engines = JSONArray()
        installedEngines.forEach { engines.put(JSONObject().put("name", it.name).put("label", it.label)) }
        val voices = JSONArray()
        offlineVoices().forEach { voices.put(JSONObject().put("name", it.name).put("language", it.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE))) }
        val current = if (ttsReady) engine?.voice else null
        val state = JSONObject().put("engine", label).put("enginePackage", packageName).put("engines", engines)
            .put("source", selectedCloud?.name ?: "offline").put("loading", engineLoading).put("initialized", ttsInitialized).put("current", current?.name.orEmpty())
            .put("language", current?.locale?.getDisplayName(Locale.SIMPLIFIED_CHINESE).orEmpty())
            .put("voices", voices).put("rate", voiceRate()).put("message", message).put("diagnostics", speechDiagnostics.joinToString("\n"))
        web.evaluateJavascript("window.setVoiceSettings($state)", null)
    }
    private fun configureVoice(name: String, rate: Float, stopCurrent: Boolean = true): Boolean {
        val engine = tts ?: return false
        val voice = offlineVoices().firstOrNull { it.name == name } ?: return false
        if (stopCurrent) stopPlayback()
        ttsReady = false
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS || engine.setSpeechRate(rate) != TextToSpeech.SUCCESS) return false
        // Do not trust a successful request if the engine silently selected a different voice.
        val actual = engine.voice ?: return false
        if (actual.name != voice.name || actual.isNetworkConnectionRequired ||
            actual.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) return false
        ttsReady = true
        prefs.edit().putString("voice.name.$requestedEngine", voice.name).putFloat("voice.rate", rate).apply()
        return true
    }
    private fun speakText(value: String, requestId: String) {
        stopPlayback()
        val utteranceId = "paragraph-${++speechSequence}"
        val clean = value.trim()
        if (!foreground) { emitSpeech(SpeechTracker.Update(utteranceId, requestId, "stopped", clean.length, 0), "应用在后台，未开始朗读。"); return }
        if (clean.isEmpty()) {
            val update = SpeechTracker.Update(utteranceId, requestId, "error", 0, 0, TextToSpeech.ERROR_INVALID_REQUEST)
            emitSpeech(update, "没有可朗读文字。请选中正文文字，或从下一段开始。")
            return
        }
        val limit = if (selectedCloud != null) CloudVoiceRequests.MAX_TEXT_UNITS else minOf(3500, TextToSpeech.getMaxSpeechInputLength())
        if (clean.length > limit) {
            emitSpeech(SpeechTracker.Update(utteranceId, requestId, "error", clean.length, 0, TextToSpeech.ERROR_INVALID_REQUEST), "朗读片段过长，请重新选择较短文字。")
            return
        }
        if (selectedCloud != null) {
            cloudUtterance = utteranceId; cloudRequest = requestId; cloudCharacters = clean.length
            cloud.speak(selectedCloud!!, clean)
            return
        }
        val engine = tts
        if (!ttsReady || engine == null || offlineVoices().none { it.name == engine.voice?.name }) {
            emitSpeech(SpeechTracker.Update(utteranceId, requestId, "error", clean.length, 0, TextToSpeech.ERROR_SERVICE), if (!ttsInitialized) "语音引擎尚未初始化完成或初始化失败，请稍后重试并检查系统 TTS。" else "没有可用的已安装离线中文音色，请打开声音设置检查。")
            return
        }
        emitSpeech(speech.begin(utteranceId, requestId, clean.length, SystemClock.elapsedRealtime()), "已提交朗读请求，等待语音引擎开始。")
        val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) speechEvent(utteranceId, "error", result)
        else mainHandler.postDelayed({
            speech.queuedTimeout(utteranceId, SystemClock.elapsedRealtime())?.let { update ->
                engine.stop(); emitSpeech(update, speechError(update.errorCode))
            }
        }, 12000)
    }
    private fun switchEngine(packageName: String) {
        stopPlayback("切换语音引擎，朗读已停止。")
        if (ready) web.evaluateJavascript("window.stopForEngineChange && window.stopForEngineChange()", null)
        val generation = ++engineGeneration
        tts?.shutdown()
        tts = null
        ttsReady = false
        ttsInitialized = false
        engineLoading = true
        requestedEngine = packageName
        sendVoiceSettings("正在初始化所选引擎…")
        val listener = TextToSpeech.OnInitListener { status -> finishEngineInit(generation, status) }
        try {
            tts = if (packageName.isBlank()) TextToSpeech(this, listener) else TextToSpeech(this, listener, packageName)
            installedEngines = tts?.engines.orEmpty().sortedBy { it.label.toString() }
            if (packageName.isNotBlank() && installedEngines.none { it.name == packageName }) {
                switchEngine("")
                sendVoiceSettings("保存的引擎已卸载，正在请求系统默认引擎。")
                return
            }
            sendVoiceSettings("正在初始化所选引擎，可选择其他引擎或重试。")
        } catch (_: Exception) {
            ++engineGeneration; engineLoading = false; tts?.shutdown(); tts = null
            sendVoiceSettings("引擎服务无法启动，请选择其他已安装引擎。")
            return
        }
        mainHandler.postDelayed({
            if (generation == engineGeneration && engineLoading && !isDestroyed) {
                ++engineGeneration
                tts?.shutdown(); tts = null; engineLoading = false
                sendVoiceSettings("引擎初始化超时，请重试或切换其他已安装引擎。")
            }
        }, 12000)
    }
    private fun finishEngineInit(generation: Int, status: Int) {
        mainHandler.post {
            if (isDestroyed || generation != engineGeneration) return@post
            engineLoading = false
            installedEngines = tts?.engines.orEmpty().sortedBy { it.label.toString() }
            if (requestedEngine.isBlank()) requestedEngine = tts?.defaultEngine.orEmpty()
            ttsInitialized = status == TextToSpeech.SUCCESS
            if (ttsInitialized) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { runOnUiThread { if (generation == engineGeneration) speechEvent(utteranceId, "started") } }
                    override fun onDone(utteranceId: String?) { runOnUiThread { if (generation == engineGeneration) speechEvent(utteranceId, "done") } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { runOnUiThread { if (generation == engineGeneration) speechEvent(utteranceId, "stopped") } }
                    @Deprecated("Required legacy callback")
                    override fun onError(utteranceId: String?) { runOnUiThread { if (generation == engineGeneration) speechEvent(utteranceId, "error") } }
                    override fun onError(utteranceId: String?, errorCode: Int) { runOnUiThread { if (generation == engineGeneration) speechEvent(utteranceId, "error", errorCode) } }
                })
                val voices = offlineVoices()
                prefs.edit().putString("voice.engine", requestedEngine).apply()
                val saved = prefs.getString("voice.name.$requestedEngine", null)
                val choice = voices.firstOrNull { it.name == saved } ?: voices.firstOrNull()
                if (choice != null) configureVoice(choice.name, voiceRate(), stopCurrent = false)
            }
            sendVoiceSettings(if (ttsReady) "" else if (!ttsInitialized) "引擎初始化失败，请重试或选择其他已安装引擎。" else "未找到可用的离线中文语音，请在系统 TTS 设置检查语音包。")
        }
    }
    inner class Bridge {
        @JavascriptInterface fun panelState(open: Boolean) = runOnUiThread { setPanelOpen(open) }
        @JavascriptInterface fun ready() = runOnUiThread { ready = true; showDocument(); sendVoiceSettings(); setPanelOpen(false) }
        @JavascriptInterface fun openDocument() = runOnUiThread {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(picker, OPEN)
        }
        @JavascriptInterface fun saveProgress(anchor: Int, font: Int, dark: Boolean) = runOnUiThread {
            applyReaderTheme(dark)
            prefs.edit().putInt("anchor.$documentId", anchor.coerceAtLeast(0)).putInt("font", font.coerceIn(14, 32)).putBoolean("dark", dark).apply()
        }
        @JavascriptInterface fun selectVoiceEngine(name: String) = runOnUiThread {
            val current = tts?.engines ?: installedEngines
            if (name.isBlank() || current.any { it.name == name }) switchEngine(name)
            else sendVoiceSettings("未找到该引擎，请刷新列表后重试。")
        }
        @JavascriptInterface fun setSpeechSource(source: String) = runOnUiThread {
            stopPlayback()
            selectedCloud = CloudVoiceProvider.values().firstOrNull { it.name == source }
            sendVoiceSettings(if (selectedCloud == null) "已选择系统离线朗读。" else "实验性云朗读：每段发送前需要确认，可能产生费用；真实音色尚未实测。")
        }
        @JavascriptInterface fun configureCloudVoice(source: String) = runOnUiThread {
            CloudVoiceProvider.values().firstOrNull { it.name == source }?.let { cloud.configure(it) }
        }
        @JavascriptInterface fun previewCloudVoice() = runOnUiThread {
            if (selectedCloud == null) sendVoiceSettings("请先选择云服务商，或使用下方系统音色试听。")
            else speakText("你好，这里是 InkDock。窗外的风轻轻吹过，我们继续读下一段故事。", "preview-${++speechSequence}")
        }
        @JavascriptInterface fun getVoiceSettings() = runOnUiThread { installedEngines = tts?.engines ?: installedEngines; sendVoiceSettings() }
        @JavascriptInterface fun saveVoiceSettings(name: String, rate: Double, preview: Boolean) = runOnUiThread {
            if (!rate.isFinite() || !configureVoice(name, rate.toFloat().coerceIn(0.5f, 1.5f))) {
                sendVoiceSettings("该离线音色不可用，设置未保存。请检查系统语音包。")
            } else {
                sendVoiceSettings("设置已保存。音色的自然度由系统语音引擎决定。")
                if (preview) speakText("你好，这里是 InkDock。窗外的风轻轻吹过，我们继续读下一段故事。", "preview-${++speechSequence}")
            }
        }
        @JavascriptInterface fun speak(value: String, requestId: String) = runOnUiThread { speakText(value, requestId.take(80)) }
        @JavascriptInterface fun stopSpeaking() = runOnUiThread { stopPlayback() }
    }
    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() {
        foreground = false
        super.onPause()
        stopPlayback("应用进入后台，朗读已停止。")
        if (ready && !isDestroyed) web.evaluateJavascript("window.stopForBackground && window.stopForBackground()", null)
    }
    override fun onDestroy() { ++engineGeneration; setPanelOpen(false); mainHandler.removeCallbacksAndMessages(null); cloud.close(); web.removeJavascriptInterface("Native"); web.destroy(); tts?.shutdown(); io.shutdown(); super.onDestroy() }
    companion object { private const val OPEN = 7; private const val MAX_BYTES = 8 * 1024 * 1024 }
}
