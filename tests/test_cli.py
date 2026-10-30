from argparse import Namespace
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import socket
import sqlite3
import ssl
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
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


def network_args(**overrides):
    return Namespace(**dict({'host':'192.168.8.20','port':None,'bind':None},**overrides))


def state_snapshot(settings):
    return {str(path.relative_to(settings.data_dir)):path.read_bytes()
        for path in settings.data_dir.rglob('*') if path.is_file()}


def tls_handshake(settings, hostname):
    """Connect only on loopback while strictly checking the requested SAN name."""
    ca,cert,key=cli.tls_paths(settings)
    server=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);server.load_cert_chain(cert,key)
    client=ssl.create_default_context(cafile=str(ca))
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0));listener.listen(1);listener.settimeout(3)
        def accept():
            connection,_=listener.accept()
            with connection:
                try:
                    with server.wrap_socket(connection,server_side=True) as secure: secure.sendall(b'OK')
                except ssl.SSLError: pass  # A rejected hostname aborts the peer handshake too.
        with ThreadPoolExecutor(max_workers=1) as executor:
            future=executor.submit(accept)
            try:
                with socket.create_connection(listener.getsockname(),timeout=3) as connection:
                    with client.wrap_socket(connection,server_hostname=hostname) as secure:
                        assert secure.recv(2)==b'OK'
            finally: future.result(timeout=4)


def test_prepare_network_retains_accounts_keys_and_old_trust_with_strict_tls(tmp_path):
    settings=initialized(tmp_path)
    # Include durable account and session rows, so the check covers existing user data.
    with sqlite3.connect(settings.db_path) as conn:
        conn.execute("INSERT INTO users VALUES ('user','lookup','encrypted profile','hash','elder',1,1)")
        conn.execute("INSERT INTO sessions VALUES ('token hash','user',9999999999,1,NULL)")
    before=state_snapshot(settings)
    cli.prepare_network(network_args(port=19443),settings)
    after=state_snapshot(settings)
    assert after.keys()==before.keys()
    assert {name for name in after if after[name]!=before[name]}=={'tls/server.crt','server.json'}
    config=json.loads(after['server.json'])
    assert (config['host'],config['port'],config['bind'])==('192.168.8.20',19443,'192.168.8.20')
    assert all(path.stat().st_mode&0o077==0 for path in settings.data_dir.rglob('*'))
    tls_handshake(settings,'192.168.8.20')
    tls_handshake(settings,'127.0.0.1')
    tls_handshake(settings,'caremonitor.local')
    with pytest.raises(ssl.SSLCertVerificationError): tls_handshake(settings,'192.168.8.21')
    cli.prepare_network(network_args(host='192.168.9.20'),settings)
    tls_handshake(settings,'192.168.8.20')
    tls_handshake(settings,'192.168.9.20')
    assert json.loads((settings.data_dir/'server.json').read_text())['port']==19443


@pytest.mark.parametrize('arguments',[
    {'host':'0.0.0.0'}, {'host':'224.0.0.1'}, {'host':'8.8.8.8'}, {'host':'169.254.1.1'},
    {'host':'https://192.168.8.20'}, {'host':'192.168.8.20/24'}, {'host':'fe80::1%wlan0'},
    {'bind':'8.8.8.8'}, {'bind':'::'}, {'port':80}, {'port':65536},
])
def test_prepare_network_rejects_unsafe_inputs_without_changes(tmp_path,arguments):
    settings=initialized(tmp_path);before=state_snapshot(settings)
    with pytest.raises(ValueError): cli.prepare_network(network_args(**arguments),settings)
    assert state_snapshot(settings)==before


def test_prepare_network_refuses_active_server(tmp_path,monkeypatch):
    settings=initialized(tmp_path);before=state_snapshot(settings)
    monkeypatch.setattr(cli,'running_pid',lambda _:12345)
    with pytest.raises(ValueError,match='Stop the managed server'): cli.prepare_network(network_args(),settings)
    assert state_snapshot(settings)==before


@pytest.mark.parametrize('name',['tls/ca.key','tls/server.key','tls/ca.crt','tls/server.crt',
    'encryption.key','caremonitor.sqlite3','server.json'])
def test_prepare_network_rejects_file_symlinks(tmp_path,name):
    settings=initialized(tmp_path);path=settings.data_dir/name
    target=tmp_path/'linked-original';path.rename(target);path.symlink_to(target)
    before=state_snapshot(settings)
    with pytest.raises(ValueError,match='private owned'): cli.prepare_network(network_args(),settings)
    assert state_snapshot(settings)==before and path.is_symlink()


def test_prepare_network_rejects_tls_directory_symlink(tmp_path):
    settings=initialized(tmp_path);path=settings.data_dir/'tls'
    target=tmp_path/'linked-tls';path.rename(target);path.symlink_to(target,target_is_directory=True)
    before={p.name:p.read_bytes() for p in target.iterdir()}
    with pytest.raises(ValueError,match='private owned'): cli.prepare_network(network_args(),settings)
    assert {p.name:p.read_bytes() for p in target.iterdir()}==before and path.is_symlink()


