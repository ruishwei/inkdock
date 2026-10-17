package org.inkdock.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
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

/** Only our packaged UI can reach this bridge. No network or storage permission. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var appliedDark: Boolean? = null
    private var speechSequence = 0
    private var activeUtterance: String? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitialized = false
    private var ready = false
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
        tts = TextToSpeech(this, this)
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
    private fun playbackState(playing: Boolean) {
        if (ready && !isDestroyed) web.evaluateJavascript("window.setPlaybackState($playing)", null)
    }
    private fun stopPlayback() {
        activeUtterance = null
        tts?.stop()
        playbackState(false)
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
        val packageName = engine?.defaultEngine.orEmpty()
        val label = engine?.engines?.firstOrNull { it.name == packageName }?.label ?: packageName
        val voices = JSONArray()
        offlineVoices().forEach { voices.put(JSONObject().put("name", it.name).put("language", it.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE))) }
        val current = if (ttsReady) engine?.voice else null
        val state = JSONObject().put("engine", label).put("current", current?.name.orEmpty())
            .put("language", current?.locale?.getDisplayName(Locale.SIMPLIFIED_CHINESE).orEmpty())
            .put("voices", voices).put("rate", voiceRate()).put("message", message)
        web.evaluateJavascript("window.setVoiceSettings($state)", null)
    }
    private fun configureVoice(name: String, rate: Float): Boolean {
        val engine = tts ?: return false
        val voice = offlineVoices().firstOrNull { it.name == name } ?: return false
        stopPlayback()
        ttsReady = false
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS || engine.setSpeechRate(rate) != TextToSpeech.SUCCESS) return false
        // Do not trust a successful request if the engine silently selected a different voice.
        val actual = engine.voice ?: return false
        if (actual.name != voice.name || actual.isNetworkConnectionRequired ||
            actual.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) return false
        ttsReady = true
        prefs.edit().putString("voice.name", voice.name).putFloat("voice.rate", rate).apply()
        return true
    }
    private fun speakText(value: String) {
        val engine = tts
        if (!ttsReady || engine == null || offlineVoices().none { it.name == engine.voice?.name }) {
            toast("离线中文语音尚未就绪，请打开「声音」检查设置。")
            return
        }
        if (value.isBlank()) return
        val utteranceId = "paragraph-${++speechSequence}"
        activeUtterance = utteranceId
        val result = engine.speak(value.take(minOf(3500, TextToSpeech.getMaxSpeechInputLength())), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        playbackState(result == TextToSpeech.SUCCESS)
        if (result != TextToSpeech.SUCCESS) { activeUtterance = null; sendVoiceSettings("朗读失败，请检查系统语音引擎或换一个已安装音色。") }
    }
    override fun onInit(status: Int) {
        runOnUiThread {
            ttsInitialized = status == TextToSpeech.SUCCESS
            if (ttsInitialized) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { runOnUiThread { if (utteranceId == activeUtterance) playbackState(true) } }
                    override fun onDone(utteranceId: String?) { runOnUiThread { if (utteranceId == activeUtterance) { activeUtterance = null; playbackState(false) } } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { runOnUiThread { if (utteranceId == activeUtterance) { activeUtterance = null; playbackState(false) } } }
                    @Deprecated("Required legacy callback")
                    override fun onError(utteranceId: String?) { runOnUiThread { if (utteranceId == activeUtterance) { activeUtterance = null; playbackState(false); sendVoiceSettings("语音引擎未能完成朗读，请检查离线语音包。") } } }
                })
                val voices = offlineVoices()
                val saved = prefs.getString("voice.name", null)
                val choice = voices.firstOrNull { it.name == saved } ?: voices.firstOrNull()
                if (choice != null) configureVoice(choice.name, voiceRate())
            }
            sendVoiceSettings(if (ttsReady) "" else "未找到可用的离线中文语音，请在系统 TTS 设置检查语音包。")
        }
    }
    inner class Bridge {
        @JavascriptInterface fun ready() = runOnUiThread { ready = true; showDocument(); sendVoiceSettings(); playbackState(activeUtterance != null) }
        @JavascriptInterface fun openDocument() = runOnUiThread {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(picker, OPEN)
        }
        @JavascriptInterface fun saveProgress(anchor: Int, font: Int, dark: Boolean) = runOnUiThread {
            applyReaderTheme(dark)
            prefs.edit().putInt("anchor.$documentId", anchor.coerceAtLeast(0)).putInt("font", font.coerceIn(14, 32)).putBoolean("dark", dark).apply()
        }
        @JavascriptInterface fun getVoiceSettings() = runOnUiThread { sendVoiceSettings() }
        @JavascriptInterface fun saveVoiceSettings(name: String, rate: Double, preview: Boolean) = runOnUiThread {
            if (!rate.isFinite() || !configureVoice(name, rate.toFloat().coerceIn(0.5f, 1.5f))) {
                sendVoiceSettings("该离线音色不可用，设置未保存。请检查系统语音包。")
            } else {
                sendVoiceSettings("设置已保存。音色的自然度由系统语音引擎决定。")
                if (preview) speakText("你好，这里是 InkDock。窗外的风轻轻吹过，我们继续读下一段故事。")
            }
        }
        @JavascriptInterface fun speak(value: String) = runOnUiThread { speakText(value) }
        @JavascriptInterface fun stopSpeaking() = runOnUiThread { stopPlayback() }
    }
    override fun onPause() { super.onPause(); stopPlayback() }
    override fun onDestroy() { web.removeJavascriptInterface("Native"); web.destroy(); tts?.shutdown(); io.shutdown(); super.onDestroy() }
    companion object { private const val OPEN = 7; private const val MAX_BYTES = 8 * 1024 * 1024 }
}
