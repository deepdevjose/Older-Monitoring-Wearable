"""Build/run isolated physical Bluetooth authentication and exclusivity checks.

Only org.caremonitor.btprobe is installed, granted Bluetooth permissions and removed.
The helper has no INTERNET permission, never opens CareMonitor account storage and
never retains sensor values. A board key is read from a private local file or stdin;
it is never placed in argv, instrumentation extras, logs, APKs or public evidence.
--pair-only requests normal Android system pairing and waits for the user's phone
confirmation. It never accepts a pairing dialog or types an ESP32 serial approval.
"""
from __future__ import annotations

import argparse
import errno
from datetime import datetime, timezone
import hashlib
import json
import os
import queue
from pathlib import Path
import re
import shutil
import subprocess
import socket
import threading
import time
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.caremonitor.btprobe"
PRODUCTION = "org.caremonitor.demo"
RESULT_FIELDS = {
    "result", "scope", "detail", "phase", "checks", "sensor_frames_discarded",
    "unauthenticated_disconnect_ms", "unauthenticated_timeout", "wrong_key_rejected",
    "mutual_authentication", "second_socket_rejected", "owner_survived_second_socket",
    "exclusivity_scope", "stale_proof_rejected", "reconnect_after_owner_disconnect",
    "server_access", "sample_values_retained", "private_key_cleanup", "owner_survived_external_contender",
}
INTEGER_FIELDS = {"checks", "sensor_frames_discarded", "unauthenticated_disconnect_ms"}
BOOLEAN_FIELDS = {
    "unauthenticated_timeout", "wrong_key_rejected", "mutual_authentication",
    "second_socket_rejected", "owner_survived_second_socket", "stale_proof_rejected",
    "reconnect_after_owner_disconnect", "server_access", "sample_values_retained", "owner_survived_external_contender",
}
EXPECTED_PERMISSIONS = {
    "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN",
    "android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
}


def parse_results(output: str) -> dict:
    """Accept only fixed instrumentation fields; never expose arbitrary subprocess output."""
    values = {}
    for line in output.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_RESULT: ([a-z_]+)=([A-Za-z0-9_, .-]{1,180})", line)
        if not match or match[1] not in RESULT_FIELDS:
            continue
        field, value = match.groups()
        if field in INTEGER_FIELDS:
            if not value.isdecimal() or len(value) > 9:
                continue
            value = int(value)
        elif field in BOOLEAN_FIELDS:
            if value not in {"true", "false"}:
                continue
            value = value == "true"
        values[field] = value
    return values


def active_monitor(output: str) -> bool:
    """Bound idle UI services are harmless; started/foreground monitoring must stay untouched."""
    return bool(re.search(r"\b(?:isForeground|startRequested)=true\b", output))



def cleanup_probe(run, selected: list, evidence: dict) -> None:
    """Attempt every cleanup step even if an earlier adb operation times out."""
    steps = [
        ("force_stop", [*selected, "shell", "am", "force-stop", PACKAGE], 20),
        ("remove_private_key", [*selected, "shell", "run-as", PACKAGE, "rm", "-f", "files/wearable-key.private"], 20),
        ("uninstall", [*selected, "uninstall", PACKAGE], 30),
    ]
    evidence["probe_package_removed"] = False
    for name, command, timeout in steps:
        try:
            code, output = run(command, check=False, timeout=timeout)
            evidence["cleanup_" + name] = "PASS" if code == 0 else "FAILED"
            if name == "uninstall":
                evidence["probe_package_removed"] = code == 0 and "Success" in output
        except Exception:
            # Exception messages/commands are never copied into public evidence.
            evidence["cleanup_" + name] = "FAILED"
    if not evidence["probe_package_removed"]:
        evidence["result"] = "FAIL"


