package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Every test injects in-memory fake HTTP. No DNS, network, provider accounts, or real keys. */
class CloudVoiceClientTest {
    private val qwen = CloudVoiceConfig.defaults(CloudVoiceProvider.QWEN)
    private val mini = CloudVoiceConfig.defaults(CloudVoiceProvider.MINIMAX)
    private val text = "合成格式测试"
    private val response = """{"base_resp":{"status_code":0},"data":{"status":2,"audio":"49443304000000000000"},"extra_info":{"audio_format":"mp3"}}"""
    private val qwenResponse = """{"output":{"finish_reason":"stop","audio":{"url":"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/synthetic.wav?Signature=synthetic","expires_at":200}},"request_id":"synthetic"}"""
    private val keys = object : CloudVoiceCredentialSource {
        override fun <T> withKey(provider: CloudVoiceProvider, block: (CharArray) -> T): T {
            val synthetic = "synthetic-test-key".toCharArray()
            return try { block(synthetic) } finally { synthetic.fill('\u0000') }
        }
    }

    private fun approval(config: CloudVoiceConfig) = CloudVoiceConsent.approveDisplayedRequest(config, text, true)
    private fun fails(expected: CloudVoiceError, action: () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (error: CloudVoiceException) { assertEquals(expected, error.error) }
    }

