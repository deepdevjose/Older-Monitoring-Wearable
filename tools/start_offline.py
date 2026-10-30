#!/usr/bin/env python3
"""Start the prepared deployment over USB or an already joined local Wi-Fi network."""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.local/offline'
STATE = ROOT / '.local/runtime'
PACKAGE = 'org.caremonitor.demo'


def run(command, *, check=True):
    result = subprocess.run([str(item) for item in command], cwd=ROOT,
                            capture_output=True, text=True)
    if check and result.returncode:
        # Commands used here never contain the Wi-Fi password or account tokens.
        raise RuntimeError(f"Falló {Path(str(command[0])).name}: "
                           + (result.stderr or result.stdout)[-1500:].strip())
    return result


def interface_network(interfaces, name):
    matches = [item for item in interfaces if item.get('ifname') == name]
    if len(matches) != 1:
        raise ValueError(f'No se encontró la interfaz Wi-Fi {name}.')
    addresses = [item for item in matches[0].get('addr_info', [])
                 if item.get('family') == 'inet' and item.get('scope') == 'global']
    if len(addresses) != 1:
        raise ValueError('La Wi-Fi debe tener una sola dirección IPv4 asignada por DHCP.')
    network = ipaddress.ip_interface(f"{addresses[0]['local']}/{addresses[0]['prefixlen']}")
    if (not network.ip.is_private or network.ip.is_loopback or network.ip.is_link_local
            or network.ip.is_multicast or network.ip.is_unspecified or network.ip.is_reserved
            or not network.network.is_private or network.network.is_unspecified):
        raise ValueError('La dirección Wi-Fi no pertenece a una LAN privada utilizable.')
    return network


def read_private_config(path, *, usb=False):
    import stat
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid()
            or metadata.st_mode & 0o077 or metadata.st_nlink != 1):
        raise ValueError('La configuración de red debe ser un archivo privado propio con permisos 0600.')
    config = json.loads(path.read_text())
    if not isinstance(config, dict):
        raise ValueError('La configuración de red debe ser un objeto JSON.')
    for field in (('phone_serial',) if usb else ('ssid', 'interface', 'phone_serial')):
        value = config.get(field)
        if not isinstance(value, str) or not value or value.startswith('-') or any(ord(c) < 32 for c in value):
            raise ValueError(f'Configuración de red no válida: {field}.')
    if not usb and not re.fullmatch(r'[A-Za-z0-9_.:-]{1,64}', config['interface']):
        raise ValueError('Nombre de interfaz no válido.')
    port = config.get('server_port')
    if not usb and (isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535):
        raise ValueError('El puerto debe estar entre 1024 y 65535.')
    return config


def carectl(*arguments):
    return run([ROOT / 'carectl', '--data-dir', STATE, *arguments])


def replace_default(source, endpoint):
    # Only deployment configuration changes; never user preferences or encrypted data.
    pattern = r'(static final String DEFAULT_BASE=)"[^"]+";'
    replacement, count = re.subn(pattern, lambda match: match[1] + json.dumps(endpoint) + ';', source)
    if count != 1:
        raise ValueError('No se pudo localizar de forma única DEFAULT_BASE en ApiSession.java.')
    return replacement


