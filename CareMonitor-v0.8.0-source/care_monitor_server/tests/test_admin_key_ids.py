"""Regression for a real random-ID corner case discovered during candidate checks."""
import pytest
from care_monitor_server.admin import main
from care_monitor_server.database import connect, transaction
from care_monitor_server import security


@pytest.mark.parametrize('command', ['revoke', 'rotate'])
def test_hyphen_prefixed_key_id_can_be_managed(db, monkeypatch, capsys, command):
    original = security.secrets.token_urlsafe
    monkeypatch.setattr(security.secrets, 'token_urlsafe', lambda n: '-testkey1234' if n == 9 else original(n))
    with connect(db) as conn, transaction(conn):
        token, _ = security.issue(conn, label='Fixture only', scopes={'sensor:read'})
    monkeypatch.setattr(security.secrets, 'token_urlsafe', original)
    args = ['--db', str(db), command, token.split('.')[0]]
    if command == 'rotate':
        args += ['--label', 'Rotated fixture']
    assert main(args) == 0
    capsys.readouterr()
    with connect(db) as conn:
        assert conn.execute('SELECT revoked_at FROM cm_credentials WHERE key_id=?', (token.split('.')[0],)).fetchone()[0] is not None
