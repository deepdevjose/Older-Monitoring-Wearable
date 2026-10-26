"""Replay identical synthetic snapshots twice, using baseline Git code and current API."""
import contextlib, importlib.util, io, json, os, secrets, sqlite3, subprocess, sys, tempfile, time
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'server'))
from fastapi.testclient import TestClient
from caremonitor.api import create_app
from caremonitor.config import Settings
source=subprocess.run(['git','show','6757c0a:main.py'],cwd=ROOT,capture_output=True,text=True,check=True).stdout
N=20
result={'executed_at_utc':datetime.now(timezone.utc).isoformat(),'campaign':'20 unique purely synthetic snapshots; each exact message delivered twice after a fictional lost response','transport':'In-process TestClient ASGI; no network/TLS/phone','baseline_commit':'6757c0a','final_commit':subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip(),'baseline_source':'main.py','final_source':'server/caremonitor/api.py','source_changed':False}
with tempfile.TemporaryDirectory(prefix='indicare-idempotency-') as folder:
 folder=Path(folder)
 legacy_source=folder/'baseline-main-6757c0a.py';legacy_source.write_text(source)
 spec=importlib.util.spec_from_file_location('indicare_legacy_api', legacy_source);legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)
 legacy.DB_PATH=folder/'baseline.sqlite3'
 key=secrets.token_urlsafe(28);previous=os.environ.get('SENSOR_API_KEY');os.environ['SENSOR_API_KEY']=key
 now=int(time.time())
 payloads=[{'device_id':'elder-demo-01','sample_id':f'fixture-{i}','uptime_ms':1000+i,'max30102':{'status':'ok','finger_detected':True,'heart_rate_valid':True,'spo2_valid':True,'heart_rate_bpm':80,'spo2_percent':98},'gy906':{'online':True,'object_temp_c':34,'ambient_temp_c':24},'photoresistor':{'percent':5,'adc_raw':200}} for i in range(N)]
 try:
  pairs=[]
  with contextlib.redirect_stdout(io.StringIO()):
   with TestClient(legacy.app) as client:
    for payload in payloads:
     pair=[]
     for _ in range(2):
      time.sleep(.11) # respect unmodified baseline rate limit; not a throughput benchmark
      r=client.post('/sensor-data',headers={'X-API-Key':key},json=payload);assert r.status_code==200,r.text;pair.append(r.json()['record_id'])
     pairs.append(pair)
  with sqlite3.connect(legacy.DB_PATH) as db:rows=db.execute('SELECT COUNT(*) FROM sensor_readings').fetchone()[0]
  result['baseline']={'http_success':40,'unique_input_samples':N,'stored_rows':rows,'extra_rows_due_to_retry':rows-N,'pairs_reused_record_id':sum(a==b for a,b in pairs),'record_id_pairs':pairs,'rate_limit_respected_seconds_between_posts':.11}
 finally:
  if previous is None:os.environ.pop('SENSOR_API_KEY',None)
  else:os.environ['SENSOR_API_KEY']=previous
 settings=Settings(data_dir=folder/'final',enforce_https=False,scheduler_interval_seconds=3600);settings.initialize_key();app=create_app(settings)
 with TestClient(app) as client:
  r=client.post('/api/v1/auth/register',json={'email':'fictional@example.test','password':'fictional educational phrase','display_name':'Synthetic fixture','role':'elder','educational_ack':True});assert r.status_code==201,r.text
  h={'Authorization':'Bearer '+r.json()['access_token']};pairs=[];duplicate_flags=[]
  for i,payload in enumerate(payloads):
   envelope={'source':'synthetic','captured_at':datetime.fromtimestamp(now-N+i,timezone.utc).isoformat(),'sample_id':f'fixture-{i}','payload':payload}
   pair=[];flags=[]
   for _ in range(2):
    r=client.post('/api/v1/readings',headers=h,json=envelope);assert r.status_code==201,r.text;pair.append(r.json()['record_id']);flags.append(r.json()['duplicate'])
   pairs.append(pair);duplicate_flags.append(flags)
  with app.state.db.connection() as db:rows=db.execute('SELECT COUNT(*) FROM readings').fetchone()[0]
  result['final']={'http_success':40,'unique_input_samples':N,'stored_rows':rows,'extra_rows_due_to_retry':rows-N,'pairs_reused_record_id':sum(a==b for a,b in pairs),'record_id_pairs':pairs,'duplicate_flags':duplicate_flags}
result['limitations']='Backend idempotency comparison with comparable synthetic payloads across changed route/auth contracts. Not an app UI before/after experiment, latency comparison, physical delivery test or proof against every concurrent failure.'
(Path(__file__).resolve().parents[1]/'evidence/idempotency-rerun.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in {'baseline','final'}},indent=2))
print('baseline', {k:v for k,v in result['baseline'].items() if k!='record_id_pairs'})
print('final', {k:v for k,v in result['final'].items() if k not in {'record_id_pairs','duplicate_flags'}})
