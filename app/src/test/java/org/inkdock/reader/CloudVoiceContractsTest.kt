package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class CloudVoiceContractsTest {
    private val qwen = CloudVoiceConfig.defaults(CloudVoiceProvider.QWEN)
    private val mini = CloudVoiceConfig.defaults(CloudVoiceProvider.MINIMAX)

    private fun fails(expected: CloudVoiceError, action: () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (error: CloudVoiceException) { assertEquals(expected, error.error) }
    }

    @Test fun supportedDefaultsAndExplicitQwenContracts() {
        assertTrue(CloudVoiceConfigValidator.validate(qwen).isEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(mini).isEmpty())
        assertEquals(CloudVoiceContract.QWEN3, CloudVoiceConfigValidator.contract(qwen))
        val newer = qwen.copy(model = "qwen-audio-3.1-tts-flash", voice = "longanhuan_v3.1",
            endpoint = "https://workspace-123.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer")
        assertTrue(CloudVoiceConfigValidator.validate(newer).isEmpty())
        assertEquals(CloudVoiceContract.QWEN_AUDIO, CloudVoiceConfigValidator.contract(newer))
        assertTrue(CloudVoiceConfigValidator.validate(newer.copy(endpoint = qwen.endpoint)).isNotEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(qwen.copy(endpoint = newer.endpoint)).isNotEmpty())
        assertTrue(CloudVoiceRequests.serialize(newer, "测试").contains("\"format\":\"wav\""))
    }

    @Test fun rejectsProxyAndUrlConfusionRatherThanLeakingBearerKey() {
        val bad = listOf("http://dashscope.aliyuncs.com", "https://evil.example", "https://dashscope.aliyuncs.com.evil.example",
            "https://dashscope.aliyuncs.com@evil.example", "https://user:secret@dashscope.aliyuncs.com",
            "https://DASHSCOPE.aliyuncs.com", "https://dashscope.aliyuncs.com.", "https://dashscope.aliyuncs.com:444",
            "https://127.0.0.1", "https://[::1]")
        val path = "/api/v1/services/aigc/multimodal-generation/generation"
        bad.forEach { assertTrue(it, CloudVoiceConfigValidator.validate(qwen.copy(endpoint = it + path)).isNotEmpty()) }
        listOf("?api_key=synthetic", "#fragment", "/", "/../generation", "\n").forEach {
            assertTrue(CloudVoiceConfigValidator.validate(qwen.copy(endpoint = qwen.endpoint + it)).isNotEmpty())
        }
        assertTrue(CloudVoiceConfigValidator.validate(qwen.copy(endpoint = qwen.endpoint.replace("/generation", "/%67eneration"))).isNotEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(mini.copy(endpoint = qwen.endpoint)).isNotEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(mini.copy(endpoint = "https://api.minimax.io/v1/t2a_v2")).isEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(mini.copy(endpoint = "https://api-uw.minimax.io/v1/t2a_v2")).isEmpty())
    }

    @Test fun rejectsUnsupportedModelsAndUnsafeVoiceIds() {
        assertTrue(CloudVoiceConfigValidator.validate(qwen.copy(model = "qwen-plus")).isNotEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(qwen.copy(model = "qwen3-tts-flash-realtime")).isNotEmpty())
        assertTrue(CloudVoiceConfigValidator.validate(mini.copy(model = "qwen3-tts-flash")).isNotEmpty())
        listOf("", "a b", "a\nAuthorization", "a\"b", "a/b", "a".repeat(129)).forEach {
            assertTrue(CloudVoiceConfigValidator.validate(mini.copy(voice = it)).isNotEmpty())
        }
    }

    @Test fun serializationPreservesExactTextAndEscapesJson() {
        val text = " 引号\"\\\n\t中文😀 "
        val request = CloudVoiceRequests.serialize(qwen, text)
        assertTrue(request.contains("\\\"\\\\\\n\\t中文😀 "))
        assertTrue(request.contains("\"input\""))
        assertFalse(request.contains("Authorization"))
        val miniRequest = CloudVoiceRequests.serialize(mini, text)
        assertTrue(miniRequest.contains("\"output_format\":\"hex\""))
        assertTrue(miniRequest.contains("\"stream\":false"))
        assertTrue(miniRequest.contains("\"voice_id\":\"male-qn-qingse\""))
    }

    @Test fun textBoundsDoNotSilentlyTrimTruncateOrReplaceInvalidUnicode() {
        CloudVoiceRequests.serialize(qwen, "字".repeat(600))
        listOf("", " \n\t", "字".repeat(601), "a\u0000b", "\uD800", "\uDC00").forEach {
            fails(CloudVoiceError.INVALID_TEXT) { CloudVoiceRequests.serialize(qwen, it) }
        }
        CloudVoiceRequests.serialize(qwen, "😀".repeat(300))
    }

    @Test fun consentIsBoundToExactRequestSingleUseAndBillingDisclosure() {
        fails(CloudVoiceError.CONSENT_REQUIRED) { CloudVoiceConsent.approveDisplayedRequest(qwen, "测试", false) }
        val approved = CloudVoiceConsent.approveDisplayedRequest(qwen, "测试", true)
        approved.consume(qwen, "测试")
        fails(CloudVoiceError.CONSENT_REQUIRED) { approved.consume(qwen, "测试") }
        val changedText = CloudVoiceConsent.approveDisplayedRequest(qwen, "测试", true)
        fails(CloudVoiceError.CONSENT_REQUIRED) { changedText.consume(qwen, "测试 ") }
        fails(CloudVoiceError.CONSENT_REQUIRED) { changedText.consume(qwen, "测试") }
        val changedVoice = CloudVoiceConsent.approveDisplayedRequest(qwen, "测试", true)
        fails(CloudVoiceError.CONSENT_REQUIRED) { changedVoice.consume(qwen.copy(voice = "OtherVoice"), "测试") }
        val changedDestination = CloudVoiceConsent.approveDisplayedRequest(qwen, "测试", true)
        fails(CloudVoiceError.CONSENT_REQUIRED) { changedDestination.consume(qwen.copy(endpoint = qwen.endpoint.replace("dashscope.", "dashscope-intl.")), "测试") }
        assertFalse(approved.toString().contains("测试"))
    }

    @Test fun qwenResponseRequiresFinishedSynthesisAndSecureRegionalOss() {
        val url = "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/synthetic.wav?Signature=synthetic"
        fun response(value: String = url, finish: String? = "stop", code: String? = null, expires: Long? = 200L) =
            QwenVoiceResponse(code, 200, finish, value, expires)
        assertEquals(url, CloudVoiceResponses.qwenDownload(qwen, response(), 100).toString())
        fails(CloudVoiceError.INVALID_RESPONSE) { CloudVoiceResponses.qwenDownload(qwen, response(finish = null), 100) }
        fails(CloudVoiceError.PROVIDER_REJECTED) { CloudVoiceResponses.qwenDownload(qwen, response(code = "raw provider text must never appear"), 100) }
        fails(CloudVoiceError.EXPIRED_AUDIO) { CloudVoiceResponses.qwenDownload(qwen, response(expires = 100), 100) }
        listOf(url.replace("cn-beijing", "ap-southeast-1"), "https://evil.example/audio.wav",
            "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com.evil.example/audio.wav", "https://user:secret@dashscope-result-bj.oss-cn-beijing.aliyuncs.com/audio.wav").forEach {
            fails(CloudVoiceError.UNSAFE_AUDIO_URL) { CloudVoiceResponses.qwenDownload(qwen, response(value = it), 100) }
        }
    }

    @Test fun qwenLegacyOssSchemeUpgradePreservesSignedBytesAndNeverPermitsHttpDestination() {
        val raw = "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a%2Fb/%E4%B8%AD.wav?Signature=a%2Fb%2Bc%3D&Expires=200"
        val result = CloudVoiceConfigValidator.audioDownloadUri(qwen, raw)
        assertEquals("https" + raw.substring(4), result.toString())
        assertEquals("https", result.scheme)
        listOf(raw.replace(".com/", ".com:80/"), raw.replace(".com/", ".com:443/"),
            raw.replace("http://", "http://user:secret@"), raw + "#fragment",
            raw.replace("aliyuncs.com", "aliyuncs.com.evil.example"), raw.replace("cn-beijing", "ap-southeast-1"),
            raw.replace("http:", "HTTP:"), "http://evil.example/audio.wav").forEach {
            fails(CloudVoiceError.UNSAFE_AUDIO_URL) { CloudVoiceConfigValidator.audioDownloadUri(qwen, it) }
        }
    }

    @Test fun minimaxErrorsAndIncompleteOrMalformedHexCannotBecomeAudio() {
        val syntheticHeader = "49443304000000000000" // Header fixture only; not playable synthesized audio.
        assertEquals(10, CloudVoiceResponses.miniMaxAudio(MiniMaxVoiceResponse(0, 2, syntheticHeader, "mp3")).size)
        fails(CloudVoiceError.PROVIDER_REJECTED) { CloudVoiceResponses.miniMaxAudio(MiniMaxVoiceResponse(1004, 2, syntheticHeader, "mp3")) }
        listOf(MiniMaxVoiceResponse(null, 2, syntheticHeader, "mp3"), MiniMaxVoiceResponse(0, 1, syntheticHeader, "mp3"),
            MiniMaxVoiceResponse(0, 2, null, "mp3"), MiniMaxVoiceResponse(0, 2, syntheticHeader, "wav")).forEach {
            fails(CloudVoiceError.INVALID_RESPONSE) { CloudVoiceResponses.miniMaxAudio(it) }
        }
        listOf("", "f", "zz", "1 00").forEach { fails(CloudVoiceError.INVALID_RESPONSE) { CloudVoiceAudioValidator.decodeHex(it) } }
        fails(CloudVoiceError.INVALID_AUDIO) { CloudVoiceResponses.miniMaxAudio(MiniMaxVoiceResponse(0, 2, "00000000", "mp3")) }
    }

    @Test fun mockMakesNoAudioOrConnectivityClaims() {
        val result = CloudVoiceMock.check(qwen)
        assertTrue(result.passed); assertEquals(0, result.networkRequests); assertFalse(result.producedAudio)
        assertTrue(result.message.contains("没有联网"))
        assertFalse(CloudVoiceMock.check(qwen.copy(model = "invalid")).passed)
    }
}
