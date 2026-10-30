"""Exercise the actual firmware transport using deterministic host SDK stubs."""
from pathlib import Path
import shutil
import subprocess

import pytest


def test_native_spp_transport_state_machine(tmp_path):
    compiler = shutil.which("g++")
    if not compiler:
        pytest.skip("g++ is required for the firmware host state-machine checks")
    root = Path(__file__).resolve().parents[1]
    stubs = root / "tests/firmware_bt_stubs"
    executable = tmp_path / "wearable_bluetooth_test"
    build = subprocess.run([
        compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
        "-I", str(stubs), "-I", str(root / "firmware/esp32"),
        str(root / "firmware/esp32/WearableBluetooth.cpp"),
        str(stubs / "transport_test.cpp"), "-o", str(executable),
    ], capture_output=True, text=True, timeout=30)
    assert build.returncode == 0, build.stdout + build.stderr
    run = subprocess.run([str(executable)], capture_output=True, text=True, timeout=10)
    assert run.returncode == 0, run.stdout + run.stderr
    assert "PASS: native SPP ownership" in run.stdout
