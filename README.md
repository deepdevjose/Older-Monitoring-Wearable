# CareMonitor 1.0 — demostrador académico local

Una única base de código para ESP32 → Bluetooth → Android → HTTPS → servidor local. Sin Firebase, servicios de nube ni APIs de mapas. Cuentas de adulto y cuidadores, vínculos aprobados por el adulto, avisos privados, recordatorios y experimentos de IA claramente separados de los diagnósticos del dispositivo.

**Para retomar el equipo preparado:** [arranque paso a paso](docs/PUESTA_EN_MARCHA.md). El S22 usa el servidor por USB, conservando Wi-Fi y VPN de la computadora. Mantén el cable conectado. El modo LAN es una alternativa manual; el arranque no cambia de red. **Instalación general:** [servidor y teléfono](docs/SERVER.md). **Contrato:** [API](docs/API.md).

```bash
./tools/start_offline.sh --usb --serial R5CT20D7X6D
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

La revisión 1.0.0/código **21**, instalada en el S22 SM-S901E / Android 13, organiza la interfaz en inglés alrededor de las tareas de cada rol. **Today** prioriza pedir ayuda y lecturas antes del plan; al cuidador le muestra la persona seleccionada, avisos por revisar, lecturas y ubicación compartida si está autorizada. Las tarjetas de ancho completo usan valores grandes, unidades discretas y la fecha de la lectura. **Updates**, **Care** y **Settings** mantienen nombres y posiciones estables. Los textos técnicos y límites están en **Settings → Reading details / About CareMonitor**. Las cinco métricas abren **Day / Week / Month**, con fecha elegible y acceso al último día guardado cuando el periodo está vacío.

Los controles grandes, el texto ampliable, las actualizaciones sin reconstruir la pantalla y las animaciones breves de conexión apoyan esa jerarquía. **Settings → Reduce motion** desactiva el movimiento. Las transiciones no inventan ni suavizan valores de sensores. Es un diseño orientado a personas mayores y cuidadores; no se presenta como un estudio de usabilidad con adultos mayores.

El APK, su instalación y el alcance comprobado se identifican por versión y SHA-256 en [la evidencia vigente](docs/STATUS.md). Las pruebas físicas del **27 de octubre, código 17**, acreditan autenticación Bluetooth con una clave privada por placa, una sola conexión activa y recepción confirmada por el servidor, con la cola en cero. Esos registros fechados no se atribuyen a una revisión posterior de la interfaz. El canal de avisos con pantalla bloqueada, TalkBack manual y el acceso exterior mantienen su propia comprobación. Settings → Learning insights conserva los ejercicios sintéticos separados del cuidado y de los sensores.

La IA se evalúa con datos sintéticos o reproducciones del dataset documentado; no diagnostica personas. No hay sensor de presión arterial ni detector validado de deshidratación. El GY906 mide superficie y ambiente, y oscuridad no prueba que el wearable esté puesto. Ver [matriz de capacidades y ética](docs/SECURITY_ETHICS.md).

La revisión del 30 de octubre integra los diagramas de arquitectura, conexiones, clases, permisos, protocolos y seguridad en [el informe](docs/report/README.md). La evidencia actual distingue la app principal instalada, los controles aislados de interfaz y las pruebas físicas del mismo código en cuentas controladas; véase [arquitectura actual](docs/ARCHITECTURE.md).
