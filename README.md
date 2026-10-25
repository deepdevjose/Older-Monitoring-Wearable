# Care Monitor: ESP32, Android y FastAPI

El ESP32 envía sus lecturas a la app Android mediante Bluetooth Classic SPP. El
teléfono las reenvía al servidor FastAPI por HTTPS con certificado fijado y
cabecera API key. El backend conserva la API key; el ESP32 no la necesita.
La red configurada para esta prueba es:

- Wi-Fi: `Test`
- IP fija de la computadora: `10.238.118.117`
- Puerto FastAPI: `8000`
- Endpoint del teléfono: `https://10.238.118.117:8000/sensor-data`

## 1. Conectar la computadora a la red correcta

Conecta Fedora al hotspot o red Wi-Fi `Test`. Desde una terminal, verifica que
la conexión esté activa:

```bash
nmcli connection up "Test"
```

Comprueba la IP:

```bash
ip -4 addr show wlp0s20f3
```

Debe aparecer una dirección similar a:

```text
10.238.118.117/24
```

Comprueba también la ruta:

```bash
ip route
```

Debe existir una ruta para la red `10.238.118.0/24` y el gateway configurado
debe ser `10.238.118.1`.

Si la computadora está conectada a otra red, como `XJTLU`, la dirección
`10.238.118.117` no estará disponible y el servidor no podrá iniciar usando esa
IP.

## 2. Iniciar FastAPI con HTTPS

Abre una terminal y ejecuta:

```bash
cd "/home/deepdevjose/Downloads/Iot App"
./start_server.sh
```

El script configura automáticamente la API key y ejecuta Uvicorn con el
certificado TLS local.

Debe aparecer un mensaje similar a:

```text
Uvicorn running on https://10.238.118.117:8000
```

No cierres esta terminal mientras estés probando el ESP32.

## 3. Probar FastAPI desde la computadora

En una segunda terminal, prueba el endpoint de estado:

```bash
curl --cacert "/home/deepdevjose/Downloads/Iot App/certs/server.crt" \
  https://10.238.118.117:8000/
```

La respuesta esperada es:

```json
{"status":"ok","service":"elder-sensors-api"}
```

También puedes abrir la documentación interactiva en el navegador:

```text
https://10.238.118.117:8000/docs
```

El navegador puede mostrar una advertencia porque el certificado es
autofirmado. Para esta prueba local, acepta la excepción de seguridad.

En `/docs`, pulsa **Authorize**, introduce la API key y confirma. Después podrás
probar `GET /sensors` directamente desde Swagger. Si abres `/sensors` en el
navegador sin autorizarte, recibirás `{"detail":"Invalid API key"}` porque un
navegador no envía la cabecera `X-API-Key` automáticamente.

## 4. Probar autenticación y recepción de datos

La API key se configura en FastAPI y se introduce en la app Android; ya no se
incluye en el firmware. Para pruebas manuales, carga la key en una variable de
esta terminal (no la escribas en el historial de comandos):

```bash
read -rsp "API key: " SENSOR_API_KEY; echo
```

```bash
curl --cacert "/home/deepdevjose/Downloads/Iot App/certs/server.crt" \
  -X POST "https://10.238.118.117:8000/sensor-data" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${SENSOR_API_KEY}" \
  -d '{"device_id":"elder-demo-01","uptime_ms":1}'
```

Debe responder con HTTP `200` y un JSON que contenga:

```json
{
  "status": "received",
  "data": {
    "device_id": "elder-demo-01"
  }
}
```

Después, consulta la última lectura:

```bash
curl --cacert "/home/deepdevjose/Downloads/Iot App/certs/server.crt" \
  -H "X-API-Key: ${SENSOR_API_KEY}" \
  https://10.238.118.117:8000/sensors
```

## 5. Cargar y probar el ESP32

En Arduino IDE abre:

```text
data_API/data_API.ino
```

El firmware conserva Wi-Fi únicamente para sincronizar la hora por NTP. Ya no
envía lecturas al servidor ni contiene la API key o el certificado HTTPS. Las
lecturas se transmiten como una línea JSON por Bluetooth Classic SPP.

1. Selecciona la placa ESP32 clásica y el puerto correctos. En **Tools →
   Partition Scheme**, elige **Huge APP (3MB No OTA/1MB SPIFFS)**; Bluetooth
   Classic más los sensores necesita más espacio que la partición predeterminada.
2. Compila el programa.
3. Carga el programa al ESP32.
4. Abre el monitor serial a `115200` baudios.

