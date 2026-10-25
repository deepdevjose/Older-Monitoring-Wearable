"""Compile the BLE sketch without uploading; keep reproducible UTF-8 evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone


def environment_versions(cli: str) -> dict:
    """Read installed metadata without exposing the full package indexes."""
    cores = json.loads(subprocess.check_output([cli, "core", "list", "--format", "json"]))
    libraries = json.loads(subprocess.check_output([cli, "lib", "list", "--format", "json"]))
    core_version = next((core.get("installed_version") for core in cores.get("platforms", [])
                         if core.get("id") == "esp32:esp32"), None)
    wanted = {"ArduinoJson", "SparkFun MAX3010x Pulse and Proximity Sensor Library"}
    versions = {item["library"]["name"]: item["library"].get("version")
                for item in libraries.get("installed_libraries", [])
                if item.get("library", {}).get("name") in wanted}
    return {"core_version": core_version, "library_versions": versions,
            "ble_library": "Arduino-ESP32 built-in BLE (Bluedroid)"}


def write_evidence(firmware_root: Path, evidence: dict, output: str, cli: str) -> dict:
    """Normalize successful compiler facts into the source-package receipt."""
    evidence.update(environment_versions(cli))
    evidence["result"] = "compiled" if evidence["return_code"] == 0 else "failed"
    evidence["exit_code"] = evidence["return_code"]
    evidence["final_source_sha256"] = evidence["sketch_sha256"]
    evidence["compiler_log"] = "compile-v0.6.0.log"
    evidence["no_device_flashed"] = True
    flash = re.search(r"Sketch uses (\d+) bytes.*?Maximum is (\d+) bytes", output)
    ram = re.search(r"Global variables use (\d+) bytes.*?Maximum is (\d+) bytes", output)
    evidence["resource_usage"] = {
        "flash_bytes": int(flash[1]) if flash else None,
        "flash_maximum_bytes": int(flash[2]) if flash else None,
        "global_ram_bytes": int(ram[1]) if ram else None,
        "ram_maximum_bytes": int(ram[2]) if ram else None,
    }
    (firmware_root / "FIRMWARE_BUILD_EVIDENCE.json").write_text(
        json.dumps(evidence, indent=2) + "\n", encoding="utf-8"
    )
    return evidence


def main() -> int:
    firmware_root = Path(__file__).resolve().parent
    native_root = firmware_root.parent
    windows_cli = Path(os.environ.get("LOCALAPPDATA", "")) / (
        "Programs/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe"
    )
    default_cli = shutil.which("arduino-cli")
    if not default_cli and windows_cli.is_file():
        default_cli = str(windows_cli)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", default=default_cli)
    parser.add_argument("--build-path", type=Path, default=native_root / "build/esp32_ble")
    parser.add_argument("--fqbn", default="esp32:esp32:esp32")
    args = parser.parse_args()
    if not args.cli:
        parser.error("Arduino CLI was not found; pass --cli with its full path.")

    sketch = firmware_root / "care_monitor_ble/care_monitor_ble.ino"
    command = [
        args.cli, "compile", "--fqbn", args.fqbn,
        "--build-path", str(args.build_path.resolve()), str(sketch.parent),
    ]
    initial_source_hash = hashlib.sha256(sketch.read_bytes()).hexdigest()
    # No flash/upload command exists in this helper.
    print("Compiling ESP32 BLE firmware; no board will be flashed.", flush=True)
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode("utf-8", errors="replace")
    final_source_hash = hashlib.sha256(sketch.read_bytes()).hexdigest()
    source_unchanged = initial_source_hash == final_source_hash
    (firmware_root / "compile-v0.6.0.log").write_text(output, encoding="utf-8")
    binaries = {}
    for path in args.build_path.glob("care_monitor_ble.ino*.bin"):
        binaries[path.name] = {
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
    evidence = {
        "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "command": command,
        "return_code": result.returncode if source_unchanged else 100,
        "compiler_return_code": result.returncode,
        "source_unchanged_during_build": source_unchanged,
        "sketch_sha256": final_source_hash,
        "fqbn": args.fqbn,
        "compiled_binaries": binaries if result.returncode == 0 and source_unchanged else {},
        "hardware_flashed": False,
        "physical_sensor_or_phone_tested": False,
    }
    write_evidence(firmware_root, evidence, output, args.cli)
    print(output[-7000:], end="" if output.endswith("\n") else "\n")
    print("Compiler exit code:", result.returncode)
    print("Sketch SHA-256:", evidence["sketch_sha256"])
    return evidence["return_code"]


if __name__ == "__main__":
    sys.exit(main())
