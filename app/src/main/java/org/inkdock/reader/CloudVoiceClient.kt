package org.inkdock.reader

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** New token per request. Cancellation disconnects an in-flight connection and never triggers retry. */
class CloudVoiceCancellation {
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val active = AtomicReference<HttpsURLConnection?>(null)

    fun cancel() { cancelled.set(true); active.getAndSet(null)?.disconnect() }

    internal fun start(timeoutMs: Long): AutoCloseable {
        if (!started.compareAndSet(false, true)) throw CloudVoiceException(CloudVoiceError.CANCELLED)
        check()
        val timer = watchdog.schedule({ timedOut.set(true); active.getAndSet(null)?.disconnect() }, timeoutMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { timer.cancel(false); active.getAndSet(null)?.disconnect() }
    }

    internal fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CloudVoiceException(CloudVoiceError.CANCELLED)
        if (timedOut.get()) throw CloudVoiceException(CloudVoiceError.TIMEOUT)
    }

    internal fun attach(connection: HttpsURLConnection) {
        check()
        if (!active.compareAndSet(null, connection)) throw CloudVoiceException(CloudVoiceError.NETWORK)
        try { check() } catch (error: CloudVoiceException) { active.compareAndSet(connection, null); connection.disconnect(); throw error }
    }

    internal fun detach(connection: HttpsURLConnection) { active.compareAndSet(connection, null) }

    companion object {
        private val watchdog = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "cloud-voice-timeout").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}

/** Owned by the caller. Delete any temporary playback file and clear bytes after use. */
class CloudVoiceAudio(val bytes: ByteArray, val format: String) {
    val extension: String get() = format
    val mimeType: String get() = if (format == "wav") "audio/wav" else "audio/mpeg"
}

