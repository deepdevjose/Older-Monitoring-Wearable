"""Run profile setup, status, inbox and transport checks (no Android runtime)."""
import argparse
from datetime import datetime
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME',
    Path('/usr')))
args = parser.parse_args()
suffix = '.exe' if os.name == 'nt' else ''
folder = ROOT / 'build' / 'jvm-tests'
folder.mkdir(parents=True,exist_ok=True)
env = os.environ.copy()
env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') +
    ' -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8').strip()
subprocess.run([str(args.jdk / ('bin/javac' + suffix)), '-encoding', 'UTF-8',
    '--release', '8', '-d', str(folder),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/AccountSetup.java'),
    str(ROOT / 'tests/AccountSetupTest.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/ReadingSummary.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/AlertCatalog.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/AlertInbox.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/NdjsonFramer.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/LiveSampleFreshness.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/RelayPolicy.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/SessionLineBuffer.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/PrivacyBoundary.java'),
    str(ROOT / 'tests/PrivacyBoundaryTest.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/NotificationCursor.java'),
    str(ROOT / 'tests/NotificationCursorTest.java'),
    str(ROOT / 'tests/ReadingSummaryTest.java'),
    str(ROOT / 'tests/AlertInboxTest.java'),
    str(ROOT / 'tests/NdjsonFramerTest.java'),
    str(ROOT / 'tests/LiveSampleFreshnessTest.java'),
    str(ROOT / 'tests/RelayPolicyTest.java'),
    str(ROOT / 'tests/SessionLineBufferTest.java')], check=True, env=env)
subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.ReadingSummaryTest'], check=True, env=env)
subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.AlertInboxTest'], check=True, env=env)
for name in ['org.caremonitor.demo.NdjsonFramerTest','org.caremonitor.demo.LiveSampleFreshnessTest','RelayPolicyTest','org.caremonitor.demo.SessionLineBufferTest']:
    subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
        name], check=True, env=env)

subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.PrivacyBoundaryTest'], check=True, env=env)

subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.NotificationCursorTest'], check=True, env=env)

subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.AccountSetupTest'], check=True, env=env)
