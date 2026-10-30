"""Offline deployment guards; no real network, phone or private state is touched."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location('start_offline', Path(__file__).parents[1] / 'tools/start_offline.py')
offline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offline)


def addresses(ip='192.168.3.3', prefix=24):
    return [{'ifname': 'wlan-test', 'addr_info': [{'family': 'inet', 'scope': 'global',
             'local': ip, 'prefixlen': prefix}]}]


@pytest.mark.parametrize('ip,prefix', [('192.168.3.3', 0), ('192.168.3.3', 1), ('127.0.0.1', 8),
    ('169.254.1.2', 16), ('224.0.0.3', 24), ('0.0.0.0', 0), ('8.8.8.8', 24)])
def test_untrusted_dhcp_cannot_broaden_firewall_scope(ip, prefix):
    with pytest.raises(ValueError): offline.interface_network(addresses(ip, prefix), 'wlan-test')


def test_dhcp_selection_uses_only_prepared_wifi_interface():
    network = offline.interface_network([{'ifname': 'tun0', 'addr_info': addresses('10.6.0.2')[0]['addr_info']},
                                         *addresses()], 'wlan-test')
    assert str(network.ip) == '192.168.3.3'
    assert str(network.network) == '192.168.3.0/24'
    with pytest.raises(ValueError): offline.interface_network([], 'wlan-test')
    with pytest.raises(ValueError): offline.interface_network(addresses() + addresses(), 'wlan-test')


@pytest.mark.parametrize('field,value', [('server_port', True), ('server_port', 0), ('server_port', 65536),
    ('phone_serial', '-arbitrary'), ('ssid', ''), ('interface', 'wifi\nunsafe')])
def test_bad_private_configuration_fails_before_commands(tmp_path, field, value):
    config = {'ssid': 'Network', 'interface': 'wlan-test', 'phone_serial': 'fictional', 'server_port': 8443}
    config[field] = value
    path = tmp_path / 'network.json'; path.write_text(json.dumps(config)); path.chmod(0o600)
    with pytest.raises(ValueError): offline.read_private_config(path)


def test_configuration_rejects_shared_files_and_links(tmp_path):
    path = tmp_path / 'network.json'; path.write_text('{}'); path.chmod(0o644)
    with pytest.raises(ValueError): offline.read_private_config(path)
    path.chmod(0o600)
    link = tmp_path / 'link'; link.symlink_to(path)
    with pytest.raises(ValueError): offline.read_private_config(link)


def test_wrong_wifi_refuses_before_server_phone_or_firewall_mutation(tmp_path, monkeypatch):
    config = {'ssid': 'Network', 'interface': 'wlan-test', 'phone_serial': 'fictional', 'server_port': 8443}
    path = tmp_path / 'network.json'; path.write_text(json.dumps(config)); path.chmod(0o600)
    monkeypatch.setattr(offline, 'PRIVATE', tmp_path)
    monkeypatch.setattr(offline, 'preflight', lambda _: tmp_path)
    monkeypatch.setattr(offline.sys, 'argv', ['start_offline.py'])
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        if command == ['nmcli', '-g', 'GENERAL.CONNECTION', 'device', 'show', 'wlan-test']:
            return SimpleNamespace(stdout='Other Wi-Fi\n')
        if command == ['nmcli', '-g', '802-11-wireless.ssid', 'connection', 'show', 'Other Wi-Fi']:
            return SimpleNamespace(stdout='Other Wi-Fi\n')
        pytest.fail('Unexpected mutation or command: ' + repr(command))
    monkeypatch.setattr(offline, 'run', run)
    with pytest.raises(ValueError, match='Primero conecta'): offline.main()
    assert len(calls) == 2


def test_canonical_state_is_explicit_even_when_environment_overrides(monkeypatch):
    monkeypatch.setenv('CAREMONITOR_HOME', '/unrelated/private/state')
    captured = []
    monkeypatch.setattr(offline, 'run', lambda command: captured.append(command))
    offline.carectl('stop')
    assert captured == [[offline.ROOT / 'carectl', '--data-dir', offline.STATE, 'stop']]


@pytest.mark.parametrize('serial_override', [None, 'R5CT20D7X6D'])
def test_usb_mode_uses_only_authorized_usb_and_loopback_canonical_backend(tmp_path,monkeypatch,capsys,serial_override):
    # No SSID/interface/port are needed, and no networking or hardware command runs for real.
    path=tmp_path/'network.json';path.write_text(json.dumps({'phone_serial':'fictional-usb'}));path.chmod(0o600)
    before=path.read_bytes();sdk=tmp_path/'sdk';events=[]
    chosen_serial=serial_override or 'fictional-usb'
    monkeypatch.setattr(offline,'PRIVATE',tmp_path)
    monkeypatch.setattr(offline.sys,'argv',['start_offline.py','--usb']+
                        (['--serial',serial_override] if serial_override else []))
    monkeypatch.setenv('CAREMONITOR_HOME','/unrelated/private/state')
    def preflight(config,*,usb=False):
        assert usb and config=={'phone_serial':chosen_serial}
        events.append('preflight');return sdk
    monkeypatch.setattr(offline,'preflight',preflight)
    def mobile(actual_sdk,endpoint,serial):
        assert (actual_sdk,endpoint,serial)==(sdk,'https://127.0.0.1:19443/api/v1',chosen_serial)
        events.append('mobile');return 'mobile/artifacts/fixture.apk'
    monkeypatch.setattr(offline,'prepare_mobile',mobile)
    monkeypatch.setattr(offline,'allow_local_firewall',lambda *args:pytest.fail('USB must not change firewall'))
    monkeypatch.setattr(offline,'interface_network',lambda *args:pytest.fail('USB must not query a Wi-Fi interface'))
    expected=[
        [offline.ROOT/'carectl','--data-dir',offline.STATE,'stop'],
        [offline.ROOT/'carectl','--data-dir',offline.STATE,'prepare-network','--host','127.0.0.1','--port',19443],
        [offline.ROOT/'carectl','--data-dir',offline.STATE,'start'],
        [sdk/'platform-tools/adb','-s',chosen_serial,'reverse','tcp:19443','tcp:19443'],
        [offline.ROOT/'carectl','--data-dir',offline.STATE,'status'],
    ]
    commands=[]
    def run(command,**kwargs):
        assert command==expected[len(commands)],'Unexpected external/network command: '+repr(command)
        commands.append(command);events.append(command[-1]);return SimpleNamespace(stdout='fixture\n',returncode=0)
    monkeypatch.setattr(offline,'run',run)
    assert offline.main() is None
    assert commands==expected and events[:2]==['preflight','mobile']
    assert path.read_bytes()==before
    record=json.loads((tmp_path/'last-start.json').read_text())
    assert record['mode']=='usb' and record['endpoint']=='https://127.0.0.1:19443/api/v1'
    assert record['firewall']=='not-needed-loopback' and 'Wi-Fi delivery not verified' in record['scope']
    assert (tmp_path/'last-start.json').stat().st_mode&0o077==0
    output=capsys.readouterr().out
    assert 'Mantén el Samsung conectado' in output and 'no acredita' in output


@pytest.mark.parametrize('serial', ['', '-s', 'has space', 'line\nbreak', 'tab\tserial',
                                  'serial/other', 'serial;command', 'a'*129])
def test_invalid_serial_override_fails_before_reading_config_or_running_commands(monkeypatch,serial):
    monkeypatch.setattr(offline.sys,'argv',['start_offline.py','--serial='+serial,'--check'])
    monkeypatch.setattr(offline,'read_private_config',lambda *args,**kwargs:pytest.fail('Invalid serial must fail before state access'))
    monkeypatch.setattr(offline,'run',lambda *args,**kwargs:pytest.fail('Invalid serial must not execute commands'))
    with pytest.raises(SystemExit) as error:
        offline.main()
    assert error.value.code==2


@pytest.mark.parametrize('usb', [False, True])
def test_check_serial_override_is_ephemeral_and_does_not_mutate_shared_config(tmp_path,monkeypatch,usb):
    config={'ssid':'Network','interface':'wlan-test','phone_serial':'R5GL436GDXD','server_port':8443}
    path=tmp_path/'network.json';path.write_text(json.dumps(config));path.chmod(0o600)
    before=path.read_bytes();seen=[]
    monkeypatch.setattr(offline,'PRIVATE',tmp_path)
    monkeypatch.setattr(offline.sys,'argv',['start_offline.py','--check','--serial','192.168.3.5:5555']+
                        (['--usb'] if usb else []))
    # Reuse the same object to verify the override does not mutate a caller's configuration either.
    monkeypatch.setattr(offline,'read_private_config',lambda *args,**kwargs:config)
    def preflight(selected,*,usb=False):
        seen.append(selected.copy());return tmp_path/'sdk'
    monkeypatch.setattr(offline,'preflight',preflight)
    monkeypatch.setattr(offline,'run',lambda *args,**kwargs:pytest.fail('Check must not execute deployment commands'))
    monkeypatch.setattr(offline,'prepare_mobile',lambda *args:pytest.fail('Check must not install or build'))
    monkeypatch.setattr(offline,'private_json',lambda *args:pytest.fail('Check must not write a start record'))
    assert offline.main() is None
    assert seen==[{**config,'phone_serial':'192.168.3.5:5555'}]
    assert config['phone_serial']=='R5GL436GDXD' and path.read_bytes()==before
    assert list(tmp_path.iterdir())==[path]


def test_lan_mode_targets_override_phone_without_replacing_saved_serial(tmp_path,monkeypatch):
    config={'ssid':'Network','interface':'wlan-test','phone_serial':'R5GL436GDXD','server_port':8443}
    path=tmp_path/'network.json';path.write_text(json.dumps(config));path.chmod(0o600)
    before=path.read_bytes();sdk=tmp_path/'sdk';mobile_calls=[];server_calls=[]
    monkeypatch.setattr(offline,'PRIVATE',tmp_path)
    monkeypatch.setattr(offline.sys,'argv',['start_offline.py','--serial','R5CT20D7X6D'])
    monkeypatch.setattr(offline,'preflight',lambda _:sdk)
    def run(command,**kwargs):
        if command==['nmcli','-g','GENERAL.CONNECTION','device','show','wlan-test']:
            return SimpleNamespace(stdout='Network\n')
        if command==['nmcli','-g','802-11-wireless.ssid','connection','show','Network']:
            return SimpleNamespace(stdout='Network\n')
        if command==['ip','-j','-4','address','show','dev','wlan-test']:
            return SimpleNamespace(stdout=json.dumps(addresses()))
        pytest.fail('Unexpected command '+repr(command))
    monkeypatch.setattr(offline,'run',run)
    def mobile(*args):
        mobile_calls.append(args);return 'mobile/artifacts/fixture.apk'
    monkeypatch.setattr(offline,'prepare_mobile',mobile)
    def carectl(*args):
        server_calls.append(args);return SimpleNamespace(stdout='fixture\n')
    monkeypatch.setattr(offline,'carectl',carectl)
    monkeypatch.setattr(offline,'allow_local_firewall',lambda *args:'allowed')
    assert offline.main() is None
    assert mobile_calls==[(sdk,'https://192.168.3.3:8443/api/v1','R5CT20D7X6D')]
    assert [tuple(map(str,call)) for call in server_calls]==[
        ('stop',),('prepare-network','--host','192.168.3.3','--port','8443'),('start',),('status',)]
    assert path.read_bytes()==before


def test_usb_check_does_not_install_reverse_start_server_or_change_files(tmp_path,monkeypatch):
    path=tmp_path/'network.json';path.write_text(json.dumps({'phone_serial':'fictional-usb'}));path.chmod(0o600)
    before=path.read_bytes();calls=[]
    monkeypatch.setattr(offline,'PRIVATE',tmp_path)
    monkeypatch.setattr(offline.sys,'argv',['start_offline.py','--usb','--check'])
    def preflight(config,*,usb=False):
        assert usb;calls.append('preflight');return tmp_path/'sdk'
    monkeypatch.setattr(offline,'preflight',preflight)
    monkeypatch.setattr(offline,'run',lambda *args,**kwargs:pytest.fail('USB check must not execute commands after preflight'))
    monkeypatch.setattr(offline,'prepare_mobile',lambda *args:pytest.fail('USB check must not install or rebuild'))
    monkeypatch.setattr(offline,'private_json',lambda *args:pytest.fail('USB check must not write a start record'))
    assert offline.main() is None and calls==['preflight']
    assert list(tmp_path.iterdir())==[path] and path.read_bytes()==before


def test_endpoint_patch_changes_only_deployment_default():
    before = 'prefix\nstatic final String DEFAULT_BASE="https://192.168.3.3:8443/api/v1";\nsuffix'
    after = offline.replace_default(before, 'https://192.168.3.5:8443/api/v1')
    assert after == before.replace('192.168.3.3', '192.168.3.5')
    with pytest.raises(ValueError): offline.replace_default('unrelated source', 'https://192.168.3.5:8443/api/v1')


def test_firewall_permission_failure_is_pending_not_success(monkeypatch):
    monkeypatch.setattr(offline.shutil, 'which', lambda _: '/fake/firewall-cmd')
    commands = []
    def run(command, **kwargs):
        commands.append(command)
        if '--get-zone-of-interface' in command:
            return SimpleNamespace(returncode=1, stdout='')
        if '--get-default-zone' in command:
            return SimpleNamespace(returncode=0, stdout='home\n')
        return SimpleNamespace(returncode=int(command[0] == 'sudo'), stdout='running\n')
    monkeypatch.setattr(offline, 'run', run)
    assert offline.allow_local_firewall('wlan-test', '192.168.3.0/24', 8443) == 'pending'
    assert commands[-1][-1] == '--timeout=8h'
    assert 'source address="192.168.3.0/24"' in commands[-1][-2]


@pytest.mark.parametrize('ports,already_allowed', [
    ('1025-65535/tcp', True), ('8443/tcp', True), ('8443-9000/tcp', True),
    ('8000-8443/tcp', True), ('8443-8443/tcp', True), ('53/udp 80/tcp 8443/tcp', True),
    ('8443/udp', False), ('8444-9000/tcp', False), ('8000-8442/tcp', False),
    ('8444-8443/tcp', False), ('0-65535/tcp', False), ('1-65536/tcp', False),
    ('84430/tcp', False), ('8443/sctp', False), ('malformed', False), ('', False),
])
def test_existing_firewall_tcp_permission_is_detected_without_sudo(monkeypatch,capsys,ports,already_allowed):
    monkeypatch.setattr(offline.shutil,'which',lambda _:'/fake/firewall-cmd')
    commands=[]
    def run(command,**kwargs):
        commands.append(command)
        if command[0]=='sudo':
            assert not already_allowed, 'An existing allowance must not require administrative permission'
            return SimpleNamespace(returncode=1,stdout='')
        if '--state' in command:
            return SimpleNamespace(returncode=0,stdout='running\n')
        if '--get-zone-of-interface' in command:
            return SimpleNamespace(returncode=0,stdout='fixture-zone\n')
        if '--list-ports' in command:
            assert '--zone=fixture-zone' in command
            return SimpleNamespace(returncode=0,stdout=ports)
        pytest.fail('Unexpected firewall command '+repr(command))
    monkeypatch.setattr(offline,'run',run)
    result=offline.allow_local_firewall('wlan-test','192.168.3.0/24',8443)
    assert result==('already-allowed' if already_allowed else 'pending')
    assert any(command[0]=='sudo' for command in commands)==(not already_allowed)
    if already_allowed:
        assert 'no se cambió su política existente' in capsys.readouterr().out


def test_failed_firewall_port_inspection_does_not_claim_existing_permission(monkeypatch):
    monkeypatch.setattr(offline.shutil,'which',lambda _:'/fake/firewall-cmd')
    commands=[]
    def run(command,**kwargs):
        commands.append(command)
        if '--get-zone-of-interface' in command:
            return SimpleNamespace(returncode=0,stdout='fixture-zone\n')
        if '--list-ports' in command:
            # Even plausible output is unusable if firewalld rejected the query.
            return SimpleNamespace(returncode=1,stdout='1025-65535/tcp')
        return SimpleNamespace(returncode=int(command[0]=='sudo'),stdout='running\n')
    monkeypatch.setattr(offline,'run',run)
    assert offline.allow_local_firewall('wlan-test','192.168.3.0/24',8443)=='pending'
    assert commands[-1][0]=='sudo'


def test_changed_sources_force_rebuild_instead_of_installing_stale_release(tmp_path, monkeypatch):
    import hashlib
    root = tmp_path / 'repo'
    java = root / 'mobile/app/src/main/java/org/caremonitor/demo/ApiSession.java'
    java.parent.mkdir(parents=True)
    java.write_text('static final String DEFAULT_BASE="https://192.168.3.3:8443/api/v1";')
    builder = root / 'mobile/scripts/build_apk.py'; builder.parent.mkdir(parents=True); builder.write_text('# fixture')
    release = root / 'release'; release.mkdir()
    (release / 'CareMonitor-1.0.0.apk').write_bytes(b'obsolete apk')
    (release / 'ANDROID_BUILD.json').write_text(json.dumps({'input_sha256': {}, 'apk': 'CareMonitor-1.0.0.apk'}))
    private = root / '.local/offline'; private.mkdir(parents=True)
    monkeypatch.setattr(offline, 'ROOT', root)
    monkeypatch.setattr(offline, 'PRIVATE', private)
    commands = []
    def run(command, **kwargs):
        commands.append([str(item) for item in command])
        if str(builder) in commands[-1]:
            directory = root / 'mobile/artifacts'; directory.mkdir()
            apk = directory / 'CareMonitor-1.0.0.apk'; apk.write_bytes(b'newly verified apk')
            inputs = {str(path.relative_to(root / 'mobile')): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in (java, builder)}
            (directory / 'BUILD_EVIDENCE.json').write_text(json.dumps({'input_sha256': inputs,
                'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}))
            return SimpleNamespace(returncode=0, stdout='fixture build completed')
        if 'get-state' in commands[-1]: return SimpleNamespace(returncode=0, stdout='device\n')
        if 'path' in commands[-1]: return SimpleNamespace(returncode=0, stdout='package:/fixture/base.apk\n')
        if 'sha256sum' in commands[-1]: return SimpleNamespace(returncode=0, stdout='')
        if 'install' in commands[-1]:
            assert command[-1] == root / 'mobile/artifacts/CareMonitor-1.0.0.apk'
            return SimpleNamespace(returncode=0, stdout='Success\n')
        pytest.fail('Unexpected command ' + repr(command))
    monkeypatch.setattr(offline, 'run', run)
    assert offline.prepare_mobile(tmp_path / 'sdk', 'https://192.168.3.3:8443/api/v1', 'fictional') == 'mobile/artifacts/CareMonitor-1.0.0.apk'
    assert any(str(builder) in command for command in commands)
