"""Build the native Bluetooth monitor with the official JDK and Android SDK only."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PORTABLE = Path.home() / 'Documents/Codex/toolchains/android-build'

def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME', PORTABLE / 'android-sdk'))
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME', PORTABLE / 'jdk'))
    parser.add_argument('--keystore', type=Path, default=PORTABLE / 'signing/caremonitor-debug.jks')
    args = parser.parse_args()
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    version_name = manifest.attrib[android + 'versionName']
    version_code = int(manifest.attrib[android + 'versionCode'])
    build_inputs = [*(ROOT / 'app/src/main').rglob('*'), Path(__file__).resolve()]
    input_sha256 = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in sorted(build_inputs) if path.is_file()}
    sdk, jdk = args.sdk.absolute(), args.jdk.absolute()
    tools = sdk / 'build-tools/36.0.0'
    android_jar = sdk / 'platforms/android-36/android.jar'
    java, javac, keytool = [jdk / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
                           for name in ('java', 'javac', 'keytool')]
    aapt = tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    align = tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign')
    for path in [java, javac, keytool, aapt, align, android_jar,
                 tools / 'lib/d8.jar', tools / 'lib/apksigner.jar']:
        if not path.is_file():
            raise SystemExit('Required tool missing: ' + str(path))
    build = ROOT / 'build' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    generated, classes, dex = [build / name for name in ['generated', 'classes', 'dex']]
    for directory in [generated, classes, dex]:
        directory.mkdir(parents=True, exist_ok=True)
    artifact_dir = ROOT / 'artifacts'
    artifact_dir.mkdir(exist_ok=True)
    env = os.environ.copy()
    env['JAVA_HOME'] = str(jdk)
    env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') +
        ' -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8').strip()
    steps = []

    def run(label, command):
        print(label, flush=True)
        result = subprocess.run([str(item) for item in command], cwd=ROOT, env=env,
                                text=True, encoding='utf-8', errors='replace',
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        steps.append({'step': label, 'exit_code': result.returncode, 'output': result.stdout})
        if result.stdout:
            print(result.stdout, flush=True)
        if result.returncode:
            (build / 'failed-steps.json').write_text(json.dumps(steps, indent=2), encoding='utf-8')
            raise SystemExit(f'{label} failed with exit code {result.returncode}')
        return result.stdout

    unsigned = build / 'unsigned.apk'
    compiled_resources = build / 'compiled-resources'
    linked = build / 'linked'
    compiled_resources.mkdir()
    linked.mkdir()
    resources = ROOT / 'app/src/main/res'
    # Official aapt2 can emit its binary output into a directory. Standard ZIP
    # packaging avoids its native ZIP-writer crash on this Windows host.
    for directory in resources.rglob('*'):
        if directory.is_dir():
            (linked / 'res' / directory.relative_to(resources)).mkdir(parents=True, exist_ok=True)
    for resource in sorted(resources.rglob('*.xml')):
        run('Compile resource ' + resource.name, [aapt, 'compile', resource, '-o', compiled_resources])
    run('Link Android resources and manifest', [aapt, 'link', '-I', android_jar,
        '--manifest', ROOT / 'app/src/main/AndroidManifest.xml',
        '--java', generated,
        *sorted(compiled_resources.glob('*.flat')), '--output-to-dir',
        '--min-sdk-version', '24', '--target-sdk-version', '36', '-o', linked])
    with zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in linked.rglob('*'):
            if file.is_file():
                archive.write(file, file.relative_to(linked).as_posix(),
                    compress_type=zipfile.ZIP_STORED if file.name == 'resources.arsc' else zipfile.ZIP_DEFLATED)
        for asset in (ROOT / 'app/src/main/assets').rglob('*'):
            if asset.is_file():
                archive.write(asset, 'assets/' + asset.relative_to(ROOT / 'app/src/main/assets').as_posix())
    sources = [*generated.rglob('*.java'), *(ROOT / 'app/src/main/java').rglob('*.java')]
    run('Compile Java application', [javac, '-encoding', 'UTF-8', '--release', '8',
        '-classpath', android_jar, '-d', classes, *sources])
    run('Convert application classes to Android DEX', [java, '-cp', tools / 'lib/d8.jar',
        'com.android.tools.r8.D8', '--min-api', '24', '--lib', android_jar,
        '--output', dex, *classes.rglob('*.class')])
    with zipfile.ZipFile(unsigned, 'a') as archive:
        for path in dex.glob('*.dex'):
            archive.write(path, path.name, compress_type=zipfile.ZIP_STORED)
    aligned = build / 'aligned.apk'
    print('Align ZIP entries to four-byte boundaries', flush=True)
    # Preserve ordinary APK ZIP layout while avoiding the same native writer
    # failure seen in aapt2. Every extra field is a well-formed ZIP TLV.
    with zipfile.ZipFile(unsigned) as before, zipfile.ZipFile(aligned, 'w') as after:
        for old in before.infolist():
            info = zipfile.ZipInfo(old.filename, date_time=old.date_time)
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = old.external_attr
            offset = after.fp.tell() + 30 + len(info.filename.encode('utf-8'))
            padding = (-offset - 4) % 4
            info.extra = struct.pack('<HH', 0xFFFF, padding) + b'\0' * padding
            after.writestr(info, before.read(old.filename))
    steps.append({'step': 'Four-byte ZIP alignment', 'exit_code': 0,
                  'output': 'Standard ZIP TLV padding; independently verified by Android SDK zipalign'})
    run('Verify unsigned APK alignment', [align, '-c', '4', aligned])
    store = args.keystore.absolute()
    if not store.is_file():
        store.parent.mkdir(parents=True, exist_ok=True)
        run('Create a local development signing key', [keytool, '-genkeypair',
            '-keystore', store, '-storetype', 'JKS', '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=Android Debug,O=Care Monitor Demo,C=CN'])
    apk = artifact_dir / 'CareMonitor-demo-debug.apk'
    run('Sign the development APK', [java, '-jar', tools / 'lib/apksigner.jar', 'sign',
        '--ks', store, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'true', '--v4-signing-enabled', 'false',
        '--out', apk, aligned])
    signature = run('Verify APK signature', [java, '-jar', tools / 'lib/apksigner.jar',
        'verify', '--verbose', '--print-certs', apk])
    run('Verify APK alignment', [align, '-c', '4', apk])
    badging = run('Inspect packaged Android metadata', [aapt, 'dump', 'badging', apk])
    if ("package: name='org.caremonitor.demo'" not in badging or
        f"versionName='{version_name}'" not in badging or
        f"versionCode='{version_code}'" not in badging or
        not any(value in badging for value in ["minSdkVersion:'24'", "sdkVersion:'24'"])):
        raise SystemExit('Packaged application metadata did not match the project')
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() or not {'classes.dex', 'AndroidManifest.xml', 'resources.arsc',
                                    'assets/sample_sensor_data.json', 'assets/sample_sensor_history.json',
                                    'assets/sample_alerts.json', 'assets/server_ca.crt'}.issubset(archive.namelist()):
            raise SystemExit('APK structure validation failed')
    evidence = {'result': 'APK compiled, aligned, signed and verified',
        'apk': apk.name, 'bytes': apk.stat().st_size,
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'application_id': 'org.caremonitor.demo', 'version_name': version_name,
        'version_code': version_code, 'min_api': 24, 'target_api': 36,
        'input_sha256': input_sha256,
        'framework': 'Native Android Java, no WebView',
        'manifest_compiler': 'Official Android SDK aapt2',
        'resource_table': True,
        'phone_or_emulator_tested': False,
        'data_source': 'Live Classic SPP or BLE sensor reception with a separate bundled demo entrance',
        'transport': 'Secure Classic SPP/RFCOMM or BLE/GATT UTF-8 NDJSON to phone; Wi-Fi-only HTTPS forwarding',
        'physical_spp_pairing_tested': False,
        'physical_ble_or_server_delivery_tested': False,
        'steps': steps}
    (artifact_dir / 'BUILD_EVIDENCE.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in evidence.items() if key != 'steps'}, indent=2))

if __name__ == '__main__':
    main()
