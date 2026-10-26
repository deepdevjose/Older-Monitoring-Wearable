"""Read-only repository audit benchmark; disposable DBs and in-process ASGI only."""
import sys, json, time, tempfile, platform
from pathlib import Path
from datetime import datetime, timezone
import numpy as np
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'server'))
from fastapi.testclient import TestClient
from caremonitor.api import create_app
from caremonitor.config import Settings

def register(client):
 r=client.post('/api/v1/auth/register',json={'email':'fictional@example.test','password':'fictional educational phrase','display_name':'Synthetic fixture','role':'elder','educational_ack':True})
 assert r.status_code==201,r.text
 return {'Authorization':'Bearer '+r.json()['access_token']}
def stats(values):
 return {'n':len(values),'mean_ms':float(np.mean(values)),'p50_ms':float(np.percentile(values,50)),'p95_ms':float(np.percentile(values,95)),'min_ms':min(values),'max_ms':max(values),'percentile_method':'numpy.percentile linear'}
def settings(path):
 s=Settings(data_dir=path,enforce_https=False,scheduler_interval_seconds=3600);s.initialize_key();return s

def classify_body(i):
 samples=[(75.,98.,36.5),(110.,96.,37.7),(135.,88.,39.)]
 a,b,c=samples[i%3]
 return {'source':'synthetic','features':{'heart_rate_bpm':a,'spo2_percent':b,'body_temp_c':c,'sample_age_seconds':0.}}
result={'executed_at_utc':datetime.now(timezone.utc).isoformat(),'python':platform.python_version(),'platform':platform.platform(),'transport':'In-process FastAPI TestClient ASGI; enforce_https=False in disposable test configuration; no socket/network/phone','changes':'No source, model, release, hardware or live service changed','classification':{},'episode':{}}
with tempfile.TemporaryDirectory(prefix='indicare-benchmark-') as folder:
 base=Path(folder)
 with TestClient(create_app(settings(base/'classification'))) as client:
  h=register(client)
  t=time.perf_counter_ns();r=client.post('/api/v1/ai/classify',headers=h,json=classify_body(0));cold=(time.perf_counter_ns()-t)/1e6
  assert r.status_code==200,r.text
  times=[];examples=[]
  for i in range(25):
   body=classify_body(i);t=time.perf_counter_ns();r=client.post('/api/v1/ai/classify',headers=h,json=body);times.append((time.perf_counter_ns()-t)/1e6)
   assert r.status_code==200,r.text
   if i<3:examples.append({'request':body,'classification':r.json()['classification'],'confidence':r.json()['confidence']})
  result['classification']={'endpoint':'POST /api/v1/ai/classify','cold_ms':cold,'warm':stats(times),'warm_raw_ms':times,'examples':examples,'scope':'Authentication lookup, JSON validation, rate-limit transaction, prediction, JSON response; excludes account registration and any mobile/TLS/network transport'}
 episode=[];ingest=[]
 for trial in range(20):
  with TestClient(create_app(settings(base/f'episode-{trial}'))) as client:
   h=register(client);now=int(time.time())
   def reading(i):
    return {'sample_id':f'synthetic-{i}','captured_at':datetime.fromtimestamp(now-3+i,timezone.utc).isoformat(),'source':'synthetic','payload':{'device_id':'elder-demo-01','uptime_ms':1000,'max30102':{'status':'ok','finger_detected':True,'heart_rate_valid':True,'spo2_valid':True,'heart_rate_bpm':150,'spo2_percent':98},'gy906':{'online':True,'object_temp_c':34,'ambient_temp_c':24},'photoresistor':{'percent':5,'adc_raw':200}}}
   for i in range(2):
    r=client.post('/api/v1/readings',headers=h,json=reading(i));assert r.status_code==201 and not r.json()['alert_created'],r.text
   t=time.perf_counter_ns();r=client.post('/api/v1/readings',headers=h,json=reading(2));after_post=time.perf_counter_ns()
   assert r.status_code==201 and r.json()['alert_created'],r.text
   inbox=client.get('/api/v1/notifications',headers=h);after_inbox=time.perf_counter_ns()
   assert inbox.status_code==200 and len(inbox.json()['items'])==1,inbox.text
   ingest.append((after_post-t)/1e6);episode.append((after_inbox-t)/1e6)
   retry=client.post('/api/v1/readings',headers=h,json=reading(2));assert retry.json()['duplicate'] and not retry.json()['alert_created']
   fourth=client.post('/api/v1/readings',headers=h,json=reading(3));assert not fourth.json()['alert_created']
   assert len(client.get('/api/v1/notifications',headers=h).json()['items'])==1
 result['episode']={'endpoint':'Third POST /api/v1/readings followed by GET /api/v1/notifications','third_ingestion':stats(ingest),'server_inbox_available':stats(episode),'third_ingestion_raw_ms':ingest,'server_inbox_raw_ms':episode,'episodes_created':20,'duplicate_unresolved_alerts':0,'duplicate_retry_checks_passed':20,'sustained_fourth_sample_checks_passed':20,'scope':'Synthetic pulse_high only; two prior valid samples already stored. Timer starts before third POST and ends after authenticated inbox GET. No sampling dwell time, long-poll delay, caregiver handset, OS notification or network/TLS latency. Each trial uses one fresh DB and fictional elder account; elder inbox used.'}
(Path(__file__).resolve().parents[1]/'evidence/benchmark-rerun.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in {'classification','episode'}}))
print('classification',result['classification']['warm'],'cold_ms',cold)
print('episode_third_ingestion',result['episode']['third_ingestion'])
print('episode_inbox',result['episode']['server_inbox_available'])
