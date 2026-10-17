#!/usr/bin/env python3
"""Create an opt-in ARM64 distribution from immutable, full-symbol audit inputs.

No JNI compilation, package installation, agreement acceptance, device execution,
network request, signing-key change, or release is performed by this script.
The original dual-ABI builder and its full-symbol checks remain unchanged.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/kokoro-arm64"
STAGING = WORK / "staging"
AUDIT = WORK / "audit"
RAW = AUDIT / "baseline"
DIST_NAME = "kokoro-native-distribution.json"
BASE_NAME = "kokoro-native-build.json"
ABI = "arm64-v8a"
BASELINE = {
    "repository": "ruishwei/inkdock",
    "runId": 39017151369,
    "commit": "66356a624b116b61b9f022ccd2fdba247ed3e2e7",
    "artifactName": "InkDock-kokoro-experiment-audit-39017151369",
    "artifactId": 12095860545,
    "artifactSha256": "64ab9aa071ff91b771e38d0ae1d88d734a31ced2ee8b7ce05def1778e26c6bc8",
}
METHODS = ("newFromAsset", "newFromFile", "delete", "getSampleRate", "getNumSpeakers",
           "getLang", "generateImpl", "generateWithCallbackImpl", "generateWithConfigImpl")
JNI_EXPORTS = {"Java_com_k2fsa_sherpa_onnx_OfflineTts_" + method for method in METHODS}
spec = importlib.util.spec_from_file_location("kokoro_raw_baseline", ROOT / "scripts/build-kokoro-native.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
require, sha256 = native.require, native.digest


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def data_sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(json_bytes(value))


def file_record(path):
    return {"sha256": sha256(path), "bytes": path.stat().st_size}


def verify_file(path, record):
    require(path.is_file() and not path.is_symlink(), "Missing or linked evidence: " + str(path))
    require(file_record(path) == record, "Evidence checksum mismatch: " + str(path))


def elf_symbols(data):
    """Parse ELF64 section/dynamic symbols without depending on a host toolchain."""
    require(len(data) >= 64, "Truncated ELF header")
    offset = struct.unpack_from("<Q", data, 40)[0]
    size, count, names_index = struct.unpack_from("<HHH", data, 58)
    require(size == 64 and count > 0 and names_index < count and offset + size * count <= len(data),
            "Invalid ELF section headers")
    headers = [struct.unpack_from("<IIQQQQIIQQ", data, offset + size * i) for i in range(count)]
    for header in headers:
        if header[1] != 8:  # SHT_NOBITS occupies no file bytes.
            require(header[4] + header[5] <= len(data), "ELF section outside file")
    names_header = headers[names_index]
    require(names_header[1] == 3, "Invalid ELF section-name table")
    names = data[names_header[4]:names_header[4] + names_header[5]]

    def string_at(strings, index):
        require(0 <= index < len(strings), "ELF symbol string outside table")
        end = strings.find(b"\0", index)
        require(end >= 0, "Unterminated ELF symbol string")
        return strings[index:end].decode("utf-8", errors="strict")

    section_names = [string_at(names, h[0]) for h in headers]
    tables = [h for h in headers if h[1] == 11]  # SHT_DYNSYM
    require(len(tables) == 1, "Expected one ELF dynamic-symbol table")
    table = tables[0]
    require(table[9] == 24 and table[5] % 24 == 0 and table[6] < count, "Invalid ELF dynamic symbols")
    strings_header = headers[table[6]]
    require(strings_header[1] == 3, "Invalid ELF dynamic-symbol string table")
    strings = data[strings_header[4]:strings_header[4] + strings_header[5]]
    symbols = []
    for pos in range(table[4], table[4] + table[5], 24):
        name_index, info, other, section_index, value, length = struct.unpack_from("<IBBHQQ", data, pos)
        name = string_at(strings, name_index)
        if name:
            # Section numbering can change when strip removes debug sections.
            symbols.append([name, info >> 4, info & 15, other & 3, section_index != 0, value, length])
    require(symbols, "Empty ELF dynamic-symbol inventory")
    return section_names, sorted(symbols)


def inspect_library(data, abi, name, *, stripped=False):
    alignment = native.inspect_elf(data, abi)
    needed, soname = native.elf_dynamic(data)
    require(soname == name, "Wrong native SONAME")
    require(set(needed) <= native.SYSTEM_LIBRARIES | {"libonnxruntime.so"}, "Unapproved DT_NEEDED")
    sections, symbols = elf_symbols(data)
    require(not native.FORBIDDEN.search("\n".join([soname, *needed, *(s[0] for s in symbols)])),
            "Forbidden phonemizer in dynamic inventory")
    if name.startswith("libsherpa"):
        require("libonnxruntime.so" in needed, "Missing shared ONNX Runtime dependency")
        exported = {s[0] for s in symbols if s[1] in (1, 2) and s[2] == 2 and s[3] in (0, 3) and s[4]}
        require(JNI_EXPORTS <= exported, "Missing defined TTS JNI exports: " + str(sorted(JNI_EXPORTS - exported)))
        if not stripped:
            require(".symtab" in sections, "Raw JNI lacks the full symbol table; refusing weaker audit")
    if stripped:
        require(".symtab" not in sections and not any(s.startswith((".debug", ".zdebug")) for s in sections),
                "Distribution copy still contains debug/full symbol sections")
    return {"sha256": data_sha(data), "bytes": len(data), "minLoadAlignment": alignment,
            "needed": needed, "soname": soname, "dynamicSymbolsSha256": data_sha(json_bytes(symbols)),
            "dynamicSymbolCount": len(symbols)}


def protected_tree(tree):
    require(tree.get("truncated") is False and tree.get("tree"), "Incomplete GitHub source tree")
    # Compare the entire repository's file/mode/type inventory. A prefix allowlist
    # would miss automatically loaded buildSrc, version catalogs or future inputs.
    # Parent directory tree hashes are omitted because exempt documentation and
    # packaging additions necessarily alter their ancestor hashes.
    packaging = {".github/workflows/kokoro-arm64-distribution.yml", "scripts/prepare-kokoro-arm64.py",
                 "scripts/test-kokoro-arm64.py", "app/build.gradle.kts"}
    required = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "package.json", "package-lock.json",
                "scripts/build-kokoro-native.py", "scripts/test-kokoro-native.py", "scripts/kokoro-device-models.py",
                ".github/workflows/kokoro-device.yml", "scripts/vendor.mjs"}
    seen, result = set(), {}
    for entry in tree["tree"]:
        path = str(native.safe_relative(entry["path"]))
        require(path not in seen, "Duplicate GitHub tree path")
        seen.add(path)
        if entry["type"] == "tree":
            continue
        if path in packaging or path.startswith("docs/"):
            require(entry["type"] == "blob" and entry["mode"] == "100644", "Exempt packaging/docs input is not a regular file")
            continue
        result[path] = {"sha": entry["sha"], "mode": entry["mode"], "type": entry["type"]}
    require(required <= result.keys() and any(p.startswith("app/src/main/") for p in result),
            "Missing protected build/source inputs")
    return result


# Only these reviewed packaging changes may differ from the passed device run.
# Runtime dependencies, version/signing settings and all other app Gradle text
# must remain identical, even though this opt-in property was added afterward.
GRADLE_SUBSTITUTIONS = (
    ("""// Explicit packaging-only opt-in. The default dual-ABI audit/device build is unchanged.
