#!/usr/bin/env python3
"""Build/audit the experimental eSpeak-free JNI. Never installs an SDK/NDK.

Both supported ABIs are mandatory. No model download, inference, emulator, secret,
license acceptance, release, or stable sherpa binary substitution is performed.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/kokoro-native"
ASSETS = ROOT / "app/src/main/assets"
JNILIBS = ROOT / "app/src/main/jniLibs"
PROVENANCE = ASSETS / "kokoro-native-build.json"
SOURCE_COMMIT = "0586cd8e14d7eae56e52ae0f1fd7b947e74b84f7"
SOURCE_SHA = "13101a9ac5f72719a7290c6ec2c0ad71f8392dd974a128a05dfbb6a38f0f44d4"
SOURCE_URL = "https://api.github.com/repos/csukuangfj/sherpa-onnx/tarball/" + SOURCE_COMMIT
SOURCE_DIR = "csukuangfj-sherpa-onnx-0586cd8"
ONNX_SOURCE_COMMIT = "33ca9628233dc8f002435e868d4c2e9f82766ca1"
ONNX_NOTICE_SHA = {
    "LICENSE": "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
    "ThirdPartyNotices.txt": "0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2",
}
UPSTREAM_PR = "https://github.com/k2-fsa/sherpa-onnx/pull/4055"
ABIS = ("arm64-v8a", "x86_64")
LIBRARIES = ("libsherpa-onnx-jni.so", "libonnxruntime.so")
SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
FORBIDDEN = re.compile(r"espeak[_-]|piper[_-]phonemize", re.I)
# These are the exact FetchContent archives selected by the pinned source.
# They are technical inventory, not a conclusion that all transitive legal duties are met.
DEPENDENCIES = {
    "onnxruntime": ("1.28.2", "MIT", "01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d"),
    "kaldi_native_fbank": ("1.22.3", "Apache-2.0", "9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61"),
    "kaldi_decoder": ("0.3.0", "Apache-2.0", "b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0"),
    "kaldifst": ("1.8.0", "Apache-2.0", "3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3"),
    "kissfft": ("febd4caeed32e33ad8b2e0bb5ea77542c40f18ec", "BSD-3-Clause", "497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd"),
    "openfst": ("1.8.5-2026-07-09", "Apache-2.0", "2ff712a32952fcb01d351121a6bc8ccf4fdc6b2aa06ce8df2b3095dedd518c0e"),
    "simple-sentencepiece": ("0.7", "Apache-2.0", "1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af"),
    "json": ("3.12.0", "MIT", "4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187"),
    "eigen": ("5.0.1", "MPL-2.0 (EIGEN_MPL2_ONLY)", "e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec"),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_relative(name):
    require(isinstance(name, str) and name and "\\" not in name, f"Unsafe path: {name!r}")
    parts = name.rstrip("/").split("/")
    require(not name.startswith("/") and all(p not in ("", ".", "..") for p in parts), f"Unsafe path: {name!r}")
    require(":" not in parts[0], f"Unsafe path: {name!r}")
    return Path(*parts)


def extract_source(archive, destination):
    """Extract regular files only after validating the entire pinned archive.

    Legitimate upstream example symlinks are recorded and omitted, never followed.
    A fresh directory prevents preexisting symlinks or stale source files affecting a build.
    """
    require(not destination.exists(), "Source extraction requires a fresh destination")
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        require(len(members) < 25000 and sum(m.size for m in members) < 200_000_000, "Unexpected source archive size")
        seen, skipped = set(), []
        for member in members:
            relative = safe_relative(member.name)
            require(relative.parts[0] == SOURCE_DIR, "Unexpected source archive root")
            require(str(relative) not in seen, "Duplicate archive path")
            seen.add(str(relative))
            if member.issym() or member.islnk():
                skipped.append(member.name)
            else:
                require(member.isdir() or member.isfile(), "Archive contains a device or special file")
        destination.mkdir(parents=True)
        for member in members:
            target = destination / safe_relative(member.name)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                # All links were skipped and the destination was empty.
                with tar.extractfile(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o755 if member.mode & 0o111 else 0o644)
    return destination / SOURCE_DIR, skipped


def download_source(archive):
    if not archive.exists():
        partial = archive.with_suffix(".part")
        try:
            with urllib.request.urlopen(SOURCE_URL, timeout=120) as response, partial.open("wb") as stream:
                shutil.copyfileobj(response, stream)
            require(digest(partial) == SOURCE_SHA, "Source checksum mismatch; refusing build")
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    require(digest(archive) == SOURCE_SHA, "Source checksum mismatch; refusing build")


def find_ndk():
    candidates = [Path(os.environ[key]) for key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK") if os.environ.get(key)]
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            candidates.extend(sorted((Path(os.environ[key]) / "ndk").glob("*"), reverse=True))
    for candidate in candidates:
        if (candidate / "build/cmake/android.toolchain.cmake").is_file():
            return candidate.resolve()
    raise RuntimeError("No existing Android NDK found. Install/approve it separately; this script never accepts agreements or installs SDK packages.")


def run(command, log=None):
    print("+", " ".join(map(str, command)), flush=True)
    if log:
        with log.open("w") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text()[-20000:], flush=True)
        result.check_returncode()
    else:
        subprocess.run(command, check=True)


def inspect_elf(data, abi):
    require(data[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64")
    require(len(data) >= 64 and struct.unpack_from("<H", data, 16)[0] == 3, "Expected ELF shared object (ET_DYN)")
    require(struct.unpack_from("<H", data, 18)[0] == {"arm64-v8a": 183, "x86_64": 62}[abi], "Wrong ELF machine for " + abi)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    require(entry_size == 56 and count and phoff + entry_size * count <= len(data), "Invalid ELF program headers")
    alignments = []
    for index in range(count):
        values = struct.unpack_from("<IIQQQQQQ", data, phoff + index * entry_size)
        kind, _, offset, address, _, size, _, alignment = values
        if kind == 1:
            require(alignment >= 0x4000 and offset % 0x4000 == address % 0x4000, "16KB ELF LOAD alignment failed")
            require(offset + size <= len(data), "Invalid ELF LOAD size")
            alignments.append(alignment)
    require(alignments, "No ELF LOAD segments")
    return min(alignments)


def elf_dynamic(data):
    """Read DT_NEEDED/SONAME directly so final APK verification needs no NDK."""
    phoff = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + index * entry_size) for index in range(count)]
    dynamic = [h for h in headers if h[0] == 2]
    require(len(dynamic) == 1, "Expected one ELF dynamic table")
    header = dynamic[0]
    offset, length = header[2], header[5]
    require(offset + length <= len(data) and length % 16 == 0, "Invalid ELF dynamic table")
    entries = []
    for pos in range(offset, offset + length, 16):
        tag, value = struct.unpack_from("<qQ", data, pos)
        if tag == 0:
            break
        entries.append((tag, value))
    tables = [value for tag, value in entries if tag == 5]
    sizes = [value for tag, value in entries if tag == 10]
    require(len(tables) == len(sizes) == 1, "Invalid ELF string table")
    loads = [h for h in headers if h[0] == 1 and h[3] <= tables[0] and tables[0] + sizes[0] <= h[3] + h[5]]
    require(len(loads) == 1, "ELF strings outside a file-backed LOAD segment")
    string_offset = loads[0][2] + tables[0] - loads[0][3]
    strings = data[string_offset:string_offset + sizes[0]]
    def string_at(index):
        require(index < len(strings), "Invalid ELF dynamic string offset")
        end = strings.find(b"\0", index)
        require(end >= 0, "Unterminated ELF dynamic string")
        return strings[index:end].decode("ascii")
    needed = [string_at(value) for tag, value in entries if tag == 1]
    sonames = [string_at(value) for tag, value in entries if tag == 14]
    require(len(sonames) == 1, "Missing or duplicate ELF SONAME")
    return needed, sonames[0]


def supplementary_onnx_notices():
    # The verified Android AAR-derived ZIP contains no notices. Preserve the full
    # corresponding Microsoft source notices separately, without patching the ZIP.
    destination = WORK / "onnxruntime-supplemental-notices"
    destination.mkdir(exist_ok=True)
    for name, sha in ONNX_NOTICE_SHA.items():
        path = destination / name
        if not path.exists():
            url = "https://raw.githubusercontent.com/microsoft/onnxruntime/" + ONNX_SOURCE_COMMIT + "/" + name
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read(2_000_000)
            require(hashlib.sha256(data).hexdigest() == sha, "Microsoft ONNX notice checksum mismatch")
            path.write_bytes(data)
        require(digest(path) == sha, "Microsoft ONNX notice checksum mismatch")
    return destination


def license_files(source):
    files = set()
    for path in source.iterdir():
        if path.is_file() and any(token in path.name.upper() for token in ("LICENSE", "COPYING", "NOTICE")):
            files.add(path)
        if path.is_dir() and path.name.lower() in ("licenses", "license"):
            files.update(p for p in path.rglob("*") if p.is_file())
    require(files, "Missing dependency notices in " + str(source))
    return sorted(files)


def copy_notices(name, source, destination):
    records = []
    for path in license_files(source):
        require(not path.is_symlink(), "Unexpected notice symlink")
        relative = Path(name) / path.relative_to(source)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        records.append({"path": str(relative), "sha256": digest(target), "bytes": target.stat().st_size})
    return records


def audit_dependencies(src, build):
    deps = {p.name[:-4]: p for p in (build / "_deps").glob("*-src")}
    require(set(deps) == set(DEPENDENCIES), f"Unexpected dependency set: {sorted(deps)}")
    configs = list((src / "cmake").glob("*.cmake"))
    configs += [p for dep in deps.values() for p in (dep / "cmake").glob("*.cmake")]
    declaration_text = "\n".join(p.read_text() for p in configs)
    for name, (_, _, sha) in DEPENDENCIES.items():
        require("SHA256=" + sha in declaration_text, "Missing pinned dependency checksum for " + name)
    commands = json.loads((build / "compile_commands.json").read_text())
    require(commands, "Missing CMake compilation inventory")
    for entry in commands:
        command = entry.get("command", " ".join(entry.get("arguments", [])))
        require(not FORBIDDEN.search(command), "Forbidden phonemizer found in build commands")
        if Path(entry["file"]).suffix in (".cc", ".cpp", ".cxx"):
            require("-DEIGEN_MPL2_ONLY" in command, "EIGEN_MPL2_ONLY missing from C++ compile")
    return deps


def build_native(jobs):
    ndk = find_ndk()  # Fail before any downloads or output mutation.
    require(shutil.which("cmake"), "CMake must already be available")
    toolchain = ndk / "toolchains/llvm/prebuilt"
    hosts = [p for p in toolchain.glob("*") if (p / "bin/llvm-readelf").is_file()]
    require(len(hosts) == 1, "Cannot identify NDK host LLVM tools")
    readelf, nm = hosts[0] / "bin/llvm-readelf", hosts[0] / "bin/llvm-nm"
    require(nm.is_file(), "Missing NDK llvm-nm")
    WORK.mkdir(parents=True, exist_ok=True)
    archive = WORK / "source.tar.gz"
    download_source(archive)
    # Remove only generated build directories; never reuse configured dependency sources.
    for path in (WORK / "source", WORK / "staging", *(WORK / ("android-" + abi) for abi in ABIS)):
        require(not path.is_symlink(), "Refusing generated-directory symlink: " + str(path))
        if path.exists():
            shutil.rmtree(path)
    PROVENANCE.unlink(missing_ok=True)  # A failed rebuild can never validate an old runtime.
    src, skipped = extract_source(archive, WORK / "source")
    staging = WORK / "staging"
    notices = staging / "licenses/kokoro-native"
    notices.mkdir(parents=True)
    notice_records = copy_notices("sherpa-onnx", src, notices)
    notice_records += copy_notices("android-ndk", ndk, notices)
    records, dependency_records = [], []
    onnx_notices = supplementary_onnx_notices()
    audit = WORK / "audit"
    audit.mkdir(exist_ok=True)
    for abi in ABIS:
        build = WORK / ("android-" + abi)
        command = ["cmake", "-S", str(src), "-B", str(build),
                   "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
                   "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-DCMAKE_CXX_FLAGS=-DEIGEN_MPL2_ONLY",
                   "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
                   "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DBUILD_SHARED_LIBS=ON",
                   "-DSHERPA_ONNX_ENABLE_JNI=ON", "-DSHERPA_ONNX_ENABLE_TTS=ON",
                   "-DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF"]
        command += ["-D" + option + "=OFF" for option in (
            "SHERPA_ONNX_ENABLE_BINARY", "SHERPA_ONNX_ENABLE_PYTHON", "SHERPA_ONNX_ENABLE_TESTS",
            "SHERPA_ONNX_ENABLE_PORTAUDIO", "SHERPA_ONNX_ENABLE_WEBSOCKET", "SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION",
            "SHERPA_ONNX_ENABLE_C_API", "SHERPA_ONNX_BUILD_C_API_EXAMPLES")]
        run(command, audit / (abi + "-configure.log"))
        deps = audit_dependencies(src, build)
        if not dependency_records:
            for name, dep in sorted(deps.items()):
                dependency_notices = copy_notices(name, onnx_notices if name == "onnxruntime" else dep, notices)
                notice_records += dependency_notices
                version, license_name, sha = DEPENDENCIES[name]
                dependency_records.append({"name": name, "version": version, "declaredLicense": license_name,
                                           "sourceArchiveSha256": sha, "noticePaths": [r["path"] for r in dependency_notices]})
        else:
            # Same pinned dependencies must carry identical notices for each ABI.
            for name, dep in deps.items():
                notice_source = onnx_notices if name == "onnxruntime" else dep
                for path in license_files(notice_source):
                    require(digest(path) == digest(notices / name / path.relative_to(notice_source)), "ABI dependency notices differ")
        run(["cmake", "--build", str(build), "--target", "sherpa-onnx-jni", "--parallel", str(jobs)], audit / (abi + "-build.log"))
        for name in LIBRARIES:
            library = build / ("lib/" + name) if name.startswith("libsherpa") else deps["onnxruntime"] / "jni" / abi / name
            require(library.is_file(), "Missing native output: " + str(library))
            data = library.read_bytes()
            alignment = inspect_elf(data, abi)
            parsed_needed, soname = elf_dynamic(data)
            require(soname == name, "Wrong native SONAME")
            dynamic = subprocess.check_output([str(readelf), "-dW", str(library)], text=True)
            needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
            require(needed == parsed_needed, "ELF dependency parsers disagree")
            require(set(needed) <= SYSTEM_LIBRARIES | {"libonnxruntime.so"}, "Unexpected DT_NEEDED dependency: " + str(needed))
            if name.startswith("libsherpa"):
                require("libonnxruntime.so" in needed, "JNI is not linked against expected shared ONNX Runtime")
            if name.startswith("libsherpa"):
                sections = subprocess.check_output([str(readelf), "-SW", str(library)], text=True)
                require(re.search(r"\s\.symtab\s", sections), "JNI was stripped before full-symbol audit")
            # Check full symbols as well as exports: the JNI version script hides static code.
            symbols = subprocess.check_output([str(nm), "-a", str(library)], text=True, stderr=subprocess.STDOUT)
            exported = subprocess.check_output([str(nm), "--dynamic", str(library)], text=True, stderr=subprocess.STDOUT)
            if name.startswith("libsherpa"):
                for method in ("newFromAsset", "newFromFile", "delete", "getSampleRate", "getNumSpeakers", "getLang", "generateImpl", "generateWithCallbackImpl", "generateWithConfigImpl"):
                    require("Java_com_k2fsa_sherpa_onnx_OfflineTts_" + method in exported, "Missing TTS JNI export: " + method)
            require(not FORBIDDEN.search(symbols + exported + dynamic), "Forbidden phonemizer in native binary")
            (audit / (abi + "-" + name + "-elf.txt")).write_text(subprocess.check_output([str(readelf), "-hlWd", str(library)], text=True))
            (audit / (abi + "-" + name + "-symbols.txt")).write_text(symbols + "\nDYNAMIC\n" + exported)
            target = staging / "jniLibs" / abi / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(library, target)
            records.append({"abi": abi, "file": name, "sha256": digest(target), "bytes": target.stat().st_size,
                            "minLoadAlignment": alignment, "needed": needed})
        shutil.copy2(build / "compile_commands.json", audit / (abi + "-compile_commands.json"))
        shutil.copy2(build / "CMakeCache.txt", audit / (abi + "-CMakeCache.txt"))
    properties = (ndk / "source.properties").read_text()
    revision = re.search(r"^Pkg.Revision\s*=\s*(.+)$", properties, re.M)
    require(revision, "Missing NDK revision")
    provenance = {"schemaVersion": 1, "experimental": True, "upstreamPr": UPSTREAM_PR,
                  "sourceCommit": SOURCE_COMMIT, "sourceSha256": SOURCE_SHA, "sourceUrl": SOURCE_URL,
                  "abis": list(ABIS), "androidApi": 26, "ndkRevision": revision.group(1),
                  "cmakeVersion": subprocess.check_output(["cmake", "--version"], text=True).splitlines()[0],
                  "ciCommit": os.environ.get("GITHUB_SHA"), "runnerImage": os.environ.get("ImageVersion"),
                  "eigenMpl2Only": True, "skippedUpstreamLinks": skipped, "libraries": records,
                  "supplementalOnnxNotices": {"sourceCommit": ONNX_SOURCE_COMMIT,
                      "sourceBaseUrl": "https://raw.githubusercontent.com/microsoft/onnxruntime/" + ONNX_SOURCE_COMMIT + "/",
                      "sha256": ONNX_NOTICE_SHA, "reason": "Android runtime ZIP omits all license and third-party notice files"},
                  "dependencies": dependency_records, "notices": sorted(notice_records, key=lambda r: r["path"]),
                  "auditScope": "Build, ELF dependency/symbol/alignment and notice inventory checks; no device or synthesis test; no legal signoff."}
    for source, target in ((staging / "jniLibs", JNILIBS), (notices, ASSETS / "licenses/kokoro-native")):
        require(not target.is_symlink(), "Refusing output symlink")
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
    PROVENANCE.write_text(json.dumps(provenance, indent=2) + "\n")
    verify_runtime()
    shutil.copy2(PROVENANCE, audit / PROVENANCE.name)
    print(json.dumps({"abis": list(ABIS), "libraries": records, "noticeFiles": len(notice_records)}, indent=2))


def verify_runtime(apk=None):
    """No NDK or network is needed to enforce the Gradle and final APK gates."""
    archive = zipfile.ZipFile(apk) if apk else None
    try:
        if archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Duplicate APK entries")
            read = archive.read
            prefix = "assets/"
        else:
            names = ["lib/" + str(p.relative_to(JNILIBS)) for p in JNILIBS.rglob("*.so")]
            def read(name):
                path = JNILIBS / safe_relative(name[4:]) if name.startswith("lib/") else ASSETS / safe_relative(name[7:])
                return path.read_bytes()
            prefix = "assets/"
        provenance = json.loads(read(prefix + "kokoro-native-build.json"))
        require(provenance.get("schemaVersion") == 1 and provenance.get("experimental") is True, "Missing experimental provenance")
        require(provenance.get("sourceCommit") == SOURCE_COMMIT and provenance.get("sourceSha256") == SOURCE_SHA, "Wrong native source provenance")
        require(provenance.get("upstreamPr") == UPSTREAM_PR and provenance.get("abis") == list(ABIS), "Wrong native branch/ABI provenance")
        require(provenance.get("androidApi") == 26 and provenance.get("eigenMpl2Only") is True, "Wrong native configuration")
        expected = {"lib/" + abi + "/" + name for abi in ABIS for name in LIBRARIES}
        actual = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        require(actual == expected, f"Native APK/runtime file set mismatch: {sorted(actual)}")
        libraries = provenance.get("libraries", [])
        require(len(libraries) == len(expected), "Wrong number of native library records")
        seen = set()
        for record in libraries:
            name = "lib/" + str(safe_relative(record["abi"])) + "/" + str(safe_relative(record["file"]))
            require(name in expected and name not in seen, "Unexpected or duplicate library record")
            seen.add(name)
            data = read(name)
            require(hashlib.sha256(data).hexdigest() == record["sha256"] and len(data) == record["bytes"], "Native library checksum mismatch: " + name)
            require(inspect_elf(data, record["abi"]) == record["minLoadAlignment"], "ELF alignment provenance mismatch")
            needed, soname = elf_dynamic(data)
            require(soname == record["file"] and needed == record["needed"], "Native dependency/SONAME provenance mismatch")
            require(set(needed) <= SYSTEM_LIBRARIES | {"libonnxruntime.so"}, "Unapproved native dependency")
            if record["file"].startswith("libsherpa"):
                require("libonnxruntime.so" in needed, "Missing ONNX JNI dependency")
        dependencies = provenance.get("dependencies", [])
        require(len(dependencies) == len(DEPENDENCIES) and {d["name"] for d in dependencies} == set(DEPENDENCIES), "Wrong native dependency inventory")
        for dependency in dependencies:
            version, declared_license, sha = DEPENDENCIES[dependency["name"]]
            require((dependency["version"], dependency["declaredLicense"], dependency["sourceArchiveSha256"]) == (version, declared_license, sha), "Dependency provenance mismatch")
            require(dependency.get("noticePaths"), "Dependency notices missing")
        notices = provenance.get("notices", [])
        require(notices, "Missing native dependency notices")
        seen_notices = set()
        for record in notices:
            path = str(safe_relative(record["path"]))
            require(path not in seen_notices, "Duplicate notice record")
            seen_notices.add(path)
            data = read(prefix + "licenses/kokoro-native/" + path)
            require(hashlib.sha256(data).hexdigest() == record["sha256"] and len(data) == record["bytes"], "Native notice checksum mismatch: " + path)
        supplement = provenance.get("supplementalOnnxNotices", {})
        require(supplement.get("sourceCommit") == ONNX_SOURCE_COMMIT and supplement.get("sha256") == ONNX_NOTICE_SHA, "Wrong supplemental ONNX notices")
        for name, sha in ONNX_NOTICE_SHA.items():
            data = read(prefix + "licenses/kokoro-native/onnxruntime/" + name)
            require(hashlib.sha256(data).hexdigest() == sha, "Pinned ONNX notice hash mismatch")
        for dependency in dependencies:
            require(set(dependency["noticePaths"]) <= seen_notices, "Dependency has an untracked notice")
        require(any(p.startswith("sherpa-onnx/") for p in seen_notices) and any(p.startswith("android-ndk/") for p in seen_notices), "Missing sherpa or static C++ runtime notices")
        print("Verified experimental JNI" + (" in " + str(apk) if apk else " before Gradle packaging") + ": both ABIs, 4 library hashes, ELF alignment, dependency and notice inventory.")
    finally:
        if archive:
            archive.close()


def self_test():
    checks = 0
    for name in ("../escape", "/absolute", "a/../../b", "a\\b", "a//b", "a/./b", "C:/escape"):
        try:
            safe_relative(name)
        except RuntimeError:
            checks += 1
        else:
            raise AssertionError("Accepted unsafe path " + name)
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        archive = root / "good.tar"
        with tarfile.open(archive, "w") as tar:
            member = tarfile.TarInfo(SOURCE_DIR + "/file.txt")
            member.size = 2
            tar.addfile(member, io.BytesIO(b"ok"))
            link = tarfile.TarInfo(SOURCE_DIR + "/link")
            link.type, link.linkname = tarfile.SYMTYPE, "/tmp/never-follow"
            tar.addfile(link)
        source, skipped = extract_source(archive, root / "good")
        assert (source / "file.txt").read_bytes() == b"ok" and not (source / "link").exists() and len(skipped) == 1
        checks += 1
        for index, name in enumerate((SOURCE_DIR + "/../../escape", "wrong-root/file")):
            bad = root / (str(index) + ".tar")
            with tarfile.open(bad, "w") as tar:
                tar.addfile(tarfile.TarInfo(name), io.BytesIO())
            try:
                extract_source(bad, root / str(index))
            except RuntimeError:
                checks += 1
            else:
                raise AssertionError("Extracted unsafe archive")
    for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
        data = bytearray(120)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 16, 3)
        struct.pack_into("<H", data, 18, machine)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, 16384)
        assert inspect_elf(data, abi) == 16384
        checks += 1
        struct.pack_into("<Q", data, 112, 4096)
        try:
            inspect_elf(data, abi)
        except RuntimeError:
            checks += 1
        else:
            raise AssertionError("Accepted 4KB ELF")
    print(f"Passed {checks} offline archive/ELF safety checks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--self-test", action="store_true")
    mode.add_argument("--verify-runtime", action="store_true")
    mode.add_argument("--audit-apk", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    require(1 <= args.jobs <= 32, "--jobs must be between 1 and 32")
    if args.self_test:
        self_test()
    elif args.verify_runtime or args.audit_apk:
        verify_runtime(args.audit_apk)
    else:
        build_native(args.jobs)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, KeyError, ValueError, subprocess.CalledProcessError, tarfile.TarError, zipfile.BadZipFile) as error:
        raise SystemExit("Kokoro native gate failed: " + str(error)) from error
