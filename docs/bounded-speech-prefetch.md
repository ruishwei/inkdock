# Bounded local speech prefetch experiment

System-file-buffer design history from the independent alpha.6-derived experiment. This combined copy also implements native PCM buffering; see native-pcm-prefetch.md for its architecture and final combined validation. Not a released APK and not yet Android-device validated.

## Implemented contract

- Existing foreground service, audio focus, wake-lock, notification and queue ownership stay in place.
- `FileBufferedOfflineSpeechEngine` wraps the selected system engine. It splits each original request into native units of at most 120 UTF-16 units, preserving surrogate pairs and preferring punctuation. The original request/block remains the progress unit: one external start, then one external completion only after all its subunits have played.
- The outer queue is still text, as in alpha.6. Audio is only current plus one next. `TwoSlotSpeechPipeline` serializes synthesis and independently drives playback. It never runs two syntheses concurrently and never pre-renders a whole book.
- A TTS file `onDone` means synthesis is complete. It does not advance reading. The file must also pass size/duration checks and `MediaPlayer.onPrepared` before READY. Only player completion advances the internal/outer reading cursor.
- The prepared next file/player can exist while current audio plays. On completion it is promoted, then exactly one replacement is synthesized. A queued request is never marked READY.
- Private random-named files live under the app's no-backup directory, never public/shared storage. Completed/stopped/failed files are released and unlinked. Startup removes this feature's stale files left by process death. A crash may leave files until next startup; they are not backed up. If deletion fails, no additional audio file is created by that engine instance: its next synthesis falls back to direct TTS, and the next engine creation retries stale cleanup. Existing ready audio may finish. This prevents accumulating additional files after cleanup failure.
- Accepted audio is limited to two slots, each at most 2 MiB and 60 seconds. Native chunks are <=120 UTF-16 units. No audio byte arrays are retained by the app; prepared players also have platform-managed buffers. Peak native/vendor memory is not represented by the 4 MiB accepted-file limit.
- A system TTS File API does not expose a hard write quota. A 100 ms watchdog cancels and unlinks oversized in-flight output; a broken/very fast vendor writer can transiently exceed 2 MiB before detection, including an already-open unlinked inode. This is a detected/accepted-file limit, not a hard OS disk quota. Do not claim otherwise.
- Synthesis/preparation has a 90-second timeout. Playback keeps the existing service's 20-minute request watchdog. Direct fallback retains its own 12-second no-start timeout.
- File-request rejection, synthesis error/timeout, empty/oversize file, duration violation, player preparation/error switches this engine instance to explicit `speak-fallback:<reason>`. It retries the affected current short unit through direct TTS at most once. A failed next prefetch does not interrupt an already-playing current unit. Later requests remain direct until the engine is re-created. There is no false claim of prebuffer support.
- Stop, replacement/chapter change, voice/rate/engine change and service destruction clear files and players. Monotonic internal tokens and service sequence IDs reject stale callbacks. Completed-file tokens ignore late vendor synthesis terminal callbacks; player errors remain distinct. A canceled old synthesis cannot become the new queue's audio.
- Pause clears current+next audio and preserves the original entry index. Resume replays that original entry, matching alpha.6's conservative pause contract. It does not claim word/sample-accurate resume; if an entry contains several 120-unit subunits, earlier subunits of that entry repeat.
- No new permission, model, cloud request, network path, document/title/text logging, persistent audio, or upload was added. Only synthetic strings are used in tests.

## What the metrics mean

In-memory diagnostic ring and `InkDockTTS` log contain numeric tokens, character counts and timing only:

- engine_init request_to_onInit: constructor request to initialization callback, not first sound
- queued / queued-next: request accepted by the bounded scheduler, not synthesized
- synth-start / synth-done: platform TTS file callbacks
- ready: validated file and prepared player, with duration and elapsed time
- play-start / play-done: MediaPlayer start invocation/completion callback
- readyLeadMs = previous player completion time minus next READY time; positive proves next audio was prepared before needed
- gapMs = next player start invocation minus previous completion callback; this measures app handoff, not acoustic silence
- bufferingMode / bufferedSlots / nextAudioReady: snapshot fields with coalesced post-transition pushes (both READY and promotion/cancel/fallback changes); `nextAudioReady` is never inferred from queue acceptance