El emparejamiento usa Secure Simple Pairing con comparación numérica. Inicia el
emparejamiento con `ElderSensors-elder-demo-01` desde Android. Compara que el
código de seis dígitos en ambos dispositivos coincida, confírmalo en Android y
enseguida escribe `Y` en el Monitor Serial y pulsa Enter. Si no coincide,
rechaza el emparejamiento en el teléfono y escribe otra tecla en Serial. La
confirmación se procesa desde el `loop()` para no bloquear Bluetooth.
Después, el Monitor Serial debe mostrar:

```text
Bluetooth SPP ready: ElderSensors-elder-demo-01
Pair from Android Settings; compare the numeric code on both devices.
Compare pairing code ... with the phone, then type Y to accept:
Bluetooth pairing authenticated.
Bluetooth SPP -> sent ...-byte sensor record.
```

Las lecturas siguen apareciendo como JSON en el monitor serial y se envían al
teléfono aproximadamente cada segundo. El Wi-Fi sigue sincronizando el reloj
Shanghai; no se usa como ruta de datos al servidor.

### Configurar la app Android como relay

Usa el proyecto nativo Java en
`CareMonitor-v0.4.0-source/care_monitor_native`. Instálalo en Android 7 o
posterior; Android 12 o posterior pedirá permiso de Bluetooth al seleccionar un
dispositivo. El APK incluido en `artifacts/` es la versión demo anterior y no
incluye este relay; compila e instala el APK desde el código fuente actualizado
siguiendo el README de ese proyecto.

1. En el teléfono, empareja el ESP32 desde Ajustes Bluetooth. Confirma solo si
   el código de seis dígitos coincide con el que muestra el monitor serial;
   luego escribe `Y` en Serial para aprobar el mismo código en el ESP32.
2. Abre Care Monitor y elige **Explore demo**.
3. Abre **⋮ → Settings** y confirma el URL HTTPS `https://10.238.118.117:8000`.
4. Pega la misma API key configurada en FastAPI y pulsa **Verify HTTPS backend**.
   La app valida el certificado incluido; nunca acepta HTTP ni desactiva la
   comprobación del certificado/host. La API key permanece solo en memoria.
5. Pulsa **Choose paired ESP32** y selecciona `ElderSensors-elder-demo-01`.
6. Vuelve a Home o Sensors. Los datos Bluetooth se muestran como lecturas en
   vivo; los gráficos de historial no se inventan para datos del dispositivo.
7. Verifica en Settings el estado HTTPS y en la terminal del servidor las
   solicitudes `POST /sensor-data` con HTTP `200`.

El teléfono debe poder llegar por Wi-Fi a `10.238.118.117:8000`. El ESP32 y el
teléfono no necesitan compartir Wi-Fi para transportar los datos. El teléfono
necesita Bluetooth activado y el permiso de conexión. Si se interrumpe el enlace
Bluetooth o HTTPS, la app muestra el error; no mantiene una cola durable cuando
se cierra.

En Alertas, **Refresh server alerts** consulta las alertas nuevas persistidas en
FastAPI. La alerta educativa de SpO₂ sigue dependiendo de tres lecturas válidas
consecutivas; no se presentan como alertas las demás condiciones clínicas no
validadas.

```text
POST /sensor-data HTTP/1.1" 200 OK
```

Las lecturas de los sensores se imprimen como JSON en el monitor serial. Las
lecturas se envían aproximadamente cada segundo.

El firmware sincroniza la hora por NTP y añade `timestamp` en horario
`Asia/Shanghai` (`UTC+8`). La hora del servidor `received_at` también se
devuelve en `Asia/Shanghai`, para que ambas horas coincidan.

## 6. Verificar almacenamiento SQLite

Cada `POST /sensor-data` exitoso crea un registro permanente en:

```text
sensor_data.db
```

El archivo se crea junto a `main.py`. El registro contiene:

- `id`
- `device_id`
- `uptime_ms`
- MAX30102: I2C address, status, finger/valid flags, BPM, SpO2, IR/red raw
  values, sample count and window target
- MPU6050: I2C address, online status, all accelerometer and gyroscope axes
- GY906: I2C address, online status, object and ambient temperatures
- Microphone: pin and ADC raw value
- Photoresistor: pin, ADC raw value and percentage
- Buzzer: pin
- `heart_rate_bpm`
- `spo2_percent`
- `received_at`
- `sensor_timestamp`
- `payload_json` con el JSON completo original, incluidos campos futuros

Los valores `heart_rate_bpm` y `spo2_percent` pueden ser `null` cuando el
MAX30102 no detecta un dedo. Esto es normal y no descarta las demás lecturas.
Cada POST válido añade una fila y la respuesta HTTP incluye su `record_id`.

Para consultar los últimos 20 registros:

