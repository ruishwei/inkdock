package org.inkdock.reader

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
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
    private var returnFromKokoroSetup = false
    private var kokoroSetupResumeRequested = false
    private var panelOpen = false
    private var backCallback: OnBackInvokedCallback? = null
    private var selectedCloud: CloudVoiceProvider? = null // Every launch defaults to offline.
    private var cloudUtterance = ""
    private var cloudRequest = ""
    private var cloudCharacters = 0
    private val cloud by lazy { CloudSpeechUi(this, { foreground && !isDestroyed && offline?.checkSleepTimer() == true },
        { message -> sendVoiceSettings(message) }, cloudEvent@{ phase, message ->
            if (phase in listOf("queued", "started", "done") && offline?.checkSleepTimer() != true) return@cloudEvent
            if (phase == "queued") emitSpeech(speech.begin(cloudUtterance, cloudRequest, cloudCharacters, SystemClock.elapsedRealtime()), message)
            else {
                val update = speech.event(cloudUtterance, phase, SystemClock.elapsedRealtime(), if (phase == "error") -2000 else null)
                    ?: if (phase == "stopped" || phase == "error") SpeechTracker.Update(cloudUtterance, cloudRequest, phase, cloudCharacters, 0) else null
                if (update != null) emitSpeech(update, message)
                if (phase == "done" && cloudRequest.startsWith("preview-")) offline?.cancelSleepTimer()
            }
        }) }
    private var offline: OfflineSpeechService? = null
    private var serviceBound = false
    private val offlineListener = object : OfflineSpeechService.Listener {
        override fun onSleepTimerStatus(state: JSONObject) {
            if (ready && foreground && !isDestroyed) web.evaluateJavascript("window.onSleepTimerStatus && window.onSleepTimerStatus($state)", null)
        }
        override fun onSleepTimerExpired() {
            stopCloudPlayback("睡眠定时已到，朗读已停止。")
            if (ready && !isDestroyed) web.evaluateJavascript("window.stopForSleepTimer && window.stopForSleepTimer()", null)
        }
        override fun onQueueStatus(state: JSONObject) {
            // A completed old-document snapshot must never move the new document's anchor.
            val otherDocument = state.optString("documentId").let { it.isNotEmpty() && it != documentId }
            val visible = if (otherDocument) JSONObject(state.toString()).put("block", -1).put("active", false)
                .put("phase", "stopped").put("message", "当前文档没有正在进行的离线朗读。") else state
            if (ready && foreground && !isDestroyed) web.evaluateJavascript("window.onOfflineQueueStatus && window.onOfflineQueueStatus($visible)", null)
            if (visible.optString("phase") == "error" && foreground) toast(visible.optString("message"))
        }
        override fun onVoiceSettings(state: JSONObject) = sendVoiceSettings(state.optString("message"))
        override fun onSpeechStatus(state: JSONObject) {
            if (ready && foreground && !isDestroyed) web.evaluateJavascript("window.onSpeechStatus($state)", null)
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            offline = (binder as OfflineSpeechService.LocalBinder).service
            if (foreground) offline?.setListener(offlineListener)
            if (ready && !documentShown) showDocument()
            sendVoiceSettings()
            if (ready && foreground) offline?.sendSnapshot()
            restoreKokoroAfterSetup()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            offline = null
            if (ready && foreground) web.evaluateJavascript("window.onOfflineQueueStatus && window.onOfflineQueueStatus({phase:'error',active:false,restore:true,message:'后台语音服务已结束，请重新开始朗读。'})", null)
        }
    }
    private val offlineCenter by lazy { OfflineVoiceCenter(this, { offline?.sendVoiceSettings() },
        { web.evaluateJavascript("openPanel('voicePanel')", null) }) }
    private var documentShown = false
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
        returnFromKokoroSetup = savedInstanceState?.getBoolean("returnFromKokoroSetup") ?: false
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
        serviceBound = bindService(Intent(this, OfflineSpeechService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        // Recreating a shared-document Activity must not re-import and cancel its surviving queue.
        if (savedInstanceState == null) handleIntent(intent)
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
    private fun stopCloudPlayback(reason: String = "朗读已停止。") {
        cloud.stop()
        speech.cancel(SystemClock.elapsedRealtime())?.let { emitSpeech(it, reason) }
    }
    private fun stopPlayback(reason: String = "朗读已停止。", cancelTimer: Boolean = true) {
        stopCloudPlayback(reason)
        offline?.stopPlayback(reason, cancelTimer)
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showDocument() {
        if (!ready || offline == null) return
        documentShown = true
        val anchor = prefs.getInt("anchor.$documentId", 0)
        val font = prefs.getInt("font", 20)
        val dark = prefs.getBoolean("dark", false)
        val timer = offline?.sleepTimerSnapshot()
        web.evaluateJavascript("window.loadDocument(${JSONObject.quote(text)},$anchor,$font,$dark,${offline?.isReadingDocument(documentId) == true || timer?.optBoolean("active") == true || timer?.optBoolean("expired") == true})", null)
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
                runOnUiThread { if (generation == importGeneration && !isDestroyed) { stopPlayback("导入新文档，朗读已停止。"); text = value; documentId = id; showDocument() } }
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
    private fun sendVoiceSettings(message: String = "") {
        if (!ready || isDestroyed) return
        val state = (offline?.voiceSettings(message) ?: JSONObject().put("loading", true).put("initialized", false)
            .put("engines", JSONArray()).put("voices", JSONArray()).put("rate", prefs.getFloat("voice.rate", 1f)))
            .put("source", selectedCloud?.name ?: "offline")
        val offlineDiagnostics = state.optString("diagnostics")
        state.put("diagnostics", listOf(offlineDiagnostics, speechDiagnostics.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n"))
        web.evaluateJavascript("window.setVoiceSettings($state)", null)
    }
    private fun speakText(value: String, requestId: String) {
        if (offline?.checkSleepTimer() != true) return
        stopPlayback(cancelTimer = false)
        val utteranceId = "paragraph-${++speechSequence}"
        val clean = value.trim()
        if (!foreground) { emitSpeech(SpeechTracker.Update(utteranceId, requestId, "stopped", clean.length, 0), "应用在后台，未开始新的朗读。"); return }
        if (clean.isEmpty() || clean.length > (if (selectedCloud != null) CloudVoiceRequests.MAX_TEXT_UNITS else minOf(3500, TextToSpeech.getMaxSpeechInputLength()))) {
            emitSpeech(SpeechTracker.Update(utteranceId, requestId, "error", clean.length, 0, TextToSpeech.ERROR_INVALID_REQUEST), "朗读片段为空或过长，请重新选择较短文字。")
            return
        }
        if (selectedCloud != null) {
            cloudUtterance = utteranceId; cloudRequest = requestId; cloudCharacters = clean.length
            cloud.speak(selectedCloud!!, clean)
        } else {
            val service = offline
            if (service == null) { emitSpeech(SpeechTracker.Update(utteranceId, requestId, "error", clean.length, 0), "离线服务尚未连接，请稍后重试。"); return }
            service.submitQueue(OfflineSpeechRequest("single-${SystemClock.elapsedRealtime()}-$speechSequence", listOf(OfflineSpeechSegment(clean, -1, requestId))), documentId)
            service.sendSnapshot()
        }
    }
    inner class Bridge {
        @JavascriptInterface fun beginSpeaking(token: String) = runOnUiThread {
            if (foreground && !isDestroyed && offline?.beginUserPlayback() == true)
                web.evaluateJavascript("window.onUserPlaybackReady && window.onUserPlaybackReady(${JSONObject.quote(token.take(40))})", null)
        }
        @JavascriptInterface fun finishSpeaking() = runOnUiThread { offline?.cancelSleepTimer() }
        @JavascriptInterface fun setSleepTimer(minutes: Int) = runOnUiThread {
            if (offline?.setSleepTimer(minutes) != true) toast("请连接语音服务并输入 1–720 之间的整数分钟。")
        }
        @JavascriptInterface fun cancelSleepTimer() = runOnUiThread { offline?.cancelSleepTimer() }
        @JavascriptInterface fun getSleepTimer() = runOnUiThread {
            offline?.let { it.checkSleepTimer(); offlineListener.onSleepTimerStatus(it.sleepTimerSnapshot()) }
        }
        @JavascriptInterface fun openKokoroSetup() = runOnUiThread {
            val service = offline ?: run { toast("正在连接语音服务，请稍后打开模型管理。"); return@runOnUiThread }
            stopPlayback(); service.releaseKokoroForMaintenance()
            returnFromKokoroSetup = true; kokoroSetupResumeRequested = false
            startActivity(Intent(this@MainActivity, KokoroSetupActivity::class.java))
        }
        @JavascriptInterface fun panelState(open: Boolean) = runOnUiThread { setPanelOpen(open) }
        @JavascriptInterface fun ready() = runOnUiThread { ready = true; showDocument(); sendVoiceSettings(); offline?.sendSnapshot(); setPanelOpen(false); restoreKokoroAfterSetup() }
        @JavascriptInterface fun openDocument() = runOnUiThread {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(picker, OPEN)
        }
        @JavascriptInterface fun saveProgress(anchor: Int, font: Int, dark: Boolean) = runOnUiThread {
            applyReaderTheme(dark)
            prefs.edit().putInt("anchor.$documentId", anchor.coerceAtLeast(0)).putInt("font", font.coerceIn(14, 32)).putBoolean("dark", dark).apply()
        }
        @JavascriptInterface fun selectVoiceEngine(name: String) = runOnUiThread {
            stopCloudPlayback()
            if (ready) web.evaluateJavascript("window.stopForEngineChange && window.stopForEngineChange()", null)
            offline?.selectEngine(name) ?: sendVoiceSettings("离线服务尚未连接，请稍后重试。")
        }
        @JavascriptInterface fun openOfflineVoiceCenter() = runOnUiThread { offlineCenter.show() }
        @JavascriptInterface fun setSpeechSource(source: String) = runOnUiThread {
            stopPlayback()
            selectedCloud = CloudVoiceProvider.values().firstOrNull { it.name == source }
            sendVoiceSettings(if (selectedCloud == null) "已选择系统离线朗读。" else "实验性云朗读：每段发送前需要确认，可能产生费用；真实音色尚未实测。")
        }
        @JavascriptInterface fun configureCloudVoice(source: String) = runOnUiThread {
            CloudVoiceProvider.values().firstOrNull { it.name == source }?.let { cloud.configure(it) }
        }
        @JavascriptInterface fun previewCloudVoice() = runOnUiThread {
            offline?.beginUserPlayback()
            if (selectedCloud == null) sendVoiceSettings("请先选择云服务商，或使用下方系统音色试听。")
            else speakText("你好，这里是 InkDock。窗外的风轻轻吹过，我们继续读下一段故事。", "preview-${++speechSequence}")
        }
        @JavascriptInterface fun getVoiceSettings() = runOnUiThread { sendVoiceSettings() }
        @JavascriptInterface fun saveVoiceSettings(name: String, rate: Double, preview: Boolean) = runOnUiThread {
            if (!rate.isFinite() || offline?.configureVoice(name, rate.toFloat().coerceIn(0.5f, 1.5f)) != true) {
                sendVoiceSettings("该离线音色不可用，设置未保存。请检查系统语音包。")
            } else {
                sendVoiceSettings("设置已保存。音色的自然度由系统语音引擎决定。")
                if (preview) { offline?.beginUserPlayback(); speakText("你好，这里是 InkDock。窗外的风轻轻吹过，我们继续读下一段故事。", "preview-${++speechSequence}") }
            }
        }
        @JavascriptInterface fun speak(value: String, requestId: String) = runOnUiThread { speakText(value, requestId.take(80)) }
        @JavascriptInterface fun speakOfflineQueue(json: String) {
            // Parsing on the WebView bridge thread keeps large accepted queues off the UI thread.
            var queueId = ""
            val parsed = try {
                require(json.length <= OfflineSpeechRequest.MAX_JSON_UNITS) { "朗读队列过大，请从较后段落开始。" }
                queueId = JSONObject(json).optString("queueId").take(100)
                OfflineSpeechRequest.parse(json, minOf(3500, TextToSpeech.getMaxSpeechInputLength()))
            } catch (error: Exception) {
                val reason = if (error is IllegalArgumentException) error.message ?: "朗读队列无效。" else "朗读队列格式无效，请重新开始。"
                runOnUiThread { offline?.rejectQueue(queueId, reason); toast(reason) }
                return
            }
            runOnUiThread {
                if (!foreground || isDestroyed || selectedCloud != null || offline?.checkSleepTimer() != true) { offline?.rejectQueue(parsed.queueId, "请在应用前台选择离线朗读后重试。"); return@runOnUiThread }
                stopCloudPlayback()
                val service = offline
                if (service != null) service.submitQueue(parsed, documentId)
                else if (ready) web.evaluateJavascript("window.onOfflineQueueStatus && window.onOfflineQueueStatus(${JSONObject().put("queueId", parsed.queueId).put("phase", "error").put("active", false).put("message", "离线服务尚未连接，请稍后重试。")})", null)
            }
        }
        @JavascriptInterface fun pauseSpeaking() = runOnUiThread { offline?.pausePlayback() }
        @JavascriptInterface fun resumeSpeaking() = runOnUiThread { if (foreground && selectedCloud == null) offline?.resumePlayback() }
        @JavascriptInterface fun getSpeechState() = runOnUiThread { offline?.sendSnapshot() }
        @JavascriptInterface fun stopSpeaking() = runOnUiThread { stopPlayback() }
    }
    override fun onResume() {
        super.onResume(); foreground = true
        offline?.setListener(offlineListener)
        if (ready) offline?.sendSnapshot()
        if (returnFromKokoroSetup) kokoroSetupResumeRequested = true
        restoreKokoroAfterSetup()
    }
    private fun restoreKokoroAfterSetup() {
        // Setup may outlive Activity recreation or an asynchronous Service/WebView connection.
        // Never reacquire the model while the setup Activity is still in front.
        if (!returnFromKokoroSetup || !kokoroSetupResumeRequested || !foreground || !ready) return
        val service = offline ?: return
        returnFromKokoroSetup = false; kokoroSetupResumeRequested = false
        service.resumeKokoroAfterMaintenance()
        web.evaluateJavascript("openPanel('voicePanel')", null)
        service.sendVoiceSettings()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("returnFromKokoroSetup", returnFromKokoroSetup)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        foreground = false
        // Cloud remains Activity-only; background offline synthesis belongs to the service.
        stopCloudPlayback("应用进入后台，云朗读已停止。")
        offline?.setListener(null)
        super.onPause()
        if (ready && !isDestroyed) web.evaluateJavascript("window.stopForBackground && window.stopForBackground()", null)
    }
    override fun onDestroy() {
        setPanelOpen(false); mainHandler.removeCallbacksAndMessages(null); cloud.close()
        offline?.setListener(null)
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        offline = null; web.removeJavascriptInterface("Native"); web.destroy(); io.shutdown()
        super.onDestroy()
    }
    companion object { private const val OPEN = 7; private const val MAX_BYTES = 8 * 1024 * 1024 }
}