For device runs calculate synthesis RTF as (synth-done minus synth-start) / audio duration and report warm/cold p50/p95. Also report start latency, prefetch hit rate (positive readyLead), boundary gap, max live files/bytes, and cleanup latency. RTF greater than 1 on sustained workloads drains any finite prebuffer; one-next buffering cannot make a consistently slower-than-realtime model gapless. Variable segment durations and thermal throttling can also cause gaps even when average RTF is below 1. File preparation removes synthesis gaps when ready early, but separate MediaPlayers do not promise sample-accurate gapless audio.

## Why not just QUEUE_ADD?

Android documents QUEUE_ADD as adding a request to the playback queue. `speak` and `synthesizeToFile` return after queue acceptance; that return is not synthesis or audio completion. An engine/platform may synthesize ahead, but QUEUE_ADD alone is not evidence that it did. `onAudioAvailable` can distinguish generated audio from playback when supported, yet requesting/receiving a queue entry alone cannot prove readiness. File completion plus a prepared player provides an app-owned readiness gate.

Primary Android references checked for this task:

- https://developer.android.com/reference/android/speech/tts/TextToSpeech
- https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener

## Original native Kokoro proposal (now implemented in the combined copy)

The original native experiment generates PCM and then drains AudioTrack on the same worker. It cannot overlap generation of the next part with playback on that worker. Retain a single model/synthesis executor; introduce one independent audio-output worker and a capacity-one next-PCM handoff. While current float PCM drains, only the next <=120-unit part may generate. At 24 kHz mono float32, 30 seconds is 2.88 MB and 60 seconds is 5.76 MB per PCM array; impose a sample-count/duration limit before accepting a result, and measure native intermediate memory separately. Keep model access single-threaded and never load a second model to parallelize synthesis. Stop bumps generation, pauses/flushes AudioTrack, drops both references, and aborts model callbacks; stale results are discarded. Release the model only after in-flight generation exits. Promote on drained playback, not last write/enqueue. This is an architecture proposal, not a measured native speed claim.

## Validation

Historical host checks in the standalone system-file-buffer copy (see native-pcm-prefetch.md for final combined counts):

- Production Kotlin compiles against Android API classes.
- 15 new tests pass: real READY vs queued semantics, positive readiness lead, slower-than-playback gap, synth completion not playback completion, cancellation/stale callbacks, next/current failure fallback, direct failure termination, repeated stop, 1,000 sequential units staying <=2 slots, private-text-free metrics, Unicode/long-tail segmentation.
- All 55 host JUnit tests pass; all 38 JS tests pass after importing the independently tested first-120/later-360 offline splitter.
- Android instrumentation sources compile, including the Java test-only engine and 4 new device scenarios. Compilation does not execute Android APIs.

Device tests prepared but NOT RUN locally (no Android SDK/emulator in this environment):

1. Real test-engine `synthesizeToFile` generates two seconds of zero PCM quickly. Assert next is READY while current is still playing, exactly two files, positive readiness lead, exactly one ordered playback of each request, then no files.
2. Stop while next is READY; assert immediate file/player cleanup and no later event/restart.
3. Two internal <=120-unit buffers retain one external request and produce external completion only after both play.
4. A test-only backend rejects file synthesis; assert explicit direct fallback exactly once and no claim of READY.

Existing Home/screen-off/notification Pause/Resume/Stop/activity-recreation/engine-change regressions remain in the copied test suite and must be rerun with the final integrated code. Real installed engines additionally need airplane-mode/no-network validation, unsupported-file behavior, short/long/rate extremes, repeated seek/voice/rate changes, focus loss/headphone removal, low storage, synthesis stalls, process death/relaunch cleanup, slow-device thermal runs, and audible first-start/boundary checks. Silent fixtures prove scheduling and platform plumbing, not voice quality, speaker routing, or performance on the user's phone.
