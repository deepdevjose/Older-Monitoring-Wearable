#!/usr/bin/env python3
"""Execute the release's software checks and record results, never hardware claims."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
local=ROOT/'.local/verification';local.mkdir(parents=True,exist_ok=True)
release=ROOT/'release';release.mkdir(exist_ok=True)
commands=[('python',[sys.executable,'-m','pytest','tests','--junitxml',str(local/'pytest.xml')]),
          ('jvm',[sys.executable,'mobile/scripts/test_reading_summary.py','--jdk','/usr'])]
results=[]
for name,command in commands:
    run=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (local/f'{name}.log').write_text(run.stdout)
    print(f'{name}: exit {run.returncode}')
    if run.returncode:
        print(run.stdout[-6000:]);raise SystemExit(run.returncode)
    results.append({'name':name,'command':command,'exit_code':run.returncode})
root=ET.parse(local/'pytest.xml').getroot()
suites=list(root.iter('testsuite'))
counts={key:sum(int(s.attrib.get(key,0)) for s in suites) for key in ['tests','failures','errors','skipped']}
jvm=(ROOT/'mobile/artifacts/JVM_TEST_RESULTS.txt').read_text()
checks=sum(map(int,re.findall(r'(?:PASS:\s*|:\s*)(\d+)\s+(?:\w+\s+)?checks',jvm)))
inputs={}
for prefix in ['server','tests','tools','mobile/app/src/main','mobile/tests','mobile/scripts']:
    for p in sorted((ROOT/prefix).rglob('*')):
        if not p.is_file() or '__pycache__' in p.parts:continue
        inputs[p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
evidence={'executed_at':datetime.now(timezone.utc).isoformat(),'commands':results,'python':counts,'jvm_assertions':checks,
          'input_sha256':inputs,'physical_android_tested':False,'android_emulator_tested':False,
          'full_bluetooth_phone_server_tested':False,'cloud_services_used':False}
(release/'VALIDATION.json').write_text(json.dumps(evidence,indent=2)+'\n')
print(json.dumps({k:v for k,v in evidence.items() if k not in ['input_sha256','commands']},indent=2))
