# CareMonitor 1.0 — demostrador académico local

Una única base de código para ESP32 → Bluetooth → Android → HTTPS → servidor local. Sin Firebase, servicios de nube ni APIs de mapas. Cuentas de adulto y cuidadores, vínculos aprobados por el adulto, avisos privados, recordatorios y experimentos de IA claramente separados de los diagnósticos del dispositivo.

**Para retomar el equipo preparado:** [paso a paso y estado actual](docs/PUESTA_EN_MARCHA.md). El servidor ya funciona por Wi-Fi en **Network**, el módem Huawei del dormitorio, conservando internet en la laptop. El enlace USB queda como alternativa. **Instalación general:** [guía de servidor y teléfono](docs/SERVER.md). **Límites y consentimiento:** [seguridad y ética](docs/SECURITY_ETHICS.md). **Contrato:** [API](docs/API.md).

```bash
./tools/start_offline.sh --check --serial R5CT20D7X6D
# S22 conectado por USB para preparar; después usa Wi-Fi:
./tools/start_offline.sh --serial R5CT20D7X6D
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

La versión 1.0.0/código **16**, completamente en inglés, está instalada en el Galaxy S22 SM-S901E con Android 13. Pasó 759 comprobaciones nativas de registro y diseño y HTTPS por Wi-Fi con el cliente instalado. Otros 641 controles ejercitaron los históricos y Learning insights en una app aislada con las mismas fuentes, en el S22 con Android 13. Los seis indicadores abren gráficos Day/Week/Month para la persona mayor y sus cuidadores autorizados. Settings → Learning insights muestra clases y confianza del modelo sintético, con historial cifrado separado de los sensores.

El teléfono principal SM-S948B se actualizará al ejecutar el arranque con el teléfono conectado por USB cuando vuelva. El recorrido físico completo Bluetooth–teléfono–servidor requiere comprobación independiente. El canal propio de avisos necesita activarse en cada teléfono. Fuera de la LAN necesita la VPN propia descrita en la guía.

La IA se evalúa con datos sintéticos o reproducciones del dataset documentado; no diagnostica personas. No hay sensor de presión arterial ni detector validado de deshidratación. El GY906 mide superficie y ambiente, y oscuridad no prueba que el wearable esté puesto. Ver [matriz de capacidades y ética](docs/SECURITY_ETHICS.md).
