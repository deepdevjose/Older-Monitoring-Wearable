"""Save the connected board's private setup key for an existing elder account.

Requires the exact current signed CareMonitor APK already installed and an existing
valid elder sign-in with Nearby devices permission already granted. Never creates
an account, reads/discloses a session token, grants target permissions, logs out,
clears data, starts monitoring or uploads a sample. Instrumentation restarts an idle
target process, so any started/foreground app service causes an abort.

A one-use adb-forwarded local socket transfers the 32-byte ASCII key from the private
host file directly into WearableKeys.save. The key never enters argv, extras, phone
files, the helper APK or evidence. After helper removal, launch CareMonitor normally;
connect through its usual Wearable UI. --build-only never invokes adb or reads a key.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.caremonitor.wearableprovision"
TARGET = "org.caremonitor.demo"
FIELDS = {
    "result", "detail", "phase", "checks", "role", "scope", "encrypted_wearable_key_saved",
    "account_session_reused", "account_created_or_changed", "account_token_disclosed", "monitoring_started",
    "server_access", "private_key_file_created",
}
BOOLS = FIELDS - {"result", "detail", "phase", "checks", "role", "scope"}


def filtered_results(output: str) -> dict:
    result = {}
    for line in output.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_RESULT: ([a-z_]+)=([A-Za-z0-9_, .-]{1,180})", line)
        if not match or match[1] not in FIELDS:
            continue
        name, value = match.groups()
        if name in BOOLS:
            if value not in {"true", "false"}:
                continue
            value = value == "true"
        if name == "checks":
            if not value.isdecimal() or len(value) > 9:
                continue
            value = int(value)
        result[name] = value
    return result


def active_services(output: str) -> bool:
    return re.search(r"\b(?:isForeground|startRequested)=true\b", output) is not None


def certificate(output: str) -> str:
    found = re.findall(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})", output)
    if len(found) != 1:
        raise RuntimeError("Exactly one verified signing identity required")
    return found[0].lower()


def socket_line(stream: socket.socket) -> bytes:
    line = bytearray()
    while len(line) < 64:
        char = stream.recv(1)
        if not char:
            raise ConnectionError("Local setup channel closed")
        line.extend(char)
        if char == b"\n":
            return bytes(line)
    raise RuntimeError("Unexpected local setup channel response")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Exact adb device; required except with --build-only")
    parser.add_argument("--key-file", type=Path, default=ROOT.parent / ".local/offline/wearable-setup-key.txt")
    parser.add_argument("--expected-apk", type=Path, default=ROOT / "artifacts/CareMonitor-1.0.0.apk")
    parser.add_argument("--sdk", type=Path, default=Path.home() / "Android/Sdk")
    parser.add_argument("--jdk", type=Path, default=Path("/usr"))
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--evidence", type=Path, default=ROOT.parent / ".local/bluetooth-proof/WEARABLE_PROVISION_EVIDENCE.json")
    args = parser.parse_args()
    if not args.build_only and (not args.serial or not re.fullmatch(r"[A-Za-z0-9._:][A-Za-z0-9._:-]{0,127}", args.serial)):
        parser.error("--serial must name one explicit adb device")
    sdk, jdk = args.sdk.resolve(), args.jdk.resolve()
    tools, android = sdk / "build-tools/36.0.0", sdk / "platforms/android-37.0/android.jar"
    java, javac = jdk / "bin/java", jdk / "bin/javac"
    aapt, align = tools / "aapt2", tools / "zipalign"
    keystore = ROOT.parent / ".local/dev-signing.jks"
    source = ROOT / "tests/wearableprovision"
    for path in (java, javac, aapt, align, android, tools / "lib/d8.jar", tools / "lib/apksigner.jar", keystore):
        if not path.is_file():
            raise SystemExit("Required local tool or signing key missing: " + str(path))
    inputs = [source / "AndroidManifest.xml", source / "WearableProvisionInstrumentation.java", Path(__file__).resolve()]
    hashes = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    build = ROOT / "build/wearable-provision"
    if build.exists():
        shutil.rmtree(build)
    classes, dex, linked = (build / n for n in ("classes", "dex", "linked"))
    for folder in (classes, dex, linked):
        folder.mkdir(parents=True)
    env = dict(os.environ, JAVA_HOME=str(jdk), JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8")

    def run(command, *, check=True, timeout=120):
        result = subprocess.run([str(v) for v in command], cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        output = result.stdout.decode("utf-8", errors="replace")
        if check and result.returncode:
            raise RuntimeError("Command failed with exit code " + str(result.returncode))
        return result.returncode, output

    run([aapt, "link", "-I", android, "--manifest", source / "AndroidManifest.xml", "--min-sdk-version", "24",
         "--target-sdk-version", "37", "--output-to-dir", "-o", linked])
    run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", android, "-d", classes,
         source / "WearableProvisionInstrumentation.java"])
    run([java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "24", "--lib", android,
         "--output", dex, *sorted(classes.rglob("*.class"))])
    unsigned, aligned, apk = (build / n for n in ("unsigned.apk", "aligned.apk", "WearableProvision.apk"))
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_STORED) as archive:
        for p in sorted(linked.rglob("*")):
            if p.is_file():
                archive.write(p, p.relative_to(linked).as_posix())
        for p in sorted(dex.glob("*.dex")):
            archive.write(p, p.name)
    run([align, "-f", "4", unsigned, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
         "--ks-pass", "pass:android", "--key-pass", "pass:android", "--v4-signing-enabled", "false", "--out", apk, aligned])
    _, signing = run([java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk])
    helper_signer = certificate(signing)
    run([align, "-c", "4", apk])
    _, badging = run([aapt, "dump", "badging", apk])
    if "package: name='" + PACKAGE + "'" not in badging or "uses-permission" in badging:
        raise RuntimeError("Isolated helper identity or permission-free manifest did not match")
    if any(hashlib.sha256(p.read_bytes()).hexdigest() != hashes[p.relative_to(ROOT).as_posix()] for p in inputs):
        raise RuntimeError("Helper source changed during build")
    print("Built permission-free local wearable setup helper.", flush=True)
    if args.build_only:
        print("BUILD ONLY: no adb invocation or private key access.", flush=True)
        return
    if not args.expected_apk.is_file():
        raise SystemExit("Expected current CareMonitor APK missing")
    expected_sha = hashlib.sha256(args.expected_apk.read_bytes()).hexdigest()
    adb = sdk / "platform-tools/adb"
    selected = [adb, "-s", args.serial]
    run([*selected, "get-state"], timeout=20)
    _, services = run([*selected, "shell", "dumpsys", "activity", "services", TARGET], timeout=20)
    if active_services(services):
        raise SystemExit("ABORTED_ACTIVE_APP_SERVICE: existing monitoring or started service will remain untouched")
    _, paths = run([*selected, "shell", "pm", "path", TARGET], timeout=20)
    candidates = re.findall(r"^package:(/[A-Za-z0-9_./+=~-]+\.apk)$", paths, re.MULTILINE)
    if len(candidates) != 1:
        raise SystemExit("Exactly one installed CareMonitor base APK required")
    installed_apk = build / "installed-target.apk"
    run([*selected, "pull", candidates[0], installed_apk], timeout=30)
    if hashlib.sha256(installed_apk.read_bytes()).hexdigest() != expected_sha:
        raise SystemExit("ABORTED_APK_MISMATCH: install the exact expected current APK first; no app state changed")
    _, target_signing = run([java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", installed_apk])
    if certificate(target_signing) != helper_signer:
        raise SystemExit("ABORTED_SIGNING_MISMATCH: helper and installed app must retain the original same signing key")
    if args.key_file.stat().st_mode & 0o077:
        raise SystemExit("Private setup key must have mode 600")
    with args.key_file.open("rb") as source_key:
        key = bytearray(source_key.read(35).strip())
    if not re.fullmatch(rb"[0-9a-f]{32}", key):
        raise SystemExit("Private setup key must contain exactly 32 lowercase hexadecimal characters")
    channel = "cm_wearable_" + uuid.uuid4().hex
    port = None
    process = None
    installation_attempted = False
    evidence = {
        "result": "FAIL", "recorded_at": datetime.now(timezone.utc).isoformat(),
        "test_package": PACKAGE, "target_package": TARGET, "installed_apk_sha256": expected_sha,
        "helper_apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "input_sha256": hashes,
        "same_signing_identity_verified": True, "same_current_apk_verified": True, "scope": "Save wearable key for existing real elder account only; no connection or upload",
        "key_transfer": "one-use adb-forwarded local socket; adbd peer UID checked",
        "key_in_argv_files_apk_or_evidence": False, "target_permissions_changed": False,
        "normal_monitoring_started": False,
    }
    try:
        installation_attempted = True
        _, status = run([*selected, "install", "-r", "-t", apk], timeout=30)
        if "Success" not in status:
            raise RuntimeError("Helper installation did not confirm success")
        _, allocation = run([*selected, "forward", "tcp:0", "localabstract:" + channel], timeout=20)
        if not re.fullmatch(r"\d{1,5}\s*", allocation) or not 1 <= int(allocation) <= 65535:
            raise RuntimeError("Could not allocate USB local socket forward")
        port = int(allocation)
        command = [*selected, "shell", "am", "instrument", "-w", "-r", "-e", "channel", channel,
                   PACKAGE + "/.WearableProvisionInstrumentation"]
        process = subprocess.Popen([str(v) for v in command], cwd=ROOT, env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 44
        transferred = False
        while time.monotonic() < deadline and process.poll() is None:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=1) as stream:
                    stream.settimeout(3)
                    if socket_line(stream) != b"CM1 PROVISION READY\n":
                        raise RuntimeError("Unexpected local setup greeting")
                    stream.settimeout(15)
                    stream.sendall(key)
                    if socket_line(stream) != b"CM1 PROVISION SAVED\n":
                        raise RuntimeError("Encrypted setup key save was not acknowledged")
                    transferred = True
                    break
            except (ConnectionError, socket.timeout, OSError):
                if process.poll() is not None:
                    break
                time.sleep(.2)
        output, _ = process.communicate(timeout=65)
        values = filtered_results(output.decode("utf-8", errors="replace"))
        evidence["instrumentation"] = values
        passed = (transferred and values.get("result") == "PASS" and b"INSTRUMENTATION_CODE: -1" in output
                  and values.get("encrypted_wearable_key_saved") is True and values.get("account_session_reused") is True
                  and values.get("account_created_or_changed") is False and values.get("account_token_disclosed") is False
                  and values.get("monitoring_started") is False and values.get("server_access") is False
                  and values.get("private_key_file_created") is False and values.get("role") == "elder")
        evidence["result"] = "PASS" if passed else "FAIL"
        print(json.dumps(values, indent=2, sort_keys=True), flush=True)
        if not passed:
            raise RuntimeError("Existing-account wearable setup did not pass; see sanitized result")
    finally:
        for index in range(len(key)):
            key[index] = 0
        if process is not None and process.poll() is None:
            try:
                process.terminate()
                process.wait(timeout=5)
            except Exception:
                process.kill()
        if port is not None:
            try:
                code, _ = run([*selected, "forward", "--remove", "tcp:" + str(port)], check=False, timeout=20)
                evidence["usb_forward_removed"] = code == 0
            except Exception:
                evidence["usb_forward_removed"] = False
        if installation_attempted:
            try:
                _, removed = run([*selected, "uninstall", PACKAGE], check=False, timeout=30)
                evidence["helper_package_removed"] = "Success" in removed
            except Exception:
                evidence["helper_package_removed"] = False
            if not evidence["helper_package_removed"]:
                evidence["result"] = "FAIL"
        if evidence["result"] == "PASS":
            try:
                code, launch = run([*selected, "shell", "am", "start", "-W", "-n", TARGET + "/.MainActivity"], timeout=30)
                evidence["normal_app_launch_requested"] = code == 0 and "Status: ok" in launch
            except Exception:
                evidence["normal_app_launch_requested"] = False
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    if evidence["result"] != "PASS":
        raise RuntimeError("Local wearable setup cleanup failed")
    print("PASS: encrypted key saved; connect from CareMonitor's normal Wearable page. No monitoring was started.", flush=True)


if __name__ == "__main__":
    main()
