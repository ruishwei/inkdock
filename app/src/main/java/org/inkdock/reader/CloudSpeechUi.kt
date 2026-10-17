package org.inkdock.reader

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Native-only credentials, explicit per-segment transmission consent, private temporary audio. */
class CloudSpeechUi(
    private val activity: Activity,
    private val foreground: () -> Boolean,
    private val status: (String) -> Unit,
    private val event: (String, String) -> Unit
) {
    private val prefs = activity.getSharedPreferences("cloud_voice", Activity.MODE_PRIVATE)
    private val keys = CloudVoiceKeyStore(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val timers = Handler(Looper.getMainLooper())
    private var generation = 0
    private var dialog: AlertDialog? = null
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var cancellation: CloudVoiceCancellation? = null
    private var active = false
    private val audioDirectory = File(activity.noBackupFilesDir, "speech-temporary")

    init { audioDirectory.mkdirs(); audioDirectory.listFiles()?.forEach { it.delete() } }

    fun config(provider: CloudVoiceProvider): CloudVoiceConfig {
        val defaults = CloudVoiceConfig.defaults(provider)
        val prefix = provider.name
        return defaults.copy(endpoint = prefs.getString("$prefix.endpoint", defaults.endpoint) ?: defaults.endpoint,
            model = prefs.getString("$prefix.model", defaults.model) ?: defaults.model,
            voice = prefs.getString("$prefix.voice", defaults.voice) ?: defaults.voice)
    }

    fun configure(provider: CloudVoiceProvider) {
        stop()
        val current = config(provider)
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
        fun field(label: String, value: String, secret: Boolean = false): EditText {
            layout.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply {
                setText(value); isSingleLine = true
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                if (secret) { setSaveEnabled(false); setPrivateImeOptions("nm"); }
                layout.addView(this)
            }
        }
        layout.addView(TextView(activity).apply { text = "实验性云语音：接口已实现，真实音色与账号可用性尚未验证。保存配置不会联网。API key 仅保存在本机 Android Keystore 加密存储中，留空保留原 key。" })
        val endpoint = field("HTTPS 接口地址（请核对服务商官方区域）", current.endpoint)
        val model = field("TTS 模型（不能填写对话模型）", current.model)
        val voice = field("音色 ID", current.voice)
        val key = field("API key（不回显已保存值）", "", true)
        val feedback = TextView(activity).apply { text = when (keys.status(provider)) {
            CloudVoiceKeyStatus.SAVED -> "本机已保存 key（不显示内容）。"
            CloudVoiceKeyStatus.MISSING -> "本机尚无此服务商 key。"
            CloudVoiceKeyStatus.UNAVAILABLE -> "本机 key 无法解密，请重新输入保存。"
        }; layout.addView(this) }
        val scroll = ScrollView(activity).apply { addView(layout) }
        val d = AlertDialog.Builder(activity).setTitle("${provider.displayName} 云朗读配置")
            .setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null)
            .setNeutralButton("删除本机 key", null).create()
        dialog = d
        d.setOnDismissListener { key.text?.clear(); if (dialog === d) dialog = null }
        d.setOnShowListener {
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = current.copy(endpoint = endpoint.text.toString().trim(), model = model.text.toString().trim(), voice = voice.text.toString().trim())
                val issues = CloudVoiceConfigValidator.validate(updated)
                if (issues.isNotEmpty()) { feedback.text = issues.joinToString("\n") { it.message }; return@setOnClickListener }
                try {
                    val secret = CharArray(key.length()) { key.text[it] }
                    try { if (secret.isNotEmpty()) keys.save(provider, secret) } finally { secret.fill('\u0000'); key.text?.clear() }
                    val prefix = provider.name
                    check(prefs.edit().putString("$prefix.endpoint", updated.endpoint).putString("$prefix.model", updated.model).putString("$prefix.voice", updated.voice).commit())
                    status("${provider.displayName} 配置已保存在本机。没有联网或验证真实音色。")
                    d.dismiss()
                } catch (error: CloudVoiceException) { feedback.text = error.error.message }
                catch (_: Exception) { feedback.text = "配置或密钥无法安全保存，请重试。" }
            }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try { keys.remove(provider); key.text?.clear(); feedback.text = "本机 ${provider.displayName} key 已删除。" }
                catch (_: Exception) { feedback.text = "删除失败，请重试。" }
            }
        }
        d.show()
    }

    fun speak(provider: CloudVoiceProvider, text: String) {
        stop()
        if (!foreground()) { event("stopped", "应用在后台，未发送云朗读请求。"); return }
        try { CloudVoiceRequests.validateText(text) } catch (error: CloudVoiceException) { event("error", error.error.message); return }
        val settings = config(provider)
        if (keys.status(provider) != CloudVoiceKeyStatus.SAVED) { event("error", "尚未保存可用的本机 API key，请先在配置窗口输入。"); return }
        if (CloudVoiceConfigValidator.validate(settings).isNotEmpty()) { event("error", "云语音配置无效，请先检查设置。"); return }
        val version = ++generation
        active = true
        val display = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        display.addView(TextView(activity).apply { this.text = "服务商：${provider.displayName}\n地址：${settings.endpoint}\n模型：${settings.model}\n音色：${settings.voice}\n\n仅发送下面完整片段（${text.length} 字符）。可能按服务商账户价格计费；价格和余额由你的账户决定。停止后已发送的内容无法撤回，已发生费用可能仍收取。真实音色尚未实测。每一片段都会重新询问。" })
        display.addView(TextView(activity).apply { this.text = text; setTextIsSelectable(true); setPadding(0, 24, 0, 12) })
        val d = AlertDialog.Builder(activity).setTitle("确认发送并付费合成？")
            .setView(ScrollView(activity).apply { addView(display) })
            .setPositiveButton("同意发送本段", null).setNegativeButton("取消", null).create()
        dialog = d
        var approved = false
        d.setOnDismissListener {
            if (dialog === d) dialog = null
            if (!approved && version == generation) { active = false; event("stopped", "已取消，没有发送本段。"); }
        }
        d.setOnShowListener {
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (approved || version != generation || !foreground()) return@setOnClickListener
                d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                approved = true; d.dismiss()
                synthesize(version, settings, text)
            }
        }
        d.show()
    }

    private fun synthesize(version: Int, settings: CloudVoiceConfig, text: String) {
        val cancel = CloudVoiceCancellation()
        cancellation = cancel
        val consent = try { CloudVoiceConsent.approveDisplayedRequest(settings, text, billingMayApply = true) }
        catch (error: CloudVoiceException) { active = false; event("error", error.error.message); return }
        event("queued", "已确认发送，正在向 ${settings.provider.displayName} 合成语音。停止可能无法撤销已产生的费用。")
        io.execute {
            try {
                val result = CloudVoiceClient(keys).synthesize(settings, text, consent, cancel)
                val file = try {
                    File.createTempFile("segment-", ".audio", audioDirectory).also { temporary ->
                        try { temporary.writeBytes(result.bytes) } catch (error: Exception) { temporary.delete(); throw error }
                    }
                } finally { result.bytes.fill(0) }
                main.post {
                    if (version != generation || !foreground()) { file.delete(); return@post }
                    play(version, file)
                }
            } catch (error: Exception) {
                val message = (error as? CloudVoiceException)?.error?.message ?: "云语音请求失败，请检查网络与服务商配置。未记录响应正文或密钥。"
                main.post { if (version == generation) { active = false; event("error", message); } }
            }
        }
    }

    private fun play(version: Int, file: File) {
        try {
            audioFile = file
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setDataSource(file.absolutePath)
            var prepared = false
            media.setOnPreparedListener {
                if (version != generation || player !== media) return@setOnPreparedListener
                if (!foreground()) { stop(); return@setOnPreparedListener }
                try {
                    prepared = true
                    media.start(); event("started", "正在播放云端合成的本段语音。")
                } catch (_: Exception) {
                    releaseAudio(); active = false; event("error", "合成音频无法开始播放。")
                }
            }
            media.setOnCompletionListener {
                if (version == generation && player === media) { releaseAudio(); active = false; event("done", "当前云语音片段播放完成。"); }
            }
            media.setOnErrorListener { _, _, _ ->
                if (version == generation && player === media) { releaseAudio(); active = false; event("error", "合成音频无法播放，请检查服务商音频格式。"); }; true
            }
            media.prepareAsync()
            timers.postDelayed({
                if (version == generation && player === media && !prepared) {
                    releaseAudio(); active = false; event("error", "准备音频超时，已停止播放。")
                }
            }, 15000)
            timers.postDelayed({
                if (version == generation && player === media) {
                    releaseAudio(); active = false; event("error", "单段音频超过五分钟安全上限，已停止播放。")
                }
            }, 300000)
        } catch (_: Exception) { releaseAudio(); active = false; event("error", "无法准备或播放合成音频。"); }
    }

    private fun releaseAudio() { timers.removeCallbacksAndMessages(null); player?.release(); player = null; audioFile?.delete(); audioFile = null }
    fun stop() {
        ++generation
        cancellation?.cancel(); cancellation = null
        dialog?.dismiss(); dialog = null
        releaseAudio()
        if (active) { active = false; event("stopped", "云朗读已停止；已发送请求可能已产生费用。"); }
    }
    fun close() { stop(); timers.removeCallbacksAndMessages(null); io.shutdownNow() }
}
