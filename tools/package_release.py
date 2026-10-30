#!/usr/bin/env python3
"""Package the one canonical tree, rejecting stale builds/checks and private material."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import tomllib
import zipfile
ROOT=Path(__file__).resolve().parents[1]
RELEASE=ROOT/'release'
VERSION=tomllib.loads((ROOT/'pyproject.toml').read_text())['project']['version']

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def verify_hashes(base,entries):
    for name,digest in entries.items():
        path=base/name
        if not path.is_file() or sha(path)!=digest: raise SystemExit(f'Stale evidence: {name}')


def verify_preview(build):
    """Require the isolated UI campaign to match every production build input."""
    path=RELEASE/'ANDROID_HISTORY_UI_EVIDENCE.json'
    if not path.is_file(): raise SystemExit('Missing isolated Android UI evidence')
    preview=json.loads(path.read_text())
    if (preview.get('result')!='PASS' or not preview.get('checks',0)
            or preview.get('production_application_touched') is not False
            or preview.get('preview_package_removed') is not True):
        raise SystemExit('Failed or incomplete isolated Android UI evidence')
    entries=preview.get('input_sha256',{})
    production={name:digest for name,digest in build['input_sha256'].items()
                if name.startswith('app/src/main/')}
    tested={name:digest for name,digest in entries.items()
            if name.startswith('app/src/main/')}
    if tested!=production: raise SystemExit('Isolated Android UI evidence does not match the production build')
    verify_hashes(ROOT/'mobile',entries)
    return preview


def main():
    RELEASE.mkdir(exist_ok=True)
    build=json.loads((ROOT/'mobile/artifacts/BUILD_EVIDENCE.json').read_text())
    if build['version_name']!=VERSION: raise SystemExit('Android/server version mismatch')
    verify_hashes(ROOT/'mobile',build['input_sha256'])
    actual={p.relative_to(ROOT/'mobile').as_posix() for p in (ROOT/'mobile/app/src/main').rglob('*') if p.is_file()}
    actual.add('scripts/build_apk.py')
    if actual!=set(build['input_sha256']): raise SystemExit('Android source inventory changed after build')
    apk=ROOT/'mobile/artifacts'/build['apk']
    if sha(apk)!=build['sha256']: raise SystemExit('APK hash does not match verified build')
    ui_file=RELEASE/'ANDROID_UI_EVIDENCE.json'
    ui=json.loads(ui_file.read_text()) if ui_file.exists() else None
    if ui is not None and (ui['apk_sha256']!=build['sha256'] or ui['result']!='PASS'):
        raise SystemExit('Stale or failed Android UI evidence; rerun device checks for this APK')
    if ui is not None: verify_hashes(ROOT,ui['input_sha256'])
    preview=verify_preview(build)
    validation=json.loads((RELEASE/'VALIDATION.json').read_text())
    verify_hashes(ROOT,validation['input_sha256'])
    if any(c['exit_code'] for c in validation['commands']): raise SystemExit('Failed validation command')
    if validation['python']['errors'] or validation['python']['failures']:raise SystemExit('Failed Python tests')
    integration=json.loads((RELEASE/'INTEGRATION_EVIDENCE.json').read_text())
    if integration['passed']!=integration['total'] or not all(integration['checks'].values()):raise SystemExit('Integration evidence failed')
    protocol=json.loads((RELEASE/'BT_PROTOCOL_EVIDENCE.json').read_text())
    delivery=json.loads((RELEASE/'APP_BLUETOOTH_SERVER_EVIDENCE.json').read_text())
    if (protocol.get('result')!='PASS' or delivery.get('result')!='PASS'
            or not protocol.get('recorded_at','').startswith('2026-10-27')
            or not delivery.get('recorded_at','').startswith('2026-10-27')
            or delivery.get('version_code')!=17):
        raise SystemExit('Historical Bluetooth evidence identity mismatch')
    for directory in (ROOT/'models').iterdir():
        if not directory.is_dir():continue
        metadata=json.loads((directory/'metadata.json').read_text())
        verify_hashes(directory,metadata['artifact_sha256'])
    outputs={}
    destination=RELEASE/f'CareMonitor-{VERSION}.apk';shutil.copyfile(apk,destination);outputs[destination.name]=sha(destination)
    firmware_diagnostic=ROOT/'.local/firmware-check.json'
    if not firmware_diagnostic.exists(): firmware_diagnostic=ROOT/'.local/firmware-check-20261025.json'
    for name,source in [('ANDROID_BUILD.json',ROOT/'mobile/artifacts/BUILD_EVIDENCE.json'),('JVM_TEST_RESULTS.txt',ROOT/'mobile/artifacts/JVM_TEST_RESULTS.txt'),('FIRMWARE_DIAGNOSTIC.json',firmware_diagnostic)]:
        if source.exists():shutil.copyfile(source,RELEASE/name)
    sources=[]
    for directory in ['server','mobile/app','mobile/scripts','mobile/tests','firmware','datasets','models','docs','tests','tools']:
        for path in (ROOT/directory).rglob('*'):
            if not path.is_file() or '__pycache__' in path.parts:continue
            if path.name=='secrets.h' or path.suffix in ['.key','.pem','.jks','.keystore','.pyc']:continue
            if path.is_symlink(): raise SystemExit(f'Refusing source symlink: {path.relative_to(ROOT)}')
            sources.append(path)
    for name in ['README.md','.gitignore','.env.example','pyproject.toml','requirements.txt','carectl','start_server.sh','mobile/README.md']:
        sources.append(ROOT/name)
    sources=sorted(set(sources))
    manifest={p.relative_to(ROOT).as_posix():sha(p) for p in sources}
    private_header=b'-----BEGIN '+b'PRIVATE KEY-----'
    for path in sources:
        if path.suffix in ['.py','.md','.h','.ino','.java','.xml','.json','.example'] and private_header in path.read_bytes():
            raise SystemExit(f'Private key found in sources: {path.relative_to(ROOT)}')
    package=RELEASE/f'CareMonitor-{VERSION}-source.zip'
    with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sources:
            name=path.relative_to(ROOT).as_posix()
            archive.write(path,name,compress_type=zipfile.ZIP_STORED if '.tar.gz.' in path.name else zipfile.ZIP_DEFLATED)
        archive.writestr('SOURCE_SHA256.json',json.dumps(manifest,indent=2)+'\n')
    verify_hashes(ROOT,manifest)
    with zipfile.ZipFile(package) as archive:
        if archive.testzip(): raise SystemExit('Source ZIP integrity failure')
        for name,digest in manifest.items():
            if hashlib.sha256(archive.read(name)).hexdigest()!=digest: raise SystemExit(f'Packaged source mismatch: {name}')
        if any('/secrets.h' in name or name.startswith(('.local/','.venv/')) for name in archive.namelist()):raise SystemExit('Private file packaged')
    outputs[package.name]=sha(package)
    report={'version':VERSION,'created_at':datetime.now(timezone.utc).isoformat(),'source_files':len(sources),'source_sha256':manifest,
            'outputs':outputs,'python_tests':validation['python'],'jvm_assertions':validation['jvm_assertions'],
            'https_integration_checks':integration['passed'],'private_keys_packaged':False,
            'physical_phone_tested':ui is not None,
            'physical_phone_test_scope':ui['scope'] if ui else [],
            'isolated_android_ui_checks':preview['checks'],
            'isolated_android_ui_scope':preview['scope'],
            'historical_bluetooth_evidence':{
                'recorded_date':'2026-10-27','normal_application_version_code':17,
                'normal_application_apk_sha256':delivery['installed_apk_sha256'],
                'protocol':'BT_PROTOCOL_EVIDENCE.json',
                'normal_application_delivery':'APP_BLUETOOTH_SERVER_EVIDENCE.json',
                'current_apk_flow_retested':False},
            'full_bluetooth_phone_server_tested':False,'cloud_services_used':False,
            'signing':'Local development certificate; retain private .local/dev-signing.jks for updates'}
    (RELEASE/'MANIFEST.json').write_text(json.dumps(report,indent=2)+'\n')
    checksum_files=sorted(p for p in RELEASE.iterdir() if p.is_file() and p.name!='SHA256SUMS')
    (RELEASE/'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in checksum_files))
    print(json.dumps({k:v for k,v in report.items() if k!='source_sha256'},indent=2))

if __name__=='__main__':main()
