# CareMonitor 1.0.0

Demostrador académico local de monitorización: **ESP32 → Bluetooth → Android → HTTPS → servidor local**. El repositorio mantiene una sola fuente activa para firmware, aplicación y servidor. No depende de Firebase, servicios de nube ni APIs de mapas.

> **Estado actual — 31 de octubre de 2026:** aplicación 1.0.0 / código 22 instalada y verificada en un Samsung Galaxy S22 (SM-S901E, Android 13). La ruta activa de esta computadora utiliza HTTPS por USB; conserva Wi-Fi y VPN. El alcance y la evidencia por versión se detallan en [Estado actual](docs/STATUS.md).

## Empezar

Para continuar en el equipo ya preparado, conecta y autoriza el S22 por USB y ejecuta desde la raíz:

```bash
./tools/start_offline.sh --usb --serial R5CT20D7X6D
./carectl status
```

El servidor debe indicar `"reachable": true`. Esta ruta usa `https://127.0.0.1:19443/api/v1`; mantén el cable conectado mientras la utilizas. La alternativa Wi-Fi y los pasos para instalar, crear cuentas, conectar el wearable y vincular cuidadores están en [Puesta en marcha](docs/PUESTA_EN_MARCHA.md).

Para una instalación nueva se requiere **Python 3.12 o posterior**:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
./carectl init --host IP_LAN_DEL_SERVIDOR --port 8443
./carectl doctor
./carectl start
./carectl status
```

No hay cuentas ni contraseñas predeterminadas. Configura la dirección HTTPS correspondiente en la app; la guía de [servidor y teléfonos](docs/SERVER.md) explica TLS, instalación, red y operación. No ejecutes `carectl init` sobre un servidor ya inicializado para intentar cambiar su configuración.

## Qué incluye

| Ruta | Responsabilidad |
|---|---|
| [`server/caremonitor/`](server/caremonitor/) | API FastAPI, autenticación, autorización, cifrado, avisos, CLI y modelos educativos. |
| [`mobile/`](mobile/) | Única aplicación Android nativa Java; código 22, Android mínimo API 24 y objetivo API 37. |
| [`firmware/esp32/`](firmware/esp32/) | Firmware canónico editable para ESP32. Las claves y credenciales privadas no se distribuyen. |
| [`datasets/`](datasets/) | Datos sintéticos y distribución documentada de SisFall. |
| [`models/`](models/) | Modelos generados localmente, métricas, procedencia y hashes. |
| [`tools/`](tools/) | Arranque local, diagnóstico, preparación de datos, entrenamiento y empaquetado. |
| [`tests/`](tests/) | Pruebas de API, CLI, Bluetooth, historial, aprendizaje y modelos. |
| [`docs/`](docs/) | Guías de operación, arquitectura, firmware, API, estado y seguridad/ética. |
| [`release/`](release/) | APK, paquete de fuentes y evidencia de entregas identificadas por versión. No es una segunda fuente editable. |
| `.local/` | Estado privado ignorado por Git: claves, base de datos, historial y diagnósticos. **No distribuir.** |

## Recorrido y funciones

1. Los sensores conectados a la ESP32 transmiten paquetes JSON por **Bluetooth Classic SPP/RFCOMM**; este firmware no anuncia BLE.
2. La app Android autentica el wearable, valida las lecturas y puede conservar una cola cifrada para reintentar el envío.
3. La app entrega datos mediante HTTPS al servidor local. El servidor valida identidad, rol, vínculo y consentimiento, y almacena los registros en SQLite.
4. Una persona cuidadora solicita un vínculo y la persona adulta lo aprueba. Las notificaciones se obtienen del servidor local mediante HTTPS; no se usa un proveedor push externo.

La app organiza las tareas en **Today**, **Updates**, **Care** y **Settings**. Cada rol ve sus funciones autorizadas; el cuidador solo consulta a una persona con vínculo aprobado. Las métricas disponen de históricos Day / Week / Month. Las colas se mantienen separadas por cuenta y servidor.

Sensores del montaje actual:

- **MAX30102:** estimaciones de pulso y oxígeno con indicadores de contacto/calidad.
- **GY906 / MLX90614:** temperatura de superficie/objeto y ambiente local del sensor.
- **MPU6050:** aceleración y giro.

La app y los controles priorizan texto ampliable, acciones grandes, estados explícitos y una opción **Reduce motion**. Son decisiones de diseño comprobadas con perfiles de prueba; no constituyen un estudio de usabilidad con personas mayores ni una evaluación manual completa de accesibilidad.

## Seguridad, privacidad y límites

El servidor y la app están diseñados para operación local: HTTPS con verificación de certificado, contraseñas con Argon2id, autorización por rol y vínculo, cifrado de campos sensibles en el servidor y almacenamiento cifrado en el teléfono. Las funciones de ubicación y micrófono son opcionales y requieren consentimiento y permisos; no se usan mapas ni servicios externos.

CareMonitor es un **demostrador académico, no un producto médico ni un servicio de emergencias**. Las estimaciones de sensores no tienen validación clínica. El sistema no mide presión arterial ni detecta deshidratación, y el firmware no implementa un detector de caídas validado. Los ejercicios de IA usan datos sintéticos y se mantienen separados de las lecturas físicas. La ausencia de avisos no demuestra que la persona esté bien. Consulta [Seguridad, privacidad y límites éticos](docs/SECURITY_ETHICS.md) antes de usarlo.

Protege `.local/`, las claves de wearable, credenciales, base de datos y copias de seguridad. No abras el servidor a Internet ni publiques claves o datos personales.

## Validación y entregas

El estado documentado para el 31 de octubre incluye **248 pruebas Python**, **3.381 comprobaciones JVM**, **24 controles HTTPS aislados** y **7.198 controles de interfaz**, con 48 capturas revisadas a escala normal y ampliada. Estas campañas tienen alcances distintos: las pruebas aisladas de interfaz/servidor no equivalen a una prueba física del recorrido completo.

El APK actual es [`release/CareMonitor-1.0.0.apk`](release/CareMonitor-1.0.0.apk). La evidencia y los hashes vigentes están en [`docs/STATUS.md`](docs/STATUS.md) y los manifiestos de [`release/`](release/). Las pruebas físicas de otras versiones se conservan con sus fechas y no deben atribuirse automáticamente al código 22.

## Documentación

- [Puesta en marcha](docs/PUESTA_EN_MARCHA.md): USB, Wi-Fi, app, cuentas, Bluetooth y comprobación del recorrido.
- [Servidor y teléfonos](docs/SERVER.md): instalación limpia, TLS, operación local y conexión Android.
- [Arquitectura y conexiones](docs/ARCHITECTURE.md): componentes, protocolos, sensores y límites de las comprobaciones.
- [Contrato de la API](docs/API.md)
- [Guía del firmware](docs/FIRMWARE.md)
- [Estado y evidencia por versión](docs/STATUS.md)
- [Seguridad, privacidad y ética](docs/SECURITY_ETHICS.md)
- [Informe y diagramas](docs/report/README.md)
