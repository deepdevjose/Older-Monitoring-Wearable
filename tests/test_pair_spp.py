"""Only explicit Bluetooth statuses leave the optional serial observer."""
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('pair_spp', Path(__file__).resolve().parents[1] / 'tools/pair_spp.py')
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_authentication_status_survives_every_chunk_boundary():
    payload = b'Bluetooth app authenticated; exclusive sensor stream enabled.\r\n'
    for split in range(len(payload) + 1):
        parser = module.BluetoothStatusParser()
        assert parser.feed(payload[:split]) + parser.feed(payload[split:]) == [module.STATUSES[payload.strip().decode()]]


def test_readings_secrets_and_substring_matches_never_escape():
    parser = module.BluetoothStatusParser()
    assert parser.feed(b'{"heart_rate_bpm":72}\nWiFi password: private\nCM1 AUTH secret\n'
                       b'prefix Bluetooth app authenticated; exclusive sensor stream enabled.\n') == []


def test_oversized_line_discard_recovers_only_on_newline():
    parser = module.BluetoothStatusParser()
    status = next(iter(module.STATUSES)).encode()
    assert parser.feed(b'x'*(module.MAX_BUFFER+1) + status + b'\n') == []
    assert parser.feed(status + b'\n') == [module.STATUSES[status.decode()]]
