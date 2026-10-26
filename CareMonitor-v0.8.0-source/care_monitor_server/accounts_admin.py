"""Explicit local account provisioning; prompts hide passwords and never write them to logs."""
import argparse
import getpass
import json
from pathlib import Path

from . import account_auth
from .database import connect, initialise, transaction


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('create')
    create.add_argument('--username', required=True)
    create.add_argument('--role', choices=['patient', 'caregiver'], required=True)
    create.add_argument('--patient-id')
    assign = commands.add_parser('assign')
    assign.add_argument('--username', required=True)
    assign.add_argument('--patient-id', required=True)
    disable = commands.add_parser('disable')
    disable.add_argument('--username', required=True)
    args = parser.parse_args()
    password = None
    if args.command == 'create':
        password = getpass.getpass('New password (12-128 characters): ')
        if password != getpass.getpass('Repeat password: '):
            parser.error('Passwords do not match')
    initialise(args.database)
    with connect(args.database) as conn, transaction(conn):
        account_auth.initialise(conn)
        if args.command == 'create':
            account_auth.create_user(conn, username=args.username, password=password,
                                     role=args.role, patient_id=args.patient_id)
        elif args.command == 'assign':
            account_auth.assign_caregiver(conn, args.username, args.patient_id)
        else:
            changed = conn.execute('UPDATE cm_users SET active=0 WHERE username=?', (args.username,)).rowcount
            if not changed:
                parser.error('Unknown account')
            conn.execute('UPDATE cm_user_sessions SET revoked=1 WHERE username=?', (args.username,))
    print(json.dumps({'status': 'complete', 'operation': args.command, 'username': args.username}))


if __name__ == '__main__':
    main()
