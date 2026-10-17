#!/usr/bin/env python3
"""Stage only fixed public Kokoro models on the workflow's disposable emulator.

No APK, JNI, SDK, NDK or license installation is performed. No private user text,
credentials or cloud speech endpoint is used. All network downloads are opt-in.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app/src/main/java/org/inkdock/reader/KokoroManifest.kt"
PACKAGE = "org.inkdock.reader"
VARIANTS = ("float", "int8")
EXPECTED_INT8 = ("model.int8.onnx", "voices.bin", "lexicon-zh.txt", "tokens.txt", "number-zh.fst", "date-zh.fst", "phone-zh.fst", "LICENSE")
REVISIONS = {
    "kokoro-int8-multi-lang-v1_1": "155831f1b4ba23b1f5c058be6a61df90cefb2a37",
    "kokoro-multi-lang-v1_1": "914313412b607d95400bcd12446233fbd1248801",
}
ARTIFACTS = (
    "float-speaker-3.wav", "float-speaker-53.wav", "int8-speaker-3.wav", "int8-speaker-53.wav",
    "real-model-metrics.json", "synthetic-prefetch-metrics.json", "float-latency-benchmark.json",
)

EXPECTED_TESTS = {
    ("org.inkdock.reader.KokoroNativeDeviceTest", "x86JniLoadsBothVariantsAndExportsChineseForSpeaker3And53"),
    ("org.inkdock.reader.KokoroNativeDeviceTest", "realNativeAdapterContinuesInBackgroundAndReportsObservedPrefetchTimings"),
    ("org.inkdock.reader.KokoroModelTransferDeviceTest", "cancelBeforeWorkerCannotPublishOrOpenTransferAndRetryIsSafe"),
    ("org.inkdock.reader.KokoroModelTransferDeviceTest", "cancelDuringSecondFileKeepsOnlyVerifiedStagingAndRetryReusesFirstFile"),
    ("org.inkdock.reader.KokoroModelTransferDeviceTest", "wrongDigestNeverPromotesAStagingDirectory"),
    ("org.inkdock.reader.NativePcmPrefetchDeviceTest", "nextPcmBecomesReadyInBackgroundBeforeCurrentDrainAndStopReclaimsBothSlots"),
    ("org.inkdock.reader.KokoroFloatLatencyBenchmarkDeviceTest", "fixedFloatThreadsOneTwoFourMeasureFirstWarmAndIncrementalCallbacks"),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def parse_manifest(text):
    rows = re.findall(r'KokoroModelStore\.ModelFile\(\s*"([^"\n]+)"\s*,\s*"([^"\n]+)"\s*,\s*([0-9]+)L\s*,\s*"([a-f0-9]{64})"\s*\)', text)
    require(len(rows) == 9, "Expected exactly eight int8 entries and one float replacement in KokoroManifest.kt")
    require(tuple(row[0] for row in rows[:8]) == EXPECTED_INT8 and rows[8][0] == "model.onnx", "Model manifest structure changed; review staging contract")
    require(re.search(r"\)\s*\+\s*int8Files\.drop\(1\)", text), "Float supporting-file contract changed")
    records = []
    for name, url, size, digest in rows:
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", name) and name not in (".", ".."), "Unsafe model filename")
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == "https" and parsed.netloc == "huggingface.co" and not parsed.query and not parsed.fragment, "Expected immutable public Hugging Face URL")
        parts = parsed.path.strip("/").split("/")
        require(len(parts) == 5 and parts[0] == "csukuangfj" and parts[2] == "resolve" and parts[4] == name, "Unexpected model URL path")
        require(REVISIONS.get(parts[1]) == parts[3], "Model URL revision is not the reviewed immutable revision")
        require(0 < int(size) <= 400_000_000, "Unexpected model file size")
        records.append({"name": name, "url": url, "bytes": int(size), "sha256": digest})
    variants = {"int8": records[:8], "float": [records[8], *records[1:8]]}
    require(sum(r["bytes"] for r in variants["int8"]) == 170433930, "Int8 total changed; review staging contract")
    require(sum(r["bytes"] for r in variants["float"]) == 381766704, "Float total changed; review staging contract")
    return variants


def manifest():
    return parse_manifest(MANIFEST.read_text())


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        require(parsed.scheme == "https" and not parsed.username and not parsed.password, "Refusing insecure model redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def model_path(cache, record):
    return cache / record["sha256"] / record["name"]


def matches(path, record):
    return path.is_file() and not path.is_symlink() and path.stat().st_size == record["bytes"] and sha256(path) == record["sha256"]


def download(cache):
    variants = manifest()
    cache.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(HttpsRedirects())
    # Shared support files are downloaded once, then independently staged for each variant.
    unique = {record["sha256"]: record for files in variants.values() for record in files}
    for record in unique.values():
        path = model_path(cache, record)
        if matches(path, record):
            print("Verified cached public file:", record["name"], flush=True)
            continue
        require(not path.is_symlink() and not path.parent.is_symlink(), "Refusing cache symlink")
        path.parent.mkdir(parents=True, exist_ok=True)
        partial = path.with_suffix(path.suffix + ".part")
        require(not partial.is_symlink(), "Refusing partial-file symlink")
        for attempt in range(3):
            try:
                partial.unlink(missing_ok=True)
                request = urllib.request.Request(record["url"], headers={"User-Agent": "InkDock-PublicModelCI/1"})
                count, digest = 0, hashlib.sha256()
                with opener.open(request, timeout=120) as response, partial.open("xb") as output:
                    require(response.status == 200, "Model download did not return HTTP 200")
                    while data := response.read(1024 * 1024):
                        count += len(data)
                        require(count <= record["bytes"], "Public model download exceeded pinned size")
                        digest.update(data)
                        output.write(data)
                require(count == record["bytes"] and digest.hexdigest() == record["sha256"], "Public model integrity mismatch: " + record["name"])
                partial.replace(path)
                print("Downloaded and verified public file:", record["name"], count, flush=True)
                break
            except (urllib.error.URLError, TimeoutError, OSError):
                if attempt == 2:
                    raise
                time.sleep(2 ** attempt)
            finally:
                partial.unlink(missing_ok=True)
    (cache.parent / "model-inputs.json").write_text(json.dumps({"manifestSha256": sha256(MANIFEST), "variants": variants}, indent=2) + "\n")


class Device:
    def __init__(self, serial):
        require(serial == "emulator-5554", "Only the dedicated emulator-5554 is supported")
        require(os.environ.get("INKDOCK_EPHEMERAL_DEVICE_TEST") == "1", "Staging/collection is restricted to the explicit disposable CI emulator run")
        self.command = ["adb", "-s", serial]
        features = set(re.split(r"[,\s]+", self.text("features").strip()))
        require("shell_v2" in features,
                "ADB shell_v2 is required for binary stdin EOF and remote exit status")
        require(self.text("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Refusing a physical device")
        require(self.text("shell", "getprop", "ro.build.version.sdk").strip() == "35", "Expected Android API 35")
        require(self.text("shell", "getprop", "ro.product.cpu.abi").strip() == "x86_64", "Expected x86_64 emulator")
        require(self.text("shell", "run-as", PACKAGE, "pwd").strip().endswith("/" + PACKAGE), "Expected debug app-private run-as access")

    def text(self, *arguments):
        return subprocess.check_output([*self.command, *arguments], text=True, timeout=60)

    def private(self, *arguments):
        return self.text("shell", "run-as", PACKAGE, *arguments)

    def write(self, remote, stream):
        require(re.fullmatch(r"files/neural-models/\.kokoro-zh-v1_1-(?:float|int8)-ci-staging/[A-Za-z0-9_.-]+", remote)
                and Path(remote).name not in (".", ".."), "Unsafe private staging destination")
        # exec-in closes after copying host stdin, without waiting for dd or
        # returning its exit status. shell_v2 sends explicit stdin EOF and waits
        # for the remote exit packet. -T avoids PTY byte translation; -e none
        # disables escape handling. Every argument is fixed or allowlisted above.
        # The model still goes directly into app-private storage, never /sdcard
        # or /data/local/tmp. AOSP: packages/modules/adb/client/commandline.cpp.
        result = subprocess.run([*self.command, "shell", "-T", "-e", "none", "run-as", PACKAGE,
                                 "dd", "of=" + remote, "bs=1048576"],
                                stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=240)
        require(result.returncode == 0,
                f"Private transfer failed: {Path(remote).name} remote_exit={result.returncode} "
                f"stderr={result.stderr.decode('utf-8', errors='replace')[-2000:]!r}")


def verify_private_file(device, remote, expected_bytes, expected_sha256):
    # Query both values even if one differs: public filenames, byte counts and
    # hashes are safe diagnostics, and distinguish truncation from corruption.
    sizes = device.private("wc", "-c", remote).split()
    digests = device.private("sha256sum", remote).split()
    actual_bytes = sizes[0] if sizes and re.fullmatch(r"[0-9]+", sizes[0]) else "invalid"
    actual_sha256 = digests[0] if digests and re.fullmatch(r"[a-f0-9]{64}", digests[0]) else "invalid"
    detail = (f"{Path(remote).name} expected_bytes={expected_bytes} actual_bytes={actual_bytes} "
              f"expected_sha256={expected_sha256} actual_sha256={actual_sha256}")
    print("Private device file integrity:", detail, flush=True)
    require(actual_bytes == str(expected_bytes) and actual_sha256 == expected_sha256,
            "Device model integrity mismatch: " + detail)


def stage(cache, serial):
    variants = manifest()
    # Validate every host file before touching the disposable app's private data.
    for files in variants.values():
        for record in files:
            require(matches(model_path(cache, record), record), "Missing or corrupt cache file; run download first: " + record["name"])
    device = Device(serial)
    device.text("shell", "am", "force-stop", PACKAGE)
    for variant in VARIANTS:
        directory = "files/neural-models/kokoro-zh-v1_1-" + variant
        staging = "files/neural-models/.kokoro-zh-v1_1-" + variant + "-ci-staging"
        # This touches only known test-created paths on the fresh guarded emulator.
        device.private("rm", "-rf", staging)
        device.private("mkdir", "-p", staging)
        for record in variants[variant]:
            remote = staging + "/" + record["name"]
            with model_path(cache, record).open("rb") as stream:
                device.write(remote, stream)
            verify_private_file(device, remote, record["bytes"], record["sha256"])
        marker = "\n".join(record["name"] + " " + record["sha256"] for record in variants[variant]).encode()
        with tempfile.TemporaryFile() as stream:
            stream.write(marker)
            stream.seek(0)
            device.write(staging + "/VERIFIED", stream)
        verify_private_file(device, staging + "/VERIFIED", len(marker), hashlib.sha256(marker).hexdigest())
        device.private("rm", "-rf", directory)
        device.private("mv", staging, directory)
        print("Verified private emulator model:", variant, flush=True)
    device.private("mkdir", "-p", "files/native-test-artifacts")


def collect(output, serial, require_all):
    device = Device(serial)
    output.mkdir(parents=True, exist_ok=True)
    results, missing = [], []
    for name in ARTIFACTS:
        remote = "files/native-test-artifacts/" + name
        exists = subprocess.run([*device.command, "shell", "run-as", PACKAGE, "test", "-f", remote], capture_output=True, timeout=30)
        if exists.returncode:
            missing.append(name)
            continue
        size = int(device.private("wc", "-c", remote).split()[0])
        require(0 < size <= 20_000_000, "Unexpected synthetic artifact size")
        temporary = output / (name + ".part")
        with temporary.open("wb") as stream:
            subprocess.run([*device.command, "exec-out", "run-as", PACKAGE, "cat", remote], stdout=stream, timeout=120, check=True)
        require(temporary.stat().st_size == size, "Synthetic artifact transfer truncated")
        if name.endswith(".wav"):
            with temporary.open("rb") as stream:
                header = stream.read(12)
            require(header[:4] == b"RIFF" and header[8:12] == b"WAVE", "Expected WAV artifact")
        else:
            json.loads(temporary.read_text())
        target = output / name
        temporary.replace(target)
        results.append({"file": name, "bytes": size, "sha256": sha256(target)})
    (output / "artifact-index.json").write_text(json.dumps({"files": results, "missing": missing, "source": "fixed public Chinese fixture only"}, indent=2) + "\n")
    require(not require_all or not missing, "Required native test artifacts missing: " + ", ".join(missing))
    if require_all:
        verify_metric_reports(output)
    print("Collected", len(results), "synthetic audio/metric artifacts; missing:", ", ".join(missing) or "none")


def verify_metric_reports(output):
    real = json.loads((output / "real-model-metrics.json").read_text())
    synthetic = json.loads((output / "synthetic-prefetch-metrics.json").read_text())
    require(real.get("jni", {}).get("passed") is True and real.get("adapter", {}).get("passed") is True,
            "Real native metric report is incomplete or failed")
    require(synthetic.get("background", {}).get("passed") is True, "Synthetic background report is incomplete or failed")
    benchmark = json.loads((output / "float-latency-benchmark.json").read_text())
    require(benchmark.get("passed") is True and benchmark.get("schemaVersion") == 1
            and benchmark.get("variant") == "float" and benchmark.get("threadOrder") == [1, 2, 4],
            "Float latency benchmark is incomplete or has an unexpected contract")
    require(benchmark.get("androidApi") == 35 and type(benchmark.get("availableProcessors")) is int
            and benchmark["availableProcessors"] > 0, "Float benchmark lacks its actual emulator CPU/API context")
    require(benchmark.get("ownedModelsPeak") == 1 and benchmark.get("releasedModels") == 3
            and benchmark.get("ownedModelsAtExit") == 0, "Float benchmark model cleanup is incomplete")
    configurations = benchmark.get("configurations", [])
    require(len(configurations) == 3, "Float benchmark must include all three thread configurations")
    for threads, configuration in zip((1, 2, 4), configurations):
        require(configuration.get("threads") == threads and configuration.get("passed") is True
                and configuration.get("modelReleased") is True, "Float benchmark thread configuration failed or is incomplete")
        runs = configuration.get("runs", [])
        require(len(runs) == 3, "Float benchmark must include first, warm and multi-sentence runs")
        for kind, run in zip(("first", "warm", "multi_sentence"), runs):
            require(run.get("kind") == kind and run.get("passed") is True,
                    "Float benchmark generation failed or is incomplete: " + kind)
            for field in ("firstCallbackMs", "wholeGenerateMs"):
                require(type(run.get(field)) in (int, float) and math.isfinite(run[field]) and run[field] >= 0,
                        "Float benchmark lacks finite timing: " + field)
            callbacks = run.get("callbacks", [])
            require(isinstance(callbacks, list) and len(callbacks) > 0, "Float benchmark callback timeline is missing")
            if kind == "multi_sentence":
                comparison = run.get("callbackAndReturnComparison", {})
                # Compatibility gate, not a latency threshold: the reviewed
                # callback contract reconstructs returned PCM exactly.
                require(comparison.get("comparisonPerformed") is True and comparison.get("exactMatch") is True,
                        "Float benchmark callback/returned PCM comparison failed or was not completed")


def instrumentation_report(log, output, exit_code):
    """am instrument may exit zero for test failures: validate every expected test."""
    text = log.read_text()
    suite = ET.Element("testsuite", name="Kokoro API35 x86_64 native experiment")
    state, seen, failures = {}, set(), 0
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field = line[len("INSTRUMENTATION_STATUS: "):]
            if "=" in field:
                key, value = field.split("=", 1)
                state[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            if code <= 0 and state.get("class") and state.get("test"):
                identity = (state["class"], state["test"])
                case = ET.SubElement(suite, "testcase", classname=identity[0], name=identity[1])
                if code != 0 or identity in seen:
                    failures += 1
                    ET.SubElement(case, "failure", message="Instrumentation status " + str(code)).text = state.get("stack", text[-5000:])
                seen.add(identity)
            state = {}
    final_count = re.search(r"^OK \(([1-9][0-9]*) tests?\)", text, re.M)
    success = (exit_code == 0 and not failures and seen == EXPECTED_TESTS
               and final_count and int(final_count.group(1)) == len(EXPECTED_TESTS)
               and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", text, re.M))
    if not success:
        failures += 1
        case = ET.SubElement(suite, "testcase", classname="runner", name="complete_expected_native_tests")
        ET.SubElement(case, "failure", message="Missing/duplicate/failed/skipped test, timeout or incomplete result").text = text[-10000:]
    suite.set("tests", str(len(suite)))
    suite.set("failures", str(failures))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(output, encoding="utf-8", xml_declaration=True)
    require(success, "Native instrumentation failed; see raw Android output and JUnit report")
    print(f"All {len(EXPECTED_TESTS)} expected native instrumentation tests passed with no skipped tests")


def transfer_self_test(directory):
    """Use a local fake adb process, never an emulator or model download."""
    import contextlib
    import io
    from unittest.mock import patch

    directory = Path(directory)
    remote = "files/neural-models/.kokoro-zh-v1_1-float-ci-staging/model.onnx"
    captured = directory / "captured-binary"
    fake_adb = directory / "fake-adb.py"
    expected_args = ["shell", "-T", "-e", "none", "run-as", PACKAGE, "dd", "of=" + remote, "bs=1048576"]
    fake_adb.write_text("import pathlib, sys, time\n"
                        + "assert sys.argv[1:] == " + repr(expected_args) + "\n"
                        + "data = sys.stdin.buffer.read()\n"
                        + "time.sleep(0.05)  # The caller must wait for remote completion.\n"
                        + "pathlib.Path(" + repr(str(captured)) + ").write_bytes(data)\n")
    device = Device.__new__(Device)
    device.command = [sys.executable, str(fake_adb)]
    binary = bytes(range(256)) * 4096 + b"\x00\r\n~.\n~\x04\xff"
    with tempfile.TemporaryFile() as stream:
        stream.write(binary)
        stream.seek(0)
        device.write(remote, stream)
    require(captured.read_bytes() == binary, "Binary transfer did not await byte-exact completion")
    checks = 1
    fake_adb.write_text("import sys\nsys.stdin.buffer.read()\nsys.stderr.write('dd: input/output error')\nsys.exit(7)\n")
    with tempfile.TemporaryFile() as stream:
        try:
            device.write(remote, stream)
        except RuntimeError as error:
            require("remote_exit=7" in str(error) and "input/output error" in str(error), "Remote transfer failure diagnostic missing")
            checks += 1
        else:
            raise AssertionError("Ignored nonzero remote dd status")
        for unsafe in (remote + ";touch_bad", remote.rsplit("/", 1)[0] + "/..", "/data/local/tmp/model.onnx"):
            try:
                device.write(unsafe, stream)
            except RuntimeError as error:
                require("Unsafe private staging" in str(error), "Unsafe path reached adb")
                checks += 1
            else:
                raise AssertionError("Accepted unsafe staging path")
    with patch.dict(os.environ, {"INKDOCK_EPHEMERAL_DEVICE_TEST": "1"}), patch.object(Device, "text", return_value="cmd,stat_v2"):
        try:
            Device("emulator-5554")
        except RuntimeError as error:
            require("shell_v2" in str(error), "Unexpected missing-feature error")
            checks += 1
        else:
            raise AssertionError("Accepted missing remote exit protocol")
    for output in ("cmd,shell_v2,stat_v2", "cmd\nshell_v2\nstat_v2\n", "cmd\r\nshell_v2\r\nstat_v2\r\n"):
        with patch.dict(os.environ, {"INKDOCK_EPHEMERAL_DEVICE_TEST": "1"}), patch.object(Device, "text", side_effect=[output, "1", "35", "x86_64", "/data/user/0/" + PACKAGE]):
            Device("emulator-5554")
            checks += 1
    expected_sha = hashlib.sha256(binary).hexdigest()
    with patch.object(device, "private", side_effect=[str(len(binary)), expected_sha]), contextlib.redirect_stdout(io.StringIO()):
        verify_private_file(device, remote, len(binary), expected_sha)
        checks += 1
    for size, digest in ((str(len(binary) - 1), expected_sha), (str(len(binary)), "0" * 64), ("bad size", "bad digest")):
        with patch.object(device, "private", side_effect=[size, digest]), contextlib.redirect_stdout(io.StringIO()):
            try:
                verify_private_file(device, remote, len(binary), expected_sha)
            except RuntimeError as error:
                require(all(field in str(error) for field in ("model.onnx", "expected_bytes=", "actual_bytes=", "expected_sha256=", "actual_sha256=")),
                        "Integrity error lacks expected/actual diagnostics")
                checks += 1
            else:
                raise AssertionError("Accepted corrupt device model")
    return checks


def metric_self_test(directory):
    import copy
    output = Path(directory)
    reports = {
        "real-model-metrics.json": {"jni": {"passed": True}, "adapter": {"passed": True}},
        "synthetic-prefetch-metrics.json": {"background": {"passed": True}},
        "float-latency-benchmark.json": {
            "schemaVersion": 1, "passed": True, "variant": "float", "threadOrder": [1, 2, 4],
            "androidApi": 35, "availableProcessors": 2,
            "ownedModelsPeak": 1, "releasedModels": 3, "ownedModelsAtExit": 0,
            "configurations": [{"threads": threads, "passed": True, "modelReleased": True,
                "runs": [{"kind": kind, "passed": True, "firstCallbackMs": 4.0, "wholeGenerateMs": 8.0,
                          "callbacks": [{"atMs": 4.0}],
                          "callbackAndReturnComparison": {"comparisonPerformed": True, "exactMatch": True}}
                         for kind in ("first", "warm", "multi_sentence")]}
                for threads in (1, 2, 4)],
        },
    }

    def write_reports(values):
        for name, report in values.items():
            (output / name).write_text(json.dumps(report))

    write_reports(reports)
    verify_metric_reports(output)
    checks = 1
    mutations = [
        ("real-model-metrics.json", ("jni", "passed"), False),
        ("real-model-metrics.json", ("adapter", "passed"), False),
        ("synthetic-prefetch-metrics.json", ("background", "passed"), False),
        ("float-latency-benchmark.json", ("passed",), False),
        ("float-latency-benchmark.json", ("availableProcessors",), 0),
        ("float-latency-benchmark.json", ("threadOrder",), [1, 2]),
        ("float-latency-benchmark.json", ("releasedModels",), 2),
        ("float-latency-benchmark.json", ("configurations",), []),
        ("float-latency-benchmark.json", ("configurations", 0, "modelReleased"), False),
        ("float-latency-benchmark.json", ("configurations", 0, "runs"), []),
        ("float-latency-benchmark.json", ("configurations", 0, "runs", 1, "passed"), False),
        ("float-latency-benchmark.json", ("configurations", 0, "runs", 1, "wholeGenerateMs"), float("nan")),
        ("float-latency-benchmark.json", ("configurations", 0, "runs", 1, "callbacks"), []),
        ("float-latency-benchmark.json", ("configurations", 0, "runs", 2, "callbackAndReturnComparison", "comparisonPerformed"), False),
        ("float-latency-benchmark.json", ("configurations", 0, "runs", 2, "callbackAndReturnComparison", "exactMatch"), False),
    ]
    for name, path, replacement in mutations:
        altered = copy.deepcopy(reports)
        target = altered[name]
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = replacement
        write_reports(altered)
        try:
            verify_metric_reports(output)
        except RuntimeError:
            checks += 1
        else:
            raise AssertionError("Accepted incomplete metric report: " + str(path))
    write_reports(reports)
    (output / "float-latency-benchmark.json").unlink()
    try:
        verify_metric_reports(output)
    except FileNotFoundError:
        checks += 1
    else:
        raise AssertionError("Accepted missing float benchmark report")
    return checks


def self_test():
    text = MANIFEST.read_text()
    variants = parse_manifest(text)
    require(all(len(files) == 8 for files in variants.values()), "Expected two eight-file manifests")
    checks = 1
    mutations = [
        text.replace("155831f1b4ba23b1f5c058be6a61df90cefb2a37", "main"),
        text.replace("https://huggingface.co", "http://huggingface.co"),
        text.replace('ModelFile("model.int8.onnx"', 'ModelFile("../model.int8.onnx"'),
        text.replace("int8Files.drop(1)", "int8Files.drop(2)"),
        text.replace("114299010L", "114299011L"),
        text.replace("huggingface.co/csukuangfj/", "example.com/csukuangfj/"),
    ]
    for mutated in mutations:
        try:
            parse_manifest(mutated)
        except RuntimeError:
            checks += 1
        else:
            raise AssertionError("Accepted unsafe or changed model manifest")
    with tempfile.TemporaryDirectory() as directory:
        checks += transfer_self_test(directory)
        checks += metric_self_test(directory)
        path = Path(directory) / "fixture"
        path.write_bytes(b"fixed")
        record = {"bytes": 5, "sha256": hashlib.sha256(b"fixed").hexdigest()}
        require(matches(path, record), "Valid checksum rejected")
        path.write_bytes(b"wrong")
        require(not matches(path, record), "Wrong checksum accepted")
        checks += 2
        log, report = Path(directory) / "instrumentation.txt", Path(directory) / "report.xml"
        passing = "".join("INSTRUMENTATION_STATUS: class=" + cls + "\nINSTRUMENTATION_STATUS: test=" + method
                          + "\nINSTRUMENTATION_STATUS_CODE: 0\n" for cls, method in sorted(EXPECTED_TESTS))
        ok_line = f"OK ({len(EXPECTED_TESTS)} tests)"
        passing += ok_line + "\nINSTRUMENTATION_CODE: -1\n"
        without_benchmark = "".join("INSTRUMENTATION_STATUS: class=" + cls + "\nINSTRUMENTATION_STATUS: test=" + method
                                    + "\nINSTRUMENTATION_STATUS_CODE: 0\n" for cls, method in sorted(EXPECTED_TESTS)
                                    if not cls.endswith("KokoroFloatLatencyBenchmarkDeviceTest"))
        without_benchmark += f"OK ({len(EXPECTED_TESTS) - 1} tests)\nINSTRUMENTATION_CODE: -1\n"
        log.write_text(passing)
        instrumentation_report(log, report, 0)
        checks += 1
        for altered, exit_code in ((passing.replace("CODE: 0", "CODE: -2", 1), 0),
                                   (passing.replace("CODE: 0", "CODE: -3", 1), 0),
                                   (passing.replace(ok_line, "FAILURES!!!"), 0),
                                   (passing.replace(ok_line, f"OK ({len(EXPECTED_TESTS) - 1} tests)"), 0),
                                   (passing.replace("INSTRUMENTATION_CODE: -1", ""), 0),
                                   (without_benchmark, 0),
                                   (passing, 124)):
            log.write_text(altered)
            try:
                instrumentation_report(log, report, exit_code)
            except RuntimeError:
                checks += 1
            else:
                raise AssertionError("Accepted incomplete/failed instrumentation")
    print(f"Passed {checks} offline model/cache/transfer/metrics/instrumentation checks; no download or device used")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("self-test", "manifest", "download", "stage", "collect", "report"))
    parser.add_argument("--cache", type=Path, default=ROOT / "build/kokoro-device/models")
    parser.add_argument("--output", type=Path, default=ROOT / "build/kokoro-device/native-test-artifacts")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--require-all", action="store_true")
    parser.add_argument("--log", type=Path, default=ROOT / "build/kokoro-device/reports/instrumentation.txt")
    parser.add_argument("--report", type=Path, default=ROOT / "build/kokoro-device/reports/native-instrumentation.xml")
    parser.add_argument("--exit-code", type=int, default=0)
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
    elif args.command == "manifest":
        print(json.dumps(manifest(), indent=2))
    elif args.command == "download":
        download(args.cache)
    elif args.command == "stage":
        stage(args.cache, args.serial)
    elif args.command == "collect":
        collect(args.output, args.serial, args.require_all)
    else:
        instrumentation_report(args.log, args.report, args.exit_code)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit("Kokoro device preparation failed: " + str(error)) from error