def private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix('.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(value, output, indent=2); output.write('\n')
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def adb_serial(value):
    if (not isinstance(value, str) or value.startswith('-')
            or not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', value)):
        raise argparse.ArgumentTypeError(
            'El serial ADB debe tener entre 1 y 128 caracteres, sin espacios ni un guion inicial.')
    return value


def preflight(config, *, usb=False):
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk'))
    required = [ROOT / '.venv/bin/python', ROOT / '.local/dev-signing.jks',
                sdk / 'platform-tools/adb', sdk / 'platforms/android-37.0/android.jar',
                sdk / 'build-tools/36.0.0/aapt2', sdk / 'build-tools/36.0.0/zipalign',
                sdk / 'build-tools/36.0.0/lib/d8.jar', sdk / 'build-tools/36.0.0/lib/apksigner.jar',
                Path('/usr/bin/java'), Path('/usr/bin/javac')]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError('Faltan herramientas locales: ' + ', '.join(missing))
    for executable in (() if usb else ('nmcli', 'ip')):
        if not shutil.which(executable):
            raise ValueError(f'Falta la herramienta local {executable}.')
    public_ca = ROOT / 'mobile/app/src/main/assets/server_ca.crt'
    runtime_ca = STATE / 'tls/ca.crt'
    if public_ca.read_bytes().strip() != runtime_ca.read_bytes().strip():
        raise ValueError('La CA del APK y del servidor difieren; conservar la confianza existente antes de continuar.')
    doctor = json.loads(carectl('doctor').stdout)
    for key in ('fastapi', 'uvicorn', 'argon2', 'cryptography', 'sklearn', 'serial',
                'state_initialized', 'private_state'):
        if doctor.get(key) is not True:
            raise ValueError(f'La comprobación local falló: {key}. Ejecuta ./carectl doctor.')
    from cryptography import x509
    from datetime import datetime, timezone
    ca = x509.load_pem_x509_certificate(runtime_ca.read_bytes())
    now = datetime.now(timezone.utc)
    if not ca.not_valid_before_utc <= now < ca.not_valid_after_utc:
        raise ValueError('La CA no está vigente; requiere renovar la confianza antes del arranque.')
    if not doctor.get('tls_valid'):
        print('El certificado del servidor requiere renovación; prepare-network lo renovará bajo la misma CA.')
    print('Dependencias, firma existente, CA y estado privado: preparados sin descargas.', flush=True)
    if usb:
        print('Modo USB preparado: no requiere una Wi-Fi concreta ni cambios del firewall.', flush=True)
    else:
        print(f"Red preparada: {config['ssid']}; interfaz: {config['interface']}.", flush=True)
    return sdk


def allow_local_firewall(interface, network, port):
    if not shutil.which('firewall-cmd'):
        print('No hay firewalld; comprueba la política del firewall local si el teléfono no conecta.')
        return 'unmanaged'
    if run(['firewall-cmd', '--state'], check=False).returncode:
        print('firewalld no está activo; no se cambió el firewall.')
        return 'inactive'
    zone = run(['firewall-cmd', '--get-zone-of-interface', interface], check=False).stdout.strip()
    if not zone or zone == 'no zone':
        zone = run(['firewall-cmd', '--get-default-zone']).stdout.strip()
    existing = run(['firewall-cmd', '--zone=' + zone, '--list-ports'], check=False)
    if existing.returncode == 0:
        for entry in existing.stdout.split():
            match = re.fullmatch(r'(\d+)(?:-(\d+))?/tcp', entry)
            if match:
                first = int(match[1]); last = int(match[2] or match[1])
                if 1 <= first <= port <= last <= 65535:
                    print(f'Firewall: la zona {zone} ya permite TCP {port}; no se cambió su política existente.')
                    return 'already-allowed'
    rule = f'rule family="ipv4" source address="{network}" port port="{port}" protocol="tcp" accept'
    command = ['sudo', '-n', 'firewall-cmd', '--zone=' + zone,
               '--add-rich-rule=' + rule, '--timeout=8h']
    result = run(command, check=False)
    if result.returncode:
        print('Falta autorizar el puerto LAN en el firewall. Ejecuta en esta terminal:')
        import shlex
        print(shlex.join([item for item in command if item != '-n']))
        return 'pending'
    else:
        print(f'Firewall: TCP {port} permitido solo desde {network}, durante 8 horas.')
        return 'allowed'


def prepare_mobile(sdk, endpoint, serial):
    import hashlib
    source = ROOT / 'mobile/app/src/main/java/org/caremonitor/demo/ApiSession.java'
    previous = source.read_text()
    updated = replace_default(previous, endpoint)
    if updated != previous:
        print('La IP cambió; recompilando la dirección inicial del APK con herramientas locales…', flush=True)
        source.write_text(updated)
    actual_inputs = {path.relative_to(ROOT / 'mobile').as_posix()
                     for path in (ROOT / 'mobile/app/src/main').rglob('*') if path.is_file()}
    actual_inputs.add('scripts/build_apk.py')

    def verified_apk(evidence, directory):
        if not evidence.is_file(): return None
        metadata = json.loads(evidence.read_text())
        inputs = metadata.get('input_sha256', {})
        if set(inputs) != actual_inputs: return None
        if not all(hashlib.sha256((ROOT / 'mobile' / name).read_bytes()).hexdigest() == digest
                   for name, digest in inputs.items()): return None
        filename = metadata.get('apk', '')
        if not filename or Path(filename).name != filename: return None
        candidate = directory / filename
        if not candidate.is_file() or hashlib.sha256(candidate.read_bytes()).hexdigest() != metadata.get('sha256'):
            return None
        return candidate

    try:
        apk = (verified_apk(ROOT / 'mobile/artifacts/BUILD_EVIDENCE.json', ROOT / 'mobile/artifacts')
               or verified_apk(ROOT / 'release/ANDROID_BUILD.json', ROOT / 'release'))
        if apk is None:
            print('Comprobando y compilando el APK de estas fuentes sin descargas…', flush=True)
            result = run([sys.executable, ROOT / 'mobile/scripts/build_apk.py', '--sdk', sdk,
                          '--jdk', '/usr', '--keystore', ROOT / '.local/dev-signing.jks'])
            (PRIVATE / 'android-build.log').write_text(result.stdout)
            (PRIVATE / 'android-build.log').chmod(0o600)
            apk = verified_apk(ROOT / 'mobile/artifacts/BUILD_EVIDENCE.json', ROOT / 'mobile/artifacts')
            if apk is None: raise ValueError('La compilación no produjo un APK verificado para las fuentes actuales.')
    except BaseException:
        if updated != previous: source.write_text(previous)
        raise
    adb = sdk / 'platform-tools/adb'
    state = run([adb, '-s', serial, 'get-state'], check=False)
    if state.returncode or state.stdout.strip() != 'device':
        raise ValueError('Conecta y autoriza el Samsung por USB y repite el comando; aún no se ha arrancado el servidor.')
    package_path = run([adb, '-s', serial, 'shell', 'pm', 'path', PACKAGE], check=False).stdout.strip()
    identical = False
    if package_path.startswith('package:') and '\n' not in package_path:
        remote_hash = run([adb, '-s', serial, 'shell', 'sha256sum', package_path.removeprefix('package:')], check=False)
        fields = remote_hash.stdout.split()
        identical = remote_hash.returncode == 0 and bool(fields) and fields[0] == hashlib.sha256(apk.read_bytes()).hexdigest()
    if identical:
        print('El Samsung ya tiene este APK; no se reinstaló ni se interrumpió la app.')
    else:
        installed = run([adb, '-s', serial, 'install', '-r', apk])
        if 'Success' not in installed.stdout:
            raise ValueError('Android no confirmó la actualización. No se desinstaló ni borró la app.')
        print('APK actualizado por USB conservando datos locales. No se crearon cuentas ni se cambió tu sesión.')
    print('Si ya guardaste otra conexión: Settings → Server connection → ' + endpoint)
    print('Si no puedes iniciar sesión con una conexión antigua, pulsa «Update my connection» en el acceso.')
    return str(apk.relative_to(ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Comprobar preparación sin cambiar red, servidor ni APK')
    parser.add_argument('--usb', action='store_true', help='Usar HTTPS por el cable USB autorizado; no requiere acceso entre clientes Wi-Fi')
    parser.add_argument('--serial', type=adb_serial,
                        help='Usar este serial ADB solo para este arranque; conserva el teléfono de la configuración privada')
    args = parser.parse_args()
    os.umask(0o077)
    config = read_private_config(PRIVATE / 'network.json', usb=args.usb)
    if args.serial is not None:
        config = {**config, 'phone_serial': args.serial}
    sdk = preflight(config, usb=True) if args.usb else preflight(config)
    if args.check:
        if args.usb:
            print('Preparación USB comprobada sin cambios. Conserva el Samsung conectado y autorizado; ejecuta ./tools/start_offline.sh --usb')
        else:
            print(f"Preparación terminada sin cambiar tu red. Cuando decidas usar Wi-Fi, conecta la computadora a {config['ssid']} y ejecuta ./tools/start_offline.sh")
        return
    if args.usb:
        endpoint = 'https://127.0.0.1:19443/api/v1'
        apk = prepare_mobile(sdk, endpoint, config['phone_serial'])
        carectl('stop')
        print(carectl('prepare-network', '--host', '127.0.0.1', '--port', 19443).stdout, end='', flush=True)
        print(carectl('start').stdout, end='', flush=True)
        run([sdk / 'platform-tools/adb', '-s', config['phone_serial'], 'reverse', 'tcp:19443', 'tcp:19443'])
        print(carectl('status').stdout, end='', flush=True)
        private_json(PRIVATE / 'last-start.json', {'mode': 'usb', 'endpoint': endpoint,
            'apk': apk, 'firewall': 'not-needed-loopback',
            'scope': 'Loopback HTTPS and USB reverse prepared; USB required; Wi-Fi delivery not verified'})
        print('\nHTTPS por USB preparado. Mantén el Samsung conectado por el cable autorizado.')
        print('No requiere cambiar de Wi-Fi. Este modo no acredita conexión entre clientes del hotspot.')
        print('Si conservabas otra conexión, pulsa «Update my connection» antes de iniciar sesión.')
        return
    interface, ssid = config['interface'], config['ssid']
    connection = run(['nmcli', '-g', 'GENERAL.CONNECTION', 'device', 'show', interface]).stdout.strip()
    active_ssid = run(['nmcli', '-g', '802-11-wireless.ssid', 'connection', 'show', connection]).stdout.strip()
    if ssid != active_ssid:
        raise ValueError(f'Primero conecta la computadora a {ssid}. No se cambió la red ni la VPN.')
    network = interface_network(json.loads(run(['ip', '-j', '-4', 'address', 'show', 'dev', interface]).stdout), interface)
    endpoint = f'https://{network.ip}:{config["server_port"]}/api/v1'
    print(f'Servidor para esta LAN: {endpoint}', flush=True)
    # Prepare/verify the phone artifact first, before stopping an existing backend.
    apk = prepare_mobile(sdk, endpoint, config['phone_serial'])
    carectl('stop')
    print(carectl('prepare-network', '--host', network.ip,
                  '--port', config['server_port']).stdout, end='', flush=True)
    firewall_ready = allow_local_firewall(interface, network.network, config['server_port'])
    print(carectl('start').stdout, end='', flush=True)
    print(carectl('status').stdout, end='', flush=True)
    private_json(PRIVATE / 'last-start.json', {'endpoint': endpoint, 'interface': interface,
                 'ssid': ssid, 'apk': apk, 'firewall': firewall_ready,
                 'scope': 'PC HTTPS ready; verify Bluetooth and delivery in the phone'})
    if firewall_ready == 'pending':
        print('\nServidor HTTPS local iniciado; falta completar la autorización del firewall indicada arriba.')
        return 1
    print(f"\nServidor HTTPS local listo para {ssid}. Abre CareMonitor en el Samsung que proporciona o comparte esa LAN.")
    print('Conserva la Wi-Fi aunque indique «Sin internet». Si una VPN bloquea la LAN, pausa esa VPN manualmente.')
    print('La recepción Bluetooth y la entrega al servidor se comprueban por separado en la app.')


if __name__ == '__main__':
    try:
        sys.exit(main() or 0)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        print(f'No se completó el arranque: {failure}', file=sys.stderr)
        sys.exit(1)
