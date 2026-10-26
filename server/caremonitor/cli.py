"""Local server administration: verified TLS, private state, encrypted backups."""
from __future__ import annotations
import argparse
from contextlib import closing
from datetime import datetime, timedelta, timezone
import getpass
import importlib.util
import io
import ipaddress
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import time
import uuid
import zipfile
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives.asymmetric import ec
from .config import Settings

ROOT = Path(__file__).resolve().parents[2]
BACKUP_MAGIC = b"CAREMONITOR-BACKUP-1\n"


def private_write(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data); stream.flush(); os.fsync(stream.fileno())


def tls_paths(settings):
    directory = settings.data_dir / "tls"
    return directory / "ca.crt", directory / "server.crt", directory / "server.key"


def init_tls(settings: Settings, host: str):
    ca_path, cert_path, key_path = tls_paths(settings)
    address = ipaddress.ip_address(host)
    if any(p.exists() for p in (ca_path, cert_path, key_path)):
        if not all(p.exists() for p in (ca_path, cert_path, key_path)):
            raise ValueError("Incomplete TLS state. Restore it before continuing.")
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        sans = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        if address not in sans.get_values_for_type(x509.IPAddress):
            raise ValueError("Host changed. Create new TLS state and rebuild the phone trust configuration.")
        return
    now = datetime.now(timezone.utc)
    authority_key = ec.generate_private_key(ec.SECP256R1())
    authority_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "CareMonitor educational local CA")])
    authority = (x509.CertificateBuilder().subject_name(authority_name).issuer_name(authority_name)
        .public_key(authority_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(authority_key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(authority_key.public_key()), critical=False)
        .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
        .sign(authority_key, hashes.SHA256()))
    server_key = ec.generate_private_key(ec.SECP256R1())
    server_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, str(address))])
    addresses = list(dict.fromkeys([address, ipaddress.ip_address("127.0.0.1")]))
    server = (x509.CertificateBuilder().subject_name(server_name).issuer_name(authority_name)
        .public_key(server_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=90))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(server_key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(authority_key.public_key()), critical=False)
        .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), critical=True)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.SubjectAlternativeName([*(x509.IPAddress(a) for a in addresses),x509.DNSName("caremonitor.local")]), critical=False)
        .sign(authority_key, hashes.SHA256()))
    key_format = dict(encoding=serialization.Encoding.PEM,format=serialization.PrivateFormat.PKCS8,encryption_algorithm=serialization.NoEncryption())
    private_write(ca_path.parent / "ca.key", authority_key.private_bytes(**key_format))
    private_write(ca_path, authority.public_bytes(serialization.Encoding.PEM))
    private_write(key_path, server_key.private_bytes(**key_format))
    private_write(cert_path, server.public_bytes(serialization.Encoding.PEM)+authority.public_bytes(serialization.Encoding.PEM))


def read_config(settings):
    path = settings.data_dir / "server.json"
    if not path.exists(): raise ValueError("Run ./carectl init --host YOUR_LAN_IP first.")
    return json.loads(path.read_text())


def initialize(args, settings):
    from .db import Database
    if not 1024 <= args.port <= 65535: raise ValueError("Choose a port between 1024 and 65535")
    config_path = settings.data_dir / "server.json"
    if config_path.exists():
        previous = json.loads(config_path.read_text())
        if previous["host"] != args.host or previous["port"] != args.port:
            raise ValueError("Existing server configuration differs; init never silently changes address or port")
    settings.initialize_key(); init_tls(settings, args.host); Database(settings).initialize()
    if not config_path.exists():
        private_write(config_path,json.dumps({"host":args.host,"port":args.port,"mode":"educational"},indent=2).encode())
    print(f"Initialized: https://{args.host}:{args.port} (educational)")
    print(f"Private state: {settings.data_dir}")
    print(f"Phone trust certificate: {tls_paths(settings)[0]}")
    print("No default account exists. Register an elder and caregivers in the app.")


