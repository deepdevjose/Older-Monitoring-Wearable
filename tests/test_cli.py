from argparse import Namespace
from pathlib import Path
import json
import sqlite3
import ssl
import pytest
from cryptography import x509
from caremonitor.config import Settings
from caremonitor.db import Database
from caremonitor import cli


def initialized(tmp_path):
    settings=Settings(data_dir=tmp_path/'state')
    cli.initialize(Namespace(host='127.0.0.1',port=18443),settings)
    return settings


def test_tls_is_usable_with_strict_hostname_validation(tmp_path):
    settings=initialized(tmp_path)
    ca,cert,key=cli.tls_paths(settings)
    context=ssl.create_default_context(cafile=str(ca))
    context.check_hostname=True
    certificate=x509.load_pem_x509_certificate(cert.read_bytes())
    assert str(certificate.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.IPAddress)[0])=='127.0.0.1'
    certificate.extensions.get_extension_for_class(x509.AuthorityKeyIdentifier)
    assert key.stat().st_mode&0o077==0
    assert settings.data_dir.stat().st_mode&0o077==0


def test_init_does_not_replace_encryption_or_tls_keys(tmp_path):
    settings=initialized(tmp_path)
    before=settings.key_path.read_bytes(),cli.tls_paths(settings)[2].read_bytes()
    cli.initialize(Namespace(host='127.0.0.1',port=18443),settings)
    assert before==(settings.key_path.read_bytes(),cli.tls_paths(settings)[2].read_bytes())


def test_encrypted_backup_restore_invalidates_sessions(tmp_path,monkeypatch):
    settings=initialized(tmp_path)
    from caremonitor.api import create_app
    from fastapi.testclient import TestClient
    settings.enforce_https=False
    with TestClient(create_app(settings)) as client:
        response=client.post('/api/v1/auth/register',json={'email':'elder@example.test','password':'a long fictional passphrase','display_name':'Fictional elder','role':'elder','educational_ack':True})
        assert response.status_code==201
    phrase='an independent secure backup passphrase'
    monkeypatch.setattr(cli.getpass,'getpass',lambda _:phrase)
    backup=tmp_path/'backup.cmbackup'
    cli.backup(Namespace(output=str(backup)),settings)
    assert b'SQLite format' not in backup.read_bytes()
    assert backup.stat().st_mode&0o077==0
    destination=tmp_path/'restored'
    cli.restore(Namespace(backup=str(backup),destination=str(destination)),settings)
    restored=Settings(data_dir=destination)
    assert restored.load_key()==settings.load_key()
    with sqlite3.connect(restored.db_path) as conn:
        assert conn.execute('select count(*) from users').fetchone()[0]==1
        assert conn.execute('select count(*) from sessions').fetchone()[0]==0
    with pytest.raises(ValueError): cli.restore(Namespace(backup=str(backup),destination=str(destination)),settings)


def test_wrong_passphrase_never_creates_restore_state(tmp_path,monkeypatch):
    settings=initialized(tmp_path)
    monkeypatch.setattr(cli.getpass,'getpass',lambda _:'a sufficiently long original passphrase')
    output=tmp_path/'encrypted.cmbackup';cli.backup(Namespace(output=str(output)),settings)
    monkeypatch.setattr(cli.getpass,'getpass',lambda _:'a different sufficiently long passphrase')
    destination=tmp_path/'should-not-exist'
    with pytest.raises(Exception): cli.restore(Namespace(backup=str(output),destination=str(destination)),settings)
    assert not destination.exists()


def test_init_rejects_misleading_port_change(tmp_path):
    settings=initialized(tmp_path)
    with pytest.raises(ValueError): cli.initialize(Namespace(host='127.0.0.1',port=18444),settings)
    assert json.loads((settings.data_dir/'server.json').read_text())['port']==18443


def test_vpn_configs_are_private_and_never_overwritten(tmp_path):
    settings=initialized(tmp_path)
    args=Namespace(endpoint='vpn.example.test:51820',peers=2)
    cli.vpn_config(args,settings)
    directory=settings.data_dir.parent/'wireguard'
    assert len(list(directory.glob('*.conf')))==3
    assert all(not path.stat().st_mode&0o077 for path in directory.glob('*.conf'))
    assert 'AllowedIPs = 127.0.0.1/32, 10.77.0.1/32' in (directory/'phone-1.conf').read_text()
    with pytest.raises(ValueError): cli.vpn_config(args,settings)
