package org.inkdock.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/** Only our packaged UI can reach this bridge. No network or storage permission. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
        web.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemWindowInsetTop
            view.setPadding(insets.systemWindowInsetLeft, bars, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(web)
        web.requestApplyInsets()
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
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val offline = engine.voices?.firstOrNull { it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired }
            if (offline != null) { engine.voice = offline; ttsReady = true }
            else toast("未找到离线中文语音。请在系统文字转语音设置下载中文语音包。")
        }
    }
    inner class Bridge {
        @JavascriptInterface fun ready() = runOnUiThread { ready = true; showDocument() }
        @JavascriptInterface fun openDocument() = runOnUiThread {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(picker, OPEN)
        }
        @JavascriptInterface fun saveProgress(anchor: Int, font: Int, dark: Boolean) = runOnUiThread {
            prefs.edit().putInt("anchor.$documentId", anchor.coerceAtLeast(0)).putInt("font", font.coerceIn(14, 32)).putBoolean("dark", dark).apply()
        }
        @JavascriptInterface fun speak(value: String) = runOnUiThread {
            if (!ttsReady) toast("离线中文语音尚未就绪，请检查系统 TTS 设置。")
            else tts?.speak(value.take(3500), TextToSpeech.QUEUE_FLUSH, null, "paragraph")
        }
        @JavascriptInterface fun stopSpeaking() = runOnUiThread { tts?.stop() }
    }
    override fun onPause() { super.onPause(); tts?.stop() }
    override fun onDestroy() { web.removeJavascriptInterface("Native"); web.destroy(); tts?.shutdown(); io.shutdown(); super.onDestroy() }
    companion object { private const val OPEN = 7; private const val MAX_BYTES = 8 * 1024 * 1024 }
}
