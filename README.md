# CareMonitor 1.0 — demostrador académico local

Una única base de código para ESP32 → Bluetooth → Android → HTTPS → servidor local. Sin Firebase, servicios de nube ni APIs de mapas. Cuentas de adulto y cuidadores, vínculos aprobados por el adulto, avisos privados, recordatorios y experimentos de IA claramente separados de los diagnósticos del dispositivo.

**Para retomar el equipo preparado:** [arranque paso a paso sin internet](docs/PUESTA_EN_MARCHA.md). La nueva **Network** está preparada para conexión manual; la computadora conserva su red con internet hasta que decidas cambiarla. Al hacer la prueba, conecta ambos equipos a Network y utiliza la IP que imprima el arranque. No necesita descargas. El enlace USB queda como alternativa; el arranque no cambia Wi-Fi ni VPN. **Instalación general:** [guía de servidor y teléfono](docs/SERVER.md). **Límites y consentimiento:** [seguridad y ética](docs/SECURITY_ETHICS.md). **Contrato:** [API](docs/API.md).

```bash
./tools/start_offline.sh --check --serial R5CT20D7X6D
# Cuando laptop y S22 ya estén en Network; USB conectado para preparar el APK:
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

La revisión 1.0.0/código **20**, instalada en el S22 SM-S901E / Android 13, organiza la interfaz en inglés alrededor de las tareas de cada rol. **Today** ofrece a la persona mayor una conexión comprensible, pedir ayuda, su próximo recordatorio y sus indicadores; al cuidador le muestra la persona seleccionada, avisos por revisar, lecturas y ubicación compartida si está autorizada. **Updates**, **Care** y **Settings** mantienen nombres y posiciones estables. La tarjeta técnica Recent readings se sustituye por **Settings → Reading details**; las cinco métricas siguen abriendo históricos **Day / Week / Month**.

Los controles grandes, el texto ampliable, las actualizaciones sin reconstruir la pantalla y las animaciones breves de conexión apoyan esa jerarquía. **Settings → Reduce motion** desactiva el movimiento. Las transiciones no inventan ni suavizan valores de sensores. Es un diseño orientado a personas mayores y cuidadores; no se presenta como un estudio de usabilidad con adultos mayores.

El APK, su instalación y el alcance comprobado se identifican por versión y SHA-256 en [la evidencia vigente](docs/STATUS.md). Las pruebas físicas del **27 de octubre, código 17**, acreditan autenticación Bluetooth con una clave privada por placa, una sola conexión activa y recepción confirmada por el servidor, con la cola en cero. Esos registros fechados no se atribuyen a una revisión posterior de la interfaz. El canal de avisos con pantalla bloqueada, TalkBack manual y el acceso exterior mantienen su propia comprobación. Settings → Learning insights conserva los ejercicios sintéticos separados del cuidado y de los sensores.

La IA se evalúa con datos sintéticos o reproducciones del dataset documentado; no diagnostica personas. No hay sensor de presión arterial ni detector validado de deshidratación. El GY906 mide superficie y ambiente, y oscuridad no prueba que el wearable esté puesto. Ver [matriz de capacidades y ética](docs/SECURITY_ETHICS.md).

La revisión del 30 de octubre integra los diagramas de arquitectura, conexiones, clases, permisos, protocolos y seguridad en [el informe](docs/report/README.md). La evidencia actual distingue la app principal instalada, los controles aislados de interfaz y las pruebas físicas del mismo código en cuentas controladas; véase [arquitectura actual](docs/ARCHITECTURE.md).