def serve(args, settings):
    import ssl
    import uvicorn
    from .api import create_app
    config = read_config(settings); settings.enforce_https = True; settings.expose_docs = args.docs
    ca, cert, key = tls_paths(settings); settings.load_key()
    if not all(p.is_file() for p in (ca,cert,key)): raise ValueError("TLS files are missing; run init.")
    if key.stat().st_mode & 0o077: raise ValueError("TLS key must have mode 0600")
    uvicorn.run(create_app(settings),host=args.bind or config["host"],port=args.port or config["port"],
        ssl_certfile=str(cert),ssl_keyfile=str(key),ssl_version=ssl.PROTOCOL_TLS_SERVER,
        access_log=False,proxy_headers=False,server_header=False,log_level="warning")


def running_pid(settings):
    path = settings.data_dir / "server.pid"
    if not path.exists(): return None
    try:
        pid = int(path.read_text()); process = Path(f"/proc/{pid}")
        cmd = (process/"cmdline").read_bytes().split(b"\0")
        if b"caremonitor.cli" not in cmd or b"serve" not in cmd or str(settings.data_dir).encode() not in cmd: return None
        if process.stat().st_uid != os.getuid(): return None
        return pid
    except (ValueError,OSError): return None


def start(args, settings):
    if running_pid(settings): print("Server already running."); return
    config = read_config(settings); log = settings.data_dir / "server.log"
    descriptor = os.open(log,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
    with os.fdopen(descriptor,"ab") as output:
        process = subprocess.Popen([sys.executable,"-m","caremonitor.cli","--data-dir",str(settings.data_dir),"serve"],
            stdin=subprocess.DEVNULL,stdout=output,stderr=output,start_new_session=True)
    path = settings.data_dir / "server.pid"; path.unlink(missing_ok=True); private_write(path,str(process.pid).encode())
    time.sleep(1)
    if process.poll() is not None: raise ValueError(f"Startup failed; inspect {log}")
    print(f"Started PID {process.pid}: https://{config['host']}:{config['port']}")


def stop(args, settings):
    pid = running_pid(settings)
    if pid is None: print("No managed server is running."); return
    os.kill(pid,signal.SIGTERM)
    for _ in range(50):
        if running_pid(settings) is None: break
        time.sleep(.1)
    else: raise ValueError("Still shutting down; no forced termination was sent.")
    (settings.data_dir / "server.pid").unlink(missing_ok=True); print("Server stopped gracefully.")


def status(args,settings):
    import ssl
    import httpx
    config = read_config(settings); context = ssl.create_default_context(cafile=str(tls_paths(settings)[0]))
    try:
        response=httpx.get(f"https://{config['host']}:{config['port']}/health",verify=context,timeout=5,trust_env=False)
        response.raise_for_status(); print(json.dumps({"reachable":True,"managed_pid":running_pid(settings),"health":response.json()},indent=2))
    except httpx.HTTPError as exc:
        print(json.dumps({"reachable":False,"managed_pid":running_pid(settings),"reason":type(exc).__name__})); return 1


def doctor(args, settings):
    results={name:importlib.util.find_spec(name) is not None for name in ["fastapi","uvicorn","argon2","cryptography","sklearn","serial"]}
    results["state_initialized"]=settings.db_path.exists() and settings.key_path.exists()
    results["private_state"]=settings.data_dir.exists() and not settings.data_dir.stat().st_mode & 0o077
    results["firmware_ports"]=[str(p) for p in Path('/dev/serial/by-id').glob('*')]
    results["mode"]="educational; no clinical claims"
    try:
        cert=x509.load_pem_x509_certificate(tls_paths(settings)[1].read_bytes())
        results["tls_expires"]=cert.not_valid_after_utc.isoformat()
        results["tls_valid"]=cert.not_valid_before_utc<=datetime.now(timezone.utc)<cert.not_valid_after_utc
    except (OSError,ValueError): results["tls_valid"]=False
    print(json.dumps(results,indent=2))


def backup(args,settings):
    import tempfile
    settings.load_key(); password=getpass.getpass("New backup passphrase (at least 15 characters): ")
    if len(password)<15 or password!=getpass.getpass("Repeat passphrase: "): raise ValueError("Passphrases must match and have at least 15 characters")
    output=Path(args.output).expanduser().resolve()
    if output.exists(): raise ValueError("Refusing to overwrite an existing backup")
    with tempfile.TemporaryDirectory() as directory:
        copy=Path(directory)/"snapshot.sqlite3"
        with closing(sqlite3.connect(settings.db_path)) as source, closing(sqlite3.connect(copy)) as target: source.backup(target)
        content=io.BytesIO()
        with zipfile.ZipFile(content,"w",zipfile.ZIP_DEFLATED) as z:
            z.writestr("caremonitor.sqlite3",copy.read_bytes()); z.writestr("encryption.key",settings.key_path.read_bytes())
            z.writestr("manifest.json",json.dumps({"format":1,"created_at":datetime.now(timezone.utc).isoformat()}))
        salt,nonce=os.urandom(16),os.urandom(12)
        key=Scrypt(salt=salt,length=32,n=2**17,r=8,p=1).derive(password.encode())
        private_write(output,BACKUP_MAGIC+salt+nonce+AESGCM(key).encrypt(nonce,content.getvalue(),BACKUP_MAGIC))
    print(f"Encrypted database/key backup: {output}. Store its passphrase separately; TLS keys excluded.")


def restore(args,settings):
    destination=Path(args.destination).expanduser().resolve()
    if destination.exists() and any(destination.iterdir()): raise ValueError("Destination must be empty; active state is never overwritten")
    content=Path(args.backup).expanduser().read_bytes()
    if not content.startswith(BACKUP_MAGIC) or len(content)>512*1024*1024: raise ValueError("Unsupported backup")
    position=len(BACKUP_MAGIC); salt=content[position:position+16]; nonce=content[position+16:position+28]
    key=Scrypt(salt=salt,length=32,n=2**17,r=8,p=1).derive(getpass.getpass("Backup passphrase: ").encode())
    plain=AESGCM(key).decrypt(nonce,content[position+28:],BACKUP_MAGIC)
    with zipfile.ZipFile(io.BytesIO(plain)) as z:
        if set(z.namelist())!={"manifest.json","caremonitor.sqlite3","encryption.key"}: raise ValueError("Unexpected backup members")
        if any(i.file_size>512*1024*1024 for i in z.infolist()): raise ValueError("Restore exceeds size limit")
        if len(z.read("encryption.key"))!=32: raise ValueError("Invalid encryption key")
        destination.mkdir(parents=True,exist_ok=True,mode=0o700); destination.chmod(0o700)
        for name in ("caremonitor.sqlite3","encryption.key"): private_write(destination/name,z.read(name))
    with closing(sqlite3.connect(destination/'caremonitor.sqlite3')) as conn:
        conn.execute("DELETE FROM sessions"); conn.commit()
        if conn.execute("PRAGMA integrity_check").fetchone()[0]!="ok": raise ValueError("Database integrity check failed")
    print(f"Restored {destination}. Run init for new TLS. All sessions invalidated.")


def synthetic_demo(args,settings):
    import ssl
    import httpx
    config=read_config(settings); context=ssl.create_default_context(cafile=str(tls_paths(settings)[0]))
    email=input("Elder account email: ").strip(); password=getpass.getpass("Password: ")
    with httpx.Client(base_url=f"https://{config['host']}:{config['port']}/api/v1",verify=context,timeout=10,trust_env=False) as client:
        auth=client.post("/auth/login",json={"email":email,"password":password}); auth.raise_for_status(); token=auth.json()["access_token"]
        try:
            for _ in range(3):
                payload={"device_id":"elder-demo-01","max30102":{"status":"ok","online":True,"finger_detected":True,"heart_rate_valid":True,"heart_rate_bpm":75,"spo2_valid":True,"spo2_percent":92},"gy906":{"online":True,"status":"ok","object_temp_c":33,"ambient_temp_c":22},"photoresistor":{"adc_raw":100}}
                response=client.post("/readings",headers={"Authorization":f"Bearer {token}"},json={"sample_id":str(uuid.uuid4()),"captured_at":datetime.now(timezone.utc).isoformat(),"source":"synthetic","payload":payload})
                response.raise_for_status(); result=response.json()
                print(json.dumps({"record_id":result["record_id"],"alert_created":result.get("alert_created"),"source":"synthetic"})); time.sleep(1.1)
        finally: client.post("/auth/logout",headers={"Authorization":f"Bearer {token}"})


def vpn_config(args,settings):
    """Generate private WireGuard material locally; never modify host networking."""
    import base64
    import re
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    if not re.fullmatch(r"[A-Za-z0-9.-]+:[0-9]{1,5}",args.endpoint):
        raise ValueError("Use an endpoint like your-public-ip:51820 (no URL or whitespace)")
    port=int(args.endpoint.rsplit(":",1)[1])
    if not 1<=port<=65535 or not 1<=args.peers<=20: raise ValueError("Invalid endpoint port or peer count")
    config=read_config(settings); host=ipaddress.ip_address(config["host"])
    if host.version!=4: raise ValueError("This WireGuard template currently requires an IPv4 LAN server")
    directory=settings.data_dir.parent/"wireguard"
    if directory.exists(): raise ValueError("WireGuard material exists; it will not be overwritten")
    directory.mkdir(mode=0o700)
    def pair():
        key=X25519PrivateKey.generate()
        return (base64.b64encode(key.private_bytes(serialization.Encoding.Raw,serialization.PrivateFormat.Raw,serialization.NoEncryption())).decode(),
                base64.b64encode(key.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)).decode())
    private,public=pair()
    server=f"[Interface]\nAddress = 10.77.0.1/24\nListenPort = {port}\nPrivateKey = {private}\n"
    for index in range(args.peers):
        peer_private,peer_public=pair(); address=f"10.77.0.{index+2}"
        server+=f"\n[Peer]\nPublicKey = {peer_public}\nAllowedIPs = {address}/32\n"
        phone=f"[Interface]\nAddress = {address}/32\nPrivateKey = {peer_private}\n\n[Peer]\nPublicKey = {public}\nEndpoint = {args.endpoint}\nAllowedIPs = {host}/32, 10.77.0.1/32\nPersistentKeepalive = 25\n"
        private_write(directory/f"phone-{index+1}.conf",phone.encode())
    private_write(directory/"wg-caremonitor.conf",server.encode())
    print(f"Generated private VPN configs in {directory}. Nothing was installed or activated.")
    print("Forward only the VPN UDP port on your router. Do not publish TCP 8443 to the Internet.")


