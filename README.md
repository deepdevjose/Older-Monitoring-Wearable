# CareMonitor 1.0 — demostrador académico local

Una única base de código para ESP32 → Bluetooth → Android → HTTPS → servidor local. Sin Firebase, servicios de nube ni APIs de mapas. Cuentas de adulto y cuidadores, vínculos aprobados por el adulto, avisos privados, recordatorios y experimentos de IA claramente separados de los diagnósticos del dispositivo.

**Empezar:** [guía de servidor y teléfono](docs/SERVER.md). **Límites y consentimiento:** [seguridad y ética](docs/SECURITY_ETHICS.md). **Contrato:** [API](docs/API.md).

```bash
./carectl doctor
./carectl start
./carectl status
```

Instalación nueva: `python3 -m venv .venv`, `.venv/bin/python -m pip install -r requirements.txt`, `./carectl init --host TU_IP_LAN`. No existen cuentas ni contraseñas predeterminadas.

## Fuente única

| Ruta | Responsabilidad |
|---|---|
| `server/caremonitor/` | API, autorización, cifrado, alertas, avisos, CLI y modelos educativos. |
| `mobile/` | Única app Android Java y compilador local. SDK 37; sin dependencias de Google Play Services. |
| `firmware/esp32/` | Único firmware editable. Credenciales fuera del código distribuible. |
| `datasets/` | CSV sintético y SisFall en un archivo comprimido, sin miles de archivos sueltos. |
| `models/` | Modelos generados localmente, métricas, procedencia y hashes. |
| `tools/` | Entrenamiento, diagnóstico serial y empaquetado. |
| `tests/` | Pruebas del contrato, seguridad, recuperación y modelos. |
| `docs/` | Guías actuales; laboratorios originales en un único ZIP de referencia. |
| `release/` | APK, paquete de fuentes y evidencia de la entrega actual. No es otra copia editable. |
| `.local/` | Estado privado ignorado: claves, base, historial anterior y diagnóstico. Nunca distribuir. |

Las carpetas de versiones anteriores se retiraron del árbol activo después de crear `.local/before-consolidation-20261025.tar.gz`. El snapshot de firmware defectuoso del 23 de octubre se conserva como evidencia histórica privada; no es el firmware canónico. Git mantiene también el historial. No se importó la antigua base de salud al servidor nuevo.

## Alcance

La versión está preparada para instalación y pruebas en el Samsung indicado, pero no se ha conectado ese teléfono. El canal de avisos propio requiere activarlo en cada dispositivo, conectividad al servidor y aceptación en Android real. Fuera de la LAN necesita la VPN propia descrita en la guía.

La IA se evalúa con datos sintéticos o reproducciones del dataset documentado; no diagnostica personas. No hay sensor de presión arterial ni detector validado de deshidratación. El GY906 mide superficie y ambiente, y oscuridad no prueba que el wearable esté puesto. Ver [matriz de capacidades y ética](docs/SECURITY_ETHICS.md).
