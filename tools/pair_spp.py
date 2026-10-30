#!/usr/bin/env python3
"""Observe Bluetooth status without sending bytes or retaining sensor readings.

Pairing/authorization now happens on the phone. This optional serial diagnostic
never confirms a pairing, displays a setup credential, or resets/erases the board.
"""
import argparse
import sys
import time

DEFAULT_PORT = '/dev/serial/by-id/usb-1a86_USB_Serial-if00-port0'
MAX_BUFFER = 4096
STATUSES = {
    'Bluetooth phone connected; waiting for app authentication.': 'Teléfono conectado; autentica el wearable desde la app.',
    'Bluetooth app authentication expired; connection closed.': 'Autenticación agotada; conexión cerrada.',
    'Bluetooth app authentication rejected; connection closed.': 'Autenticación rechazada; conexión cerrada.',
    'Bluetooth app authenticated; exclusive sensor stream enabled.': 'App autenticada; un solo teléfono recibe los datos.',
}


class BluetoothStatusParser:
    def __init__(self):
        self.buffer = bytearray()
        self.discard = False

    def feed(self, chunk):
        output = []
        for byte in chunk:
            if byte == 10:
                if not self.discard:
                    line = self.buffer.rstrip(b'\r').decode('ascii', errors='replace')
                    if line in STATUSES:
                        output.append(STATUSES[line])
                self.buffer.clear()
                self.discard = False
            elif not self.discard:
                self.buffer.append(byte)
                if len(self.buffer) > MAX_BUFFER:
                    self.buffer.clear()
                    self.discard = True
        return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', default=DEFAULT_PORT)
    parser.add_argument('--seconds', type=float, default=120)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 600:
        parser.error('--seconds debe estar entre 1 y 600')
    import serial
    stream = serial.Serial(port=None, baudrate=115200, timeout=.1, exclusive=True)
    stream.dtr = False
    stream.rts = False
    stream.port = args.port
    try:
        stream.open()
        print('No necesitas escribir Y. Autoriza el wearable desde CareMonitor. Solo se muestran estados.', flush=True)
        statuses = BluetoothStatusParser()
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            for status in statuses.feed(stream.read(min(max(stream.in_waiting, 1), 4096))):
                print(status, flush=True)
    except KeyboardInterrupt:
        return 130
    except OSError:
        print('No se pudo abrir el puerto de la ESP32; cierra otros monitores seriales.', file=sys.stderr)
        return 2
    finally:
        stream.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
