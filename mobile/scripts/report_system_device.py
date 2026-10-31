"""Run real report acceptance in isolated Android/server state, never production accounts.

Requires explicit --run-device, serial and private installation-key file. Start
the isolated HTTPS deployment on PC loopback19444 with the existing CA first.
Uses unchanged production Java/resources, its own application ID and real Android
permissions. App-owned Canvas captures retain FLAG_SECURE. No UI values are faked.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PACKAGE='org.caremonitor.reportqa'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-device',action='store_true',required=True)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--key-file',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--notification-trials',type=int,choices=range(31),default=30)
    parser.add_argument('--capture-atlas',action='store_true',help='Capture the complete current screen atlas and registration drafts')
    parser.add_argument('--learning-only',action='store_true',help='Capture saved synthetic model results without connecting the wearable')
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}',args.serial) or args.serial.startswith('-'):parser.error('Use one exact serial')
    if args.key_file.stat().st_mode & 0o077:parser.error('Installation-key file must be private (0600)')
    key=args.key_file.read_bytes().strip()
    if not re.fullmatch(rb'[0-9a-fA-F]{32}',key):parser.error('Installation-key file format not recognised')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True,mode=0o700)
    sdk=Path.home()/'Android/Sdk'; bt=sdk/'build-tools/36.0.0'; android=sdk/'platforms/android-37.0/android.jar'
    adb=sdk/'platform-tools/adb'; aapt=bt/'aapt2'; align=bt/'zipalign'; store=ROOT.parent/'.local/dev-signing.jks'
    env=dict(os.environ,JAVA_HOME='/usr',JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8')
    def run(command,*,check=True,timeout=180):
        result=subprocess.run([str(x) for x in command],cwd=ROOT,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
        if check and result.returncode:raise RuntimeError(result.stdout[-3000:])
        return result.stdout
    selected=[adb,'-s',args.serial]
    run([*selected,'get-state'])
    before=run([*selected,'shell','pm','path','org.caremonitor.demo'])
    if 'package:' not in before:raise RuntimeError('Production app must already be installed')
    if 'package:' in run([*selected,'shell','pm','path',PACKAGE],check=False):raise RuntimeError('Previous report helper still installed; inspect before removing it')
    inputs=sorted(p for p in (ROOT/'app/src/main').rglob('*') if p.is_file())
    inputs += [ROOT/'tests/report/ReportSystemInstrumentation.java',Path(__file__).resolve()]
    hashes={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    build=ROOT/'build/report-system';shutil.rmtree(build,ignore_errors=True)
    folders={name:build/name for name in ('compiled','generated','linked','classes','dex')}
    for folder in folders.values():folder.mkdir(parents=True)
    android_ns='{http://schemas.android.com/apk/res/android}'
    manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml');m=manifest.getroot();m.set('package',PACKAGE)
    application=m.find('application');application.set(android_ns+'label','CareMonitor report checks');application.set(android_ns+'debuggable','true')
    for item in application:
        name=item.get(android_ns+'name','')
        if name.startswith('.'):item.set(android_ns+'name','org.caremonitor.demo'+name)
    ET.SubElement(m,'instrumentation',{android_ns+'name':'org.caremonitor.demo.ReportSystemInstrumentation',android_ns+'targetPackage':PACKAGE})
    manifest.write(build/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
    for resource in sorted((ROOT/'app/src/main/res').rglob('*.xml')):run([aapt,'compile',resource,'-o',folders['compiled']])
    run([aapt,'link','-I',android,'--manifest',build/'AndroidManifest.xml','--custom-package','org.caremonitor.demo','--java',folders['generated'],
         *sorted(folders['compiled'].glob('*.flat')),'--output-to-dir','--debug-mode','--min-sdk-version','24','--target-sdk-version','37','-o',folders['linked']])
    sources=[*folders['generated'].rglob('*.java'),*(ROOT/'app/src/main/java').rglob('*.java'),ROOT/'tests/report/ReportSystemInstrumentation.java']
    run(['/usr/bin/javac','-encoding','UTF-8','--release','8','-classpath',android,'-d',folders['classes'],*sources])
    run(['/usr/bin/java','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',android,'--output',folders['dex'],*folders['classes'].rglob('*.class')])
    unsigned,aligned,apk=(build/name for name in ('unsigned.apk','aligned.apk','ReportSystem.apk'))
    with zipfile.ZipFile(unsigned,'w',compression=zipfile.ZIP_STORED) as archive:
        for path in folders['linked'].rglob('*'):
            if path.is_file():archive.write(path,path.relative_to(folders['linked']).as_posix())
        for path in folders['dex'].glob('*.dex'):archive.write(path,path.name)
        for path in (ROOT/'app/src/main/assets').rglob('*'):
            if path.is_file():archive.write(path,'assets/'+path.relative_to(ROOT/'app/src/main/assets').as_posix())
    run([align,'-f','4',unsigned,aligned]);run(['/usr/bin/java','-jar',bt/'lib/apksigner.jar','sign','--ks',store,'--ks-key-alias','androiddebugkey',
        '--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',apk,aligned])
    run(['/usr/bin/java','-jar',bt/'lib/apksigner.jar','verify',apk]);run([align,'-c','4',apk])
    if "package: name='"+PACKAGE+"'" not in run([aapt,'dump','badging',apk]):raise RuntimeError('Wrong report package')
    if any(hashlib.sha256(p.read_bytes()).hexdigest()!=hashes[p.relative_to(ROOT).as_posix()] for p in inputs):raise RuntimeError('Sources changed during build')
    evidence={'result':'FAIL','recorded_at':datetime.now(timezone.utc).isoformat(),'source_sha256':hashes,
        'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'package':PACKAGE,'production_application_touched':False,
        'scope':('Unchanged production Java/resources in isolated Android package and database. Saved synthetic model exercises and role-authorised real HTTPS retrieval; no physical wearable connection or care delivery trial.' if args.learning_only else 'Unchanged production Java/resources in isolated Android package and isolated HTTPS database. Physical SPP telemetry, real app HTTPS, controlled account linking, native charts, saved synthetic learning, own caregiver notifications. Not production-account, same-LAN, second-phone or clinical acceptance.')}
    installed=False
    try:
        print('Built isolated report app; production accounts and APK remain unchanged.',flush=True)
        installed='Success' in run([*selected,'install','-t',apk])
        if not installed:raise RuntimeError('Report app installation failed')
        for permission in ('BLUETOOTH_SCAN','BLUETOOTH_CONNECT','POST_NOTIFICATIONS'):
            run([*selected,'shell','pm','grant',PACKAGE,'android.permission.'+permission])
        run([*selected,'reverse','tcp:19444','tcp:19444'])
        run([*selected,'shell','run-as',PACKAGE,'mkdir','-p','files'])
        if not args.learning_only:
            transfer=subprocess.run([str(x) for x in [*selected,'shell','run-as',PACKAGE,'sh','-c','"umask 077; cat > files/report-setup.key"']],input=key+b'\n',stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
            if transfer.returncode:raise RuntimeError('Private USB key transfer failed')
        print('Starting saved synthetic learning capture; no wearable connection.' if args.learning_only else 'Starting real physical/HTTPS campaign; please hold optical contact for about one minute.',flush=True)
        log=run([*selected,'shell','am','instrument','-w','-r','-e','notification_trials',str(args.notification_trials),
                 '-e','capture_atlas',str(args.capture_atlas).lower(),'-e','learning_only',str(args.learning_only).lower(),PACKAGE+'/org.caremonitor.demo.ReportSystemInstrumentation'],check=False,timeout=720)
        (output/'instrumentation.log').write_text(log)
        result=run([*selected,'exec-out','run-as',PACKAGE,'cat','files/report-result.json'],check=False)
        try:evidence['device_result']=json.loads(result)
        except json.JSONDecodeError:raise RuntimeError('No structured device result; see private instrumentation log')
        names=run([*selected,'shell','run-as',PACKAGE,'ls','files/report-captures'],check=False).splitlines();screens={}
        for name in names:
            if not re.fullmatch(r'(elder|caregiver)_[a-z0-9_]+\.png',name):continue
            path=output/name
            with path.open('wb') as target:
                copied=subprocess.run([str(x) for x in [*selected,'exec-out','run-as',PACKAGE,'cat','files/report-captures/'+name]],stdout=target,stderr=subprocess.PIPE,timeout=20)
            if copied.returncode or not path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):raise RuntimeError('Invalid native capture')
            screens[name]=hashlib.sha256(path.read_bytes()).hexdigest()
        evidence['screenshots_sha256']=screens
        evidence['result']='PASS' if evidence['device_result'].get('result')=='PASS' and 'INSTRUMENTATION_CODE: -1' in log else 'FAIL'
    finally:
        if installed:evidence['helper_removed']='Success' in run([*selected,'uninstall',PACKAGE],check=False)
        evidence['production_apk_path_unchanged']=before==run([*selected,'shell','pm','path','org.caremonitor.demo'])
        (output/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print(json.dumps({k:v for k,v in evidence.items() if k not in ('source_sha256','screenshots_sha256')},indent=2),flush=True)
    if evidence['result']!='PASS':raise SystemExit(1)

if __name__=='__main__':main()
