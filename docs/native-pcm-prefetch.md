# Native one-next PCM prefetch experiment

Independent copy of the frozen native experiment. The original native/JNI build directory is not modified. No model download, real model inference, paid call, permission change, KVM access, or Android-device execution was performed for this implementation.

## Architecture and limits

- Preserve exactly one selected float/int8 Kokoro model and the existing shared model-store lease. Model loading, generation and release all use one generation executor. The ONNX session's existing internal two-thread setting is unchanged; there is never a second model to produce lookahead.
- A separate single output executor owns AudioTrack creation, writes, playback-head draining and release. Its synthetic input is a complete PCM unit, so playback does not block the generation executor.
- Shared `TwoSlotSpeechPipeline` owns current plus one next request, with a distinct READY state. `SubsentenceSpeechCursor` splits each original queue entry into <=120 UTF-16 units and preserves original request/block progress. Internal continuation takes precedence over the following original entry. Service.prefetch covers paragraph/entry boundaries too.
- The generation worker reserves from a two-permit `PcmBudget` BEFORE calling JNI. A reservation becomes exactly one accepted 24 kHz mono float32 buffer, without copying. Accepted output must be finite, nonempty and <=1,440,000 samples (60 seconds, 5.76 MB decimal per buffer). The callback also aborts additional generation when returned callback samples exceed that limit, then rejects the result rather than playing a truncated unit.
- The two permits include in-flight generation and canceled-but-still-draining/queued playback. Cancellation cannot prematurely return a playback-owned permit. This prevents rapid stop/restart from accumulating a third accepted/reserved buffer. Maximum accepted float PCM is 11.52 MB decimal across both slots; native model/intermediate tensors, JNI callback copies, AudioTrack buffers and JVM garbage awaiting collection are outside that bound. It is not a hard process-heap limit.
- PCM readiness is marked only after JNI completes and validation passes. It does not require AudioTrack to have been created yet. A prepared next buffer can therefore eliminate generation wait, while creating a new AudioTrack per unit can still introduce a device-dependent handoff gap. This is not a sample-accurate gapless claim.
- Output preloads a small bounded portion and uses nonblocking writes with cancellation checks. Zero-progress writes have a 5-second watchdog. Completion is reported only after playbackHeadPosition has consumed every written frame, then AudioTrack is released. Enqueueing/writing the last frames is not completion.
- The native path does not fall back to another voice/model/system engine after a generation/output error. A failed current or next native unit is terminal and stops the current original request without advancing its saved position. The next failure may interrupt the current unit; retry conservatively starts that original entry again.
- File-based system TTS prefetch from the separately reviewed experiment is also integrated for external system engines. It retains its two private-file cap, explicit direct-TTS fallback and no-backup cleanup contract. See bounded-speech-prefetch.md for its design history; final combined validation is recorded below.

## Cancellation and lifecycle

- Stop, explicit chapter/queue replacement, voice/rate changes and shutdown invalidate the generation first. Both the core's monotonic unit tokens and the adapter's epoch checks reject stale generation/playback callbacks.
- Cancel pauses/flushes the registered AudioTrack under a small lifecycle lock, removes queued generation work, disposes unclaimed next PCM immediately and lets claimed output jobs run their finally cleanup. The output executor is shut down gracefully, never with shutdownNow, because dropped claimed tasks would leak permits.
- A model generation already inside JNI is cooperatively canceled by its callback. The code cannot force-kill an unresponsive native call or reclaim its model underneath it. The 120-second per-unit watchdog stops the reader and invalidates results; model release remains queued behind the active generation call. If that call never returns, its lease remains held rather than risking use-after-free or a second model.
- Model release occurs on the same generation executor after in-flight work exits. Existing float/int8 selection, store verification, maintenance-mode reload and native availability safeguards remain in place.
- Pause keeps alpha.6's conservative original-entry progress contract: clear current/next PCM, then resume that original entry from its beginning. It does not claim word-accurate or internal-subunit resume.
- No PCM files are written by the native path. Diagnostics contain only opaque numeric unit tokens, counts, fixed reasons and timings; no document/paragraph text or title is logged.

## Metrics and what they prove

- queued / queued-next prove scheduling only.
- synth-start / synth-done identify completion separately from playback. Native `pcm generated` adds genMs, durationMs and held permits. genMs starts after a permit is acquired and measures JNI generation/validation handling, excluding budget wait.
- ready means validated PCM exists. readyLeadMs >0 proves it existed before prior playback completion.
- boundary gapMs measures the controller's enqueue handoff. play-start callbackGapMs includes output-worker/AudioTrack setup until the start callback arrives. Neither is physical/acoustic silence.
- play-done follows playback-head drain; only this event advances the internal/original cursor.
- Snapshots expose bufferingMode, bufferedSlots, nextAudioReady and retainedAudioBuffers, with coalesced state updates while a queue remains present. Canceled worker cleanup also reports the settled retained count; after a terminal stop, a fresh snapshot getter reads the live count. The retained count can temporarily remain nonzero just after Stop while a claimed output task exits; it never grants new generation capacity early.
- Sustained generation RTF >1 cannot be hidden indefinitely by a finite one-next buffer. Real-phone cold/warm/thermal measurements are still required; this change makes no speed or voice-quality promise.

## Verification gate

Final local validation (2026-10-17):
- All 113 host JUnit tests passed, including 55 added helper/contract tests relative to the frozen native base.
- All 40 JavaScript tests passed; all asset JavaScript passes node --check. Native-base JS is unchanged.
- All production Kotlin plus test sources compile against the supplied Android API classes. The Java test-engine fixture and all Android instrumentation Kotlin sources compile too.
- The five deterministic synthetic concurrency tests passed 200 consecutive runs (1,000 tests total). They exercise the production scheduler/cursor/PCM budget with fake JNI generation and fake output drain on independent JVM workers. They do not instantiate the Android adapter or prove device overlap.
- Independent concurrency review found a deferred-service boundary failure: next-unit failure after original completion could be dropped and retried. The cursor now latches that failure until promotion; two focused cursor tests and a composed worker regression cover it. The fix passed re-review.
- No SDK/Gradle/lint/APK/JNI build or Android-device execution was performed in this directory. The publisher's separate original-native build does not validate this delta.

Logs and the source manifest are in build/all-junit.log, build/node-test.log, build/host-compile.log, build/java-fixture-compile.log, build/device-compile.log and build/changed-files.json.

New pure-JVM coverage includes:
- two-slot READY vs queued state, ready-before-needed and slow-synthesis gaps;
- terminal native error versus explicit file fallback;
- two PCM reservations, cancellation while waiting, ownership transfer, claimed-discard deferral, invalid/over-limit/nonfinite PCM and repeated/concurrent release races;
- complete subunit preservation, cross-original lookahead, original completion only after the final played unit, reentrant completion, stop/error stale-callback rejection;
- synthetic independent generation/output workers with blocked playback and ordered model cleanup (contract tests only).

The existing copied Android platform regressions and four new real-system-TTS-file scenarios compile but are not run here. Before release, run the final combined code on Android: screen off/Home, notification pause/resume/stop, focus loss, headphone removal, rapid stop/restart and chapter/voice/rate changes during generation/output, float/int8 variant maintenance/reload, JNI stalled callback, invalid/over-limit PCM, actual next READY-before-current-finish, AudioTrack drain, audible routing/gaps, and memory/thermal endurance. Synthetic JVM success cannot prove those platform/native behaviors.