```bash
curl --cacert "/home/deepdevjose/Downloads/Iot App/certs/server.crt" \
  -H "X-API-Key: ${SENSOR_API_KEY}" \
  https://10.238.118.117:8000/sensor-data
```

También puedes usar `/docs`, pulsar **Authorize** y ejecutar `GET
/sensor-data`.

Para comprobar persistencia:

1. Envía varias lecturas desde el ESP32.
2. Consulta `GET /sensor-data` y anota el `id` más reciente.
3. Detén FastAPI con `Ctrl+C`.
4. Inicia nuevamente con `./start_server.sh`.
5. Consulta otra vez `GET /sensor-data`.

Los mismos registros deben seguir apareciendo. No borres `sensor_data.db`.

## 7. Alertas Step 5 (demostración)

La primera alerta es únicamente una regla de software de demostración:

- El firmware debe marcar SpO2 como válida (`spo2_valid: true`).
- El valor debe estar en el rango aceptado por el firmware (70–100).
- Se requieren tres lecturas válidas consecutivas menores que 95.
- Una sola lectura baja o cualquier lectura inválida no crea una alerta.
- Solo se crea una alerta durante un episodio bajo.
- El episodio se considera recuperado después de tres lecturas válidas
  consecutivas de 95 o más.
- La alerta se asocia al paciente ficticio `demo-patient-001`.

`95` es el umbral arbitrario del ejercicio del laboratorio, no un umbral
clínico. Esta alerta no es diagnóstico ni sustituye atención médica. No pruebes
valores anormales en una persona; utiliza únicamente datos simulados para
probar la regla. Las alertas de caída, presión arterial, pulso, temperatura,
deshidratación y uso del wearable todavía no están activas.

En Swagger, autoriza con la API key y usa `POST /sensor-data` con un payload
simulado que incluya `device_id: "elder-demo-01"` y un bloque `max30102` válido.
Envía tres lecturas bajas consecutivas para verificar que la tercera responde
`"alert_created": true`. Enviar una lectura baja aislada, una muestra inválida,
o una lectura de `95` o más no debe crear una nueva alerta.

Consulta alertas nuevas con:

```bash
curl --cacert "/home/deepdevjose/Downloads/Iot App/certs/server.crt" \
  -H "X-API-Key: ${SENSOR_API_KEY}" \
  "https://10.238.118.117:8000/alerts"
```

Para incluir alertas resueltas:

```text
GET /alerts?include_resolved=true
```

Los datos de la lectura, la alerta y su estado se guardan en una transacción
SQLite. La alerta conserva `reading_id`, paciente, dispositivo, métrica, valor
observado, umbral de demostración, motivo, hora Shanghai y estado. La app
Android consulta `/alerts`; el endpoint no envía notificaciones push.

## 8. Errores comunes

### `cannot assign requested address`

La computadora no tiene actualmente la IP `10.238.118.117`. Conéctala a la red
`Test` y vuelve a comprobar:

```bash
ip -4 addr show wlp0s20f3
```

### `ERR_CONNECTION_REFUSED`

FastAPI no está ejecutándose o se inició en otro puerto. Inicia:

```bash
cd "/home/deepdevjose/Downloads/Iot App"
./start_server.sh
```

### Error de certificado HTTPS en Android

Comprueba que:

- La URL de Settings usa `https://`.
- La IP es `10.238.118.117`.
- `app/src/main/assets/server.crt` corresponde al certificado activo en
  `certs/server.crt`.
- El certificado incluye la IP del servidor en `subjectAltName` y no ha vencido.

### Respuesta `401`

La API key pegada en la app no coincide con la que usa FastAPI. Vuelve a
introducirla en Settings; la app no la guarda al cerrar sesión ni en el APK.

### Respuesta `422`

El JSON no contiene el identificador esperado:

```json
{"device_id":"elder-demo-01"}
```

### No aparece el ESP32 en la app

Empareja primero el ESP32 desde Ajustes Bluetooth de Android con Secure Simple
Pairing; compara el código numérico y confírmalo también en el monitor serial.
Bluetooth Classic SPP requiere un ESP32 clásico; no está
disponible en ciertos modelos ESP32-C3/S3. Si falla la compilación por
`CONFIG_BT_SPP_ENABLED`, confirma el modelo de placa seleccionado.

## 9. Detener el servidor

En la terminal donde corre FastAPI, presiona:

```text
Ctrl+C
```

Cuando esta prueba funcione, el siguiente paso será conectar el almacenamiento
SQLite de los laboratorios `4aSQLite_Sensor_Storage_Lab.docx` y
`4bSQLite_Sensor_Storage_Lab.docx`.