def external_radio_preflight() -> None:
    """Read adapter state; never enable a radio, pair or change networking."""
    if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
        raise RuntimeError("External contender requires Linux Bluetooth socket support")
    controller = shutil.which("bluetoothctl")
    if controller is None:
        raise RuntimeError("bluetoothctl required for read-only adapter readiness check")
    status = subprocess.run([controller, "show"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    if status.returncode or b"Powered: yes" not in status.stdout:
        raise RuntimeError("External Bluetooth controller must already be powered on; no setting was changed")


def external_radio_attempt(address: str, channel: int) -> dict:
    """Attempt a second physical controller connection; emit no peer identity/data."""
    allowed = {errno.ECONNREFUSED: "REFUSED", errno.ECONNRESET: "RESET", errno.ETIMEDOUT: "TIMEOUT", errno.EBUSY: "BUSY"}
    outcome = None
    with socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM) as contender:
        contender.settimeout(5)
        try:
            contender.connect((address, channel))
        except (socket.timeout, TimeoutError):
            outcome = "TIMEOUT"
        except OSError as failure:
            outcome = allowed.get(failure.errno)
            if outcome is None:
                raise RuntimeError("External controller attempt failed locally or could not reach the board") from None
        else:
            # Admission is a failure even if the second peer never gets a sample.
            raise RuntimeError("SECOND_PHYSICAL_RADIO_CONNECTION_WAS_ADMITTED")
    return {"attempted": True, "connection_rejected": True, "outcome": outcome,
            "sensor_frames_received": 0, "rfcomm_channel": channel,
            "scope": "separate Linux physical controller while Android owner remains authenticated"}


def run_external_instrumentation(command, *, cwd, env, channel: int, timeout: int = 240) -> tuple[str, dict]:
    """Consume immediate private status in memory, retaining only result lines."""
    process = subprocess.Popen([str(v) for v in command], cwd=cwd, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    pending = queue.Queue(maxsize=256)
    def read_output():
        try:
            for raw in iter(process.stdout.readline, b""):
                pending.put(raw.decode("utf-8", errors="replace").strip())
        finally:
            pending.put(None)
    thread = threading.Thread(target=read_output, daemon=True)
    thread.start()
    retained = []
    peer = None
    ready = False
    result = {}
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            try:
                line = pending.get(timeout=.25)
            except queue.Empty:
                continue
            if line is None:
                break
            if line.startswith("INSTRUMENTATION_STATUS: peer_address="):
                value = line.split("=", 1)[1]
                if not re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", value):
                    raise RuntimeError("Invalid private external-contender target")
                peer = value
                continue
            if line == "INSTRUMENTATION_STATUS: phase=external_contender_ready":
                ready = True
                continue
            if line == "INSTRUMENTATION_STATUS_CODE: 2" and ready:
                if peer is None or result:
                    raise RuntimeError("Unexpected external-contender coordination state")
                result = external_radio_attempt(peer, channel)
                peer = None
                ready = False
                continue
            if line.startswith("INSTRUMENTATION_RESULT:") or line.startswith("INSTRUMENTATION_CODE:"):
                retained.append(line)
        if process.poll() is None:
            process.wait(timeout=max(1, deadline - time.monotonic()))
        if not result:
            result = {"attempted": False, "connection_rejected": False}
        return "\n".join(retained), result
    finally:
        peer = None
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Exact adb serial; required except with --build-only")
    parser.add_argument("--address", help="Optional exact Bluetooth address; omitted discovers the one ElderSensors device")
    keys = parser.add_mutually_exclusive_group()
    keys.add_argument("--key-file", type=Path, help="Private 32-hex-character setup key file; default .local/offline/wearable-setup-key.txt")
    keys.add_argument("--key-stdin", action="store_true", help="Read private board key from stdin, never from argv")
    parser.add_argument("--external-contender", action="store_true", help="Use an already enabled Linux physical Bluetooth radio for the exclusivity attempt")
    parser.add_argument("--contender-channel", type=int, default=1, help="Board SPP RFCOMM server channel (default 1)")
    parser.add_argument("--pair-only", action="store_true", help="Only request normal Android pairing; no key or authentication checks")
    parser.add_argument("--build-only", action="store_true", help="Build and verify helper; never invokes adb or reads a key")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")))
    parser.add_argument("--jdk", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")))
    parser.add_argument("--evidence", type=Path, default=ROOT.parent / ".local/bluetooth-proof/BT_PROTOCOL_EVIDENCE.json")
    args = parser.parse_args()
    if not args.build_only and (not args.serial or not re.fullmatch(r"[A-Za-z0-9._:][A-Za-z0-9._:-]{0,127}", args.serial)):
        parser.error("--serial must name exactly one explicit adb device")
    if not 1 <= args.contender_channel <= 30:
        parser.error("--contender-channel must be between 1 and 30")
    if args.external_contender and args.pair_only:
        parser.error("--external-contender requires the complete authentication test")
    if args.address and not re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", args.address):
        parser.error("--address must be a valid Bluetooth address")
    if not args.key_file and not args.key_stdin:
        args.key_file = ROOT.parent / ".local/offline/wearable-setup-key.txt"
    sdk, jdk = args.sdk.absolute(), args.jdk.absolute()
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-37.0/android.jar"
    java, javac = jdk / "bin/java", jdk / "bin/javac"
    aapt, align = tools / "aapt2", tools / "zipalign"
    store = ROOT.parent / ".local/dev-signing.jks"
    auth = ROOT / "app/src/main/java/org/caremonitor/demo/WearableAuth.java"
    sources = ROOT / "tests/bluetoothprobe"
    for path in (java, javac, aapt, align, android, tools / "lib/d8.jar", tools / "lib/apksigner.jar", store, auth):
        if not path.is_file():
            raise SystemExit("Required local input missing: " + str(path))
    inputs = [auth, sources / "AndroidManifest.xml", sources / "BluetoothProbeInstrumentation.java", Path(__file__).resolve()]
    hashes = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    build = ROOT / "build/bluetooth-probe"
    if build.exists():
        shutil.rmtree(build)
    classes, dex, linked = (build / name for name in ("classes", "dex", "linked"))
    for directory in (classes, dex, linked):
        directory.mkdir(parents=True)
    env = dict(os.environ, JAVA_HOME=str(jdk), JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8")

    def run(command, *, check=True, show=False, timeout=120, private_input=None):
        result = subprocess.run([str(item) for item in command], cwd=ROOT, env=env,
                                input=private_input, stdout=subprocess.DEVNULL if private_input is not None else subprocess.PIPE,
                                stderr=subprocess.PIPE if private_input is not None else subprocess.STDOUT,
                                timeout=timeout)
        output = (result.stdout or b"").decode("utf-8", errors="replace")
        # No output from the private transfer is ever decoded, retained or printed.
        if show and output:
            print(output, end="" if output.endswith("\n") else "\n", flush=True)
        if check and result.returncode:
            raise RuntimeError("Command failed with exit code " + str(result.returncode))
        return result.returncode, output

    run([aapt, "link", "-I", android, "--manifest", sources / "AndroidManifest.xml", "--min-sdk-version", "24",
         "--target-sdk-version", "37", "--output-to-dir", "-o", linked])
    run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", android, "-d", classes,
         auth, sources / "BluetoothProbeInstrumentation.java"])
    run([java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "24", "--lib", android,
         "--output", dex, *sorted(classes.rglob("*.class"))])
    unsigned, aligned, apk = (build / name for name in ("unsigned.apk", "aligned.apk", "BluetoothProbe.apk"))
    with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(linked.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(linked).as_posix())
        for path in sorted(dex.glob("*.dex")):
            archive.write(path, path.name)
    run([align, "-f", "4", unsigned, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "sign", "--ks", store, "--ks-key-alias", "androiddebugkey",
         "--ks-pass", "pass:android", "--key-pass", "pass:android", "--v4-signing-enabled", "false", "--out", apk, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", apk])
    run([align, "-c", "4", apk])
    _, badging = run([aapt, "dump", "badging", apk])
    actual_permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", badging))
    if "package: name='" + PACKAGE + "'" not in badging or actual_permissions != EXPECTED_PERMISSIONS:
        raise RuntimeError("Isolated probe identity or Bluetooth-only permission set did not match")
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != hashes[path.relative_to(ROOT).as_posix()] for path in inputs):
        raise RuntimeError("Probe sources changed during build; rebuild for accurate evidence")
    print("Built isolated Bluetooth protocol helper: " + str(apk), flush=True)
    if args.build_only:
        print("BUILD ONLY: no adb call or private key access.", flush=True)
        return

    if args.external_contender:
        external_radio_preflight()
    key = bytearray()
    if not args.pair_only:
        if args.key_file:
            if args.key_file.stat().st_mode & 0o077:
                raise SystemExit("Board key file must be private (chmod 600)")
            with args.key_file.open("rb") as source:
                encoded = source.read(35)
        else:
            encoded = sys.stdin.buffer.read(35)
        encoded = encoded.strip()
        if not re.fullmatch(rb"[0-9a-fA-F]{32}", encoded):
            raise SystemExit("Private setup key must contain exactly 32 hexadecimal characters")
        key = bytearray(encoded.lower())
        encoded = None

    adb = sdk / "platform-tools/adb"
    if not adb.is_file():
        raise SystemExit("adb missing")
    selected = [adb, "-s", args.serial]
    run([*selected, "get-state"], timeout=20)
    _, services = run([*selected, "shell", "dumpsys", "activity", "services", PRODUCTION], timeout=20)
    if active_monitor(services):
        raise SystemExit("ABORTED_ACTIVE_MONITOR: disconnect CareMonitor sensor monitoring explicitly first")
    _, sdk_text = run([*selected, "shell", "getprop", "ro.build.version.sdk"], timeout=20)
    api = int(sdk_text.strip())
    installed = False
    install_attempted = False
    evidence = {
        "result": "FAIL", "recorded_at": datetime.now(timezone.utc).isoformat(), "test_package": PACKAGE,
        "target_package": PACKAGE, "production_application_touched": False,
        "test_mode": "system_pairing_only" if args.pair_only else "physical_protocol",
        "scope": "Isolated physical SPP checks with unchanged production WearableAuth. No account or server access; sensor values discarded.",
        "exclusivity_scope": ("Android owner and separate Linux physical Bluetooth radio" if args.external_contender
                              else "two RFCOMM sockets on one physical handset; not a second physical phone test"),
        "probe_apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "input_sha256": hashes,
        "network_permission": False, "account_storage_access": False, "private_key_in_argv_or_apk": False,
    }
    try:
        install_attempted = True
        _, output = run([*selected, "install", "-r", "-t", apk], timeout=30)
        installed = "Success" in output
        if not installed:
            raise RuntimeError("Isolated probe installation did not confirm success")
        permissions = ["android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"] if api >= 31 else ["android.permission.ACCESS_FINE_LOCATION"]
        for permission in permissions:
            run([*selected, "shell", "pm", "grant", PACKAGE, permission], timeout=20)
        if not args.pair_only:
            run([*selected, "shell", "run-as", PACKAGE, "mkdir", "-p", "-m", "700", "files"], timeout=20)
            run([*selected, "exec-in", "run-as", PACKAGE, "dd", "of=files/wearable-key.private", "bs=1", "count=32"], timeout=20, private_input=key)
            run([*selected, "shell", "run-as", PACKAGE, "chmod", "600", "files/wearable-key.private"], timeout=20)
        command = [*selected, "shell", "am", "instrument", "-w", "-r", "-e", "pair_only", str(args.pair_only).lower()]
        if args.address:
            command += ["-e", "address", args.address.upper()]
        command += ["-e", "external_contender", str(args.external_contender).lower()]
        command += [PACKAGE + "/org.caremonitor.demo.BluetoothProbeInstrumentation"]
        print("Running isolated Bluetooth checks; normal Android pairing confirmation may appear on the phone.", flush=True)
        if args.external_contender:
            outcome, external = run_external_instrumentation(command, cwd=ROOT, env=env, channel=args.contender_channel)
            evidence["external_contender"] = external
            print(json.dumps(external, indent=2, sort_keys=True), flush=True)
        else:
            _, outcome = run(command, timeout=240)
        values = parse_results(outcome)
        evidence["instrumentation"] = values
        passed = (values.get("result") == "PASS" and "INSTRUMENTATION_CODE: -1" in outcome
                  and values.get("private_key_cleanup") == "PASS" and values.get("checks", 0) > 0
                  and values.get("server_access") is False and values.get("sample_values_retained") is False)
        if not args.pair_only:
            required = ["unauthenticated_timeout", "wrong_key_rejected", "mutual_authentication",
                        "stale_proof_rejected", "reconnect_after_owner_disconnect"]
            if args.external_contender:
                required.append("owner_survived_external_contender")
                passed = passed and external.get("attempted") is True and external.get("connection_rejected") is True
            else:
                required += ["second_socket_rejected", "owner_survived_second_socket"]
            passed = passed and all(values.get(field) is True for field in required) and values.get("sensor_frames_discarded", 0) >= 9
        if any(hashlib.sha256(path.read_bytes()).hexdigest() != hashes[path.relative_to(ROOT).as_posix()] for path in inputs):
            evidence["source_changed_during_test"] = True
            passed = False
        evidence["result"] = "PASS" if passed else "FAIL"
        print(json.dumps(values, indent=2, sort_keys=True), flush=True)
        if not passed:
            raise RuntimeError("Isolated Bluetooth checks failed; see sanitized result")
    finally:
        for index in range(len(key)):
            key[index] = 0
        # Never resize this bytearray: a timed-out subprocess may retain a
        # temporary exported memoryview until its exception is released.
        if install_attempted:
            cleanup_probe(run, selected, evidence)
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    if evidence["result"] != "PASS":
        raise RuntimeError("Probe cleanup failed")
    print("PASS: isolated Bluetooth checks complete; helper removed. Evidence: " + str(args.evidence), flush=True)


if __name__ == "__main__":
    main()
