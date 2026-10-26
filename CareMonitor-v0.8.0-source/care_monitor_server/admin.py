"""Local-only credential administration. There is deliberately no admin HTTP API."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import sqlite3
import sys
import time

from .database import connect, default_path, initialise, transaction
from .security import SCOPES, issue, revoke, utc_timestamp


class CredentialArgumentParser(argparse.ArgumentParser):
    def _parse_optional(self, arg_string):
        # Generated URL-safe key IDs may start with '-'. On key-ID commands,
        # recognize that bounded identifier without swallowing real options.
        if (getattr(self, 'accept_key_id', False)
                and arg_string not in self._option_string_actions
                and re.fullmatch(r'-[A-Za-z0-9_-]{11}', arg_string)):
            return None
        return super()._parse_optional(arg_string)


def main(argv: list[str] | None = None) -> int:
    parser = CredentialArgumentParser(description="Administer Care Monitor credentials locally")
    parser.add_argument("--db",type=Path,default=None,help="Same DB as CARE_MONITOR_DB used by server")
    sub = parser.add_subparsers(dest="command",required=True)
    sub.add_parser("init",help="Create additive server tables without editing old lab tables")
    sub.add_parser("list",help="List credential metadata; secrets cannot be recovered")
    for command in ("create","rotate"):
        p = sub.add_parser(command)
        if command == "rotate":
            p.accept_key_id = True
            p.add_argument("key_id",help="Revoke this key atomically before issuing its replacement")
        p.add_argument("--label",required=True)
        p.add_argument("--device-id",default=None if command=="rotate" else "elder-demo-01")
        p.add_argument("--patient-id",default=None,help="Required only when creating a new device mapping")
        p.add_argument("--scope",action="append",choices=sorted(SCOPES),default=None)
        p.add_argument("--days",type=int,default=7)
    p = sub.add_parser("revoke")
    p.accept_key_id = True
    p.add_argument("key_id")
    args = parser.parse_args(argv)
    db = args.db if args.db is not None else default_path()
    try:
        initialise(db)
        with connect(db) as conn:
            if args.command == "init":
                print("Database initialized. Legacy tables were retained.")
            elif args.command == "list":
                rows = conn.execute("SELECT key_id,label,device_id,scopes_json,created_at,expires_at,revoked_at FROM cm_credentials ORDER BY created_at,key_id").fetchall()
                now = int(time.time())
                for row in rows:
                    status = "revoked" if row["revoked_at"] is not None else "expired" if row["expires_at"]<=now else "active"
                    print(json.dumps({"key_id":row["key_id"],"label":row["label"],"device_id":row["device_id"],
                                      "scopes":json.loads(row["scopes_json"]),"expires_at":utc_timestamp(row["expires_at"]),"status":status}))
            elif args.command == "revoke":
                with transaction(conn):
                    if not revoke(conn,args.key_id):
                        raise ValueError("Credential ID was not found")
                print("Credential revoked. Saved sensor readings and alerts were retained.")
            else:
                with transaction(conn):
                    device = args.device_id
                    scopes = set(args.scope or ["sensor:write"])
                    if args.command == "rotate":
                        old = conn.execute("SELECT device_id,scopes_json FROM cm_credentials WHERE key_id=?",(args.key_id,)).fetchone()
                        if old is None: raise ValueError("Credential ID was not found")
                        if device is not None and device != old["device_id"]: raise ValueError("Rotation must retain the device binding")
                        device = old["device_id"]
                        scopes = set(args.scope) if args.scope is not None else set(json.loads(old["scopes_json"]))
                        revoke(conn,args.key_id)
                    token,credential = issue(conn,label=args.label,device_id=device,patient_id=args.patient_id,
                                             scopes=scopes,days=args.days)
                # Intentional, one-time local issuance output. Never include this command's output in shared logs.
                print("Created credential " + credential.key_id + "; expires " + utc_timestamp(credential.expires_at))
                print("Copy this key directly into the phone once. It cannot be recovered:")
                print(token)
        return 0
    except (ValueError,OSError,sqlite3.Error):
        print("Administration failed. Check arguments, device mapping and database access.",file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
