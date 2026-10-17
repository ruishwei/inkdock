# Bounded Kokoro callback streaming prototype

Independent copy of the released-buffer source. This prototype preserves the selected float/int8 model, speaker, speed, text normalizers, sentence grouping and JNI pin. It does not switch the user's float model or alter PCM sample values. Device validation is still required before release.

## Why this is different

The baseline receives progressive JNI callbacks but counts their samples and waits for the complete `GeneratedAudio` before starting AudioTrack. An original <=120-unit group can contain multiple sentences. This adds a whole-group completion barrier even when its first PCM already exists.

`StreamingSynthesisCallback` now copies each callback into a bounded ring. The first readable prefix starts an independent AudioTrack output worker while the same model call continues. Callback exceptions are contained and converted to cancellation; no Java exception is intentionally allowed to cross JNI. Empty final output, invalid/nonfinite PCM, excessive samples, or a final sample-count mismatch cause a terminal error without advancing the original request.

`StreamingSpeechPipeline` keeps first-prefix readiness separate from generation EOF. First PCM may start current playback, but cannot release the single generation slot. JNI return releases that slot and immediately starts the next unit's generation, without waiting for current playback drain. Original progress advances only when both generation is complete and all written frames have drained. An empty ring between callbacks waits; it is never EOF.

## Bounds and cancellation

- One leased model and one generation executor; one output executor. Existing conservative original-entry progress/resume semantics remain unchanged.
- Exactly two global reserved/live stream slots, including cancelled producers or claimed players that have not exited. A slot returns its permit only after producer exit and consumer cleanup, or after producer exit plus discard if never claimed.
- Each ring holds at most 192,000 float samples: eight seconds at 24 kHz mono, 768,000 bytes. Two rings total 1,536,000 bytes, plus one 4,096-sample output scratch array and platform AudioTrack buffers.
- Ring capacity is injectable only in the internal constructor for deterministic tests. The production default is eight seconds; two seconds would unnecessarily shorten cross-unit lookahead when a fast producer is backpressured.
- Callback/output aggregate is still capped at 1,440,000 samples (60 seconds) per unit. The ring does not retain the callback array. JNI still creates each callback FloatArray and returns a complete final FloatArray; native `ans` and model tensors also remain. These temporary arrays, native allocations and garbage awaiting collection are outside the ring bound. This is not a hard process-memory quota.
- Full rings block only the producer, with cancellation checks. Stop/voice/queue replacement invalidates the epoch, cancels and wakes all rings, pauses/flushes output and removes queued model work. Old callbacks cannot restore audio or progress.
- Shutdown releases the model on the same generation executor after the active JNI call exits. An unresponsive JNI call cannot be force-killed safely; its lease stays held. The per-unit 120-second generation wall-clock watchdog includes ring backpressure, intentionally preserving a bounded resource timeout rather than claiming a compute-only budget.
- Each outer synthesis unit still owns one AudioTrack; all callback pieces within that unit are written continuously to that track. Cross-unit AudioTrack reuse is not part of this first change.

## PCM parity assumption and checks

The pinned Kokoro implementation applies silence scaling before appending each vector to its final output, then invokes the callback with that same vector. The final accumulated vector is returned without further processing. JNI copies those vectors into callback arrays and separately copies the accumulated final array. Thus callback concatenation matches the final waveform in order, including native-inserted silence. This source contract must be rechecked if the JNI pin changes. JVM ring tests preserve exact float bits, and the device benchmark must also compare actual callback concatenation with final PCM.

Cancellation is different: native may already append the callback vector before seeing return value 0. The resulting partial final audio is discarded, never replayed after callback streaming.

The separate unbackpressured float benchmark on API35 x86_64 (two reported CPU cores, two model threads) observed three callbacks for its 20-character fixture: first PCM at 0.926 seconds, whole generation 3.284 seconds, audio duration 4.363 seconds. Its callback concatenation exactly matched final float32 bits. This confirms a removable 2.358-second whole-group barrier for that fixture, not Y300t speed or this prototype's playback. A 14-character single-sentence fixture produced only one callback, so streaming cannot remove the work before that callback. The selected production thread count remains two.

## Diagnostics and acceptance gates

App diagnostics contain numeric tokens, sizes and timing, never book text:

- `pcm first`: first callback and first ring-readable PCM latency.
- `pcm output-start`: first successful AudioTrack write and play invocation timestamps, explicitly not acoustic first sound.
- `pcm head-progress`: first observed positive playback-head frames, timestamp and whether generation is still active. The probe stops after this one observation or cancellation.
- `pcm generated`: actual post-JNI-return timestamp, callback count/samples, audio duration, generation wall time, producer wait time and ring high-water sample count.
- `pcm drained`: samples written, consumer wait time/count and AudioTrack underrun count. Consumer waiting is not itself a measurement of acoustic silence because the device may still have buffered frames.
- `buffer stream-ready` means a playable prefix, not complete PCM. `nextAudioReady` remains true only after the next unit's generation EOF; `prefixAhead` is reported separately in pipeline diagnostics.

Generation wall time now includes producer backpressure. Subtracting `producerWaitMs` is at most an approximation of active generation time, including JNI copies, frontend work and callbacks; it is not pure ONNX inference time. Use the separate unbackpressured benchmark to compare model throughput. Sustained RTF greater than one still causes underruns with finite buffering. Streaming can remove the whole-group startup barrier; it cannot make slow synthesis faster than realtime.

Before release, require actual float-model multiple callbacks, first write and positive head progress before the matching generation-end timestamp, complete ordered drain, cross-unit lookahead, callback/final PCM equality, background/stop/voice-change cleanup and no stale events. Also require synthetic Android starvation not to cause early done, full-next-ring promotion without deadlock, bounded occupancy and cancellation returning both permits. No current host result proves those Android/JNI outcomes.

## Host validation at prototype freeze

On 2026-10-17, all 158 JVM tests and 40 JavaScript tests passed. Production plus JVM sources compiled with Kotlin indy lambdas enabled, retaining the named JNI callback bridge. Production plus all instrumentation-test sources also passed signature compilation against the local Android API jar; this is not an APK/device run. Five composed streaming concurrency tests additionally passed 100 repeated runs (500 executions).

Four new `KokoroStreamingDeviceTest` cases are prepared for actual-device validation: real float-model progressive playback and queued-next completion, background/screen-off stop, synthetic producer starvation through AudioTrack, and full-next-ring promotion/cancellation. The prototype is not release-validated until those Android runtime gates pass. Host logs are retained in `build/streaming-host/`; the separate instrumentation signature-compile log is `/tmp/inkdock-streaming-device-compile.log`.
