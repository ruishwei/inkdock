package org.inkdock.reader

import java.util.concurrent.atomic.AtomicBoolean

/** Safe fixed messages only. Provider response/error bodies and URLs never become error messages. */
enum class CloudVoiceError(val message: String) {
    INVALID_CONFIG("云语音配置无效，请检查模型、音色和官方 HTTPS 地址。"),
    INVALID_TEXT("本次云语音文本须为 1–600 个 UTF-16 字符，且不能只含空白。"),
    CONSENT_REQUIRED("需要重新确认服务商、本次完整文本和可能产生的费用。"),
    KEY_MISSING("尚未保存此服务商的 API 密钥。"),
    KEY_UNAVAILABLE("密钥无法安全读取，请重新保存。"),
    KEY_INVALID("密钥格式无效，请在原生密钥输入框重新输入。"),
    STORAGE_FAILED("安全存储操作未完成，请重试。"),
    CANCELLED("本次请求已取消；已提交的合成仍可能计费。"),
    TIMEOUT("云语音请求超时；不要自动重试，已提交请求仍可能计费。"),
    NETWORK("云语音连接失败；不会自动重试。"),
    TLS("无法验证服务商的安全连接。"),
    REDIRECT("服务返回了重定向，已阻止。"),
    AUTHENTICATION("服务商拒绝密钥或访问权限，请检查地区、账号及密钥。"),
    RATE_LIMIT("服务商限流或余额不足，请在官方控制台检查。"),
    PROVIDER_REJECTED("服务商未成功完成合成，请检查模型、音色及账号配额。"),
    INVALID_RESPONSE("服务商返回了不完整或不支持的数据。"),
    TOO_LARGE("服务商返回的数据超过本应用安全上限。"),
    UNSAFE_AUDIO_URL("音频地址不是允许的官方 HTTPS 存储地址，已阻止下载。"),
    EXPIRED_AUDIO("服务商返回的音频下载地址已过期。"),
    INVALID_AUDIO("返回数据不是当前支持的 WAV/MP3 音频。")
}

class CloudVoiceException(val error: CloudVoiceError) : Exception(error.message)

/** Not a data class: toString() must not print private text. Native UI owns display and approval. */
class CloudVoiceConsent private constructor(
    private val config: CloudVoiceConfig,
    private val exactText: String,
    private val approvedAtNanos: Long
) {
    private val used = AtomicBoolean(false)

    internal fun consume(config: CloudVoiceConfig, text: String) {
        val age = System.nanoTime() - approvedAtNanos
        if (!used.compareAndSet(false, true) || this.config != config || exactText != text || age < 0 || age > 5L * 60 * 1_000_000_000)
            throw CloudVoiceException(CloudVoiceError.CONSENT_REQUIRED)
    }

    companion object {
        /** Call ONLY from the affirmative button of a native dialog displaying all these details.
         * This grants one attempt, not retries, continuation segments, or future requests. */
        fun approveDisplayedRequest(config: CloudVoiceConfig, exactText: String, billingMayApply: Boolean): CloudVoiceConsent {
            CloudVoiceConfigValidator.requireValid(config)
            CloudVoiceRequests.validateText(exactText)
            if (!billingMayApply) throw CloudVoiceException(CloudVoiceError.CONSENT_REQUIRED)
            return CloudVoiceConsent(config, exactText, System.nanoTime())
        }
    }
}

object CloudVoiceRequests {
    const val MAX_TEXT_UNITS = 600 // Local conservative cap for every provider; not its billing unit.
    internal fun validateText(text: String) {
        if (text.isBlank() || text.length > MAX_TEXT_UNITS || text.any { it == '\u0000' })
            throw CloudVoiceException(CloudVoiceError.INVALID_TEXT)
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) {
                if (i >= text.length || !Character.isLowSurrogate(text[i++])) throw CloudVoiceException(CloudVoiceError.INVALID_TEXT)
            } else if (Character.isLowSurrogate(c)) throw CloudVoiceException(CloudVoiceError.INVALID_TEXT)
        }
    }

    /** Pure serializer. No credential parameter, networking, logging, trimming, or text substitution. */
    fun serialize(config: CloudVoiceConfig, exactText: String): String {
        val contract = CloudVoiceConfigValidator.requireValid(config)
        validateText(exactText)
        val model = quote(config.model); val voice = quote(config.voice); val text = quote(exactText)
        return when (contract) {
            CloudVoiceContract.QWEN3 -> "{\"model\":$model,\"input\":{\"text\":$text,\"voice\":$voice,\"language_type\":\"Auto\"}}"
            CloudVoiceContract.QWEN_AUDIO -> "{\"model\":$model,\"input\":{\"text\":$text,\"voice\":$voice,\"format\":\"wav\",\"sample_rate\":24000}}"
            CloudVoiceContract.MINIMAX_V2 -> "{\"model\":$model,\"text\":$text,\"stream\":false,\"language_boost\":\"auto\",\"output_format\":\"hex\",\"voice_setting\":{\"voice_id\":$voice,\"speed\":1,\"vol\":1,\"pitch\":0},\"audio_setting\":{\"sample_rate\":32000,\"bitrate\":128000,\"format\":\"mp3\",\"channel\":1}}"
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            '\b' -> append("\\b"); '\u000c' -> append("\\f")
            else -> if (c < ' ') { append("\\u"); append(c.code.toString(16).padStart(4, '0')) } else append(c)
        } }
        append('"')
    }
}