    private class FakeHttp(var json: String) : CloudVoiceHttpTransport {
        var posts = 0; var gets = 0
        var beforeReturn: (() -> Unit)? = null
        override fun postJson(endpoint: URI, bearerKey: CharArray, body: ByteArray, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse {
            posts++; beforeReturn?.invoke()
            return CloudVoiceHttpResponse("application/json; charset=utf-8", json.toByteArray())
        }
        override fun getAudio(endpoint: URI, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse {
            gets++
            val header = ByteArray(44) // Artificial signature fixture, never sent to a player.
            "RIFF".toByteArray().copyInto(header); "WAVE".toByteArray().copyInto(header, 8)
            return CloudVoiceHttpResponse("audio/wav", header)
        }
    }

    @Test fun missingMismatchedAndReusedConsentNeverStartsAnotherRequest() {
        val http = FakeHttp(response); val client = CloudVoiceClient(keys, http)
        fails(CloudVoiceError.CONSENT_REQUIRED) { client.synthesize(mini, text, null, CloudVoiceCancellation()) }
        fails(CloudVoiceError.CONSENT_REQUIRED) { client.synthesize(mini, text + "x", approval(mini), CloudVoiceCancellation()) }
        assertEquals(0, http.posts)
        val consent = approval(mini)
        assertEquals("mp3", client.synthesize(mini, text, consent, CloudVoiceCancellation()).format)
        fails(CloudVoiceError.CONSENT_REQUIRED) { client.synthesize(mini, text, consent, CloudVoiceCancellation()) }
        assertEquals(1, http.posts); assertEquals(0, http.gets)
    }

    @Test fun cancellationBeforeAndAfterPostNeverRetriesOrDownloads() {
        val http = FakeHttp(qwenResponse); val client = CloudVoiceClient(keys, http, { 100 })
        val before = CloudVoiceCancellation().apply { cancel() }
        fails(CloudVoiceError.CANCELLED) { client.synthesize(qwen, text, approval(qwen), before) }
        assertEquals(0, http.posts)
        val during = CloudVoiceCancellation(); http.beforeReturn = { during.cancel() }
        fails(CloudVoiceError.CANCELLED) { client.synthesize(qwen, text, approval(qwen), during) }
        assertEquals(1, http.posts); assertEquals(0, http.gets)
    }

    @Test fun totalDeadlineRejectsLateResultWithoutRetry() {
        val http = FakeHttp(qwenResponse).apply { beforeReturn = { Thread.sleep(100) } }
        val client = CloudVoiceClient(keys, http, { 100 }, 10)
        fails(CloudVoiceError.TIMEOUT) { client.synthesize(qwen, text, approval(qwen), CloudVoiceCancellation()) }
        assertEquals(1, http.posts); assertEquals(0, http.gets)
    }

    @Test fun providerFailureBodyNeverLeaksIntoExceptionAndNoFallbackRuns() {
        val http = FakeHttp("""{"base_resp":{"status_code":1004,"status_msg":"synthetic secret and private document text"},"data":null}""")
        fails(CloudVoiceError.PROVIDER_REJECTED) { CloudVoiceClient(keys, http).synthesize(mini, text, approval(mini), CloudVoiceCancellation()) }
        assertEquals(1, http.posts); assertEquals(0, http.gets)
        assertFalse(CloudVoiceError.PROVIDER_REJECTED.message.contains("synthetic"))
    }

    @Test fun malformedTypedResponseIsRejectedRatherThanCoerced() {
        val http = FakeHttp(response.replace("\"status_code\":0", "\"status_code\":\"0\""))
        fails(CloudVoiceError.INVALID_RESPONSE) { CloudVoiceClient(keys, http).synthesize(mini, text, approval(mini), CloudVoiceCancellation()) }
        http.json = "{bad json"
        fails(CloudVoiceError.INVALID_RESPONSE) { CloudVoiceClient(keys, http).synthesize(mini, text, approval(mini), CloudVoiceCancellation()) }
    }

    @Test fun unsafeQwenUrlIsRejectedBeforeDownload() {
        val http = FakeHttp(qwenResponse.replace("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com", "http://evil.example"))
        fails(CloudVoiceError.UNSAFE_AUDIO_URL) { CloudVoiceClient(keys, http, { 100 }).synthesize(qwen, text, approval(qwen), CloudVoiceCancellation()) }
        assertEquals(1, http.posts); assertEquals(0, http.gets)
    }

    @Test fun actualTransportSetsTlsRequestPolicyAndNeverAddsBearerToDownload() {
        val posted = FakeConnection(URI(qwen.endpoint).toURL(), qwenResponse.replace("https://dashscope-result-bj", "http://dashscope-result-bj").toByteArray(), "application/json")
        val header = ByteArray(44).also { "RIFF".toByteArray().copyInto(it); "WAVE".toByteArray().copyInto(it, 8) }
        val downloaded = FakeConnection(URI("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/synthetic.wav").toURL(), header, "audio/wav")
        var calls = 0
        val transport = CloudVoiceHttpsTransport { uri ->
            assertEquals("https", uri.scheme)
            if (calls++ == 0) posted else downloaded
        }
        val result = CloudVoiceClient(keys, transport, { 100 }).synthesize(qwen, text, approval(qwen), CloudVoiceCancellation())
        assertEquals(44, result.bytes.size); assertEquals(2, calls)
        assertFalse(posted.instanceFollowRedirects); assertFalse(downloaded.instanceFollowRedirects)
        assertEquals("POST", posted.requestMethod); assertEquals("GET", downloaded.requestMethod)
        assertEquals("Bearer synthetic-test-key", posted.getRequestProperty("Authorization"))
        assertNull(downloaded.getRequestProperty("Authorization")); assertEquals(0, downloaded.sent.size())
        assertEquals(CloudVoiceRequests.serialize(qwen, text), posted.sent.toString("UTF-8"))
        assertFalse(posted.useCaches); assertEquals(15_000, posted.connectTimeout); assertEquals(30_000, posted.readTimeout)
        assertEquals("close", posted.getRequestProperty("Connection")); assertEquals("close", downloaded.getRequestProperty("Connection"))
        assertTrue(posted.disconnected); assertTrue(downloaded.disconnected)
    }

    @Test fun redirectsAndHttpErrorsDoNotFollowLocationOrReadErrorBodies() {
        for ((status, error) in listOf(301 to CloudVoiceError.REDIRECT, 307 to CloudVoiceError.REDIRECT, 401 to CloudVoiceError.AUTHENTICATION,
            403 to CloudVoiceError.AUTHENTICATION, 429 to CloudVoiceError.RATE_LIMIT, 500 to CloudVoiceError.PROVIDER_REJECTED)) {
            val connection = FakeConnection(URI(mini.endpoint).toURL(), "must not read".toByteArray(), "text/plain", status)
            var calls = 0
            val transport = CloudVoiceHttpsTransport { calls++; connection }
            fails(error) { CloudVoiceClient(keys, transport).synthesize(mini, text, approval(mini), CloudVoiceCancellation()) }
            assertEquals(1, calls); assertEquals(0, connection.reads); assertEquals(0, connection.errorReads)
            assertFalse(connection.instanceFollowRedirects); assertTrue(connection.disconnected)
        }
    }

    @Test fun advertisedOrActualOversizedBodyIsRejectedAndCancelDisconnects() {
        val advertised = FakeConnection(URI(mini.endpoint).toURL(), response.toByteArray(), "application/json").apply { advertisedLength = CloudVoiceClient.MAX_JSON_BYTES.toLong() + 1 }
        fails(CloudVoiceError.TOO_LARGE) {
            CloudVoiceClient(keys, CloudVoiceHttpsTransport { advertised }).synthesize(mini, text, approval(mini), CloudVoiceCancellation())
        }
        assertEquals(0, advertised.reads)
        val oversizedAudio = FakeConnection(URI(qwen.endpoint).toURL(), ByteArray(CloudVoiceAudioValidator.MAX_AUDIO_BYTES + 1), "audio/wav")
        fails(CloudVoiceError.TOO_LARGE) {
            CloudVoiceHttpsTransport { oversizedAudio }.getAudio(URI("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/test.wav"), CloudVoiceCancellation())
        }
        val cancellation = CloudVoiceCancellation()
        val during = FakeConnection(URI(mini.endpoint).toURL(), response.toByteArray(), "application/json").apply { onRead = { cancellation.cancel() } }
        fails(CloudVoiceError.CANCELLED) { CloudVoiceClient(keys, CloudVoiceHttpsTransport { during }).synthesize(mini, text, approval(mini), cancellation) }
        assertTrue(during.disconnected)
    }

    private class FakeConnection(url: URL, private val data: ByteArray, private val type: String, private val status: Int = 200) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false; var reads = 0; var errorReads = 0
        var advertisedLength = -1L
        var onRead: (() -> Unit)? = null
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() { error("Fake must not connect") }
        override fun getCipherSuite() = "FAKE_TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode() = status
        override fun getContentType() = type
        override fun getContentEncoding(): String? = null
        override fun getContentLengthLong() = advertisedLength
        override fun getInputStream(): InputStream {
            reads++
            val stream = ByteArrayInputStream(data)
            return object : InputStream() {
                override fun read(): Int { onRead?.invoke(); return stream.read() }
                override fun read(buffer: ByteArray, off: Int, len: Int): Int { onRead?.invoke(); return stream.read(buffer, off, len) }
            }
        }
        override fun getErrorStream(): InputStream { errorReads++; error("Provider error body must not be read") }
        override fun getHeaderField(name: String?): String? = if (name == "Location") "https://evil.example/leak" else null
    }
}
