"""Package the reviewed candidate sources and already-built APK, excluding private/runtime data."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent
STEM = 'CareMonitor-v0.8.0-integration-candidate-20261026'
DENIED_PARTS = {'__pycache__', '.pytest_cache', '.git', '.venv', 'build', 'artifacts', 'candidate-data', 'data', 'node_modules'}
DENIED_EXTENSIONS = {'.pyc', '.db', '.sqlite', '.sqlite3', '.key', '.pem', '.jks', '.keystore', '.apk', '.class', '.dex'}
NATIVE_SCRIPTS = {'build_apk.py', 'test_reading_summary.py', 'test_account_policy.py'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def permitted(path):
    relative = path.relative_to(ROOT)
    if any(part in DENIED_PARTS for part in relative.parts) or path.suffix.lower() in DENIED_EXTENSIONS:
        return False
    if path.name == 'secrets.h' or path.name.startswith('.env'):
        return False
    if relative.parts[0] == 'care_monitor_native' and len(relative.parts) > 2 and relative.parts[1] == 'scripts':
        return path.name in NATIVE_SCRIPTS
    return relative.parts[0] in {'care_monitor_native', 'care_monitor_server', 'care_monitor_dashboard',
                                'care_monitor_ml', 'docs', 'evidence'} or relative.as_posix() in {
                                    'START-HERE.md', 'run_candidate.py', 'package_candidate.py', 'verification_summary.py'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    app = ROOT / 'care_monitor_native'
    built = json.loads((app / 'artifacts/BUILD_EVIDENCE.json').read_text(encoding='utf-8'))
    apk = app / 'artifacts/CareMonitor-demo-debug.apk'
    assert built['version_name'] == '0.8.0' and built['version_code'] == 17
    assert sha(apk) == built['sha256']
    for name, expected in built['input_sha256'].items():
        assert sha(app / name) == expected, f'APK source changed after build: {name}'
    verification = ROOT / 'evidence/integration/VERIFICATION.json'
    assert verification.is_file(), 'Finish and document software verification before packaging.'
    firmware = app / 'firmware/team_spp_no_sound'
    assert sha(firmware / 'team_spp_no_sound.ino') == '869fe3d60297464345a87b64fd590d2ddc8b0de51c7d5b50c16f7edbd401d4b5'
    assert sha(firmware / 'PpgReadingState.h') == '39465776bd444eba5a5de8364f9d55fda6b7451b129e5fca4c70c40f2e82da05'
    files = sorted(path for path in ROOT.rglob('*') if path.is_file() and permitted(path))
    hashes = {path.relative_to(ROOT).as_posix(): sha(path) for path in files}
    for path in files:
        raw = path.read_bytes()
        forbidden_headers = {b'-----BEGIN PRIVATE KEY-----', b'-----BEGIN RSA PRIVATE KEY-----', b'-----BEGIN EC PRIVATE KEY-----'}
        assert not any(line.strip() in forbidden_headers for line in raw.splitlines()), path
    manifest = {'created_at_utc': datetime.now(timezone.utc).isoformat(), 'version': '0.8.0',
                'release_kind': 'integration_candidate_awaiting_phone_acceptance',
                'android_phone_tested': False, 'installed_on_phone': False,
                'firmware_changed': False, 'production_server_deployed': False,
                'three_class_supported': False, 'clinical_validation': False,
                'apk_file': STEM + '.apk', 'apk_sha256': sha(apk), 'source_files': hashes}
    args.output.mkdir(parents=True, exist_ok=True)
    target_apk = args.output / (STEM + '.apk')
    target_zip = args.output / (STEM + '-source.zip')
    shutil.copy2(apk, target_apk)
    with zipfile.ZipFile(target_zip, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            archive.write(path, arcname='CareMonitor-v0.8.0-source/' + path.relative_to(ROOT).as_posix())
        archive.writestr('CareMonitor-v0.8.0-source/RELEASE_MANIFEST.json', json.dumps(manifest, indent=2))
    with zipfile.ZipFile(target_zip) as archive:
        assert archive.testzip() is None
        for name, expected in hashes.items():
            assert hashlib.sha256(archive.read('CareMonitor-v0.8.0-source/' + name)).hexdigest() == expected
    (args.output / (STEM + '-SHA256.txt')).write_text(
        sha(target_apk) + '  ' + target_apk.name + '\n' + sha(target_zip) + '  ' + target_zip.name + '\n', encoding='utf-8')
    (args.output / (STEM + '-release.json')).write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    shutil.copy2(ROOT / 'START-HERE.md', args.output / (STEM + '-START-HERE.md'))
    shutil.copy2(ROOT / 'docs/INTEGRATION_STATUS.md', args.output / (STEM + '-STATUS.md'))
    print(json.dumps({'apk': str(target_apk), 'zip': str(target_zip), 'source_files': len(files),
                      'apk_sha256': sha(target_apk), 'zip_sha256': sha(target_zip),
                      'zip_bytes': target_zip.stat().st_size}, indent=2))


if __name__ == '__main__':
    main()
