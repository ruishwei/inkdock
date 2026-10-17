# Stripped ARM64 distribution candidate

Status on 2026-10-17: implementation and offline fixtures prepared. No real strip, Gradle build, APK measurement, ARM64 installation, publication or workflow dispatch has been performed for this path in the restored local environment. Existing SDK/NDK tools are absent here. The output remains an experimental, debug-signed candidate.

## Separate, opt-in path

- Default builds keep the existing dual-ABI, full-symbol native audit unchanged.
- Only `-PinkdockArm64Distribution=true` selects `build/kokoro-arm64/staging/jniLibs`, adds its generated assets and sets the packaged ABI to `arm64-v8a`.
- `keepDebugSymbols` remains enabled. Gradle must preserve the exact bytes that the dedicated script already stripped and audited, rather than silently applying another transformation.
- Application source, version code/name and signing configuration are unchanged. No existing native/device workflow or test is modified.
- New workflow: `.github/workflows/kokoro-arm64-distribution.yml`, manual-only, read-only repository permissions, no Release step, and restricted to `experiment/kokoro-buffer`. A newly added workflow may need registration on the default branch before GitHub exposes manual dispatch; publication/registration and dispatch are separate maintainer actions.

## Fixed baseline and passing-device gate

The script `scripts/prepare-kokoro-arm64.py` requires the same immutable native baseline:

- Repository `ruishwei/inkdock`
- Successful native run `39017151369`
- Native app-source commit `66356a624b116b61b9f022ccd2fdba247ed3e2e7`
- Artifact `InkDock-kokoro-experiment-audit-39017151369`, ID `12095860545`
- Actual downloaded ZIP SHA-256 `64ab9aa071ff91b771e38d0ae1d88d734a31ced2ee8b7ce05def1778e26c6bc8`
- The original native provenance still pins sherpa source commit `0586cd8e14d7eae56e52ae0f1fd7b947e74b84f7` and its dependency/notices hashes.

Both API identity/digest and the downloaded archive bytes must match. The ZIP extractor validates all paths before extraction, rejects duplicates, encrypted entries, symlinks and special files, limits total entry count/size, and only extracts the baseline native libraries, notices/provenance and original audit reports. It refuses to overwrite an existing distribution workspace.

Manual dispatch requires `passed_device_run_id`. That run must be a completed successful `kokoro-device.yml` workflow_dispatch on the buffered experiment branch in the same repository. Git object tree identities are checked across the entire repository file/mode/type inventory, including automatically loaded buildSrc, version catalogs, ProGuard files and future inputs. Only documentation and the exact distribution workflow/scripts are exempt; directory hashes are ignored because adding exempt files changes ancestor hashes. The app Gradle files are tied to their Git blob hashes and compared after removing only the exact reviewed opt-in packaging patch. Changes to application dependencies, versions, signing or any other Gradle text are rejected. A passing device run may therefore precede these packaging-only files, but cannot validate later application changes.

The existing device gate uses x86_64. Reusing its application-source evidence does not claim a device test of the stripped ARM64 libraries or Vivo playback quality.

## Audit before and after stripping

1. The unchanged native verifier checks all four raw libraries across both ABIs, source/dependency pins, hashes, 16 KB ELF LOAD alignment, SONAME/DT_NEEDED and notices.
2. Existing NDK `llvm-nm -a`, `llvm-nm --dynamic` and `llvm-readelf` rerun the full/raw symbol, dynamic export, dependency and ELF audits on both ABIs. JNI must still contain `.symtab`; hidden full-table eSpeak/Piper phonemizer names fail the gate. The original and repeated full-symbol reports and all raw libraries are retained.
3. Each ARM64 library is copied into the separate staging directory. Only the copy is passed to the existing NDK's `llvm-strip --strip-unneeded`; the raw file's hash is rechecked afterward.
4. The stripped copies must have no `.symtab` or `.debug`/`.zdebug` sections. Their complete dynamic symbol inventories must match the raw copies, including binding, type, visibility, defined/undefined state, value and size. All nine required OfflineTts JNI exports must remain defined externally visible functions. SONAME, DT_NEEDED closure and 16 KB ELF alignment must remain unchanged.
5. The separate distribution manifest records exact raw/stripped SHA-256 and byte counts, original provenance hash, selected tool versions/hashes/NDK revision, device-source evidence and every retained audit-file hash. Original notices and native-build provenance are copied unchanged into the generated assets. `kokoro-native-build.json` describes the original raw build; `kokoro-native-distribution.json` describes the two packaged stripped copies.
6. The preBuild gate rechecks both raw and distribution inventories. The final APK gate requires exactly the two ARM64 libraries, exact audited bytes, dynamic symbols/JNI/dependencies, all original notices and both provenance files. Uncompressed native entries must be 16 KB aligned in the ZIP as well as in their ELF LOAD segments. Official `zipalign -c -P 16`, `apksigner verify` and `aapt dump badging` checks run separately.