def main(argv=None):
    parser=argparse.ArgumentParser(description="CareMonitor educational server administration")
    parser.add_argument("--data-dir",type=Path,default=Path(os.environ.get("CAREMONITOR_HOME",ROOT/".local/runtime")))
    commands=parser.add_subparsers(dest="command",required=True)
    command=commands.add_parser("init",help="Initialize private state and local TLS")
    command.add_argument("--host",required=True,help="Server LAN IP");command.add_argument("--port",type=int,default=8443);command.set_defaults(run=initialize)
    command=commands.add_parser("serve",help="Run foreground; Ctrl+C stops")
    command.add_argument("--bind");command.add_argument("--port",type=int);command.add_argument("--docs",action="store_true");command.set_defaults(run=serve)
    for name,func in [("start",start),("stop",stop),("status",status),("doctor",doctor),("demo",synthetic_demo)]: commands.add_parser(name).set_defaults(run=func)
    command=commands.add_parser("backup");command.add_argument("output");command.set_defaults(run=backup)
    command=commands.add_parser("restore");command.add_argument("backup");command.add_argument("--destination",required=True);command.set_defaults(run=restore)
    command=commands.add_parser("vpn-config",help="Generate local WireGuard server/phone configs; no network changes")
    command.add_argument("--endpoint",required=True);command.add_argument("--peers",type=int,default=2);command.set_defaults(run=vpn_config)
    command=commands.add_parser("firmware-check");command.add_argument("--port",default="/dev/ttyUSB0");command.add_argument("--seconds",type=int,default=20)
    command.set_defaults(run=lambda args,settings:subprocess.call([sys.executable,str(ROOT/'tools/check_firmware.py'),"--port",args.port,"--seconds",str(args.seconds)]))
    args=parser.parse_args(argv)
    try: return args.run(args,Settings(data_dir=args.data_dir)) or 0
    except KeyboardInterrupt: return 130
    except Exception as exc:
        if isinstance(exc,(ValueError,RuntimeError,FileNotFoundError)): print(f"Error: {exc}",file=sys.stderr)
        else: print(f"Operation failed ({type(exc).__name__}); credentials were not printed.",file=sys.stderr)
        return 1

if __name__=="__main__": raise SystemExit(main())
