#!/usr/bin/env python3
"""Exercise an isolated loopback TLS server with fictional accounts only.

Run carectl --data-dir .local/integration init --host 127.0.0.1 --port 18443,
then start it. Never points at a production server or prints credentials.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import secrets
import ssl
import time
import uuid
import httpx

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'.local/integration'
config=json.loads((DATA/'server.json').read_text())
assert config['host']=='127.0.0.1' and config['port']==18443,'Isolated loopback fixture required'
BASE='https://127.0.0.1:18443/api/v1'
TLS=ssl.create_default_context(cafile=str(DATA/'tls/ca.crt'))
checks={}

def call(method,path,token=None,body=None):
    with httpx.Client(base_url=BASE,verify=TLS,timeout=35,trust_env=False) as client:
        return client.request(method,path,headers={'Authorization':'Bearer '+token} if token else {},json=body) if body is not None else client.request(method,path,headers={'Authorization':'Bearer '+token} if token else {})

def ok(response,status=200):
    assert response.status_code==status,(response.status_code,response.text[:180])
    return response.json()

def stamp(offset=0): return (datetime.now(timezone.utc)+timedelta(seconds=offset)).isoformat()

def account(role):
    return ok(call('POST','/auth/register',body={'email':uuid.uuid4().hex+'@example.test','password':secrets.token_urlsafe(24),'display_name':'Fictional '+role,'role':role,'educational_ack':True}),201)

def link(elder,caregiver):
    code=ok(call('POST','/invitations',elder['access_token']),201)['code']
    request=ok(call('POST','/link-requests',caregiver['access_token'],{'code':code}),201)
    ok(call('POST','/link-requests/'+request['id']+'/approve',elder['access_token']))

elder=account('elder'); first=account('caregiver'); second=account('caregiver'); stranger=account('caregiver')
et=elder['access_token']; ft=first['access_token']; st=second['access_token']; patient=elder['user']['id']
link(elder,first);link(elder,second)
checks['two_approved_caregivers']=len(ok(call('GET','/caregivers',et))['caregivers'])==2
checks['unlinked_patient_denied']=call('GET','/patients/'+patient+'/overview',stranger['access_token']).status_code==404
cursors=[ok(call('GET','/notifications',t))['cursor'] for t in [ft,st]]
payload={'device_id':'elder-demo-01','max30102':{'status':'ok','finger_detected':True,'heart_rate_valid':True,'heart_rate_bpm':75,'spo2_valid':True,'spo2_percent':92},'mpu6050':{'online':True,'accel_g':{'x':0,'y':0,'z':1},'gyro_dps':{'x':0,'y':0,'z':0}},'gy906':{'online':True,'object_temp_c':33,'ambient_temp_c':22},'photoresistor':{'adc_raw':100,'percent':2}}
with ThreadPoolExecutor(max_workers=2) as pool:
    pending=[pool.submit(call,'GET',f'/notifications/wait?after={cursor}&timeout=20',token) for token,cursor in zip([ft,st],cursors)]
    for _ in range(3):
        envelope={'sample_id':str(uuid.uuid4()),'captured_at':stamp(),'source':'synthetic','payload':payload}
        received=ok(call('POST','/readings',et,envelope),201)
        time.sleep(1.1)
    checks['third_sample_created_alert']=received['alert_created'] is True
    for index,future in enumerate(pending):
        result=ok(future.result())
        checks[f'caregiver_{index+1}_received_private_longpoll']=any(item['type']=='alert' for item in result['items'])
retry=ok(call('POST','/readings',et,envelope),201)
checks['lost_response_retry_is_idempotent']=retry['duplicate'] is True and retry['record_id']==received['record_id']
alerts=ok(call('GET','/patients/'+patient+'/alerts',ft))['alerts']; alert_id=alerts[0]['id']
ok(call('POST','/alerts/'+alert_id+'/acknowledge',ft))
checks['review_is_per_caregiver']=ok(call('GET','/patients/'+patient+'/alerts',st))['alerts'][0]['acknowledged'] is False
# A second phone for the same account still sees the same item after receipt ACK.
inbox=ok(call('GET','/notifications',ft)); item=next(i for i in inbox['items'] if i['type']=='alert')
ok(call('POST',f"/notifications/{item['id']}/ack",ft))
checks['second_phone_can_fetch_acked_delivery']=any(i['id']==item['id'] for i in ok(call('GET','/notifications',ft))['items'])
synthetic=ok(call('POST','/ai/classify',et,{'source':'synthetic','features':{'heart_rate_bpm':75.,'spo2_percent':98.,'body_temp_c':36.5,'sample_age_seconds':0.}}))
checks['synthetic_model_served_over_tls']=synthetic['educational_only'] is True
example=ok(call('GET','/ai/fall/examples',et))['examples'][1]
replay=ok(call('POST','/ai/fall-classify',et,{key:example[key] for key in ['source','sample_rate_hz','record_complete','features']}))
checks['fall_replay_model_served_over_tls']=replay['task']=='complete_record_classification'
checks['location_without_consent_rejected']=call('POST','/location',et,{'outside_home':True,'latitude':0.,'longitude':0.,'accuracy_m':5.,'recorded_at':stamp()}).status_code==403
cursor=ok(call('GET','/notifications',et))['cursor']
reminder=ok(call('POST','/patients/'+patient+'/reminders',ft,{'title':'Fictional classroom reminder','dose':'Example only','instructions':'No medicine is prescribed','due_at':stamp(1),'repeat_hours':24}),201)
result=ok(call('GET',f'/notifications/wait?after={cursor}&timeout=25',et))
checks['scheduled_reminder_reaches_elder']=any(i['type']=='reminder' for i in result['items'])
ok(call('DELETE','/reminders/'+reminder['id'],ft))
checks['cancelled_reminder_cannot_be_taken']=call('POST','/reminders/'+reminder['id']+'/taken',et).status_code in [404,409]
ok(call('DELETE','/caregivers/'+first['user']['id'],et))
checks['revocation_blocks_previous_caregiver']=call('GET','/patients/'+patient+'/overview',ft).status_code==404
checks['other_caregiver_keeps_access']=call('GET','/patients/'+patient+'/overview',st).status_code==200
ok(call('POST','/auth/logout',st));checks['logout_revokes_token']=call('GET','/me',st).status_code==401
try:
    httpx.get('https://localhost:18443/health',verify=TLS,trust_env=False,timeout=5)
    checks['wrong_hostname_rejected']=False
except httpx.ConnectError: checks['wrong_hostname_rejected']=True
assert all(checks.values()),checks
out={'executed_at':stamp(),'transport':'Real loopback HTTPS with generated CA and strict hostname verification','fixture':'Fictional isolated .local/integration accounts, no physical phone or cloud','checks':checks,'passed':sum(checks.values()),'total':len(checks),'physical_phone_tested':False,'bluetooth_to_phone_tested':False}
(ROOT/'release').mkdir(exist_ok=True)
(ROOT/'release/INTEGRATION_EVIDENCE.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps(out,indent=2))
