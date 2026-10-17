# Cloud speech contracts and safety boundary

Status checked against official documentation on 2026-10-17. This branch implements native request construction, encrypted key storage, a synchronous HTTPS client, and an explicit consent contract. Development verification uses synthetic fixtures exclusively. No provider API request, real key, book text upload, paid synthesis, or audio-quality evaluation was performed.

## Supported contracts

- Qwen3-TTS: `qwen3-tts-flash`; exact Beijing/Singapore DashScope multimodal-generation endpoints; `input.text`, `input.voice`, `input.language_type`. A successful non-streaming response must finish with `stop` and provide a download URL. [Official API](https://www.alibabacloud.com/help/en/model-studio/qwen-tts-api)
- Qwen-Audio-TTS: `qwen-audio-3.1-tts-flash`, `qwen-audio-3.0-tts-plus`, `qwen-audio-3.0-tts-flash`; workspace-specific Beijing/Singapore `SpeechSynthesizer` endpoints. Requests explicitly ask for WAV at 24 kHz. This is a separate contract; changing only the model on a Qwen3 endpoint is rejected. [Official HTTP API](https://www.alibabacloud.com/help/en/model-studio/qwen-audio-tts-http-api)
- MiniMax: implemented non-streaming `t2a_v2`, with supported model IDs explicitly enumerated in `CloudVoiceConfigValidator`; requests use hexadecimal MP3 output. Both `base_resp.status_code == 0` and completed audio status are required. Endpoint hosts are restricted to `api.minimax.cn`, `api.minimax.io`, and the documented US-West host `api-uw.minimax.io`. [Global API](https://platform.minimax.io/docs/api-reference/speech-t2a-http), [China API](https://platform.minimax.cn/docs/api-reference/speech-t2a-http), [regional endpoints](https://platform.minimax.io/docs/guides/speech-t2a-websocket)

Model and voice IDs are not interchangeable with chat/LLM models. The local allowlist is intentionally narrow. Voice syntax checks do not prove that a voice exists, is accessible, supports the requested language, or is licensed for the user's purpose. The key's region/account permissions also remain unverified until an explicitly authorized real request.

Qwen's current documentation contains an HTTP audio URL example. For an allowlisted OSS bucket in the configured region, this implementation replaces only the `http` scheme with `https` before any request, preserving the raw signed path/query byte-for-byte. Explicit ports, credentials, fragments and other hosts are rejected. It never makes an HTTP request or falls back to HTTP. Alibaba documents native TLS support for OSS bucket domains: [official HTTPS guidance](https://www.alibabacloud.com/help/en/oss/user-guide/access-oss-by-https-protocol). HTTPS audio downloads remain restricted to Alibaba OSS bucket hosts in the same configured region. An undocumented CDN or new provider region therefore fails closed until separately reviewed. Arbitrary custom hosts, proxies, and self-hosted endpoints are not currently supported.

## Native UI integration

- Read non-secret defaults with `CloudVoiceConfig.defaults(provider)` and validate edited metadata through `CloudVoiceConfigValidator.validate(config)`.
- Collect credentials only in a native secure input. Ask explicitly before saving them in this app. `CloudVoiceKeyStore.save(provider, CharArray)` consumes and clears the provided array. Do not retain a separate String copy. The native UI must also clear the EditText after Save, Cancel, and dismissal.
- `status(provider)` returns only `MISSING`, `SAVED`, or `UNAVAILABLE`. It briefly decrypts locally to detect damaged or inaccessible storage. There is no plaintext-key getter or JavaScript bridge method.
- Before every segment/preview, show the provider, exact endpoint, model, voice, complete untruncated text, and that the request may be billed. Saving configuration and a format-only mock are not transmission consent. Only the affirmative button creates `CloudVoiceConsent.approveDisplayedRequest(config, exactText, billingMayApply = true)`.
- Create a new `CloudVoiceCancellation()` for each request. Run `CloudVoiceClient(keys).synthesize(config, exactText, consent, cancellation)` off the main thread. The result has `bytes`, `format`/`extension`, and `mimeType`. MediaPlayer must confirm actual decoding/start/completion; obtaining bytes does not prove speech was heard.
- On Stop, backgrounding, engine switching, new playback, or activity destruction, cancel and discard stale callbacks/results. Clear bytes and delete private playback files when done. Do not carry consent forward to a new segment, retry, provider, or changed text/config.
- Catch `CloudVoiceException` and display `error.error.message`. These are fixed messages. Do not append raw exception messages, provider bodies, signed URLs, keys, request JSON, or document text to diagnostics/logs.

Consent is one-use, expires after five minutes using a monotonic clock, and binds all configuration plus the exact text. It is an application contract, not an OS authorization primitive; future native code must preserve its calling rules. A text cap of 600 UTF-16 code units is intentionally conservative for every provider. Nothing is trimmed or silently truncated by the serializer. The reader must split before displaying each confirmation.

## Storage and transport

- A non-exportable 256-bit AES key is generated in Android Keystore. AES-GCM uses a fresh IV and provider-specific authenticated metadata. Only ciphertext is stored in the app's `noBackupFilesDir`; backup remains disabled by the app manifest. Keystore hardware backing depends on the device and is not claimed here.
- Existing keys stay intact after invalid replacement input. Corrupt/unreadable storage fails closed; re-entry is required. Remove deletes both ciphertext and the matching Keystore entry. Test aliases/files are isolated from production keys.
- The credential callback is internal native code. Plaintext arrays are cleared on exit. The platform HTTP header API requires a temporary String, which cannot be reliably zeroed in JVM memory; it is never persisted, logged, sent to JavaScript, or reused for the audio GET.
- Normal platform TLS/hostname verification stays enabled. Endpoint URLs forbid credentials, fragments, query parameters, nonstandard ports, HTTP, and ambiguous host spellings. Both request and download connections explicitly disable redirects and send `Connection: close`. Redirects, HTTP failures, and provider failures are never automatically retried or sent to another provider.
- Requests have 15-second connection and 30-second idle read timeouts, plus a 120-second request watchdog that disconnects the active connection. Cancellation disconnects active I/O and is checked between stages. Already submitted work may continue at the provider and may still be billed; cancel is not a refund.
- JSON and audio responses have strict size limits. Audio is capped at 8 MiB. Qwen download GETs never receive the API Authorization header. Signed URLs are transient and not logged/persisted. HTTP error bodies are not read; successful HTTP responses still require provider-level success validation.
- Header validation recognizes WAV/MP3 only. It does not establish codec integrity, duration, perceptual quality, or audibility. The native player owns final decode/playback errors.

## Verification

Local JVM tests use only fake HTTP transports and fake `HttpsURLConnection` objects; the fake connection's `connect()` deliberately throws. They exercise:

- Official-host/model/path mapping and malicious endpoint variants
- Exact-text JSON escaping, text/Unicode bounds, consent mismatch and reuse
- Qwen completion/expiry/HTTPS regional URL validation and strict legacy OSS scheme upgrade
- MiniMax failure, incomplete response, typed-field and hex validation
- Missing consent, cancellation, total-deadline expiration, no retry/fallback
- Actual transport configuration, no Authorization on the download, redirects and HTTP errors without error-body reads
- Advertised and actual response-size bounds and cancellation disconnect

`CloudVoiceMock.check(config)` validates only local config and request serialization with a fixed synthetic sentence. It returns `networkRequests = 0`, `producedAudio = false`, and explicitly says keys, account permissions, availability and quality are not verified. Synthetic RIFF/ID3 header fixtures are not real generated speech and are never played.

The three `CloudVoiceKeyStoreDeviceTest` cases cover encrypted at-rest storage, input/reference clearing, re-opening, randomized ciphertext, tamper rejection, provider separation, invalid replacement and removal. They require an Android device/emulator and have not been executed by the standalone JVM run. A Kotlin compile against Android API stubs is not an APK build, Android lint pass, or device test. Native dialogs, Keystore availability, MediaPlayer output, cancellation races on actual Android networking, and Vivo Y300t audio quality need on-device validation.

## IndexTTS2: future self-hosted design only

The upstream project provides model/inference code, rather than a universally interchangeable hosted API. A future adapter should pin the intended IndexTTS-2 release, model and license; deploy a separately authenticated HTTPS service; define a versioned request/response contract and bounded job cancellation; and keep reference voices server-side with the owner's permission. Sending book text or reference audio to that host requires an explicit destination/data/possible-cost confirmation. No model download, server deployment, voice cloning, guessed endpoint, or compatibility claim is included in this milestone. [Official project](https://github.com/index-tts/index-tts)
