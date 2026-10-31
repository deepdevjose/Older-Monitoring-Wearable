"""HTTPS URL and active-service guards for the isolated Android probe."""
import argparse
import importlib.util
from pathlib import Path

import pytest


SPEC = importlib.util.spec_from_file_location(
    "server_device_probe", Path(__file__).resolve().parents[1] / "mobile/scripts/check_server_device.py"
)
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


@pytest.mark.parametrize("base", [
    "https://192.168.3.3:8443/api/v1", "https://localhost/api/v1",
    "https://[::1]:8443/api/v1", "https://care.example.test:443/api/v1",
])
def test_explicit_credential_free_https_bases(base):
    assert probe.endpoint(base) == base


@pytest.mark.parametrize("base", [
    "http://192.168.3.3:8443/api/v1", "HTTPS://localhost/api/v1",
    "https://name:password@localhost/api/v1", "https://localhost/api/v1?token=secret",
    "https://localhost/api/v1#fragment", "https://localhost/api/v1/",
    "https://localhost:70000/api/v1", "https://localhost/api/v1;echo",
    "https://localhost/api/v1\n", "https://localhost/api/v1$(echo)",
])
def test_insecure_private_or_shell_sensitive_bases_are_rejected(base):
    with pytest.raises(argparse.ArgumentTypeError):
        probe.endpoint(base)


def test_active_target_service_prevents_instrumentation_from_interrupting_it():
    assert probe.target_services_active(
        "ACTIVITY MANAGER SERVICES\n  * ServiceRecord{1234 u0 org.caremonitor.demo/.LiveMonitorService c:org.caremonitor.demo}\n"
    )
    assert not probe.target_services_active(
        "ACTIVITY MANAGER SERVICES\n  * ServiceRecord{1234 u0 org.other.example/.Service}\n"
    )
    assert not probe.target_services_active("ACTIVITY MANAGER SERVICES\n  (nothing)\n")


def test_account_history_access_requires_explicit_flag():
    args=probe.arguments(["--serial","exact-device","--base","https://127.0.0.1:19443/api/v1"])
    assert args.check_history is False
    options=probe.instrumentation_options(args.base)
    assert options[options.index("check_history")+1]=="false"
    args=probe.arguments(["--serial","exact-device","--base","https://127.0.0.1:19443/api/v1","--check-history"])
    assert args.check_history is True
    options=probe.instrumentation_options(args.base,check_history=args.check_history)
    assert options[options.index("check_history")+1]=="true"


def test_read_only_history_cannot_enable_registration_probe():
    with pytest.raises(SystemExit):
        probe.arguments(["--serial","exact-device","--base","https://127.0.0.1:19443/api/v1",
                         "--check-history","--check-register-route"])


@pytest.mark.parametrize("serial",["", "-switch", "two devices", "device\nother"])
def test_history_never_selects_a_device_implicitly(serial):
    with pytest.raises(SystemExit):
        probe.arguments(["--serial",serial,"--base","https://127.0.0.1:19443/api/v1","--check-history"])