@pytest.mark.parametrize('name',['tls/ca.key','tls/server.key','server.json'])
def test_prepare_network_rejects_shared_permissions(tmp_path,name):
    settings=initialized(tmp_path);(settings.data_dir/name).chmod(0o644);before=state_snapshot(settings)
    with pytest.raises(ValueError,match='private owned'): cli.prepare_network(network_args(),settings)
    assert state_snapshot(settings)==before


@pytest.mark.parametrize('name',['tls/ca.key','tls/server.key'])
def test_prepare_network_rejects_mismatched_private_keys(tmp_path,name):
    settings=initialized(tmp_path)
    (settings.data_dir/name).write_bytes(ec.generate_private_key(ec.SECP256R1()).private_bytes(
        serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    before=state_snapshot(settings)
    with pytest.raises(ValueError,match='does not match'): cli.prepare_network(network_args(),settings)
    assert state_snapshot(settings)==before


def rewrite_authority(settings,*,expires,ca=True):
    path=cli.tls_paths(settings)[0]
    authority=x509.load_pem_x509_certificate(path.read_bytes())
    key=serialization.load_pem_private_key((path.parent/'ca.key').read_bytes(),None)
    now=datetime.now(timezone.utc)
    builder=(x509.CertificateBuilder().subject_name(authority.subject).issuer_name(authority.issuer)
        .public_key(authority.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-timedelta(days=4)).not_valid_after(expires))
    for extension in authority.extensions:
        value=x509.BasicConstraints(ca=ca,path_length=0 if ca else None) if isinstance(extension.value,x509.BasicConstraints) else extension.value
        builder=builder.add_extension(value,critical=extension.critical)
    path.write_bytes(builder.sign(key,hashes.SHA256()).public_bytes(serialization.Encoding.PEM))


@pytest.mark.parametrize('case',['expired','not-ca','missing-key'])
def test_prepare_network_rejects_unusable_authority_without_changes(tmp_path,case):
    settings=initialized(tmp_path);now=datetime.now(timezone.utc)
    if case=='missing-key': (settings.data_dir/'tls/ca.key').unlink()
    else: rewrite_authority(settings,expires=now+timedelta(days=-2 if case=='expired' else 2),ca=case!='not-ca')
    before=state_snapshot(settings)
    with pytest.raises((ValueError,FileNotFoundError)): cli.prepare_network(network_args(),settings)
    assert state_snapshot(settings)==before


def test_prepare_network_caps_leaf_lifetime_to_retained_authority(tmp_path):
    settings=initialized(tmp_path)
    rewrite_authority(settings,expires=datetime.now(timezone.utc)+timedelta(days=2))
    cli.prepare_network(network_args(),settings)
    ca,cert,_=cli.tls_paths(settings)
    assert x509.load_pem_x509_certificate(cert.read_bytes()).not_valid_after_utc==x509.load_pem_x509_certificate(ca.read_bytes()).not_valid_after_utc
    tls_handshake(settings,'192.168.8.20')


def test_prepare_network_rolls_back_if_second_atomic_replace_fails(tmp_path,monkeypatch):
    settings=initialized(tmp_path);before=state_snapshot(settings)
    replace=cli.os.replace;calls=0
    def fail_second(source,destination):
        nonlocal calls
        calls+=1
        if calls==2: raise OSError('simulated disk error')
        replace(source,destination)
    monkeypatch.setattr(cli.os,'replace',fail_second)
    with pytest.raises(OSError,match='simulated disk error'): cli.prepare_network(network_args(),settings)
    assert state_snapshot(settings)==before
    tls_handshake(settings,'127.0.0.1')


def test_serve_and_status_honor_prepared_bind_without_contacting_hotspot(tmp_path,monkeypatch):
    import httpx
    import uvicorn
    from caremonitor import api
    settings=initialized(tmp_path)
    cli.prepare_network(network_args(bind='0.0.0.0'),settings)
    seen={}
    monkeypatch.setattr(api,'create_app',lambda _:object())
    monkeypatch.setattr(uvicorn,'run',lambda app,**kwargs:seen.update(kwargs))
    cli.serve(Namespace(bind=None,port=None,docs=False),settings)
    assert seen['host']=='0.0.0.0' and seen['port']==18443
    def get(url,**kwargs):
        seen['url']=url
        assert kwargs['trust_env'] is False and kwargs['verify'].check_hostname
        return httpx.Response(200,json={'status':'ok'},request=httpx.Request('GET',url))
    monkeypatch.setattr(httpx,'get',get)
    assert cli.status(Namespace(),settings) is None
    assert seen['url']=='https://127.0.0.1:18443/health'
    cli.prepare_network(network_args(),settings)
    cli.serve(Namespace(bind=None,port=None,docs=False),settings)
    assert seen['host']=='192.168.8.20'
    cli.status(Namespace(),settings)
    assert seen['url']=='https://192.168.8.20:18443/health'


def test_prepare_network_cli_accepts_explicit_local_deployment_options(tmp_path,capsys):
    settings=initialized(tmp_path)
    assert cli.main(['--data-dir',str(settings.data_dir),'prepare-network','--host','192.168.8.21','--port','19443','--bind','0.0.0.0'])==0
    config=json.loads((settings.data_dir/'server.json').read_text())
    assert (config['host'],config['port'],config['bind'])==('192.168.8.21',19443,'0.0.0.0')
    assert 'No network, VPN or firewall settings changed' in capsys.readouterr().out
