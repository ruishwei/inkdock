#!/usr/bin/env python3
"""Offline regression fixtures for the experimental runtime and final APK gate.

The ELF fixtures are deliberately non-executable data, kept only in a temporary
folder. No real app binaries, notices, environment settings or network are used.
"""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("kokoro_native", ROOT / "scripts/build-kokoro-native.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


def fixture_elf(abi, name):
    data = bytearray(1024)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 16, 3)
    struct.pack_into("<H", data, 18, {"arm64-v8a": 183, "x86_64": 62}[abi])
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 1024, 1024, 16384)
    dependency = b"libonnxruntime.so" if name.startswith("libsherpa") else b"libc.so"
    strings = b"\0" + name.encode() + b"\0" + dependency + b"\0"
    entries = [(5, 512), (10, len(strings)), (14, 1), (1, len(name) + 2), (0, 0)]
    struct.pack_into("<IIQQQQQQ", data, 120, 2, 6, 256, 256, 0, len(entries) * 16, len(entries) * 16, 8)
    for index, (tag, value) in enumerate(entries):
        struct.pack_into("<qQ", data, 256 + index * 16, tag, value)
    data[512:512 + len(strings)] = strings
    return bytes(data)


def run_tests(root):
    native.ASSETS.mkdir()
    native.JNILIBS.mkdir()
    notes = native.ASSETS / "licenses/kokoro-native"
    notes.mkdir(parents=True)
    records, notices, dependencies = [], [], []
    for name in ["sherpa-onnx", "android-ndk", *native.DEPENDENCIES]:
        (notes / name).mkdir()
        paths = []
        for filename in (native.ONNX_NOTICE_SHA if name == "onnxruntime" else ["LICENSE"]):
            data = ("Fixture " + name + "/" + filename).encode()
            path = notes / name / filename
            path.write_bytes(data)
            relative = str(path.relative_to(notes))
            paths.append(relative)
            notices.append({"path": relative, "sha256": native.digest(path), "bytes": len(data)})
        if name in native.DEPENDENCIES:
            version, license_name, sha = native.DEPENDENCIES[name]
            dependencies.append({"name": name, "version": version, "declaredLicense": license_name,
                                 "sourceArchiveSha256": sha, "noticePaths": paths})
    for abi in native.ABIS:
        (native.JNILIBS / abi).mkdir()
        for name in native.LIBRARIES:
            data = fixture_elf(abi, name)
            path = native.JNILIBS / abi / name
            path.write_bytes(data)
            records.append({"abi": abi, "file": name, "sha256": native.digest(path), "bytes": len(data),
                            "minLoadAlignment": native.inspect_elf(data, abi), "needed": native.elf_dynamic(data)[0]})
    original = {"schemaVersion": 1, "experimental": True, "sourceCommit": native.SOURCE_COMMIT,
                "sourceSha256": native.SOURCE_SHA, "upstreamPr": native.UPSTREAM_PR,
                "abis": list(native.ABIS), "androidApi": 26, "eigenMpl2Only": True,
                "libraries": records, "dependencies": dependencies, "notices": notices,
                "supplementalOnnxNotices": {"sourceCommit": native.ONNX_SOURCE_COMMIT, "sha256": native.ONNX_NOTICE_SHA}}
    provenance_file = native.ASSETS / "kokoro-native-build.json"

    def verify(provenance):
        provenance_file.write_text(json.dumps(provenance))
        with contextlib.redirect_stdout(io.StringIO()):
            native.verify_runtime()

    verify(original)
    passed = 1
    mutations = [
        ("wrong source", lambda p: p.update(sourceCommit="0" * 40)),
        ("wrong source hash", lambda p: p.update(sourceSha256="0" * 64)),
        ("missing ABI", lambda p: p.update(abis=["arm64-v8a"])),
        ("not experimental", lambda p: p.update(experimental=False)),
        ("missing library", lambda p: p["libraries"].pop()),
        ("wrong library hash", lambda p: p["libraries"][0].update(sha256="0" * 64)),
        ("wrong dependency hash", lambda p: p["dependencies"][0].update(sourceArchiveSha256="0" * 64)),
        ("wrong notice hash", lambda p: p["notices"][0].update(sha256="0" * 64)),
        ("wrong DT_NEEDED", lambda p: p["libraries"][0].update(needed=["libc.so"])),
        ("duplicate library record", lambda p: p["libraries"].__setitem__(1, p["libraries"][0])),
        ("notice path traversal", lambda p: p["notices"][0].update(path="../escape")),
    ]
    for label, mutate in mutations:
        provenance = copy.deepcopy(original)
        mutate(provenance)
        try:
            verify(provenance)
        except RuntimeError:
            passed += 1
        else:
            raise AssertionError("Accepted " + label)
    verify(original)
    unexpected = native.JNILIBS / "arm64-v8a/libunexpected.so"
    unexpected.write_bytes(b"unexpected")
    try:
        verify(original)
    except RuntimeError:
        passed += 1
    else:
        raise AssertionError("Accepted unexpected library")
    unexpected.unlink()
    verify(original)
    apk = root / "fixture.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        for path in native.ASSETS.rglob("*"):
            if path.is_file():
                archive.write(path, "assets/" + str(path.relative_to(native.ASSETS)))
        for path in native.JNILIBS.rglob("*.so"):
            archive.write(path, "lib/" + str(path.relative_to(native.JNILIBS)))
    with contextlib.redirect_stdout(io.StringIO()):
        native.verify_runtime(apk)
    passed += 1
    print(f"Passed {passed} offline native provenance/APK fixtures, including deliberate tampering")


if __name__ == "__main__":
    # Patch only the imported test module and restore on exit. Never change the
    # production script or weaken its pinned notice hashes on disk.
    fixture_notice_hashes = {
        filename: hashlib.sha256(("Fixture onnxruntime/" + filename).encode()).hexdigest()
        for filename in native.ONNX_NOTICE_SHA
    }
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        with patch.object(native, "ASSETS", root / "assets"), \
                patch.object(native, "JNILIBS", root / "jniLibs"), \
                patch.object(native, "ONNX_NOTICE_SHA", fixture_notice_hashes):
            run_tests(root)
