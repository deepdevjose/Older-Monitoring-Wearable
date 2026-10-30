"""Build/run isolated production-UI fixtures without opening or altering CareMonitor.

The separate preview package requests no permissions and declares no services. Its
instrumentation disables network executors before activity creation. Synthetic
fixtures live only in the preview package, which is removed after the run.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.caremonitor.preview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Exact device serial; mandatory unless --build-only")
    parser.add_argument("--sdk", type=Path, default=Path.home() / "Android/Sdk")
    parser.add_argument("--jdk", type=Path, default=Path("/usr"))
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--screenshots-dir", type=Path, help="Save synthetic preview Canvas captures; never captures the production application")
    parser.add_argument("--evidence", type=Path, default=ROOT.parent / "release/ANDROID_HISTORY_UI_EVIDENCE.json")
    args = parser.parse_args()
    if not args.build_only and (not args.serial or args.serial.startswith("-")):
        parser.error("--serial must name one explicit device")
    sdk, jdk = args.sdk.resolve(), args.jdk.resolve()
    tools, android = sdk / "build-tools/36.0.0", sdk / "platforms/android-37.0/android.jar"
    java, javac = jdk / "bin/java", jdk / "bin/javac"
    aapt, align = tools / "aapt2", tools / "zipalign"
    store = ROOT.parent / ".local/dev-signing.jks"
    for path in (java, javac, aapt, align, android, tools / "lib/d8.jar", tools / "lib/apksigner.jar", store):
        if not path.is_file():
            raise SystemExit("Required local tool missing: " + str(path))
    inputs = sorted(path for base in (ROOT / "app/src/main", ROOT / "tests/preview") for path in base.rglob("*") if path.is_file())
    inputs.append(Path(__file__).resolve())
    hashes = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    build = ROOT / "build/history-preview"
    if build.exists():
        shutil.rmtree(build)
    compiled, generated, linked, classes, dex = [build / name for name in ("compiled", "generated", "linked", "classes", "dex")]
    for folder in (compiled, generated, linked, classes, dex):
        folder.mkdir(parents=True)
    env = dict(os.environ, JAVA_HOME=str(jdk), JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8")

    def run(command, *, check=True, verbose=False):
        result = subprocess.run([str(item) for item in command], cwd=ROOT, env=env, text=True,
                                encoding="utf-8", errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if verbose or result.returncode:
            print(result.stdout, end="" if result.stdout.endswith("\n") else "\n", flush=True)
        if check and result.returncode:
            raise RuntimeError("Command failed with exit code " + str(result.returncode))
        return result.stdout

    for resource in sorted((ROOT / "app/src/main/res").rglob("*.xml")):
        run([aapt, "compile", resource, "-o", compiled])
    run([aapt, "link", "-I", android, "--manifest", ROOT / "tests/preview/AndroidManifest.xml", "--custom-package", "org.caremonitor.demo",
         "--java", generated, *sorted(compiled.glob("*.flat")), "--output-to-dir", "--debug-mode", "--min-sdk-version", "24", "--target-sdk-version", "37", "-o", linked])
    sources = [*generated.rglob("*.java"), *(ROOT / "app/src/main/java").rglob("*.java"), ROOT / "tests/preview/PreviewInstrumentation.java"]
    run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", android, "-d", classes, *sources])
    run([java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "24", "--lib", android, "--output", dex, *classes.rglob("*.class")])
    unsigned, aligned, apk = [build / name for name in ("unsigned.apk", "aligned.apk", "HistoryPreview.apk")]
    with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in linked.rglob("*"):
            if path.is_file():
                archive.write(path, path.relative_to(linked).as_posix())
        for path in dex.glob("*.dex"):
            archive.write(path, path.name)
        for path in (ROOT / "app/src/main/assets").rglob("*"):
            if path.is_file():
                archive.write(path, "assets/" + path.relative_to(ROOT / "app/src/main/assets").as_posix())
    run([align, "-f", "4", unsigned, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "sign", "--ks", store, "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--v4-signing-enabled", "false", "--out", apk, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", apk])
    run([align, "-c", "4", apk])
    badging = run([aapt, "dump", "badging", apk])
    if "package: name='" + PACKAGE + "'" not in badging or "uses-permission:" in badging:
        raise RuntimeError("Preview package/permission isolation did not match")
    print("Built isolated UI preview: " + str(apk), flush=True)
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != hashes[path.relative_to(ROOT).as_posix()] for path in inputs):
        raise RuntimeError("Sources changed during compilation; rebuild to obtain accurate evidence")
    print("BUILD ONLY: no adb calls." if args.build_only else "Preview contains no permissions or services; production CareMonitor stays untouched.", flush=True)
    if args.build_only:
        return
    adb = sdk / "platform-tools/adb"
    selected = [adb, "-s", args.serial]
    run([*selected, "get-state"])
    installed = False
    evidence = {"result": "FAIL", "recorded_at": datetime.now(timezone.utc).isoformat(), "test_package": PACKAGE,
                "target_package": PACKAGE, "production_application_touched": False,
                "scope": "Synthetic role-specific Today, Updates, Settings reading details, histories, finite connection motion, learning and wearable-key privacy/role/cancel fixtures at 100/200 percent font in unchanged production Java/resources inside a separate permission-free package. No real accounts, server or Bluetooth operations.",
                "preview_apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "input_sha256": hashes}
    try:
        installed = "Success" in run([*selected, "install", "-r", "-t", apk])
        if not installed:
            raise RuntimeError("Preview installation did not confirm success")
        instrumentation = [*selected, "shell", "am", "instrument", "-w", "-r"]
        if args.screenshots_dir:
            instrumentation.extend(["-e", "capture_screenshots", "true"])
        outcome = run([*instrumentation, PACKAGE + "/org.caremonitor.demo.PreviewInstrumentation"], verbose=True)
        passed = "INSTRUMENTATION_RESULT: result=PASS" in outcome and "INSTRUMENTATION_CODE: -1" in outcome
        count = re.search(r"INSTRUMENTATION_RESULT: checks=(\d+)", outcome)
        evidence["checks"] = int(count.group(1)) if count else 0
        evidence["result"] = "PASS" if passed else "FAIL"
        if args.screenshots_dir:
            directory = args.screenshots_dir.resolve()
            directory.mkdir(parents=True, exist_ok=True)
            captured = run([*selected, "shell", "run-as", PACKAGE, "ls", "files/ui-previews"], check=False).splitlines()
            files = sorted(name.strip() for name in captured if re.fullmatch(r"(?:elder|caregiver)_[a-z_]+_(?:100|200)_(?:top|bottom)\.png", name.strip()))
            screenshots = {}
            for name in files:
                target = directory / name
                with target.open("wb") as stream:
                    copied = subprocess.run([str(item) for item in [*selected, "exec-out", "run-as", PACKAGE, "cat", "files/ui-previews/" + name]],
                                            cwd=ROOT, env=env, stdout=stream, stderr=subprocess.PIPE)
                content = target.read_bytes()
                if copied.returncode or not content.startswith(b"\x89PNG\r\n\x1a\n"):
                    target.unlink(missing_ok=True)
                    raise RuntimeError("Synthetic preview capture could not be retrieved")
                screenshots[name] = hashlib.sha256(content).hexdigest()
            evidence["synthetic_preview_screenshots_sha256"] = screenshots
            expected_count = re.search(r"INSTRUMENTATION_RESULT: screenshots=(\d+)", outcome)
            if not expected_count or len(screenshots) != int(expected_count.group(1)) or (passed and len(screenshots) != 32):
                raise RuntimeError("Synthetic preview capture count did not match")
        if not passed:
            raise RuntimeError("Isolated on-device UI checks failed")
    finally:
        if installed:
            cleanup = run([*selected, "uninstall", PACKAGE], check=False)
            evidence["preview_package_removed"] = "Success" in cleanup
            if not evidence["preview_package_removed"]:
                evidence["result"] = "FAIL"
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    if evidence.get("result") != "PASS":
        raise RuntimeError("Preview cleanup or checks failed")
    print("PASS: " + str(evidence["checks"]) + " isolated UI checks; preview package removed.")


if __name__ == "__main__":
    main()
