#!/usr/bin/env python3
"""Offline fixtures for distribution gates; no real binaries or NDK are used."""
import base64
import contextlib
import hashlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("arm64_distribution", ROOT / "scripts/prepare-kokoro-arm64.py")
dist = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dist)
spec = importlib.util.spec_from_file_location("raw_fixtures", ROOT / "scripts/test-kokoro-native.py")
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
native = dist.native


def elf(abi, name, stripped=False, *, missing_jni=False, forbidden=False, undefined_jni=False):
    data = bytearray(fixtures.fixture_elf(abi, name))
    data.extend(bytes(5000 - len(data)))
    section_names = ["", ".shstrtab", ".dynstr", ".dynsym"] + ([] if stripped else [".symtab", ".debug_info"])
    strings = b"\0"
    section_offsets = []
    for section in section_names:
        section_offsets.append(len(strings))
        strings += section.encode() + b"\0"
    data[1024:1024 + len(strings)] = strings
    names = sorted(dist.JNI_EXPORTS) if name.startswith("libsherpa") else ["OrtGetApiBase"]
    if missing_jni:
        names.pop()
    if forbidden:
        names.append("espeak_synth")
    dynstrings = b"\0"
    entries = [bytes(24)]
    for symbol in names:
        index = len(dynstrings)
        dynstrings += symbol.encode() + b"\0"
        entries.append(struct.pack("<IBBHQQ", index, 0x12, 0, 0 if undefined_jni else 1, 256, 16))
    data[1200:1200 + len(dynstrings)] = dynstrings
    dynsymbols = b"".join(entries)
    data[3000:3000 + len(dynsymbols)] = dynsymbols
    headers = [(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
               (section_offsets[1], 3, 0, 0, 1024, len(strings), 0, 0, 1, 0),
               (section_offsets[2], 3, 2, 0, 1200, len(dynstrings), 0, 0, 1, 0),
               (section_offsets[3], 11, 2, 0, 3000, len(dynsymbols), 2, 1, 8, 24)]
    if not stripped:
        headers.extend([(section_offsets[4], 2, 0, 0, 3600, 24, 2, 0, 8, 24),
                        (section_offsets[5], 1, 0, 0, 3700, 16, 0, 0, 1, 0)])
    for i, header in enumerate(headers):
        struct.pack_into("<IIQQQQIIQQ", data, 4000 + i * 64, *header)
    length = 4000 + len(headers) * 64
    data = data[:length]
    struct.pack_into("<Q", data, 40, 4000)
    struct.pack_into("<HHH", data, 58, 64, len(headers), 1)
    struct.pack_into("<QQ", data, 96, length, length)
    return bytes(data)


def evidence(path):
    path.mkdir(parents=True)
    baseline = dist.BASELINE
    fixed = ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "package.json", "package-lock.json",
             "scripts/build-kokoro-native.py", "scripts/test-kokoro-native.py", "scripts/kokoro-device-models.py",
             ".github/workflows/kokoro-device.yml", "scripts/vendor.mjs", "app/src/main/example.kt"]
    tree = {"sha": "b" * 40, "truncated": False, "tree": [
        {"path": name, "sha": "c" * 40, "mode": "100644", "type": "blob"} for name in fixed]}
    gradle_data = b"plugins { }\n"
    gradle_sha = hashlib.sha1(b"blob " + str(len(gradle_data)).encode() + b"\0" + gradle_data).hexdigest()
    gradle = {"path": "app/build.gradle.kts", "encoding": "base64", "sha": gradle_sha,
              "content": base64.b64encode(gradle_data).decode()}
    tree["tree"].append({"path": "app/build.gradle.kts", "sha": gradle_sha, "mode": "100644", "type": "blob"})
    docs = {
        "current-app-gradle.json": gradle, "device-app-gradle.json": gradle,
        "baseline-run.json": {"id": baseline["runId"], "head_sha": baseline["commit"], "head_branch": "experiment/kokoro-native",
                              "repository": {"full_name": baseline["repository"]}, "status": "completed", "conclusion": "success"},
        "baseline-artifacts.json": {"artifacts": [{"id": baseline["artifactId"], "name": baseline["artifactName"], "expired": False,
            "digest": "sha256:" + baseline["artifactSha256"], "workflow_run": {"id": baseline["runId"], "head_sha": baseline["commit"]}}]},
        "device-run.json": {"id": 42, "head_sha": "d" * 40, "head_branch": "experiment/kokoro-buffer", "event": "workflow_dispatch",
                            "repository": {"full_name": baseline["repository"]}, "path": ".github/workflows/kokoro-device.yml",
                            "status": "completed", "conclusion": "success"},
        "current-commit.json": {"sha": "a" * 40, "tree": {"sha": tree["sha"]}},
        "device-commit.json": {"sha": "d" * 40, "tree": {"sha": tree["sha"]}},
        "current-tree.json": tree, "device-tree.json": tree,
    }
    for name, value in docs.items():
        dist.write_json(path / name, value)
    return docs


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.work = self.root / "work"
        self.audit = self.work / "audit"
        self.raw = self.audit / "baseline"
        self.staging = self.work / "staging"
        self.patches = [patch.object(dist, "WORK", self.work), patch.object(dist, "AUDIT", self.audit),
                        patch.object(dist, "RAW", self.raw), patch.object(dist, "STAGING", self.staging),
                        patch.dict(os.environ, {"GITHUB_SHA": "a" * 40})]
        for item in self.patches:
            item.start()
        self.addCleanup(self.temporary.cleanup)
        for item in reversed(self.patches):
            self.addCleanup(item.stop)

    def quiet(self, function, *args):
        with contextlib.redirect_stdout(io.StringIO()):
            return function(*args)

    def test_raw_and_stripped_symbols_equal(self):
        for abi in native.ABIS:
            for name in native.LIBRARIES:
                before = dist.inspect_library(elf(abi, name), abi, name)
                after = dist.inspect_library(elf(abi, name, True), abi, name, stripped=True)
                self.assertEqual(before["dynamicSymbolsSha256"], after["dynamicSymbolsSha256"])
                self.assertLess(after["bytes"], before["bytes"])

    def test_missing_export_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Missing defined"):
            dist.inspect_library(elf(dist.ABI, native.LIBRARIES[0], True, missing_jni=True), dist.ABI, native.LIBRARIES[0], stripped=True)

    def test_undefined_export_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Missing defined"):
            dist.inspect_library(elf(dist.ABI, native.LIBRARIES[0], True, undefined_jni=True), dist.ABI, native.LIBRARIES[0], stripped=True)

    def test_forbidden_dynamic_symbol_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Forbidden"):
            dist.inspect_library(elf(dist.ABI, native.LIBRARIES[0], True, forbidden=True), dist.ABI, native.LIBRARIES[0], stripped=True)

    def test_full_symbols_required_before_strip(self):
        with self.assertRaisesRegex(RuntimeError, "full symbol table"):
            dist.inspect_library(elf(dist.ABI, native.LIBRARIES[0], True), dist.ABI, native.LIBRARIES[0])

    def test_debug_symbols_rejected_in_distribution(self):
        with self.assertRaisesRegex(RuntimeError, "still contains"):
            dist.inspect_library(elf(dist.ABI, native.LIBRARIES[0]), dist.ABI, native.LIBRARIES[0], stripped=True)

    def test_wrong_machine_and_4kb_rejected(self):
        data = bytearray(elf(dist.ABI, native.LIBRARIES[0], True))
        with self.assertRaisesRegex(RuntimeError, "machine"):
            dist.inspect_library(data, "x86_64", native.LIBRARIES[0], stripped=True)
        struct.pack_into("<Q", data, 112, 4096)
        with self.assertRaisesRegex(RuntimeError, "16KB"):
            dist.inspect_library(data, dist.ABI, native.LIBRARIES[0], stripped=True)

    def test_corrupt_sections_rejected(self):
        data = bytearray(elf(dist.ABI, native.LIBRARIES[0], True))
        struct.pack_into("<Q", data, 40, len(data) - 1)
        with self.assertRaisesRegex(RuntimeError, "section headers"):
            dist.inspect_library(data, dist.ABI, native.LIBRARIES[0], stripped=True)

    def test_evidence_success(self):
        evidence(self.root / "evidence")
        self.assertEqual(dist.verify_evidence(self.root / "evidence")["runId"], 42)

    def test_failed_running_or_wrong_device_run_rejected(self):
        path = self.root / "evidence"
        docs = evidence(path)
        for field, value in (("conclusion", "failure"), ("status", "in_progress"), ("path", ".github/workflows/other.yml"),
                             ("head_branch", "main"), ("event", "push")):
            with self.subTest(field=field):
                modified = copy.deepcopy(docs["device-run.json"])
                modified[field] = value
                dist.write_json(path / "device-run.json", modified)
                with self.assertRaisesRegex(RuntimeError, "Device gate"):
                    dist.verify_evidence(path)

    def test_changed_sources_and_truncated_tree_rejected(self):
        path = self.root / "evidence"
        docs = evidence(path)
        modified = copy.deepcopy(docs["current-tree.json"])
        modified["tree"][-2]["sha"] = "e" * 40
        dist.write_json(path / "current-tree.json", modified)
        with self.assertRaisesRegex(RuntimeError, "changed since"):
            dist.verify_evidence(path)
        modified["truncated"] = True
        dist.write_json(path / "current-tree.json", modified)
        with self.assertRaisesRegex(RuntimeError, "Incomplete"):
            dist.verify_evidence(path)

    def test_added_implicit_build_inputs_rejected(self):
        path = self.root / "evidence"
        docs = evidence(path)
        for extra in ("buildSrc/build.gradle.kts", "buildSrc/src/main/kotlin/Plugin.kt",
                      "gradle/libs.versions.toml", "app/proguard-rules.pro", "gradle/init.d/injected.gradle"):
            with self.subTest(extra=extra):
                modified = copy.deepcopy(docs["current-tree.json"])
                modified["tree"].append({"path": extra, "sha": "e" * 40, "mode": "100644", "type": "blob"})
                dist.write_json(path / "current-tree.json", modified)
                with self.assertRaisesRegex(RuntimeError, "Repository inputs changed"):
                    dist.verify_evidence(path)

    def test_only_distribution_and_docs_may_be_added(self):
        path = self.root / "evidence"
        docs = evidence(path)
        modified = copy.deepcopy(docs["current-tree.json"])
        for extra in (".github/workflows/kokoro-arm64-distribution.yml", "scripts/prepare-kokoro-arm64.py",
                      "scripts/test-kokoro-arm64.py", "docs/kokoro-arm64-distribution.md"):
            modified["tree"].append({"path": extra, "sha": "e" * 40, "mode": "100644", "type": "blob"})
        # Directory hashes change as new allowed files are added; files are still compared.
        modified["tree"].append({"path": "scripts", "sha": "f" * 40, "mode": "040000", "type": "tree"})
        dist.write_json(path / "current-tree.json", modified)
        self.assertEqual(dist.verify_evidence(path)["runId"], 42)
        modified["tree"][-2]["mode"] = "120000"
        dist.write_json(path / "current-tree.json", modified)
        with self.assertRaisesRegex(RuntimeError, "not a regular file"):
            dist.verify_evidence(path)

    def test_unreviewed_app_gradle_change_rejected(self):
        path = self.root / "evidence"
        docs = evidence(path)
        data = b"plugins { }\ndependencies { implementation(\"changed\") }\n"
        sha = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        docs["current-app-gradle.json"] = {"path": "app/build.gradle.kts", "encoding": "base64", "sha": sha,
                                          "content": base64.b64encode(data).decode()}
        docs["current-tree.json"]["tree"][-1]["sha"] = sha
        for name in ("current-app-gradle.json", "current-tree.json"):
            dist.write_json(path / name, docs[name])
        with self.assertRaisesRegex(RuntimeError, "beyond the exact"):
            dist.verify_evidence(path)

    def test_exact_gradle_opt_in_normalization(self):
        current = (ROOT / "app/build.gradle.kts").read_text()
        original = current
        for before, after in dist.GRADLE_SUBSTITUTIONS:
            self.assertEqual(original.count(before), 1)
            original = original.replace(before, after)
        self.assertNotIn("arm64Distribution", original)
        self.assertIn('commandLine("python3", "scripts/build-kokoro-native.py", "--verify-runtime")', original)

    def test_baseline_identity_and_digest_rejected(self):
        path = self.root / "evidence"
        docs = evidence(path)
        docs["baseline-artifacts.json"]["artifacts"][0]["digest"] = "sha256:" + "0" * 64
        dist.write_json(path / "baseline-artifacts.json", docs["baseline-artifacts.json"])
        with self.assertRaisesRegex(RuntimeError, "immutable"):
            dist.verify_evidence(path)
        archive = self.root / "wrong.zip"
        archive.write_bytes(b"wrong archive")
        with self.assertRaisesRegex(RuntimeError, "archive SHA"):
            dist.extract_baseline(archive, self.raw)
        self.assertFalse(self.raw.exists())

    def test_archive_traversal_symlink_and_duplicates_rejected(self):
        for kind in ("traversal", "symlink", "duplicate"):
            with self.subTest(kind=kind):
                archive = self.root / (kind + ".zip")
                with zipfile.ZipFile(archive, "w") as bundle:
                    if kind == "traversal":
                        bundle.writestr("../escape", b"bad")
                    elif kind == "symlink":
                        info = zipfile.ZipInfo("app/src/main/jniLibs/link")
                        info.external_attr = 0o120777 << 16
                        bundle.writestr(info, b"/tmp/escape")
                    else:
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter("ignore")
                            bundle.writestr("file", b"one")
                            bundle.writestr("file", b"two")
                with patch.dict(dist.BASELINE, {"artifactSha256": dist.sha256(archive)}):
                    with self.assertRaises(RuntimeError):
                        dist.extract_baseline(archive, self.raw)
                self.assertFalse(self.raw.exists())

    def install_fixture(self):
        assets = self.raw / "app/src/main/assets"
        notes = assets / "licenses/kokoro-native"
        libraries = self.raw / "app/src/main/jniLibs"
        records, notices, dependencies = [], [], []
        notice_hashes = {}
        for name in ["sherpa-onnx", "android-ndk", *native.DEPENDENCIES]:
            paths = []
            for filename in (native.ONNX_NOTICE_SHA if name == "onnxruntime" else ["LICENSE"]):
                path = notes / name / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("Fixture " + name + "/" + filename)
                relative = str(path.relative_to(notes))
                paths.append(relative)
                notices.append({"path": relative, **dist.file_record(path)})
                if name == "onnxruntime":
                    notice_hashes[filename] = dist.sha256(path)
            if name in native.DEPENDENCIES:
                version, license_name, sha = native.DEPENDENCIES[name]
                dependencies.append({"name": name, "version": version, "declaredLicense": license_name,
                                     "sourceArchiveSha256": sha, "noticePaths": paths})
        for abi in native.ABIS:
            for name in native.LIBRARIES:
                path = libraries / abi / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(elf(abi, name))
                inspected = dist.inspect_library(path.read_bytes(), abi, name)
                records.append({"abi": abi, "file": name, **{k: inspected[k] for k in ("sha256", "bytes", "minLoadAlignment", "needed")}})
        provenance = {"schemaVersion": 1, "experimental": True, "sourceCommit": native.SOURCE_COMMIT,
                      "sourceSha256": native.SOURCE_SHA, "upstreamPr": native.UPSTREAM_PR,
                      "abis": list(native.ABIS), "androidApi": 26, "eigenMpl2Only": True,
                      "ciCommit": dist.BASELINE["commit"], "libraries": records, "dependencies": dependencies, "notices": notices,
                      "supplementalOnnxNotices": {"sourceCommit": native.ONNX_SOURCE_COMMIT, "sha256": notice_hashes}}
        dist.write_json(assets / dist.BASE_NAME, provenance)
        import shutil
        shutil.copytree(assets, self.staging / "assets")
        distribution_records = []
        for name in native.LIBRARIES:
            path = self.staging / "jniLibs" / dist.ABI / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(elf(dist.ABI, name, True))
            distribution_records.append({"abi": dist.ABI, "file": name,
                                         "raw": dist.inspect_library((libraries / dist.ABI / name).read_bytes(), dist.ABI, name),
                                         "stripped": dist.inspect_library(path.read_bytes(), dist.ABI, name, stripped=True)})
        evidence(self.audit / "gate-evidence")
        for abi in native.ABIS:
            for name in native.LIBRARIES:
                original_report = self.raw / "build/kokoro-native/audit" / (abi + "-" + name + "-symbols.txt")
                original_report.parent.mkdir(parents=True, exist_ok=True)
                original_report.write_text("Fixture original full-symbol audit\n")
                for suffix in ("full-symbols.txt", "dynamic-symbols.txt", "elf.txt"):
                    (self.audit / (abi + "-" + name + "-raw-" + suffix)).write_text("Fixture repeated raw audit\n")
        for name in native.LIBRARIES:
            for suffix in ("dynamic-symbols.txt", "elf.txt"):
                (self.audit / (dist.ABI + "-" + name + "-stripped-" + suffix)).write_text("Fixture stripped audit\n")
        audit_files = {str(p.relative_to(self.audit)): dist.file_record(p) for p in self.audit.rglob("*") if p.is_file()}
        manifest = {"schemaVersion": 1, "experimental": True, "kind": "arm64-stripped-distribution", "abis": [dist.ABI],
                    "baseline": dist.BASELINE, "baselineProvenance": dist.file_record(assets / dist.BASE_NAME), "notices": notices,
                    "toolchain": {"stripArguments": ["--strip-unneeded"]}, "deviceGate": dist.verify_evidence(self.audit / "gate-evidence"),
                    "libraries": distribution_records, "auditFiles": audit_files}
        dist.write_json(self.staging / "assets" / dist.DIST_NAME, manifest)
        patches = [patch.object(native, "ASSETS", assets), patch.object(native, "JNILIBS", libraries),
                   patch.object(native, "ONNX_NOTICE_SHA", notice_hashes)]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)
        return manifest

    def apk(self, aligned=True, extra_abi=False):
        archive = self.root / "fixture.apk"
        with zipfile.ZipFile(archive, "w") as bundle:
            for path in (self.staging / "assets").rglob("*"):
                if path.is_file():
                    bundle.write(path, "assets/" + str(path.relative_to(self.staging / "assets")))
            for path in (self.staging / "jniLibs").rglob("*.so"):
                name = "lib/" + str(path.relative_to(self.staging / "jniLibs"))
                info = zipfile.ZipInfo(name)
                if aligned:
                    padding = (-(bundle.fp.tell() + 30 + len(name.encode()))) % 16384
                    if padding and padding < 4:
                        padding += 16384
                    if padding:
                        info.extra = struct.pack("<HH", 0xFFFF, padding - 4) + bytes(padding - 4)
                bundle.writestr(info, path.read_bytes())
            if extra_abi:
                bundle.writestr("lib/x86_64/libsherpa-onnx-jni.so", b"unexpected")
        return archive

    def test_hidden_forbidden_raw_symbol_rejected(self):
        self.audit.mkdir(parents=True)
        path = self.root / "raw.so"
        path.write_bytes(elf(dist.ABI, native.LIBRARIES[0]))
        tools = {name: Path("/fixture/llvm-" + name) for name in ("nm", "readelf")}
        def report(command, **kwargs):
            if "-a" in command:
                return "0000 t espeak_hidden_function\n"
            if "--dynamic" in command:
                return "\n".join(sorted(dist.JNI_EXPORTS))
            if "-SW" in command:
                return "  .symtab  SYMTAB\n"
            return "ELF fixture\n"
        with patch.object(dist.subprocess, "check_output", side_effect=report):
            with self.assertRaisesRegex(RuntimeError, "raw full-symbol audit"):
                dist.full_audit(path, dist.ABI, native.LIBRARIES[0], tools, "fixture")

    def test_prepare_strips_only_copies_and_preserves_both_raw_abis(self):
        import shutil
        self.install_fixture()
        source = self.root / "input-baseline"
        inputs = self.root / "input-evidence"
        shutil.copytree(self.raw, source)
        shutil.copytree(self.audit / "gate-evidence", inputs)
        originals = {str(p.relative_to(source)): dist.file_record(p) for p in source.rglob("*") if p.is_file()}
        shutil.rmtree(self.work)
        tools = {name: Path("/fixture/llvm-" + name) for name in ("strip", "nm", "readelf")}
        called = []
        def strip(command, **kwargs):
            self.assertEqual(command[:2], [str(tools["strip"]), "--strip-unneeded"])
            target = Path(command[2])
            self.assertTrue(target.is_relative_to(self.staging / "jniLibs"))
            self.assertFalse(target.is_relative_to(self.raw))
            called.append(target)
            target.write_bytes(elf(dist.ABI, target.name, True))
        def report(command, **kwargs):
            if "--dynamic" in command:
                return "\n".join(sorted(dist.JNI_EXPORTS))
            if "-SW" in command:
                return "  .symtab  SYMTAB\n"
            return "ELF/symbol fixture\n"
        with patch.object(dist, "find_tools", return_value=(tools, {"stripArguments": ["--strip-unneeded"]})), \
                patch.object(dist, "extract_baseline", side_effect=lambda _, target: shutil.copytree(source, target)), \
                patch.object(dist.subprocess, "check_output", side_effect=report), \
                patch.object(dist.subprocess, "run", side_effect=strip):
            self.quiet(dist.prepare, self.root / "mock-baseline.zip", inputs)
        self.assertEqual(len(called), 2)
        for relative, record in originals.items():
            self.assertEqual(dist.file_record(self.raw / relative), record)
        raw_libraries = list((self.raw / "app/src/main/jniLibs").rglob("*.so"))
        self.assertEqual(len(raw_libraries), 4)
        self.assertEqual(len(list(self.audit.glob("*-raw-full-symbols.txt"))), 4)
        self.quiet(dist.verify_runtime)

    def test_full_runtime_and_final_apk_audit(self):
        self.install_fixture()
        self.quiet(dist.verify_runtime)
        self.quiet(dist.verify_runtime, self.apk())
        report = json.loads((self.work / "reports/final-apk-audit.json").read_text())
        self.assertEqual(report["abis"], [dist.ABI])
        self.assertEqual(report["apk"]["bytes"], (self.root / "fixture.apk").stat().st_size)

    def test_extra_abi_and_unaligned_apk_rejected(self):
        self.install_fixture()
        with self.assertRaisesRegex(RuntimeError, "exactly two ARM64"):
            self.quiet(dist.verify_runtime, self.apk(extra_abi=True))
        with self.assertRaisesRegex(RuntimeError, "not 16KB aligned"):
            self.quiet(dist.verify_runtime, self.apk(aligned=False))

    def test_stripped_hash_and_raw_evidence_tamper_rejected(self):
        self.install_fixture()
        target = self.staging / "jniLibs" / dist.ABI / native.LIBRARIES[0]
        original = target.read_bytes()
        changed = bytearray(original)
        changed[200] ^= 1
        target.write_bytes(changed)
        with self.assertRaisesRegex(RuntimeError, "provenance mismatch"):
            self.quiet(dist.verify_runtime)
        target.write_bytes(original)
        raw = self.raw / "app/src/main/jniLibs/x86_64" / native.LIBRARIES[0]
        raw.write_bytes(b"changed")
        with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
            self.quiet(dist.verify_runtime)

    def test_dropping_full_symbol_evidence_rejected(self):
        manifest = self.install_fixture()
        relative = dist.ABI + "-" + native.LIBRARIES[0] + "-raw-full-symbols.txt"
        (self.audit / relative).unlink()
        del manifest["auditFiles"][relative]
        dist.write_json(self.staging / "assets" / dist.DIST_NAME, manifest)
        with self.assertRaisesRegex(RuntimeError, "Missing raw/debug symbol evidence"):
            self.quiet(dist.verify_runtime)

    def test_notice_tamper_rejected(self):
        self.install_fixture()
        (self.staging / "assets/licenses/kokoro-native/sherpa-onnx/LICENSE").write_bytes(b"changed")
        with self.assertRaisesRegex(RuntimeError, "notice mismatch"):
            self.quiet(dist.verify_runtime)


if __name__ == "__main__":
    unittest.main(verbosity=2)
