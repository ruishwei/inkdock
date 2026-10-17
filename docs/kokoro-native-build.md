# Experimental Kokoro Android native build

Status on 2026-10-17: build and audit preparation, not an Android runtime test or a release. The source pin is an **unmerged experimental upstream PR**. Never replace it with the stable sherpa TTS JNI, which includes the eSpeak/Piper phonemizer path this experiment intentionally excludes.

## Fixed inputs

- sherpa-onnx PR: <https://github.com/k2-fsa/sherpa-onnx/pull/4055>
- Exact source commit: `0586cd8e14d7eae56e52ae0f1fd7b947e74b84f7`
- Source archive: <https://api.github.com/repos/csukuangfj/sherpa-onnx/tarball/0586cd8e14d7eae56e52ae0f1fd7b947e74b84f7>
- Source SHA-256: `13101a9ac5f72719a7290c6ec2c0ad71f8392dd974a128a05dfbb6a38f0f44d4`
- ONNX Runtime Android: `1.28.2`, archive SHA-256 `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`
- Runtime archive: <https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip>
- Build ABIs: **both** `arm64-v8a` and `x86_64`; minimum Android native API 26; static C++ runtime.

The source and all nine selected FetchContent dependency archives are SHA-256-pinned. A clean CMake dependency/source tree is created for each invocation. `SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF` prevents a runner-installed runtime from replacing the pinned input. `EIGEN_MPL2_ONLY` is required in every generated C++ compilation command.

The source extractor validates every member before writing, rejects traversal, duplicate paths and special files, and omits upstream symlinks/hardlinks. The pinned archive contains 279 example links; they are recorded in provenance and never followed. Extracted files cannot retain privileged mode bits. Source checksum failure stops the build rather than falling back to another revision or release.

## Runner and authorization boundary

`.github/workflows/kokoro-experiment.yml` runs only on `experiment/**` branch pushes or an explicit workflow dispatch on that branch family. It has read-only repository permissions, does not persist checkout credentials, and contains no release step.

It selects `ubuntu-24.04`, checks the **already installed** API 35 SDK/build-tools, JDK 17, CMake and NDK, and fails if any prerequisite is missing. It does not invoke SDK/NDK package installation or accept license agreements. Gradle's SDK auto-download is disabled. CI passes the detected revision as `INKDOCK_NDK_VERSION` to select the same installed NDK instead of AGP's default version. Gradle fails with a clear message if this explicit environment value is missing. The official runner image inventory lists the existing NDK candidates; the build records the actual NDK revision and runner image rather than assuming the image remains unchanged:

<https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md>

This workflow does not run an emulator, change KVM permissions, install to a phone, download a speech model, perform live synthesis, or call cloud speech services. It compiles the instrumentation APK only. A passing run establishes compilation and the stated static checks, not Android audio quality, JNI model compatibility or foreground playback behavior.

## Local reproduction

Requires Python 3.11+, an existing NDK, CMake, installed Android SDK API/build-tools 35 and the project's Gradle/JDK versions. The experiment currently uses Gradle 8.11.1 and JDK 17.

```sh
python3 scripts/build-kokoro-native.py --self-test
python3 scripts/test-kokoro-native.py
export ANDROID_NDK_HOME=/path/to/existing/ndk
export INKDOCK_NDK_VERSION="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties")"
python3 scripts/build-kokoro-native.py --jobs 2
python3 scripts/build-kokoro-native.py --verify-runtime
gradle --no-daemon -Pandroid.builder.sdkDownload=false \
  :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
python3 scripts/build-kokoro-native.py --audit-apk app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

The Gradle preBuild gate calls the same offline Python verifier. It requires exactly the four expected libraries, fixed sherpa source provenance, both ABI machine types, each library's hash/size, ELF LOAD alignment of at least 16 KB, the SONAME and allowed `DT_NEEDED` dependency closure, the dependency inventory and every notice's hash/size. It rejects missing, stale or unexpected packaged native libraries. A rebuild removes old provenance before starting, so a failed build cannot validate stale output.

This experimental debug APK retains native symbols and exact ELF bytes (`keepDebugSymbols`), so the APK audit can verify the same SHA-256 values after Gradle packaging. CMake's Release optimization setting applies to native compilation; it does not make this a release APK. The workflow also runs Android `zipalign -P 16` on the finished APK.

## Dependency and notice audit

The script inventories the selected dependencies and copies their top-level `LICENSE*`, `COPYING*`, `*NOTICE*` files and full `LICENSES`/`licenses` directories. This includes Eigen's other COPYING files, kissfft's full BSD text, and the installed NDK's notices for the static C++ runtime/toolchain. The app's own MIT license is unchanged.

A verified packaging gap was found in ONNX Runtime's Android archive: it contains headers/JNI/AAR metadata but **no LICENSE or ThirdPartyNotices file**. The script therefore separately fetches and verifies both full files from Microsoft ONNX Runtime's exact `v1.28.2` source commit, `33ca9628233dc8f002435e868d4c2e9f82766ca1`:

- [LICENSE](https://raw.githubusercontent.com/microsoft/onnxruntime/33ca9628233dc8f002435e868d4c2e9f82766ca1/LICENSE): SHA-256 `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c`
- [ThirdPartyNotices.txt](https://raw.githubusercontent.com/microsoft/onnxruntime/33ca9628233dc8f002435e868d4c2e9f82766ca1/ThirdPartyNotices.txt): SHA-256 `0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2`

Their separate source and reason are explicit in the provenance. The complete Microsoft notice bundle is retained, without assuming every listed platform-specific component is linked on Android. Before CI, both actual pinned Android ONNX Runtime binaries were inspected: ELF machine matched the ABI, minimum LOAD alignment was 16 KB and `DT_NEEDED` contained only Android `libdl`, `liblog`, `libm` and `libc`.

JNI build auditing checks full and dynamic symbol tables for eSpeak/Piper phonemizer names, plus required OfflineTts JNI exports. The dependency allowlist rejects unexpected `_deps` sources or shared library dependencies. These are technical checks and a notice inventory, **not a legal signoff**, proof about all binary internals, or authorization to publish a release. Release review must still consider the pinned unmerged code, dependency source/notice duties and actual APK contents.

## Outputs and remaining gates

- `app/src/main/jniLibs/{arm64-v8a,x86_64}/`: audited JNI and ONNX Runtime libraries
- `app/src/main/assets/kokoro-native-build.json`: source/dependency/toolchain/ABI/ELF/hash provenance
- `app/src/main/assets/licenses/kokoro-native/`: complete collected notices
- `build/kokoro-native/audit/`: per-ABI configure/build logs, compiler command inventories, ELF and symbol reports, APK alignment/audit reports
- CI artifact `InkDock-kokoro-experiment-audit-<run id>`: collected evidence, libraries and test/lint reports, including available evidence when a step fails
- CI artifact `InkDock-kokoro-experimental-debug-apk-<run id>`: APK and SHA256SUMS, only after successful build and audit

Device gates remain: model download/cancel/retry and storage handling, real Chinese float/int8 synthesis, playback interruptions, rapid stop/next/engine changes, foreground screen-off stability, memory, latency and listening quality. No release should be inferred from a successful CI artifact.