No SDK/NDK is installed and no license is accepted. The workflow checks existing SDK licenses, SDK 35, Build Tools 35, JDK 17 and NDK LLVM tools; Gradle SDK auto-download is disabled. Missing prerequisites, expired baseline artifacts, failed device runs or changed inputs fail closed. This workflow does not start an emulator, change KVM access, download models, invoke cloud speech or install an APK to a device.

These checks preserve the full-symbol audit, but do not establish a legal signoff or prove the absence of every possible forbidden implementation. Release/license review remains a separate gate.

## Outputs and measurement

- `build/kokoro-arm64/audit/baseline/`: original raw dual-ABI libraries, original native provenance/notices and original symbol/build reports
- `build/kokoro-arm64/audit/`: repeated full/raw symbol reports, stripped reports and immutable input metadata
- `build/kokoro-arm64/staging/`: only the ARM64 stripped libraries and generated native assets
- `build/kokoro-arm64/reports/final-apk-audit.json`: measured final APK SHA-256/bytes, raw dual-ABI native byte total, raw ARM64 native byte total, stripped ARM64 native byte total and final checks
- Audit artifact `InkDock-kokoro-arm64-distribution-audit-<run id>`: raw debug libraries/symbols, native manifests/notices, input metadata, lint/JVM tests and packaging reports, including available failure evidence
- Candidate artifact `InkDock-kokoro-buffer-arm64-candidate-<run id>`: `InkDock-kokoro-buffer-arm64-experimental.apk`, final audit JSON, exact current-source commit and SHA256SUMS, uploaded only after all gates pass

There is no promised APK size. Read the measured final report after a successful build. Native library byte totals are not the APK byte size. Debug signatures remain temporary CI signatures; this work does not create a release key. Upgrade installation over another CI debug APK is not guaranteed because temporary signing certificates may differ.

## Local verification and reproduction

Offline fixture checks require only Python:

```sh
python3 scripts/build-kokoro-native.py --self-test
python3 scripts/test-kokoro-native.py
python3 scripts/test-kokoro-arm64.py
```

Prepared local validation: 24 distribution tests passed, covering deliberate archive/metadata/source/library/notice/evidence tampering and added implicit Gradle inputs, wrong ABI, missing/undefined JNI exports, hidden full-table forbidden symbols, raw-versus-stripped symbol policy, final APK alignment and mocked end-to-end copy-only stripping. The unchanged native suites each passed 14 checks. Python compilation, workflow YAML parsing and `bash -n` for every workflow shell block passed. The fixtures contain non-executable synthetic ELF bytes and mock NDK execution; they do not validate actual stripping or Android execution.

A real reproduction requires an already installed licensed SDK/NDK, JDK 17/Gradle 8.11.1, the exact baseline ZIP and the verified API metadata files produced by the workflow. Use a fresh checkout with no generated raw native assets, set `GITHUB_SHA` to the current source commit and `INKDOCK_NDK_VERSION` to the existing NDK revision, then:

```sh
python3 scripts/prepare-kokoro-arm64.py --prepare /path/to/baseline.zip --evidence /path/to/input-evidence
python3 scripts/prepare-kokoro-arm64.py --verify-runtime
gradle --no-daemon -Pandroid.builder.sdkDownload=false -PinkdockArm64Distribution=true \
  :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
python3 scripts/prepare-kokoro-arm64.py --audit-apk app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

The dedicated workflow contains the full metadata acquisition and signature/badging commands. Packaging success is not permission to publish or a substitute for installing and listening on the target ARM64 device.
