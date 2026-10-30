# Firmware y comprobación física

El único fuente vigente para trabajar es `firmware/esp32/esp32.ino`, consolidado desde `data_API/esp32.ino`. Se conserva el firmware existente: en esta consolidación únicamente se extrajeron las credenciales Wi-Fi a un archivo privado. No se modificó el algoritmo óptico, la adquisición, el formato JSON ni el emparejamiento. No se flasheó, borró NVS ni aceptó una vinculación Bluetooth.

La revisión defectuosa del 23 de octubre permanece como evidencia local en `.local/firmware-diagnostics-20261023/`; no es una segunda fuente para compilar. El dispositivo no publica una identidad de compilación verificable. Por ello **no está demostrada la equivalencia entre el binario instalado y este fuente**. El usuario informa que el firmware instalado funciona; la captura de esta sesión comprueba únicamente el estado observado.

## Contrato con Android

Bluetooth **Classic SPP**, nombre configurado `ElderSensors-elder-demo-01`, JSON UTF-8 terminado en salto de línea, aproximadamente un mensaje por segundo. No usa BLE GATT. El firmware requiere un ESP32 clásico con soporte SPP; ESP32-S3 y ESP32-C3 no sustituyen esa radio.

| Objeto | Datos y significado |
|---|---|
| Raíz | `device_id`, `uptime_ms`, `timestamp` opcional cuando NTP está disponible |
| `max30102` | `status`, `finger_detected`, `heart_rate_valid`, `spo2_valid`, `heart_rate_bpm`, `spo2_percent`, ventana óptica y valores crudos |
| `mpu6050` | `online`, `accel_g.{x,y,z}`, `gyro_dps.{x,y,z}` |
| `gy906` | `online`, `object_temp_c` de superficie/objeto, `ambient_temp_c` ambiental |
| `photoresistor` | ADC y porcentaje de escala del ADC; no equivale a certeza de llevar puesto el wearable |
| `microphone` | ADC del micrófono del ESP32, cuando está conectado; no es audio del teléfono |
| `buzzer` | Pin configurado; su presencia en el JSON no demuestra que se haya emitido una alarma |

Los valores ópticos no válidos se transmiten como `null`. Nunca deben convertirse a cero ni usarse para disparar una alerta de pulso o de oxígeno. Un sensor `online: false`, la falta de dedo o la ausencia de muestra reciente deben mostrarse como falta de señal.

La hora del firmware proviene de NTP y emplea la zona Asia/Shanghai del fuente original. El backend debe conservar su propia hora de recepción y no tratar la hora del sensor como prueba de frescura, orden o autenticidad. `uptime_ms` vuelve a empezar tras reinicio y eventualmente desborda; no es un identificador de muestra mundialmente único.

Wi-Fi se emplea para NTP; el canal de datos hacia el teléfono es SPP. La app entrega al backend un sobre autenticado y filtrado. Los valores crudos ópticos, el micrófono ADC y las trazas completas no deben aparecer en registros rutinarios del servidor.

## Conexiones del fuente

| Función | Configuración |
|---|---|
| I²C | SDA 21, SCL 22 |
| MAX30102 | Dirección `0x57` |
| GY906 / MLX90614 | Dirección `0x5A` |
| MPU6050 | Dirección `0x68` |
| Micrófono | ADC pin 34 |
| Fotoresistor | ADC pin 35 |
| Buzzer | Pin 25 |

Estos son los valores del fuente, no una certificación del cableado actual. No hay medición de presión arterial ni de hidratación. La temperatura infrarroja de superficie no es temperatura corporal central. Oscuridad, manga y orientación pueden confundir el fotoresistor; requiere calibración y no basta para declarar que una persona lleva el dispositivo.

## Configuración privada y compilación

`secrets.h` contiene únicamente la configuración Wi-Fi local. Está excluido de Git y tiene permisos `0600`. En una instalación nueva:

```bash
cp firmware/esp32/secrets.example.h firmware/esp32/secrets.h
chmod 600 firmware/esp32/secrets.h
# Editar secrets.h localmente con la red propia y una clave única del wearable.
# Para generar solo una nueva clave privada de instalación: python3 -c 'import secrets; print(secrets.token_hex(16))'
```

