package org.inkdock.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser

/** External-engine setup only. No model downloads, APK installs or invented model-ready state. */
class OfflineVoiceCenter(private val activity: Activity, private val refresh: () -> Unit, private val returnToSettings: () -> Unit = {}) {
    private var dialog: AlertDialog? = null
    private val pm get() = activity.packageManager
    private data class Engine(val label: String, val pkg: String)

    @Suppress("DEPRECATION")
    private fun engines(): List<Engine> = pm.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.GET_META_DATA
    ).mapNotNull { result -> result.serviceInfo?.takeIf { it.exported && it.enabled && it.applicationInfo.enabled }
        ?.let { Engine(result.loadLabel(pm).toString(), it.packageName) } }
        .distinctBy { it.pkg }.sortedBy { it.label }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding)
        }
        fun paragraph(value: String) { body.addView(TextView(activity).apply { text = value; textSize = 15f; setPadding(0, 12, 0, 12) }) }
        fun action(label: String, click: () -> Unit) { body.addView(Button(activity).apply { text = label; setOnClickListener { click() } }) }
        paragraph("本版使用独立的 Android TTS 引擎，尚未内置神经模型运行库。下载、进度、取消、占用空间和删除模型均由所安装的引擎应用管理；这里只负责安装指引、打开管理界面和接回朗读。打开网站会使用手机浏览器，不发送书中文字。")
        paragraph("① 获取中文离线引擎\nKokoro 中文 v1.1：优先选择名称含 Chinese / multi-lang-v1_1 的模型，不能选仅 English 的包。sherpa 官方也提供带模型的 TTS Engine APK，注意选择 arm64-v8a 和中文版本，不要选仅供演示的 TTS APK。下载及安装由你在浏览器/系统安装器中确认。")
        action("打开 sherpa 官方 TTS 引擎下载目录") { website(OfflineVoiceCatalog.SHERPA_ENGINES) }
        action("查看 Kokoro 中文模型与音色说明") { website(OfflineVoiceCatalog.KOKORO_GUIDE) }
        paragraph("VoxSherpa：另一种独立引擎，支持在应用内下载/导入 Kokoro、Piper 模型；官方标注 Android 11+、ARM64，建议至少预留 500 MB，实际空间以所选包为准。安装后先在该应用下载中文模型并启用 System TTS，再回到这里。第三方应用的模型清单与可用性由其维护者决定。")
        action("打开 VoxSherpa 官方 Releases") { website(OfflineVoiceCatalog.VOX_RELEASES) }
        paragraph("Piper 中文 huayan：medium 模型文件约 63.2 MB，另需配置和语音处理数据，不能只下载一个 ONNX 文件就播放。模型卡把训练数据许可标为 Unknown；本版不打包或自动下载此模型。请先阅读许可，再在兼容引擎内导入/管理。")
        action("查看 Piper 中文文件与许可") { website(OfflineVoiceCatalog.PIPER_CARD) }
        paragraph("② 已检测到的系统 TTS 引擎\n检测到应用不代表中文模型已经下载，也不保证引擎会按其离线声明运行。安装配置后可断网试听验证。")
        val installed = engines()
        if (installed.isEmpty()) paragraph("尚未检测到可用 TTS 服务。完成安装后点下方重新检测。")
        for (engine in installed) {
            paragraph("${engine.label}\n${engine.pkg}")
            action("打开 ${engine.label} 的模型管理") { openManager(engine.pkg) }
            action("打开 ${engine.label} 的语音设置") { openSettings(engine.pkg) }
        }
        action("重新检测已安装引擎") { refresh(); show() }
        action("打开系统文字转语音设置") { systemSettings() }
        paragraph("③ 返回声音设置试听\n关闭本窗口，在“已安装的系统语音引擎”选择刚安装的引擎，点“切换 / 重试引擎”，再选择离线中文音色并“保存并试听”。若引擎只提供一个音色，具体模型和说话人需在引擎应用中切换，再回来重试。\n\nPocket / Supertonic 未作为中文方案提供；IndexTTS / Qwen 本机方案尚未接入，不显示不可用的下载按钮。")
        dialog = AlertDialog.Builder(activity).setTitle("离线语音安装与配置")
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("返回声音设置") { _, _ -> refresh(); returnToSettings() }.create().also { it.show() }
    }

    private fun launch(intent: Intent): Boolean = try {
        activity.startActivity(intent); true
    } catch (_: android.content.ActivityNotFoundException) { false
    } catch (_: SecurityException) { false }

    private fun website(url: String) {
        // Fixed allowlist: the WebView cannot supply arbitrary URLs or personal data.
        if (url !in OfflineVoiceCatalog.urls) return
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)))
            Toast.makeText(activity, "未找到浏览器，请先安装或启用浏览器。", Toast.LENGTH_LONG).show()
    }

    private fun openManager(pkg: String) {
        if (engines().none { it.pkg == pkg }) { show(); return }
        // A package-scoped standard action, with a launcher fallback. Neither invents app-private routes.
        if (launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(pkg))) return
        val launcher = pm.getLaunchIntentForPackage(pkg)
        if (launcher != null && launch(launcher)) return
        openSettings(pkg)
    }

    @Suppress("DEPRECATION")
    private fun openSettings(pkg: String) {
        if (engines().none { it.pkg == pkg }) { show(); return }
        val services = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(pkg), PackageManager.GET_META_DATA)
        for (service in services) {
            // Android's documented tts-engine metadata points to the engine's actual settings activity.
            val parser = try { service.serviceInfo.loadXmlMetaData(pm, "android.speech.tts") } catch (_: Exception) { null } ?: continue
            try {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG || parser.name != "tts-engine") continue
                    val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "settingsActivity") ?: break
                    if (name.startsWith("@")) break // Do not guess unresolved resource references.
                    val component = ComponentName(pkg, if (name.startsWith(".")) pkg + name else if ('.' in name) name else "$pkg.$name")
                    val info = try { pm.getActivityInfo(component, 0) } catch (_: PackageManager.NameNotFoundException) { null }
                    if (info != null && info.exported && info.enabled && launch(Intent(Intent.ACTION_MAIN).setComponent(component))) return
                    break
                }
            } catch (_: Exception) { /* OEM metadata can be malformed; use the safe system fallback. */ }
            finally { parser.close() }
        }
        systemSettings()
    }

    private fun systemSettings() {
        if (!launch(Intent("com.android.settings.TTS_SETTINGS")) && !launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            Toast.makeText(activity, "系统未提供可打开的语音设置，请在手机设置中搜索“文字转语音”。", Toast.LENGTH_LONG).show()
    }
}
