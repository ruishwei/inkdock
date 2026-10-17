# Embedded Chinese Kokoro experiment — 2026-10-17

## Verified result
A pinned, eSpeak-free upstream sherpa-onnx branch compiled on Linux and synthesized Chinese locally from the actual int8 Kokoro v1.1 model. Android source integration is implemented and compiled against Android API stubs; 46 JVM tests pass. It is **not yet an Android APK or a device-tested feature**. The cloud machine has no Android NDK; native ARM64 build and device tests remain gates.

Source: https://github.com/k2-fsa/sherpa-onnx/pull/4055, commit `0586cd8e14d7eae56e52ae0f1fd7b947e74b84f7` (unmerged experimental PR, not a stable release).
Source archive SHA256: `13101a9ac5f72719a7290c6ec2c0ad71f8392dd974a128a05dfbb6a38f0f44d4`.

## Implemented app path
Voice panel → “下载 / 管理内置 Kokoro 中文包（实验）” → native explicit download confirmation. Float is the first/default choice (381,766,704 bytes); int8 is an explicit space-saving alternative (170,433,930 bytes). Download is explicit for the selected variant in eight individually SHA256-pinned files, with progress, cancellation, validated-file retry, private app storage, delete confirmation and hash verification before model load. Leaving setup cancels download; it does not promise background downloading. No browser handoff or external TTS app manages these files.

After completion the engine list exposes “Kokoro 中文（内置实验）”. It uses the real model's 100 Chinese speaker IDs 3–102; speaker labels come from model metadata. PCM output uses AudioTrack, with playback completion (not generation completion) driving the existing service queue. The existing foreground service owns notifications, focus and wake lock. Short chunks limit memory; stop uses a generation token and flushes AudioTrack. Initial native generation cannot be forcibly interrupted inside ONNX, so it may finish a short current chunk before resources release.

Each variant has a separate private directory and staging directory; deleting one cannot remove the other. Public supporting files have identical pinned hashes but are deliberately duplicated on disk for simple independent lifecycle (both installed total 552,200,634 bytes). A shared atomic inference lease prohibits both models being resident. Setup entry stops and releases the previous engine before selection; a busy engine or download blocks switching. No selection initiates a download.

Model manifest and exact hashes are in `KokoroManifest.kt`. Sources are fixed to Hugging Face revision `155831f1b4ba23b1f5c058be6a61df90cefb2a37` of https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1 . The float ONNX is pinned to revision `914313412b607d95400bcd12446233fbd1248801` of the corresponding `kokoro-multi-lang-v1_1` repo, 325,631,784 bytes, SHA256 `acc4adc175b9d9986106cd20060329673ad5a2e12ef3c557d2d3745b694f8b38`. Supporting files reuse the exact int8-manifest URLs/hashes. No espeak-ng-data or dictionary directory is downloaded.

## Licensing and provenance
- Original model: https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh, Apache-2.0. ONNX conversion is the sherpa maintainer's distribution, not the original PyTorch hash. The downloaded official int8 archive (147,031,220 bytes) SHA256 is `a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6`; eight extracted files were cross-checked against pinned HF Git/LFS metadata.
- Stable sherpa v1.13.8 TTS bundles GPL eSpeak/Piper phonemization. Do **not** substitute that binary into this MIT application without a separate licensing decision. The experiment uses PR4055's lexicon-only path; no eSpeak symbols were found in the Linux core archive. That is technical evidence, not a legal warranty.
- sherpa, kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst, simple-sentencepiece are Apache-2.0; ONNX Runtime and json are MIT; kissfft is BSD-3-Clause; Eigen is MPL-2.0 (build sets EIGEN_MPL2_ONLY). Notices are copied by the native build script. InkDock's own MIT license was not changed.
- The upstream Chinese lexicon generation references Apache-2.0 Misaki and MIT python-pinyin. Official bundled FSTs carry the package Apache license; independent training/data provenance is not a legal signoff.
- VoxSherpa application code is GPLv3 and has not been copied. Piper huayan's dataset license is Unknown and it is not bundled. No unsupported Pocket/Supertonic/Index download buttons were added.

## Reproduction / native build gate
Use an already installed and licensed Android NDK and CMake:

    ANDROID_NDK_HOME=/path/to/ndk python3 scripts/build-kokoro-native.py
    gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug

The script never accepts SDK agreements or installs an NDK. It pins source SHA, builds ARM64 and x86_64 API26 JNI, verifies ELF LOAD alignment >=16KB, checks dynamic symbols for espeak/piper, copies dependency notices and writes native provenance. Gradle preBuild refuses to advertise the feature in an APK missing the native libraries or provenance. Inspect all final APK dependencies and notices before release.

Pinned native dependency: ONNX Runtime Android 1.28.2, SHA256 `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`.
URL: https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip . Linux runtime of the same version successfully loaded the actual ONNX model. Android ABI/operator compatibility still requires the JNI run.

## Measurements and remaining tests
Cloud Linux CPU, 2 threads, 24kHz mono, same Chinese sentence: speaker3 produced8.97s audio in12.17s wall, peak316MiB RSS; speaker53 produced11.15s audio in14.87s wall, peak338MiB. These are neither mobile benchmarks nor proof of acceptable sound. User reported noise in int8 and explicitly heard no noise in the float half of the same-speaker comparison. Float is therefore preferred for this user, not guaranteed noise-free on every device or voice. Float cloud measurements: 5.70/6.68 seconds generation wall time, 516/529 MiB peak RSS. No denoising or normalization was used. Both WAV variants retain DC offset; measurements alone do not establish the perceived noise mechanism.

Remaining release gates: Android JNI compile and binary/license inspection; real Y300t model download/cancel/retry/storage exhaustion; Chinese multi-pronunciation/numbers/mixed Latin; actual listening; first-audio latency and memory; 30-minute screen-off foreground playback; focus/phone-call/headset interruptions; rapid stop/next/engine changes; model deletion while queued; notification permission behavior. No phone installation or model download has been performed by this work.

## Download/lifecycle review fixes
Download cancellation uses a reserved ticket created on the UI thread before queueing; a stopped Activity cannot have its cancellation reset by a delayed worker. HTTP connections disconnect on cancellation, redirects, exceptions and close. Capacity uses only remaining bytes after re-hashing reusable staged files plus 64 MiB reserve; storage display includes staging. Deletion refreshes controls after the busy flag clears. License browsing recursively traverses dependency directories. Regression tests include queued-before-start cancellation, near-capacity retry, staged-byte accounting, redirect cleanup and nested-license traversal.

Latest alpha6 strict engine/voice verification and lifecycle fixtures have been merged. Native maintenance releases the model without silently changing the saved engine selection. Builder owns the separate first-audio latency and CI modifications.
