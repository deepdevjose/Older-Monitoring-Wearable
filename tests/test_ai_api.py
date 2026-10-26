import json
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from caremonitor.api import create_app
from caremonitor.config import Settings


@pytest.fixture
def authenticated(tmp_path):
    settings=Settings(data_dir=tmp_path,enforce_https=False);settings.initialize_key()
    with TestClient(create_app(settings)) as client:
        result=client.post('/api/v1/auth/register',json={'email':'synthetic@example.test','password':'fictional educational phrase','display_name':'Synthetic user','role':'elder','educational_ack':True})
        assert result.status_code==201
        yield client,{'Authorization':'Bearer '+result.json()['access_token']}


def test_models_require_authentication(authenticated):
    client,_=authenticated
    assert client.get('/api/v1/ai/model').status_code==401
    assert client.get('/api/v1/ai/fall/examples').status_code==401


def test_synthetic_api_rejects_device_and_missing_features(authenticated):
    client,headers=authenticated
    body={'source':'synthetic','features':{'heart_rate_bpm':75.,'spo2_percent':98.,'body_temp_c':36.5,'sample_age_seconds':0.}}
    result=client.post('/api/v1/ai/classify',headers=headers,json=body)
    assert result.status_code==200,result.text
    assert result.json()['educational_only'] is True
    assert result.json()['classification'] in ['Normal','Warning','Critical']
    body['source']='device_diagnostic'
    assert client.post('/api/v1/ai/classify',headers=headers,json=body).status_code==422
    body['source']='synthetic';body['features'].pop('body_temp_c')
    assert client.post('/api/v1/ai/classify',headers=headers,json=body).status_code==422


def test_fall_api_replays_full_record_but_rejects_live_snapshots(authenticated):
    client,headers=authenticated
    examples=client.get('/api/v1/ai/fall/examples',headers=headers)
    assert examples.status_code==200,examples.text
    example=examples.json()['examples'][1]
    body={key:example[key] for key in ['source','sample_rate_hz','record_complete','features']}
    result=client.post('/api/v1/ai/fall-classify',headers=headers,json=body)
    assert result.status_code==200,result.text
    assert result.json()['task']=='complete_record_classification'
    assert result.json()['educational_only'] is True
    body['sample_rate_hz']=1
    assert client.post('/api/v1/ai/fall-classify',headers=headers,json=body).status_code==422
    body['sample_rate_hz']=200;body['source']='device_diagnostic'
    assert client.post('/api/v1/ai/fall-classify',headers=headers,json=body).status_code==422


def test_optional_api_docs_are_self_hosted_without_cdn(tmp_path):
    settings=Settings(data_dir=tmp_path,enforce_https=False,expose_docs=True);settings.initialize_key()
    with TestClient(create_app(settings)) as client:
        response=client.get('/docs')
        assert response.status_code==200
        assert '<script' not in response.text and '<link' not in response.text
        assert 'cdn.jsdelivr' not in response.text
        assert client.get('/openapi.json').status_code==200