internal class CloudVoiceHttpResponse(val contentType: String?, val body: ByteArray)
internal interface CloudVoiceHttpTransport {
    fun postJson(endpoint: URI, bearerKey: CharArray, body: ByteArray, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse
    fun getAudio(endpoint: URI, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse
}

/** Native synchronous client. Run off the main thread. It has no JavaScript bridge entry points.
 * No invocation without single-use consent; there is no automatic retry or provider fallback.
 * Native callers must keep the consent dialog's exact text/config unchanged through completion.
 */
internal class CloudVoiceClient(
    private val keys: CloudVoiceCredentialSource,
    private val transport: CloudVoiceHttpTransport,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val totalTimeoutMs: Long = 120_000
) {
    constructor(keys: CloudVoiceKeyStore) : this(keys, CloudVoiceHttpsTransport())

    fun synthesize(config: CloudVoiceConfig, text: String, consent: CloudVoiceConsent?, cancellation: CloudVoiceCancellation): CloudVoiceAudio {
        CloudVoiceConfigValidator.requireValid(config)
        CloudVoiceRequests.validateText(text)
        if (consent == null) throw CloudVoiceException(CloudVoiceError.CONSENT_REQUIRED)
        consent.consume(config, text)
        val request = CloudVoiceRequests.serialize(config, text).toByteArray(Charsets.UTF_8)
        try {
            cancellation.start(totalTimeoutMs).use {
                cancellation.check()
                val response = keys.withKey(config.provider) { key ->
                    cancellation.check()
                    transport.postJson(URI(config.endpoint), key, request, cancellation)
                }
                cancellation.check()
                val json = try { requireJson(response); JSONObject(response.body.toString(Charsets.UTF_8)) }
                    catch (error: CloudVoiceException) { throw error }
                    catch (_: Exception) { throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE) }
                finally { response.body.fill(0) }
                val bytes = when (config.provider) {
                    CloudVoiceProvider.QWEN -> {
                        val output = json.optJSONObject("output")
                        val audio = output?.optJSONObject("audio")
                        val url = CloudVoiceResponses.qwenDownload(config, QwenVoiceResponse(
                            optionalString(json, "code"), optionalInt(json, "status_code"),
                            output?.let { optionalString(it, "finish_reason") }, audio?.let { optionalString(it, "url") },
                            audio?.let { optionalLong(it, "expires_at") }), nowSeconds())
                        cancellation.check()
                        // No key is accessible here. Download gets no Authorization or request-body headers.
                        val downloaded = transport.getAudio(url, cancellation)
                        try { requireAudioType(downloaded.contentType); downloaded.body }
                        catch (error: Exception) { downloaded.body.fill(0); throw error }
                    }
                    CloudVoiceProvider.MINIMAX -> {
                        val base = json.optJSONObject("base_resp")
                        val data = json.optJSONObject("data")
                        val info = json.optJSONObject("extra_info")
                        CloudVoiceResponses.miniMaxAudio(MiniMaxVoiceResponse(
                            base?.let { optionalInt(it, "status_code") }, data?.let { optionalInt(it, "status") },
                            data?.let { optionalString(it, "audio") }, info?.let { optionalString(it, "audio_format") }))
                    }
                }
                try {
                    cancellation.check()
                    val format = CloudVoiceAudioValidator.format(bytes)
                    if (config.provider == CloudVoiceProvider.QWEN && format != "wav") throw CloudVoiceException(CloudVoiceError.INVALID_AUDIO)
                    return CloudVoiceAudio(bytes, format)
                } catch (error: Exception) { bytes.fill(0); throw error }
            }
        } catch (error: CloudVoiceException) { throw error }
        catch (_: SocketTimeoutException) { cancellation.check(); throw CloudVoiceException(CloudVoiceError.TIMEOUT) }
        catch (_: SSLException) { cancellation.check(); throw CloudVoiceException(CloudVoiceError.TLS) }
        catch (_: Exception) { cancellation.check(); throw CloudVoiceException(CloudVoiceError.NETWORK) }
        finally { request.fill(0) }
    }

    private fun requireJson(response: CloudVoiceHttpResponse) {
        if (response.body.size > MAX_JSON_BYTES) throw CloudVoiceException(CloudVoiceError.TOO_LARGE)
        if (response.body.isEmpty() || response.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json")
            throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
    }

    private fun requireAudioType(type: String?) {
        if (type?.substringBefore(';')?.trim()?.lowercase() !in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "application/octet-stream"))
            throw CloudVoiceException(CloudVoiceError.INVALID_AUDIO)
    }

    private fun optionalString(json: JSONObject, name: String): String? {
        val value = json.opt(name)
        if (value == null || value == JSONObject.NULL) return null
        return value as? String ?: throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
    }

    private fun optionalLong(json: JSONObject, name: String): Long? {
        val value = json.opt(name)
        if (value == null || value == JSONObject.NULL) return null
        if (value !is Number) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        val number = value.toDouble()
        if (!number.isFinite() || number != value.toLong().toDouble()) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        return value.toLong()
    }

    private fun optionalInt(json: JSONObject, name: String): Int? = optionalLong(json, name)?.let {
        if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
        it.toInt()
    }

    companion object { internal const val MAX_JSON_BYTES = CloudVoiceAudioValidator.MAX_AUDIO_BYTES * 2 + 65536 }
}

/** Platform TLS validation is unchanged. No trust-all verifier, global state changes, or logging. */
internal class CloudVoiceHttpsTransport(
    private val openConnection: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }
) : CloudVoiceHttpTransport {
    override fun postJson(endpoint: URI, bearerKey: CharArray, body: ByteArray, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse {
        if (bearerKey.isEmpty() || bearerKey.size > 8192 || bearerKey.any { it.code !in 33..126 }) throw CloudVoiceException(CloudVoiceError.KEY_INVALID)
        return exchange(endpoint, body, bearerKey, CloudVoiceClient.MAX_JSON_BYTES, cancellation)
    }

    override fun getAudio(endpoint: URI, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse =
        exchange(endpoint, null, null, CloudVoiceAudioValidator.MAX_AUDIO_BYTES, cancellation)

    private fun exchange(uri: URI, body: ByteArray?, key: CharArray?, maxBytes: Int, cancellation: CloudVoiceCancellation): CloudVoiceHttpResponse {
        if (CloudVoiceConfigValidator.parseHttps(uri.toASCIIString(), allowQuery = body == null) == null)
            throw CloudVoiceException(CloudVoiceError.INVALID_CONFIG)
        cancellation.check()
        val connection = openConnection(uri)
        try {
            cancellation.attach(connection)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Connection", "close")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                // The platform header API needs a String. It is never logged/persisted/returned.
                connection.setRequestProperty("Authorization", "Bearer ${String(key!!)}")
                cancellation.check()
                connection.outputStream.use { out -> out.write(body); out.flush() }
            }
            cancellation.check()
            val status = connection.responseCode
            when {
                status in 300..399 -> throw CloudVoiceException(CloudVoiceError.REDIRECT)
                status == 401 || status == 403 -> throw CloudVoiceException(CloudVoiceError.AUTHENTICATION)
                status == 429 || status == 402 -> throw CloudVoiceException(CloudVoiceError.RATE_LIMIT)
                status != 200 -> throw CloudVoiceException(CloudVoiceError.PROVIDER_REJECTED)
            }
            // Never read provider error bodies. Headers containing Location or cookies are ignored.
            if (connection.contentEncoding?.lowercase() !in setOf(null, "identity")) throw CloudVoiceException(CloudVoiceError.INVALID_RESPONSE)
            if (connection.contentLengthLong > maxBytes) throw CloudVoiceException(CloudVoiceError.TOO_LARGE)
            val bytes = connection.inputStream.use { boundedRead(it, maxBytes, cancellation) }
            try { cancellation.check(); return CloudVoiceHttpResponse(connection.contentType, bytes) }
            catch (error: Exception) { bytes.fill(0); throw error }
        } finally { cancellation.detach(connection); connection.disconnect() }
    }

    private fun boundedRead(input: InputStream, maxBytes: Int, cancellation: CloudVoiceCancellation): ByteArray {
        val out = object : ByteArrayOutputStream(minOf(maxBytes, 8192)) {
            override fun close() { buf.fill(0); reset(); super.close() }
        }
        val buffer = ByteArray(8192)
        try {
            while (true) {
                cancellation.check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > maxBytes - out.size()) throw CloudVoiceException(CloudVoiceError.TOO_LARGE)
                out.write(buffer, 0, count)
            }
            cancellation.check()
            return out.toByteArray()
        } finally { buffer.fill(0); out.close() }
    }
}
