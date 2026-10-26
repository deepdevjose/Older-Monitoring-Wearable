"""Build native UI instrumentation; optionally run it on one explicitly selected Android device.

Never installs/uninstalls the target app, clears data, grants permissions, takes screenshots,
or changes FLAG_SECURE. --build-only compiles and verifies the test APK without invoking adb.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TEST_PACKAGE = 'org.caremonitor.uitest'
TARGET_PACKAGE = 'org.caremonitor.demo'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Exact adb serial; never selects a device implicitly')
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk'))
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME', '/usr'))
    parser.add_argument('--build-only', action='store_true', help='Build only; do not invoke adb')
    args = parser.parse_args()
    sdk, jdk = args.sdk.absolute(), args.jdk.absolute()
    suffix = '.exe' if os.name == 'nt' else ''
    tools = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-37.0/android.jar'
    java, javac = [jdk / 'bin' / (name + suffix) for name in ('java', 'javac')]
    aapt, align = [tools / (name + suffix) for name in ('aapt2', 'zipalign')]
    store = ROOT.parent / '.local/dev-signing.jks'
    for path in [java, javac, aapt, align, android, tools / 'lib/d8.jar', tools / 'lib/apksigner.jar', store]:
        if not path.is_file():
            raise SystemExit('Required tool or existing development keystore missing: ' + str(path))
    build = ROOT / 'build/onboarding-device'
    if build.exists():
        shutil.rmtree(build)
    classes, dex, linked = [build / name for name in ('classes', 'dex', 'linked')]
    for directory in [classes, dex, linked]:
        directory.mkdir(parents=True)
    env = os.environ.copy()
    env['JAVA_HOME'] = str(jdk)
    env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') +
        ' -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8').strip()

    def run(command, *, check=True):
        result = subprocess.run([str(item) for item in command], cwd=ROOT, env=env,
                                text=True, encoding='utf-8', errors='replace',
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.stdout:
            print(result.stdout, end='' if result.stdout.endswith('\n') else '\n', flush=True)
        if check and result.returncode:
            raise RuntimeError('Command failed with exit code ' + str(result.returncode))
        return result

    sources = ROOT / 'tests/instrumentation'
    run([aapt, 'link', '-I', android, '--manifest', sources / 'AndroidManifest.xml',
         '--min-sdk-version', '24', '--target-sdk-version', '37', '--output-to-dir', '-o', linked])
    run([javac, '-encoding', 'UTF-8', '--release', '8', '-classpath', android,
         '-d', classes, sources / 'SetupInstrumentation.java'])
    run([java, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '24',
         '--lib', android, '--output', dex, *sorted(classes.rglob('*.class'))])
    unsigned, aligned, apk = [build / name for name in ('unsigned.apk', 'aligned.apk', 'SetupInstrumentation.apk')]
    with zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(linked.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(linked).as_posix())
        for path in sorted(dex.glob('*.dex')):
            archive.write(path, path.name)
    run([align, '-f', '4', unsigned, aligned])
    run([java, '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', store,
         '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
         '--v4-signing-enabled', 'false', '--out', apk, aligned])
    run([java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', apk])
    run([align, '-c', '4', apk])
    badging = run([aapt, 'dump', 'badging', apk]).stdout
    if "package: name='" + TEST_PACKAGE + "'" not in badging:
        raise RuntimeError('Test APK package did not match the isolated test package')
    print('Test APK: ' + str(apk))
    print('SHA256: ' + hashlib.sha256(apk.read_bytes()).hexdigest())
    if args.build_only:
        print('BUILD ONLY: no adb invocation or device checks executed.')
        return
    adb = sdk / 'platform-tools' / ('adb' + suffix)
    if not adb.is_file():
        raise SystemExit('adb missing: ' + str(adb))
    if not args.serial.strip() or args.serial.startswith('-'):
        raise SystemExit('Supply one exact, nonempty device serial')
    selected = [adb, '-s', args.serial]
    run([*selected, 'get-state'])
    target = run([*selected, 'shell', 'pm', 'path', TARGET_PACKAGE]).stdout
    if 'package:' not in target:
        raise SystemExit('Install the intended CareMonitor APK separately before running these checks')
    installed = False
    try:
        install = run([*selected, 'install', '-r', '-t', apk])
        installed = 'Success' in install.stdout
        if not installed:
            raise RuntimeError('Test-package installation did not confirm success')
        outcome = run([*selected, 'shell', 'am', 'instrument', '-w', '-r',
                       TEST_PACKAGE + '/.SetupInstrumentation']).stdout
        if 'INSTRUMENTATION_RESULT: result=ABORTED_EXISTING_SESSION' in outcome:
            raise SystemExit('Aborted: an existing signed-in account was detected and left unchanged')
        if ('INSTRUMENTATION_RESULT: result=PASS' not in outcome
                or 'INSTRUMENTATION_CODE: -1' not in outcome):
            raise RuntimeError('On-device UI checks did not pass; inspect instrumentation output above')
    finally:
        if installed:
            cleanup = run([*selected, 'uninstall', TEST_PACKAGE], check=False)
            if cleanup.returncode or 'Success' not in cleanup.stdout:
                raise RuntimeError('Could not remove the isolated test package: ' + TEST_PACKAGE)


if __name__ == '__main__':
    main()