val arm64Distribution = providers.gradleProperty("inkdockArm64Distribution").orNull.let {
    require(it == null || it == "true" || it == "false") { "inkdockArm64Distribution must be true or false" }
    it == "true"
}
""", ""),
    ("""    if (arm64Distribution) {
        defaultConfig { ndk { abiFilters += "arm64-v8a" } }
        sourceSets.getByName("main") {
            jniLibs.setSrcDirs(listOf(rootProject.file("build/kokoro-arm64/staging/jniLibs")))
            assets.srcDir(rootProject.file("build/kokoro-arm64/staging/assets"))
        }
    }
""", ""),
    ("// Keep exact audited bytes: raw symbols by default, separately stripped copies when opted in.",
     "// Experimental audit APK retains exact audited ELF bytes and full symbols."),
    ("// Default gate still enforces both raw ABIs. The separate distribution gate preserves that baseline.",
     "// A single stdlib verifier enforces both ABIs, source pin, ELF/notice/library hashes."),
    ('commandLine("python3", if (arm64Distribution) "scripts/prepare-kokoro-arm64.py" else "scripts/build-kokoro-native.py", "--verify-runtime")',
     'commandLine("python3", "scripts/build-kokoro-native.py", "--verify-runtime")'),
)


def normalized_app_gradle(document, tree):
    entries = [e for e in tree["tree"] if e["path"] == "app/build.gradle.kts"]
    require(len(entries) == 1 and entries[0]["type"] == "blob" and entries[0]["mode"] == "100644",
            "Missing regular app Gradle source")
    require(document.get("path") == "app/build.gradle.kts" and document.get("encoding") == "base64",
            "Wrong app Gradle source response")
    data = base64.b64decode("".join(document["content"].split()), validate=True)
    blob_sha = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    require(blob_sha == document.get("sha") == entries[0]["sha"], "App Gradle blob identity mismatch")
    text = data.decode("utf-8")
    for before, after in GRADLE_SUBSTITUTIONS:
        require(text.count(before) <= 1, "Duplicate distribution Gradle opt-in block")
        text = text.replace(before, after)
    return text


def verify_evidence(evidence):
    def read(name):
        return json.loads((evidence / name).read_text())
    run, artifacts, device = read("baseline-run.json"), read("baseline-artifacts.json"), read("device-run.json")
    require(run["id"] == BASELINE["runId"] and run["head_sha"] == BASELINE["commit"] and
            run["head_branch"] == "experiment/kokoro-native" and run["repository"]["full_name"] == BASELINE["repository"] and
            run["status"] == "completed" and run["conclusion"] == "success", "Wrong or unsuccessful baseline run")
    matches = [a for a in artifacts["artifacts"] if a["name"] == BASELINE["artifactName"]]
    require(len(matches) == 1, "Missing/duplicate baseline audit artifact")
    artifact = matches[0]
    require(artifact["id"] == BASELINE["artifactId"] and not artifact["expired"] and
            artifact["digest"] == "sha256:" + BASELINE["artifactSha256"] and
            artifact["workflow_run"]["id"] == BASELINE["runId"] and
            artifact["workflow_run"]["head_sha"] == BASELINE["commit"], "Wrong immutable baseline artifact")
    require(device["repository"]["full_name"] == BASELINE["repository"] and
            device["head_branch"] == "experiment/kokoro-buffer" and device["event"] == "workflow_dispatch" and
            device["path"] == ".github/workflows/kokoro-device.yml" and
            device["status"] == "completed" and device["conclusion"] == "success", "Device gate has not passed")
    current, tested = read("current-tree.json"), read("device-tree.json")
    current_sha = os.environ.get("GITHUB_SHA")
    require(current_sha and read("current-commit.json")["sha"] == current_sha and
            read("device-commit.json")["sha"] == device["head_sha"], "Source commit identity mismatch")
    require(current["sha"] == read("current-commit.json")["tree"]["sha"] and
            tested["sha"] == read("device-commit.json")["tree"]["sha"], "Git source tree identity mismatch")
    current_gradle = normalized_app_gradle(read("current-app-gradle.json"), current)
    tested_gradle = normalized_app_gradle(read("device-app-gradle.json"), tested)
    require(current_gradle == tested_gradle, "App Gradle changed beyond the exact reviewed distribution opt-in")
    protected = protected_tree(current)
    require(protected == protected_tree(tested), "Repository inputs changed since passing device run beyond allowed distribution/docs files")
    return {"runId": device["id"], "commit": device["head_sha"], "currentCommit": current_sha,
            "protectedSourceTreeSha256": data_sha(json_bytes(protected)),
            "normalizedAppGradleSha256": data_sha(current_gradle.encode()),
            "scope": "Same repository file/mode/type inventory except documentation and exact distribution files; app Gradle differs only by the reviewed opt-in. ARM64 stripping/packaging is separately audited; this is not an ARM64 device run."}


def extract_baseline(archive, target):
    require(sha256(archive) == BASELINE["artifactSha256"], "Baseline archive SHA-256 mismatch")
    require(not target.exists(), "Baseline extraction requires a fresh directory")
    exact = {"app/src/main/assets/" + BASE_NAME}
    prefixes = ("app/src/main/jniLibs/", "app/src/main/assets/licenses/kokoro-native/", "build/kokoro-native/audit/")
    with zipfile.ZipFile(archive) as bundle:
        infos = bundle.infolist()
        require(len(infos) < 30000 and sum(i.file_size for i in infos) < 2_000_000_000, "Unexpected baseline artifact size")
        seen = set()
        selected = []
        for info in infos:
            native.safe_relative(info.filename)
            require(info.filename not in seen and not info.flag_bits & 1, "Duplicate/encrypted baseline ZIP entry")
            seen.add(info.filename)
            mode = info.external_attr >> 16
            require(not stat.S_ISLNK(mode) and (stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR)),
                    "Baseline ZIP contains links or special files")
            if not info.is_dir() and (info.filename in exact or info.filename.startswith(prefixes)):
                selected.append(info)
        require(selected, "No baseline native evidence found")
        for info in selected:
            output = target / native.safe_relative(info.filename)
            output.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(info) as source, output.open("xb") as destination:
                shutil.copyfileobj(source, destination)


def verify_raw():
    assets, libraries = RAW / "app/src/main/assets", RAW / "app/src/main/jniLibs"
    require(not any(p.is_symlink() for p in RAW.rglob("*")), "Linked baseline evidence")
    previous = native.ASSETS, native.JNILIBS
    native.ASSETS, native.JNILIBS = assets, libraries
    try:
        native.verify_runtime()  # The unchanged, strict dual-ABI verifier.
    finally:
        native.ASSETS, native.JNILIBS = previous
    provenance = json.loads((assets / BASE_NAME).read_text())
    require(provenance.get("ciCommit") == BASELINE["commit"], "Wrong native build commit")
    return provenance


def find_tools():
    ndk = native.find_ndk()
    hosts = [p for p in (ndk / "toolchains/llvm/prebuilt").glob("*") if (p / "bin/llvm-strip").is_file()]
    require(len(hosts) == 1, "Cannot identify existing NDK LLVM tools")
    tools = {name: hosts[0] / "bin" / ("llvm-" + name) for name in ("strip", "nm", "readelf")}
    require(all(p.is_file() and os.access(p, os.X_OK) for p in tools.values()), "Missing existing NDK tools")
    revision = re.search(r"^Pkg.Revision\s*=\s*(.+)$", (ndk / "source.properties").read_text(), re.M)
    require(revision and revision.group(1) == os.environ.get("INKDOCK_NDK_VERSION"), "Existing NDK and Gradle NDK revision differ")
    return tools, {"ndkRevision": revision.group(1), "stripArguments": ["--strip-unneeded"],
                   "tools": {name: {**file_record(path), "version": subprocess.check_output([str(path), "--version"], text=True).strip()}
                             for name, path in tools.items()}}


def full_audit(path, abi, name, tools, label):
    data = path.read_bytes()
    result = inspect_library(data, abi, name)
    full = subprocess.check_output([str(tools["nm"]), "-a", str(path)], text=True, stderr=subprocess.STDOUT)
    dynamic = subprocess.check_output([str(tools["nm"]), "--dynamic", str(path)], text=True, stderr=subprocess.STDOUT)
    elf = subprocess.check_output([str(tools["readelf"]), "-hlWd", str(path)], text=True)
    sections = subprocess.check_output([str(tools["readelf"]), "-SW", str(path)], text=True)
    require(not native.FORBIDDEN.search(full + dynamic + elf), "Forbidden phonemizer in raw full-symbol audit")
    if name.startswith("libsherpa"):
        require(re.search(r"\s\.symtab\s", sections), "Raw JNI stripped before full-symbol scan")
        require(all(symbol in dynamic for symbol in JNI_EXPORTS), "Missing raw TTS JNI export")
    for suffix, content in (("full-symbols.txt", full), ("dynamic-symbols.txt", dynamic), ("elf.txt", elf + sections)):
        (AUDIT / (label + "-" + suffix)).write_text(content)
    return result


def prepare(archive, evidence):
    tools, toolchain = find_tools()  # No mutation/download before prerequisites pass.
    gate = verify_evidence(evidence)
    require(not STAGING.exists() and not RAW.exists(), "Refusing to overwrite existing distribution output; use a fresh workspace")
    require(not (native.ASSETS / BASE_NAME).exists() and not (native.ASSETS / "licenses/kokoro-native").exists(),
            "Main assets contain generated raw runtime; use a fresh checkout for separate distribution sources")
    AUDIT.mkdir(parents=True, exist_ok=True)
    extract_baseline(archive, RAW)
    provenance = verify_raw()
    raw_inventory = []
    for abi in native.ABIS:
        for name in native.LIBRARIES:
            path = RAW / "app/src/main/jniLibs" / abi / name
            before = file_record(path)
            raw_inventory.append({"abi": abi, "file": name, **full_audit(path, abi, name, tools, abi + "-" + name + "-raw")})
            require(file_record(path) == before, "Raw baseline mutated during full-symbol audit")
    records = []
    for name in native.LIBRARIES:
        source = RAW / "app/src/main/jniLibs" / ABI / name
        target = STAGING / "jniLibs" / ABI / name
        target.parent.mkdir(parents=True, exist_ok=True)
        before = inspect_library(source.read_bytes(), ABI, name)
        shutil.copy2(source, target)  # llvm-strip is only ever given this disposable copy.
        subprocess.run([str(tools["strip"]), "--strip-unneeded", str(target)], check=True)
        after = inspect_library(target.read_bytes(), ABI, name, stripped=True)
        for field in ("minLoadAlignment", "needed", "soname", "dynamicSymbolsSha256", "dynamicSymbolCount"):
            require(before[field] == after[field], "Stripping changed runtime metadata: " + field)
        require(after["bytes"] <= before["bytes"], "Strip unexpectedly increased native size")
        require(inspect_library(source.read_bytes(), ABI, name) == before, "Stripping mutated the raw baseline")
        for suffix, command in (("elf.txt", ["readelf", "-hlWd"]), ("dynamic-symbols.txt", ["nm", "--dynamic"])):
            report = subprocess.check_output([str(tools[command[0]]), *command[1:], str(target)], text=True)
            require(not native.FORBIDDEN.search(report), "Forbidden phonemizer in stripped library report")
            (AUDIT / (ABI + "-" + name + "-stripped-" + suffix)).write_text(report)
        records.append({"abi": ABI, "file": name, "raw": before, "stripped": after})
    assets = STAGING / "assets"
    assets.mkdir(parents=True)
    shutil.copy2(RAW / "app/src/main/assets" / BASE_NAME, assets / BASE_NAME)
    shutil.copytree(RAW / "app/src/main/assets/licenses", assets / "licenses")
    evidence_output = AUDIT / "gate-evidence"
    shutil.copytree(evidence, evidence_output)
    write_json(AUDIT / "raw-library-inventory.json", raw_inventory)
    # Hash every retained raw library, original report, rerun symbol report and gate
    # input. These remain in the separate audit artifact, never inside the APK.
    audit_files = {str(p.relative_to(AUDIT)): file_record(p) for p in sorted(AUDIT.rglob("*")) if p.is_file()}
    manifest = {"schemaVersion": 1, "experimental": True, "kind": "arm64-stripped-distribution",
                "abis": [ABI], "baseline": BASELINE, "baselineProvenance": file_record(assets / BASE_NAME),
                "deviceGate": gate, "toolchain": toolchain, "libraries": records, "auditFiles": audit_files,
                "notices": provenance["notices"],
                "scope": "Full raw symbols were audited before stripping copies. Technical provenance checks, not legal signoff or an ARM64 device/voice-quality test. Debug-signed experimental candidate; no release."}
    write_json(assets / DIST_NAME, manifest)
    verify_runtime()
    print(json.dumps({"arm64RawLibraryBytes": sum(r["raw"]["bytes"] for r in records),
                      "arm64StrippedLibraryBytes": sum(r["stripped"]["bytes"] for r in records)}, indent=2))


def verify_runtime(apk=None):
    provenance = verify_raw()
    manifest_path = STAGING / "assets" / DIST_NAME
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    require(manifest.get("schemaVersion") == 1 and manifest.get("experimental") is True and
            manifest.get("kind") == "arm64-stripped-distribution" and manifest.get("abis") == [ABI], "Wrong distribution manifest")
    require(manifest.get("baseline") == BASELINE, "Wrong distribution baseline")
    require(manifest.get("toolchain", {}).get("stripArguments") == ["--strip-unneeded"], "Wrong strip operation")
    verify_file(RAW / "app/src/main/assets" / BASE_NAME, manifest["baselineProvenance"])
    require(manifest["notices"] == provenance["notices"], "Distribution changed native notice inventory")
    require(manifest.get("auditFiles"), "Missing retained audit evidence")
    audit_paths = {str(p.relative_to(AUDIT)) for p in AUDIT.rglob("*") if p.is_file()}
    require(set(manifest["auditFiles"]) == audit_paths, "Retained audit file inventory changed")
    required_evidence = set()
    for abi in native.ABIS:
        for name in native.LIBRARIES:
            required_evidence.add("baseline/app/src/main/jniLibs/" + abi + "/" + name)
            required_evidence.add("baseline/build/kokoro-native/audit/" + abi + "-" + name + "-symbols.txt")
            for suffix in ("full-symbols.txt", "dynamic-symbols.txt", "elf.txt"):
                required_evidence.add(abi + "-" + name + "-raw-" + suffix)
    for name in native.LIBRARIES:
        for suffix in ("dynamic-symbols.txt", "elf.txt"):
            required_evidence.add(ABI + "-" + name + "-stripped-" + suffix)
    require(required_evidence <= audit_paths, "Missing raw/debug symbol evidence or stripped reports")
    for relative, record in manifest["auditFiles"].items():
        verify_file(AUDIT / native.safe_relative(relative), record)
    require(manifest["deviceGate"] == verify_evidence(AUDIT / "gate-evidence"), "Changed device gate evidence")
    if os.environ.get("GITHUB_SHA"):
        require(manifest["deviceGate"]["currentCommit"] == os.environ["GITHUB_SHA"], "Distribution belongs to a different source commit")
    # Recheck all raw full-symbol tables, including the ABI excluded from distribution.
    for record in provenance["libraries"]:
        inspect_library((RAW / "app/src/main/jniLibs" / record["abi"] / record["file"]).read_bytes(), record["abi"], record["file"])
    expected = {"lib/" + ABI + "/" + name for name in native.LIBRARIES}
    with zipfile.ZipFile(apk) if apk else _NullArchive() as archive:
        if archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Duplicate APK entries")
            read = archive.read
        else:
            names = ["lib/" + str(p.relative_to(STAGING / "jniLibs")) for p in (STAGING / "jniLibs").rglob("*") if p.is_file()]
            def read(name):
                path = STAGING / "jniLibs" / native.safe_relative(name[4:]) if name.startswith("lib/") else STAGING / "assets" / native.safe_relative(name[7:])
                require(not path.is_symlink(), "Distribution contains a symbolic link")
                return path.read_bytes()
        require({n for n in names if n.startswith("lib/") and not n.endswith("/")} == expected, "Distribution must contain exactly two ARM64 libraries")
        require(read("assets/" + DIST_NAME) == manifest_bytes, "Packaged distribution provenance mismatch")
        require(read("assets/" + BASE_NAME) == (RAW / "app/src/main/assets" / BASE_NAME).read_bytes(), "Packaged baseline provenance mismatch")
        records = manifest.get("libraries", [])
        require(len(records) == len(native.LIBRARIES) and {r["file"] for r in records} == set(native.LIBRARIES), "Wrong distribution library records")
        for record in records:
            name = record["file"]
            require(record["abi"] == ABI, "Unexpected distribution ABI")
            raw = inspect_library((RAW / "app/src/main/jniLibs" / ABI / name).read_bytes(), ABI, name)
            stripped = inspect_library(read("lib/" + ABI + "/" + name), ABI, name, stripped=True)
            require(raw == record["raw"] and stripped == record["stripped"], "Raw/stripped library provenance mismatch")
            for field in ("minLoadAlignment", "needed", "soname", "dynamicSymbolsSha256", "dynamicSymbolCount"):
                require(raw[field] == stripped[field], "Strip changed runtime metadata: " + field)
            if archive:
                info = archive.getinfo("lib/" + ABI + "/" + name)
                require(info.compress_type == zipfile.ZIP_STORED, "Expected directly mmap-able uncompressed native library")
                with Path(apk).open("rb") as stream:
                    stream.seek(info.header_offset)
                    header = stream.read(30)
                require(header[:4] == b"PK\x03\x04", "Invalid APK local ZIP header")
                name_len, extra_len = struct.unpack_from("<HH", header, 26)
                require((info.header_offset + 30 + name_len + extra_len) % 16384 == 0, "APK native-library offset is not 16KB aligned")
        for record in provenance["notices"]:
            data = read("assets/licenses/kokoro-native/" + str(native.safe_relative(record["path"])))
            require(data_sha(data) == record["sha256"] and len(data) == record["bytes"], "Packaged native notice mismatch")
    if apk:
        report = {"apk": file_record(Path(apk)), "sourceCommit": manifest["deviceGate"]["currentCommit"],
                  "deviceGate": manifest["deviceGate"], "abis": [ABI], "libraries": records,
                  "rawBothAbiLibraryBytes": sum(r["bytes"] for r in provenance["libraries"]),
                  "rawArm64LibraryBytes": sum(r["raw"]["bytes"] for r in records),
                  "strippedArm64LibraryBytes": sum(r["stripped"]["bytes"] for r in records),
                  "checks": ["Exact SHA-256 before/after", "Raw dual-ABI full-symbol evidence retained", "16KB ELF LOAD and APK offsets", "Unchanged dynamic symbols/JNI exports/DT_NEEDED", "Original notices retained"]}
        write_json(WORK / "reports/final-apk-audit.json", report)
    print("Verified stripped ARM64 distribution" + (" APK" if apk else " before Gradle packaging") + "; raw dual-ABI baseline and full-symbol audit retained separately.")


class _NullArchive:
    def __enter__(self):
        return None

    def __exit__(self, *_):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare", type=Path, metavar="BASELINE_ZIP")
    mode.add_argument("--verify-runtime", action="store_true")
    mode.add_argument("--audit-apk", type=Path)
    mode.add_argument("--verify-evidence", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.prepare:
        require(args.evidence and args.evidence.is_dir(), "--prepare requires --evidence directory")
        prepare(args.prepare, args.evidence)
    elif args.verify_evidence:
        print(json.dumps(verify_evidence(args.verify_evidence), indent=2))
    else:
        verify_runtime(args.audit_apk)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, KeyError, ValueError, struct.error, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit("Kokoro ARM64 distribution gate failed: " + str(error)) from error