/** Header-level validation only; MediaPlayer remains responsible for actual codec decoding. */
object CloudVoiceAudioValidator {
    const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    fun format(bytes: ByteArray): String {
        if (bytes.size > MAX_AUDIO_BYTES) throw CloudVoiceException(CloudVoiceError.TOO_LARGE)
        if (bytes.size >= 44 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(82,73,70,70)) &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(87,65,86,69))) return "wav"
        if (bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()) return "mp3"
        if (bytes.size >= 4 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xe0) == 0xe0 &&
            (bytes[1].toInt() and 0x06) != 0 && (bytes[2].toInt() and 0xf0) !in setOf(0, 0xf0) &&
            (bytes[2].toInt() and 0x0c) != 0x0c) return "mp3"
        throw CloudVoiceException(CloudVoiceError.INVALID_AUDIO)
    }

    fun decodeHex(value: String): ByteArray {
        if (value.isEmpty() || value.length % 2 != 0) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        if (value.length > MAX_AUDIO_BYTES * 2) throw CloudVoiceException(CloudVoiceError.TOO_LARGE)
        val bytes = ByteArray(value.length / 2)
        for (i in bytes.indices) {
            val high = value[i * 2].digitToIntOrNull(16)
            val low = value[i * 2 + 1].digitToIntOrNull(16)
            if (high == null || low == null) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }
}

/** These fields are extracted from JSON by the native client, then validated without Android. */
class QwenVoiceResponse(val providerCode: String?, val statusCode: Int?, val finishReason: String?, val audioUrl: String?, val expiresAt: Long?)
class MiniMaxVoiceResponse(val statusCode: Int?, val audioStatus: Int?, val audioHex: String?, val audioFormat: String?)

object CloudVoiceResponses {
    fun qwenDownload(config: CloudVoiceConfig, response: QwenVoiceResponse, nowSeconds: Long): java.net.URI {
        if (response.providerCode != null && response.providerCode.isNotEmpty()) throw CloudVoiceException(CloudVoiceError.PROVIDER_REJECTED)
        if (response.statusCode != null && response.statusCode != 200) throw CloudVoiceException(CloudVoiceError.PROVIDER_REJECTED)
        if (response.finishReason != "stop" || response.audioUrl.isNullOrEmpty()) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        if (response.expiresAt != null && response.expiresAt <= nowSeconds) throw CloudVoiceException(CloudVoiceError.EXPIRED_AUDIO)
        return CloudVoiceConfigValidator.audioDownloadUri(config, response.audioUrl)
    }

    fun miniMaxAudio(response: MiniMaxVoiceResponse): ByteArray {
        if (response.statusCode == null) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        if (response.statusCode != 0) throw CloudVoiceException(CloudVoiceError.PROVIDER_REJECTED)
        if (response.audioStatus != 2 || response.audioHex.isNullOrEmpty() || response.audioFormat != "mp3")
            throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        val bytes = CloudVoiceAudioValidator.decodeHex(response.audioHex)
        if (CloudVoiceAudioValidator.format(bytes) != "mp3") throw CloudVoiceException(CloudVoiceError.INVALID_AUDIO)
        return bytes
    }
}

data class CloudVoiceMockReport(val passed: Boolean, val message: String, val networkRequests: Int = 0, val producedAudio: Boolean = false)

object CloudVoiceMock {
    const val SAMPLE_TEXT = "这是一段仅在本机校验格式的合成示例。"
    fun check(config: CloudVoiceConfig): CloudVoiceMockReport {
        return try {
            CloudVoiceRequests.serialize(config, SAMPLE_TEXT)
            CloudVoiceMockReport(true, "本机配置和请求格式校验通过。没有联网、扣费或生成音频；密钥、账号权限、音色可用性和音质尚未验证。")
        } catch (error: CloudVoiceException) { CloudVoiceMockReport(false, error.error.message) }
    }
}
