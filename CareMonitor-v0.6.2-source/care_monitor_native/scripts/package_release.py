"""Package the verified v0.6.2 APK/source; keep teammate firmware and private inputs out."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile

APP = Path(__file__).resolve().parents[1]
OUT = APP.parent / 'outputs'
OUT.mkdir(exist_ok=True)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


proof = json.loads((APP / 'artifacts/BUILD_EVIDENCE.json').read_text(encoding='utf-8-sig'))
version = proof['version_name']
assert version == '0.6.2' and proof['version_code'] == 10, 'Build v0.6.2/code 10 first'
assert proof['application_id'] == 'org.caremonitor.demo'
assert proof['result'] == 'APK compiled, aligned, signed and verified'
apk = APP / 'artifacts' / proof['apk']
digest = sha256(apk)
assert proof['sha256'] == digest and proof['bytes'] == apk.stat().st_size
expected_inputs = {p.relative_to(APP).as_posix() for p in (APP / 'app/src/main').rglob('*') if p.is_file()}
expected_inputs.add('scripts/build_apk.py')
assert set(proof['input_sha256']) == expected_inputs, 'Build input inventory changed; rebuild first'
for name, expected in proof['input_sha256'].items():
    assert sha256(APP / name) == expected, 'Changed since build: ' + name
certificate = 'be0b46f60ca5871751697124a0ba4b50d5dff22bc2e9b560468bb321201d54af'
assert any(step['step'] == 'Verify APK signature' and certificate in step['output']
           and step['exit_code'] == 0 for step in proof['steps'])

# The included BLE sketch is an optional historical artifact, not a v0.6.2 SPP release.
legacy_evidence = json.loads((APP / 'firmware/FIRMWARE_BUILD_EVIDENCE.json').read_text(encoding='utf-8-sig'))
legacy_source = APP / 'firmware/care_monitor_ble/care_monitor_ble.ino'
assert legacy_evidence['result'] == 'compiled' and legacy_evidence['exit_code'] == 0
assert legacy_evidence['final_source_sha256'] == sha256(legacy_source)

results_path = APP / 'artifacts/JVM_TEST_RESULTS.txt'
results_text = results_path.read_text(encoding='utf-8-sig')
assert not re.search(r'(?im)^\s*(FAIL(?:ED)?\b|Exception\b|Traceback\b)', results_text), 'Tests contain a failure'
counts = {name: int(count) for count, name in re.findall(r'PASS:\s*(\d+)\s+(\w+)\s+checks', results_text)}
for name, count in re.findall(r'(\w+)Test:\s*(\d+)\s+checks passed', results_text):
    counts[name] = int(count)
assert all(counts.get(name, 0) > 0 for name in ['ReadingSummary', 'AlertInbox', 'NdjsonFramer', 'LiveSampleFreshness', 'RelayPolicy']), 'Missing baseline test results'
assert any(re.search(r'Spp|Classic|Transport|SessionLineBuffer', name, re.I) for name in counts), 'Run the new SPP/session tests before packaging'

with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    fixture = json.loads(archive.read('assets/sample_alerts.json'))['events']
    assert len(fixture) == 13 and len({item['type'] for item in fixture}) == 13
    assert sum(not item['reviewed'] and item['type'] != 'watch_worn' for item in fixture) == 5

phone_proof_path = APP / 'artifacts/PHONE_INSTALL_EVIDENCE.json'
phone_proof = None
if phone_proof_path.exists():
    candidate = json.loads(phone_proof_path.read_text(encoding='utf-8-sig'))
    if candidate.get('version_name') == version and candidate.get('version_code') == proof['version_code']:
        assert candidate.get('apk_sha256') == digest, 'Phone evidence must identify the exact release APK'
        phone_proof = candidate

release_apk = OUT / f'CareMonitor-v{version}.apk'
source_zip = OUT / f'CareMonitor-v{version}-source.zip'
shutil.copy2(apk, release_apk)
release_apk.with_suffix('.apk.sha256').write_text(digest + '  ' + release_apk.name + '\n', encoding='ascii')
count = 0
excluded = {'build', '.git', '__pycache__', '.venv', 'incoming', 'node_modules'}
private_suffixes = ('.jks', '.keystore', '.key', '.pyc', '.db', '.db2', '.sqlite', '.sqlite3', '.db-wal', '.db-shm')
with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(APP.rglob('*')):
        relative = path.relative_to(APP)
        if not path.is_file() or any(part in excluded for part in relative.parts):
            continue
        if path.name.lower().endswith(private_suffixes):
            continue
        if relative.as_posix() == 'artifacts/PHONE_INSTALL_EVIDENCE.json' and phone_proof is None:
            continue
        archive.write(path, APP.name + '/' + relative.as_posix())
        count += 1
with zipfile.ZipFile(source_zip) as archive:
    assert archive.testzip() is None
    for name, expected in proof['input_sha256'].items():
        assert hashlib.sha256(archive.read(APP.name + '/' + name)).hexdigest() == expected, name
    assert hashlib.sha256(archive.read(APP.name + '/artifacts/' + apk.name)).hexdigest() == digest
    assert not any(name.lower().endswith(private_suffixes) for name in archive.namelist())

summary = {
    'version': version, 'version_code': proof['version_code'],
    'apk': release_apk.name, 'apk_bytes': release_apk.stat().st_size, 'sha256': digest,
    'source': source_zip.name, 'source_bytes': source_zip.stat().st_size, 'source_files': count,
    'source_matches_build': True, 'same_development_certificate': True,
    'jvm_checks_by_suite': counts, 'jvm_checks_total': sum(counts.values()),
    'jvm_results_sha256': sha256(results_path),
    'classic_spp_and_ble_implemented': True, 'wifi_https_relay_implemented': True,
    'physical_spp_pairing_or_sensor_stream_tested': False,
    'physical_ble_or_server_delivery_tested': False,
    'phone_install_verified': bool(phone_proof and phone_proof.get('install_success')),
    'phone_launch_verified': bool(phone_proof and phone_proof.get('launch_success')),
    'phone_install_evidence': 'care_monitor_native/artifacts/PHONE_INSTALL_EVIDENCE.json' if phone_proof else None,
    'teammate_classic_spp_firmware_preserved': True,
    'teammate_private_archive_redistributed': False,
    'new_firmware_compile_claimed': False, 'no_device_flashed': True,
    'separate_firmware_zip': None,
    'legacy_ble_firmware': {
        'source_release': '0.6.0', 'unchanged': True, 'optional': True,
        'source_sha256': legacy_evidence['final_source_sha256'],
        'historical_compile_evidence_verified': True,
    },
    'data_source': 'Classic Bluetooth SPP or legacy BLE live gateway, with separate local demo',
    'ml_changed_in_this_release': False,
}
(OUT / f'CareMonitor-v{version}-release.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary, indent=2))
