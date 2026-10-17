package org.inkdock.reader

import java.net.URI

/** Metadata only. Never place an API key, document text, or a signed audio URL here. */
enum class CloudVoiceProvider(val id: String, val displayName: String) {
    QWEN("qwen", "阿里云 Qwen TTS"),
    MINIMAX("minimax", "MiniMax TTS");

    companion object {
        fun fromId(id: String): CloudVoiceProvider? = entries.firstOrNull { it.id == id }
    }
}

enum class CloudVoiceContract { QWEN3, QWEN_AUDIO, MINIMAX_V2 }

data class CloudVoiceConfig(
    val provider: CloudVoiceProvider,
    val endpoint: String,
    val model: String,
    val voice: String
) {
    companion object {
        fun defaults(provider: CloudVoiceProvider): CloudVoiceConfig = when (provider) {
            CloudVoiceProvider.QWEN -> CloudVoiceConfig(provider,
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                "qwen3-tts-flash", "Cherry")
            CloudVoiceProvider.MINIMAX -> CloudVoiceConfig(provider,
                "https://api.minimax.cn/v1/t2a_v2", "speech-2.8-hd", "male-qn-qingse")
        }
    }
}

data class CloudVoiceConfigIssue(val field: String, val message: String)

/** A deliberately small, versioned allowlist, not a claim that every provider model is supported. */
object CloudVoiceConfigValidator {
    private const val QWEN3_PATH = "/api/v1/services/aigc/multimodal-generation/generation"
    private const val QWEN_AUDIO_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer"
    private val qwen3Hosts = setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com")
    private val miniMaxHosts = setOf("api.minimax.cn", "api.minimax.io", "api-uw.minimax.io")
    private val workspaceHost = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.(?:cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com")
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    private val qwenAudioModels = setOf("qwen-audio-3.1-tts-flash", "qwen-audio-3.0-tts-plus", "qwen-audio-3.0-tts-flash")
    private val miniMaxModels = setOf("speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
        "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo")

    fun contract(config: CloudVoiceConfig): CloudVoiceContract? = when (config.provider) {
        CloudVoiceProvider.QWEN -> when (config.model) {
            "qwen3-tts-flash" -> CloudVoiceContract.QWEN3
            in qwenAudioModels -> CloudVoiceContract.QWEN_AUDIO
            else -> null
        }
        CloudVoiceProvider.MINIMAX -> if (config.model in miniMaxModels) CloudVoiceContract.MINIMAX_V2 else null
    }

    fun validate(config: CloudVoiceConfig): List<CloudVoiceConfigIssue> = buildList {
        val contract = contract(config)
        if (contract == null) add(CloudVoiceConfigIssue("model", "此模型不在当前已实现的接口列表中。"))
        if (!identifier.matches(config.voice)) add(CloudVoiceConfigIssue("voice", "音色 ID 只能包含字母、数字、点、横线和下划线，最长 128 字符。"))
        val uri = parseHttps(config.endpoint, allowQuery = false)
        val endpointMatches = uri != null && when (contract) {
            CloudVoiceContract.QWEN3 -> uri.host in qwen3Hosts && uri.rawPath == QWEN3_PATH
            CloudVoiceContract.QWEN_AUDIO -> workspaceHost.matches(uri.host) && uri.rawPath == QWEN_AUDIO_PATH
            CloudVoiceContract.MINIMAX_V2 -> uri.host in miniMaxHosts && uri.rawPath == "/v1/t2a_v2"
            null -> false
        }
        if (!endpointMatches) add(CloudVoiceConfigIssue("endpoint", "请使用与模型匹配的官方 HTTPS 完整地址；不允许代理、查询参数或重定向。"))
    }

    internal fun requireValid(config: CloudVoiceConfig): CloudVoiceContract {
        if (validate(config).isNotEmpty()) throw CloudVoiceException(CloudVoiceError.INVALID_CONFIG)
        return contract(config)!!
    }

    /** No normalization: the destination displayed in consent must be the one actually used. */
    internal fun parseHttps(value: String, allowQuery: Boolean): URI? = try {
        val uri = URI(value)
        if (value.length !in 1..8192 || value.any { it <= ' ' || it == '\\' } ||
            uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.host != uri.host.lowercase() ||
            uri.rawUserInfo != null || uri.rawFragment != null || (!allowQuery && uri.rawQuery != null) ||
            uri.port !in setOf(-1, 443) || uri.rawAuthority !in setOf(uri.host, "${uri.host}:443") ||
            uri.rawPath.isNullOrEmpty() || !uri.rawPath.startsWith("/")) null else uri
    } catch (_: Exception) { null }

    /** Only regional Alibaba OSS hosts for Qwen. Never forward the provider Authorization header. */
    internal fun audioDownloadUri(config: CloudVoiceConfig, value: String): URI {
        // OSS bucket domains support TLS natively. Upgrade only the scheme in the provider's
        // historical HTTP response; preserve its signed raw path/query exactly. Never fetch HTTP.
        val candidate = if (value.startsWith("http://")) {
            val original = try { URI(value) } catch (_: Exception) { throw CloudVoiceException(CloudVoiceError.UNSAFE_AUDIO_URL) }
            if (original.port != -1) throw CloudVoiceException(CloudVoiceError.UNSAFE_AUDIO_URL)
            "https" + value.substring(4)
        } else value
        val uri = parseHttps(candidate, allowQuery = true) ?: throw CloudVoiceException(CloudVoiceError.UNSAFE_AUDIO_URL)
        if (config.provider != CloudVoiceProvider.QWEN) throw CloudVoiceException(CloudVoiceError.UNSAFE_AUDIO_URL)
        val apiHost = URI(config.endpoint).host
        val region = if (apiHost == "dashscope-intl.aliyuncs.com" || apiHost.endsWith(".ap-southeast-1.maas.aliyuncs.com"))
            "ap-southeast-1" else "cn-beijing"
        val bucket = Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]\\.oss-${Regex.escape(region)}\\.aliyuncs\\.com")
        if (!bucket.matches(uri.host)) throw CloudVoiceException(CloudVoiceError.UNSAFE_AUDIO_URL)
        return uri
    }
}
