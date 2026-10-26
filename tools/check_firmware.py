#!/usr/bin/env python3
"""Read existing serial telemetry without writes; report only aggregate diagnostics."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import time


def capture(port: str, duration: float = 25.0, baud: int = 115200) -> dict:
    if not 1 <= duration <= 300:
        raise ValueError("duration must be between 1 and 300 seconds")
    import serial

    # Configure control lines before opening; do not pulse reset, send commands,
    # accept pairings, or modify the ESP32/NVS. Some USB drivers may still glitch
    # control lines on open, so observed uptime discontinuities are reported.
    connection = serial.Serial(port=None, baudrate=baud, timeout=0.2)
    connection.dtr = False
    connection.rts = False
    connection.port = port
    devices, states, flags, online = Counter(), Counter(), Counter(), Counter()
    frames = invalid = non_json = oversized = resets = 0
    first = last = previous_uptime = None
    buffer = bytearray()
    started = time.monotonic()
    connection.open()
    try:
        deadline = started + duration
        while time.monotonic() < deadline:
            chunk = connection.read(min(max(connection.in_waiting, 1), 16384))
            buffer.extend(chunk)
            if len(buffer) > 65536:
                buffer.clear()
                oversized += 1
            while b"\n" in buffer:
                raw, _, tail = buffer.partition(b"\n")
                buffer = bytearray(tail)
                raw = raw.strip()
                if not raw.startswith(b"{"):
                    non_json += 1
                    continue
                try:
                    sample = json.loads(raw)
                    if not isinstance(sample, dict) or "device_id" not in sample:
                        invalid += 1
                        continue
                except (ValueError, UnicodeError):
                    invalid += 1
                    continue
                now = time.monotonic()
                first = first if first is not None else now
                last = now
                frames += 1
                devices[str(sample.get("device_id"))] += 1
                optical = sample.get("max30102") or {}
                states[str(optical.get("status", "unknown"))] += 1
                for key in ("finger_detected", "heart_rate_valid", "spo2_valid"):
                    flags[f"{key}:{optical.get(key)}"] += 1
                for sensor in ("mpu6050", "gy906"):
                    online[f"{sensor}:{(sample.get(sensor) or {}).get('online')}"] += 1
                uptime = sample.get("uptime_ms")
                if isinstance(uptime, int):
                    if previous_uptime is not None and uptime < previous_uptime:
                        resets += 1
                    previous_uptime = uptime
    finally:
        connection.close()
    source = Path(__file__).resolve().parents[1] / "firmware/esp32/esp32.ino"
    return {
        "port": port, "baud": baud, "duration_seconds": round(time.monotonic() - started, 3),
        "frames": frames, "invalid_json_frames": invalid, "non_json_lines": non_json,
        "oversized_buffers_discarded": oversized, "uptime_discontinuities": resets,
        "frequency_hz": round((frames - 1) / (last - first), 3) if frames > 1 and last > first else None,
        "device_ids": dict(devices), "optical_states": dict(states),
        "validity_flags": dict(flags), "sensor_online_flags": dict(online),
        "firmware_identity": "unknown; serial telemetry does not attest the compiled source",
        "canonical_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest() if source.exists() else None,
        "privacy": "Aggregate metadata only; physiological and raw sensor values are not retained.",
        "actions": "Read only; no serial writes, explicit reset, flash, pairing acceptance or NVS changes.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="/dev/ttyUSB0")
    parser.add_argument("--duration", "--seconds", dest="duration", type=float, default=25)
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--output", type=Path, help="Optional aggregate JSON report, never raw telemetry")
    args = parser.parse_args()
    try:
        result = capture(args.port, args.duration, args.baud)
    except (OSError, ValueError) as error:
        print(json.dumps({"error": str(error), "frames": 0}, ensure_ascii=False))
        return 2
    encoded = json.dumps(result, indent=2, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if result["frames"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
