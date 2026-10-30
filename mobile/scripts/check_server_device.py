"""Probe public HTTPS health using the installed Android app's real ApiClient.

Builds a separate signed instrumentation APK. Device use always requires an exact
--serial. Only this helper package is installed/uninstalled. No account is loaded,
no login/permission grant/settings write/screenshot/sensor operation is performed.
Android instrumentation may restart the target process; active target services
cause an abort before installing the helper. --build-only never invokes adb.
--check-register-route also sends an empty JSON object to registration and expects
schema rejection (HTTP 422), without supplying account data or creating a login.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TEST_PACKAGE = "org.caremonitor.networkprobe"
TARGET_PACKAGE = "org.caremonitor.demo"


def endpoint(value: str) -> str:
    """Restrict an explicit URL to a credential-free HTTPS API base."""
    try:
        parsed = urlsplit(value)
        port = parsed.port
        hostname = parsed.hostname
    except ValueError as error:
        raise argparse.ArgumentTypeError("Invalid HTTPS endpoint") from error
    if (
        not value.startswith("https://") or parsed.scheme != "https" or not hostname
        or parsed.username is not None or parsed.password is not None
        or parsed.path != "/api/v1" or parsed.query or parsed.fragment
        or not re.fullmatch(r"[A-Za-z0-9.:/\[\]-]+", value)
        or (port is not None and not 1 <= port <= 65535)
    ):
        raise argparse.ArgumentTypeError("Use https://HOST:PORT/api/v1 without credentials, query or fragment")
    return value


def target_services_active(output: str) -> bool:
    return re.search(r"\bServiceRecord\{[^\r\n]*\borg\.caremonitor\.demo/", output) is not None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact adb serial; never selects a device implicitly")
    parser.add_argument("--base", required=True, type=endpoint, help="Explicit HTTPS base ending in /api/v1")
    parser.add_argument("--check-config", action="store_true", help="Report only whether installed default/saved endpoints match --base")
    parser.add_argument("--check-register-route", action="store_true", help="Expect HTTP 422 for an empty registration object; supplies no account data")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME", "/usr"))
    parser.add_argument("--build-only", action="store_true", help="Build and verify the helper without invoking adb")
    args = parser.parse_args()
    if not args.serial.strip() or args.serial.startswith("-") or re.search(r"\s", args.serial):
        parser.error("Supply one exact, nonempty device serial")
    sdk, jdk = args.sdk.absolute(), args.jdk.absolute()
    suffix = ".exe" if os.name == "nt" else ""
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-37.0/android.jar"
    java, javac = [jdk / "bin" / (name + suffix) for name in ("java", "javac")]
    aapt, align = [tools / (name + suffix) for name in ("aapt2", "zipalign")]
    store = ROOT.parent / ".local/dev-signing.jks"
    for path in [java, javac, aapt, align, android, tools / "lib/d8.jar", tools / "lib/apksigner.jar", store]:
        if not path.is_file():
            raise SystemExit("Required local tool or existing development keystore missing: " + str(path))
    build = ROOT / "build/network-probe"
    if build.exists():
        shutil.rmtree(build)
    classes, dex, linked = [build / name for name in ("classes", "dex", "linked")]
    for directory in [classes, dex, linked]:
        directory.mkdir(parents=True)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
        " -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8").strip()

    def run(command, *, check=True, show=True, timeout=120):
        result = subprocess.run([str(item) for item in command], cwd=ROOT, env=env,
                                text=True, encoding="utf-8", errors="replace",
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if show and result.stdout:
            print(result.stdout, end="" if result.stdout.endswith("\n") else "\n", flush=True)
        if check and result.returncode:
            raise RuntimeError("Command failed with exit code " + str(result.returncode))
        return result

    sources = ROOT / "tests/networkprobe"
    run([aapt, "link", "-I", android, "--manifest", sources / "AndroidManifest.xml",
         "--min-sdk-version", "24", "--target-sdk-version", "37", "--output-to-dir", "-o", linked])
    run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", android,
         "-d", classes, sources / "NetworkProbeInstrumentation.java"])
    run([java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "24",
         "--lib", android, "--output", dex, *sorted(classes.rglob("*.class"))])
    unsigned, aligned, apk = [build / name for name in ("unsigned.apk", "aligned.apk", "NetworkProbeInstrumentation.apk")]
    with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(linked.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(linked).as_posix())
        for path in sorted(dex.glob("*.dex")):
            archive.write(path, path.name)
    run([align, "-f", "4", unsigned, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "sign", "--ks", store,
         "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--v4-signing-enabled", "false", "--out", apk, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", apk])
    run([align, "-c", "4", apk])
    badging = run([aapt, "dump", "badging", apk], show=False).stdout
    if "package: name='" + TEST_PACKAGE + "'" not in badging:
        raise RuntimeError("Probe APK identity did not match the isolated package")
    print("Probe APK: " + str(apk))
    print("SHA256: " + hashlib.sha256(apk.read_bytes()).hexdigest())
    if args.build_only:
        print("BUILD ONLY: no adb invocation or device checks executed.")
        return
    adb = sdk / "platform-tools" / ("adb" + suffix)
    if not adb.is_file():
        raise SystemExit("adb missing: " + str(adb))
    selected = [adb, "-s", args.serial]
    run([*selected, "get-state"], timeout=20)
    target = run([*selected, "shell", "pm", "path", TARGET_PACKAGE], show=False, timeout=20).stdout
    if "package:" not in target:
        raise SystemExit("Install the intended CareMonitor APK separately before probing")
    services = run([*selected, "shell", "dumpsys", "activity", "services", TARGET_PACKAGE], show=False, timeout=20).stdout
    if target_services_active(services):
        raise SystemExit("ABORTED_ACTIVE_SERVICES: stop CareMonitor services explicitly first; instrumentation may restart its process")
    installed = False
    try:
        install = run([*selected, "install", "-r", "-t", apk], timeout=30)
        installed = "Success" in install.stdout
        if not installed:
            raise RuntimeError("Probe-package installation did not confirm success")
        print("Probing public health using installed CareMonitor: " + args.base)
        outcome = run([*selected, "shell", "am", "instrument", "-w", "-r",
                       "-e", "base", args.base, "-e", "check_config", str(args.check_config).lower(),
                       "-e", "check_register_route", str(args.check_register_route).lower(),
                       TEST_PACKAGE + "/.NetworkProbeInstrumentation"], timeout=60).stdout
        if "INSTRUMENTATION_RESULT: result=NEEDS_LOCAL_NETWORK_PERMISSION" in outcome:
            raise SystemExit("NEEDS_LOCAL_NETWORK_PERMISSION: allow local network access for CareMonitor in Android settings; no permissions were granted")
        if ("INSTRUMENTATION_RESULT: result=PASS" not in outcome
                or "INSTRUMENTATION_CODE: -1" not in outcome):
            raise RuntimeError("Installed-app HTTPS health probe did not pass; inspect its filtered result above")
    finally:
        if installed:
            cleanup = run([*selected, "uninstall", TEST_PACKAGE], check=False, timeout=30)
            if cleanup.returncode or "Success" not in cleanup.stdout:
                raise RuntimeError("Could not remove the isolated probe package: " + TEST_PACKAGE)


if __name__ == "__main__":
    main()
