"""Compile and run pure account policy checks with the portable JDK; no Android runtime."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME", str(
        Path.home() / "Documents/Codex/toolchains/android-build/jdk")))
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    javac = args.jdk / ("bin/javac" + suffix)
    java = args.jdk / ("bin/java" + suffix)
    if not javac.is_file() or not java.is_file():
        parser.error("JDK compiler/runtime not found; pass --jdk with the portable JDK directory")
    folder = ROOT / "build" / ("account-policy-tests-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    folder.mkdir(parents=True)
    source = ROOT / "app/src/main/java/org/caremonitor/demo/AccountPresentationPolicy.java"
    test = ROOT / "tests/AccountPresentationPolicyTest.java"
    subprocess.run([str(javac), "-encoding", "UTF-8", "--release", "8", "-d", str(folder),
                    str(source), str(test)], check=True)
    result = subprocess.run([str(java), "-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8",
                             "-cp", str(folder), "org.caremonitor.demo.AccountPresentationPolicyTest"],
                            check=True, capture_output=True, text=True, encoding="utf-8")
    print(result.stdout.strip())
    if result.stderr:
        print(result.stderr.strip())
    matched = re.search(r"PASS: (\d+) AccountPresentationPolicy checks", result.stdout)
    if not matched:
        raise RuntimeError("The policy test did not report its expected completion marker")
    evidence = {
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(), "status": "passed",
        "checks": int(matched.group(1)), "scope": "Plain JVM AccountPresentationPolicy only",
        "android_runtime_tested": False, "keystore_tested": False, "live_network_tested": False,
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "test_sha256": hashlib.sha256(test.read_bytes()).hexdigest(),
    }
    (folder / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("Evidence: " + str(folder / "evidence.json"))


if __name__ == "__main__":
    main()
