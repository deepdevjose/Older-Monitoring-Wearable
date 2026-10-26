"""Run status, inbox, framing, freshness, relay and stream-session checks (no Android runtime)."""
import argparse
from datetime import datetime
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME',
    Path.home() / 'Documents/Codex/toolchains/android-build/jdk'))
args = parser.parse_args()
suffix = '.exe' if os.name == 'nt' else ''
folder = ROOT / 'build' / ('status-tests-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
folder.mkdir(parents=True)
env = os.environ.copy()
env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') +
    ' -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8').strip()
subprocess.run([str(args.jdk / ('bin/javac' + suffix)), '-encoding', 'UTF-8',
    '--release', '8', '-d', str(folder),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/ReadingSummary.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/OpticalEstimatePolicy.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/AlertCatalog.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/AlertInbox.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/NdjsonFramer.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/LiveSampleFreshness.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/RelayPolicy.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/ServerEndpointMigration.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/SensorModuleNavigation.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/SessionLineBuffer.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/RemoteAlertInbox.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/ServerCredentialState.java'),
    str(ROOT / 'app/src/main/java/org/caremonitor/demo/SecureSetupPolicy.java'),
    str(ROOT / 'tests/ReadingSummaryTest.java'),
    str(ROOT / 'tests/OpticalEstimatePolicyTest.java'),
    str(ROOT / 'tests/AlertInboxTest.java'),
    str(ROOT / 'tests/NdjsonFramerTest.java'),
    str(ROOT / 'tests/LiveSampleFreshnessTest.java'),
    str(ROOT / 'tests/RelayPolicyTest.java'),
    str(ROOT / 'tests/ServerEndpointMigrationTest.java'),
    str(ROOT / 'tests/SensorModuleNavigationTest.java'),
    str(ROOT / 'tests/SessionLineBufferTest.java'),
    str(ROOT / 'tests/RemoteAlertInboxTest.java'),
    str(ROOT / 'tests/SecureSetupPolicyTest.java')], check=True, env=env)
subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.ReadingSummaryTest'], check=True, env=env)
subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.AlertInboxTest'], check=True, env=env)
for name in ['org.caremonitor.demo.NdjsonFramerTest','org.caremonitor.demo.LiveSampleFreshnessTest','RelayPolicyTest','org.caremonitor.demo.ServerEndpointMigrationTest','org.caremonitor.demo.SensorModuleNavigationTest','org.caremonitor.demo.SessionLineBufferTest','org.caremonitor.demo.RemoteAlertInboxTest','org.caremonitor.demo.SecureSetupPolicyTest']:
    subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
        name], check=True, env=env)
subprocess.run([str(args.jdk / ('bin/java' + suffix)), '-cp', str(folder),
    'org.caremonitor.demo.OpticalEstimatePolicyTest'], check=True, env=env)