Dependencias verificadas: Arduino ESP32 core 3.3.12, ArduinoJson 7.4.3 y SparkFun MAX3010x Pulse and Proximity Sensor Library 1.1.2. La compilación predeterminada con partición de 1.2 MiB falla por tamaño. Para comprobar el fuente sin escribir al dispositivo se utilizó la configuración de 4 MiB con aplicación de 1.9 MiB:

```bash
arduino-cli compile \
  --fqbn esp32:esp32:esp32:PartitionScheme=min_spiffs \
  --build-path .local/firmware-build firmware/esp32
```

Este comando **solo compila**. No cambia las particiones de la placa ni prueba que su flash instalada tenga ese tamaño/esquema. El directorio de compilación es privado: los binarios incorporan `secrets.h` y no deben distribuirse. Para una futura instalación habrá que identificar placa, flash y particiones y autorizar esa operación expresamente; no ejecutar un borrado o una subida como parte del diagnóstico.

El firmware utiliza SPP cifrado con SSP sin entrada en la placa. Android puede mostrar su propia confirmación; no se escribe `Y` por Serial. Antes de emitir sensores, la app y la placa verifican un reto mutuo HMAC-SHA256 con nonces nuevos y la clave privada de 128 bits de esa placa (`WEARABLE_SETUP_KEY_HEX` en `secrets.h`). La app pide la clave una sola vez y la guarda cifrada por cuenta/servidor/MAC. La autenticación vence a los 10 segundos; los datos, escrituras y cierres se filtran por el handle y la generación de la única conexión admitida. Al ocuparla se desactiva descubrimiento y conectabilidad; se restauran al desconectarse. No publicar la clave, pruebas HMAC ni registros Serial completos.

## Diagnóstico de solo lectura

```bash
python tools/check_firmware.py --port /dev/ttyUSB0 --duration 25 \
  --output .local/firmware-check.json
```

Se configura Serial a 115200, se desactivan DTR/RTS antes de abrir, y no se envían bytes ni pulsos de reset. Ciertos adaptadores pueden producir transitorios en las líneas de control al abrir; el informe cuenta discontinuidades de `uptime_ms` observadas. El tiempo de lectura está acotado y no depende de recibir una línea completa. La salida contiene solo agregados de encuadre, frecuencia, identidad declarada y estados; no guarda valores fisiológicos, audio ni muestras crudas. Un puerto ausente devuelve código 2; una lectura sin JSON válido devuelve 1.

Captura realizada el **25 de octubre de 2026**, con teléfono todavía no disponible:

| Indicador | Resultado observado |
|---|---|
| Duración | 25.015 s |
| Mensajes JSON | 25 válidos, 0 malformados |
| Frecuencia | 0.997 Hz |
| Discontinuidades de uptime | 0 observadas |
| Identidad declarada | `elder-demo-01` |
| Estado óptico | `no_finger`, 25/25 |
| Dedo / HR válido / SpO₂ válido | `false`, 25/25 |
| MPU6050 online | `false`, 25/25 |
| GY906 online | `true`, 25/25 |

El informe privado está en `.local/firmware-check-20261025.json`. La recepción USB está comprobada; la entrega SPP al futuro Samsung, la validez fisiológica, el uso en una persona y el extremo a extremo teléfono-servidor quedan pendientes de prueba. Durante esta captura no había señal óptica válida y el MPU6050 se declaraba fuera de línea. No se atribuye esa condición a una revisión concreta del firmware porque su identidad instalada es desconocida.

## Límite para caídas

El JSON entrega snapshots de movimiento a aproximadamente 1 Hz. SisFall contiene series inerciales a 200 Hz de otros sensores, con otra colocación y protocolo. No se interpola un snapshot de 1 Hz para presentarlo como una serie de 200 Hz ni se despliega un detector de caídas entrenado así. Para un estudio futuro se requiere adquisición continua de ventanas, frecuencia medida, metadatos de colocación, unidades calibradas, separación por sujeto y validación independiente; modificar ese contrato sería otro cambio de firmware.
