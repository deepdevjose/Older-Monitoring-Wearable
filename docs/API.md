# API de CareMonitor 1.0

Este servicio es una **demostración académica**. El servidor conserva las lecturas del ESP32 y evalúa avisos de límites configurados, separando esos episodios de los ejemplos sintéticos. No ofrece diagnóstico, vigilancia clínica, presión arterial, diagnóstico de deshidratación ni detección validada de caídas. Las notificaciones requieren conectividad y un cliente que consulte la bandeja; no contactan servicios de emergencia.

Base: `https://IP_DEL_SERVIDOR:8443/api/v1`. Usa el certificado local instalado/verificado siguiendo [SERVER.md](SERVER.md). Los ejemplos de esta página son contratos JSON; las credenciales, tokens y datos de pacientes no deben copiarse a documentación o registros.

## Autenticación y transporte

Cada llamada privada requiere `Authorization: Bearer TOKEN`. El token aleatorio caduca en 24 horas, se almacena mediante su hash y se revoca con logout. La API no acepta una API key global. No se admiten identificadores de paciente enviados en una lectura: el paciente se obtiene de la sesión del adulto mayor.

Los cuerpos son JSON de hasta 64 KiB; la lectura de sensores admite hasta 16 KiB y campos explícitos compatibles con los firmwares documentados. La API rechaza claves duplicadas, números no finitos, campos de formulario desconocidos, audio crudo y fechas sin zona horaria. Los errores de validación no devuelven el valor rechazado. Las fechas de respuesta usan UTC/ISO 8601. La CLI activa HTTPS obligatorio; no se debe exponer un proceso de desarrollo HTTP en la red.

| Método | Ruta | Resultado |
|---|---|---|
| POST | `/auth/register` | Crea una cuenta local y entrega una sesión |
| POST | `/auth/login` | Entrega una sesión independiente |
| GET | `/me` | Usuario de la sesión |
| POST | `/auth/logout` | Revoca esta sesión |
| GET | `/health` | Estado público mínimo, sin pacientes ni secretos |

Registro:

```json
{"email":"persona@example.com","password":"una contraseña larga y exclusiva","display_name":"Persona de demostración","role":"elder","educational_ack":true}
```

`role` solo admite `elder` o `caregiver`. `educational_ack` debe ser el booleano `true`; la contraseña debe tener entre 12 y 128 caracteres. Login recibe solamente `{ "email": "…", "password": "…" }`. Registro responde `201`, login `200`, ambos:

```json
{"access_token":"TOKEN_OPACO","expires_at":"2026-10-26T12:00:00Z","user":{"id":"ID_USUARIO","role":"elder","display_name":"Persona de demostración","email":"persona@example.com"}}
```

`GET /me` entrega directamente el objeto `user`. Logout responde `{ "status": "logged_out" }`. Los intentos de registro, login, invitaciones y códigos se limitan en SQLite por IP, cuenta y, donde corresponde, servicio completo. Las escrituras autenticadas también tienen límites por cuenta: 180 lecturas/minuto (permite recuperar una cola), diez eventos/minuto y veinte recordatorios nuevos/hora; el cliente debe conservar la cola y reintentar un `429` después del tiempo indicado. Los límites sobreviven al reinicio. La IP procede de la conexión; `X-Forwarded-For` no concede confianza automáticamente.

## Adultos mayores y datos

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/patients` | Adulto: él mismo; cuidador: solo sus vínculos aprobados |
| GET | `/patients/{id}/overview` | Adulto propio o cuidador actualmente autorizado |
| POST | `/readings` | Solo adulto mayor, para su dispositivo asignado |
| GET | `/patients/{id}/readings?limit=50` | Miembro autorizado; límite 1–500 |
| GET | `/patients/{id}/alerts?limit=50` | Miembro autorizado; límite 1–500 |
| POST | `/alerts/{id}/acknowledge` | Solo cuidador actualmente autorizado |

`GET /patients` responde `{ "patients": [{ "id": "…", "display_name": "…", "device_id": "elder-demo-01" }] }`.

Una lectura conserva el JSON de sensores dentro de un contenedor de transporte:

```json
{
  "sample_id":"telefono-sesion-000001",
  "captured_at":"2026-10-25T12:00:00Z",
  "source":"device_diagnostic",
  "payload":{
    "device_id":"elder-demo-01",
    "uptime_ms":10000,
    "max30102":{"status":"ok","finger_detected":true,"heart_rate_valid":true,"spo2_valid":true,"heart_rate_bpm":76,"spo2_percent":98},
    "mpu6050":{"online":true,"accel_g":{"x":0.02,"y":-0.01,"z":1.0},"gyro_dps":{"x":0.1,"y":-0.2,"z":0.0}},
    "gy906":{"online":true,"object_temp_c":33.5,"ambient_temp_c":24.1},
    "photoresistor":{"adc_raw":2000,"percent":48.84}
  }
}
```

`source` admite `synthetic` o `device_diagnostic`. Los datos de dispositivo pueden activar avisos de límites configurados con tres muestras recientes consecutivas. Se conservan valores numéricos fuera de rango y banderas de calidad sin convertirlos en una evaluación clínica. La muestra puede capturarse hasta 30 días atrás para recuperar una cola; una marca más de 30 segundos futura se rechaza. Las muestras antiguas se almacenan como historial y no pasan a ser la lectura actual por el orden de recepción.

Respuesta `201`:

```json
{"status":"received","record_id":1,"duplicate":false,"alert_created":false}
```

La pareja `(paciente, sample_id)` es única y se verifica dentro de una transacción. Reenviar exactamente el mismo contenedor devuelve el mismo `record_id` con `duplicate:true`; cambiar el contenido con el mismo ID devuelve `409`. El teléfono debe conservar el contenedor original en cada reintento.

Campos adicionales permitidos en `payload`: `timestamp`, `phone_received_at`, `sample_id`, `boot_nonce`; direcciones I²C y pines; diagnósticos escalares del MAX30102, incluidos contadores de FIFO de la revisión del 23 de octubre. Se preservan los indicadores de validez y valores `null`. `microphone` solo admite `pin` y `adc_raw` escalares: ese valor diagnóstico no es una grabación ni prueba de un accidente. No se aceptan ondas, blobs, archivos ni texto de audio.

`GET /readings` responde `{ "readings": [CONTENEDOR] }`; cada contenedor añade `record_id` y `received_at`.

El resumen responde:

```json
{
  "patient":{"id":"ID","display_name":"Persona","device_id":"elder-demo-01"},
  "reading":null,
  "alerts":[],
  "capabilities":[{"id":"blood_pressure","status":"unavailable","reason":"The installed sensors do not measure blood pressure."}],
  "location":null,
  "freshness":{"state":"no_data","age_seconds":null},
  "wear_inference":{"state":"unknown","validated":false,"reason":"No fresh sensor evidence"}
}
```

`reading` contiene la muestra con fecha de captura más reciente. `freshness.state` admite `no_data`, `fresh` (hasta diez segundos) y `stale`. Una inferencia de uso puede ser `possible_worn` solo con oscuridad y contacto óptico; sigue siendo no validada. La luz sola produce incertidumbre. Un sensor antiguo no se interpreta como evidencia actual de uso.

Las alertas devuelven:

```json
{"alerts":[{"id":"ALERTA","patient_id":"ID","type":"oxygen_low","created_at":"2026-10-25T12:00:00Z","source":"synthetic","severity":"attention","title":"Oxígeno bajo simulado","message":"Descripción educativa y límites","educational":true,"acknowledged":false,"acknowledged_at":null,"episode_status":"open","recovered_at":null}]}
```

El acuse de un cuidador responde `{ "status": "acknowledged", "patient_resolved": false }`. Es individual: no cambia la revisión de otro cuidador, la recepción de una notificación ni el estado de la persona.

### Reglas educativas

Se evalúan tres muestras del mismo paciente y origen, con capturas distintas, separadas entre uno y treinta segundos, dentro de una ventana de dos minutos. Los episodios físicos y sintéticos se mantienen separados. Pulso y oxígeno usan la estimación numérica presente con contacto óptico; las banderas de calidad se conservan, sin ocultar estimaciones fuera de rango. Un resultado ausente o el centinela −999 del algoritmo no se sustituye por cero. Se crea un aviso por episodio; tres estimaciones normales permiten recuperarlo.

| Señal | Límite configurado |
|---|---|
| Pulso | Menor que 50 o mayor que 120 bpm |
| SpO₂ | Menor que 95 % |
| Ambiente | Menor que 10 °C o mayor que 35 °C, sensor online |
| Superficie | Menor que 28 °C o mayor que 39 °C, con ambiente disponible |

Estos límites son ilustrativos y **no son recomendaciones médicas**. El GY906 mide superficie y ambiente; no se estima temperatura corporal central. Una saturación superior a 100 es inválida; no se implementa un detector de “oxígeno alto”.

Cada condición mantiene un episodio persistente: genera una alerta al entrar y permanece abierta mientras siga la condición. Tres observaciones sintéticas normales y válidas de ese mismo indicador cierran el episodio (`episode_status:recovered`). Datos ausentes, antiguos, inválidos o reales no prueban recuperación. Un nuevo episodio posterior puede generar otra alerta. Este estado educativo tampoco certifica recuperación de una persona.

## Vinculación y revocación

| Método | Ruta | Función |
|---|---|---|
| POST | `/invitations` | Adulto crea un código de cuatro dígitos válido diez minutos |
| POST | `/link-requests` | Cuidador envía `{ "code": "0123" }` |
| GET | `/link-requests` | Adulto consulta solicitudes pendientes identificadas |
| POST | `/link-requests/{id}/approve` | Adulto aprueba explícitamente al cuidador |
| GET | `/caregivers` | Adulto consulta sus cuidadores aprobados |
| DELETE | `/caregivers/{caregiver_id}` | Adulto revoca el vínculo |

Crear invitación responde `{ "code": "0123", "expires_at": "…" }`. Solo una invitación del adulto permanece activa; crear otra revoca la anterior. El código se conserva mediante un HMAC y no aparece en auditoría.

Solicitar vínculo responde `{ "id": "SOLICITUD", "status": "pending" }`, **sin dar acceso al paciente**. El código es solo una forma de localizar una solicitud; no es una credencial suficiente. El adulto verifica la identidad por un canal acordado y aprueba la persona correcta. El código se consume al aprobar un único cuidador; los demás usan un código nuevo.

`GET /link-requests` entrega `{ "requests": [{ "id": "…", "status": "pending", "caregiver": { "id": "…", "display_name": "…", "email": "…" }, "created_at": "…" }] }`. `GET /caregivers` entrega `{ "caregivers": [{ "id": "…", "display_name": "…" }] }`.

La revocación es inmediata para nuevas consultas, acuses y notificaciones pendientes del paciente. También cancela los recordatorios creados por ese cuidador para ese adulto y retira sus notificaciones pendientes de todos los destinatarios; otro cuidador autorizado puede crear una indicación nuevamente acordada. Se eliminan sus notificaciones del cuidador revocado y se invalidan invitaciones pendientes que pudieran restaurar el acceso. No puede borrar datos que una persona ya hubiera visto o copiado antes de la revocación.

## Bandeja durable

| Método | Ruta | Función |
|---|---|---|
| GET | `/notifications?after=0&limit=100` | Obtiene hasta 200 elementos propios posteriores al cursor |
| GET | `/notifications/wait?after=0&timeout=20` | Espera privada hasta 1–30 segundos; entrega hasta 100 elementos |
| POST | `/notifications/{id}/ack` | Confirma recepción de un elemento propio |

```json
{"items":[{"id":1,"type":"alert","created_at":"2026-10-25T12:00:00Z","data":{"patient_id":"ID","alert_id":"ALERTA","type":"sos","title":"Solicitud de ayuda","message":"…","severity":"urgent","educational":true},"acknowledged":false}],"cursor":1}
```

La espera privada usa exclusivamente este servidor HTTPS. Vuelve a verificar la sesión y los vínculos al menos cada segundo; un logout o caducidad corta la espera con `401`. Devuelve inmediatamente los elementos disponibles o `{ "items": [], "cursor": CURSOR }` al vencer el tiempo. Admite hasta cinco esperas simultáneas por cuenta y cien por proceso. El servicio Android consentido vuelve a conectar tras cada respuesta; el timeout HTTP del cliente debe superar `timeout` al menos diez segundos.

El cliente guarda el cursor **después** de persistir/publicar la notificación local. Un corte antes de ese punto permite recuperar el elemento. El acuse de recepción es idempotente, solo para el destinatario, y responde `{ "status": "delivered" }`.

Alertas y recordatorios se crean y distribuyen dentro de la misma transacción a todos los cuidadores activos y al adulto. La bandeja permanece después de reinicios. No se usan FCM, APNs ni otros servicios de nube. El servicio Android consentido mantiene la conexión privada con este PC. Fuera de la red local hace falta una conexión privada al servidor según la guía; el servidor no descubre teléfonos por internet. La entrega con la app detenida, sin conexión o limitada por Android no está garantizada. No se puede presentar como un sistema de emergencias.

## Recordatorios

| Método | Ruta | Acceso |
|---|---|---|
| POST | `/patients/{id}/reminders` | Cuidador autorizado |
| GET | `/patients/{id}/reminders` | Adulto o cuidador autorizado |
| POST | `/reminders/{id}/taken` | Solo el adulto propietario |
| DELETE | `/reminders/{id}` | Adulto propietario o cuidador autorizado; cancela futuras ocurrencias |

```json
{"title":"Recordatorio acordado","dose":"Texto indicado por la persona responsable","instructions":"Instrucción educativa","due_at":"2026-10-25T16:00:00Z","repeat_hours":24}
```

Se permiten como máximo cincuenta recordatorios activos por paciente. `repeat_hours` es opcional (`null` o entero 1–720). `title` tiene hasta 120 caracteres; `dose` hasta 120; `instructions` hasta 500. El servicio no prescribe fármacos, calcula dosis ni modifica indicaciones. Una alarma de hidratación es un recordatorio acordado, no diagnóstico de deshidratación.

Creación devuelve directamente el recordatorio; consulta devuelve `{ "reminders": [RECORDATORIO] }`. El objeto contiene `id`, `patient_id`, los tres textos, `due_at` (próxima fecha), `repeat_hours`, `active`, `last_due_at`, `taken_at` y `cancelled_at`.

El programador comprueba vencimientos cada quince segundos mientras el servidor funciona. La notificación contiene `patient_id`, `reminder_id`, los textos, `due_at` y `missed_occurrences`. Una clave única por ocurrencia/destinatario evita duplicados. Tras una interrupción larga se notifica únicamente la última ocurrencia vencida, con el número de ocurrencias omitidas, para evitar una avalancha de posibles dosis repetidas. No se indica tomar todas las dosis omitidas.

`/taken` confirma la última ocurrencia ya vencida y responde `{ "status": "recorded", "message": "Self-reported confirmation; no medication ingestion is verified." }`. No hay verificación de ingestión. Repetir la confirmación conserva el primer registro.

`DELETE /reminders/{id}` responde `{ "status": "cancelled" }`, registra la acción, desactiva el recordatorio y retira sus notificaciones pendientes de las bandejas del servidor. No borra una notificación ya publicada en otro teléfono. Un recordatorio cancelado no admite nuevas confirmaciones de toma.

## Consentimiento, ubicación y eventos

| Método | Ruta | Función |
|---|---|---|
| GET | `/consents` | Adulto consulta sus permisos del servidor |
| POST | `/consents` | Adulto envía `{ "location": false, "audio": false }` |
| POST | `/location` | Adulto envía una posición reciente fuera del hogar |
| POST | `/events` | Adulto envía un SOS confirmado o evento educativo |

Los permisos comienzan desactivados. Los permisos Android son adicionales al consentimiento en el servidor. Revocar ubicación elimina sus coordenadas y hace que el resumen deje de mostrarlas.

```json
{"outside_home":true,"latitude":25.6872,"longitude":-100.3161,"accuracy_m":10,"recorded_at":"2026-10-25T12:00:00Z"}
```

La ubicación exige consentimiento activo, `outside_home:true`, coordenadas válidas y una marca de hasta cinco minutos atrás/treinta segundos futura. La captura debe ser posterior al comienzo del consentimiento actual: tras activar o volver a activar el permiso se necesita una posición nueva, no una posición guardada anteriormente. El servidor confía en la clasificación de salida del cliente: no demuestra por sí mismo que alguien haya abandonado su hogar. Se muestra solo a miembros autorizados; caduca a las 24 horas y se elimina en el ciclo del programador. Nunca se recibe la dirección del hogar mediante este endpoint.

```json
{"type":"sos","confirmed":true,"occurred_at":"2026-10-25T12:00:00Z","source":"device_diagnostic"}
```

Tipos: `sos`, `possible_fall`, `audio_impact`. Un SOS exige confirmación humana y crea una solicitud de ayuda. Los otros dos tipos solo generan alertas si son sintéticos; `audio_impact` también exige consentimiento de audio. Se reciben únicamente tipo, confirmación, hora y procedencia, nunca sonido. Los eventos deben ser recientes (dos minutos). La respuesta contiene `status`, `event_id` y `alert_created`.

## Clasificador sintético

`GET /ai/model` ofrece metadatos del modelo local autenticado. `POST /ai/classify` recibe:

```json
{"source":"synthetic","features":{"heart_rate_bpm":80,"spo2_percent":98,"body_temp_c":36.5,"sample_age_seconds":1}}
```

El clasificador acepta únicamente el contrato del dataset sintético y muestras de hasta diez segundos. `body_temp_c` pertenece al dataset y **no puede sustituirse por `gy906.object_temp_c`**. El resultado educativo no activa alarmas clínicas. La ruta permite treinta llamadas por minuto y usuario; si el artefacto no está preparado responde `503`.

## Persistencia y controles

### Históricos por métrica

`GET /patients/{id}/metrics/{metric}/history?period=day&source=device_diagnostic` exige sesión y acceso al paciente. `period` admite `day`, `week` o `month` (24 horas, siete o treinta días); `end` opcional es una fecha ISO con zona horaria. Las métricas son `heart_rate_bpm`, `spo2_percent`, `surface_temp_c`, `ambient_temp_c`, `light_adc` y `acceleration_g`. `source` distingue `device_diagnostic` de `synthetic`; nunca se combinan.

Devuelve `points` con hora, media, mínimo, máximo y conteo; `summary` con media ponderada, rango y última observación; `coverage` informa indexación y observaciones omitidas. Las estimaciones ópticas numéricas se incluyen aunque las banderas de calidad sean falsas o superen límites. Los puntos sin estimación tienen `value:null`, no cero. La revisión de política reconstruye solamente los agregados derivados; conserva los registros originales cifrados. Rollups horarios cifrados se actualizan en la misma transacción de ingestión; las horas de los extremos se leen exactamente. La migración de históricos existentes tiene trabajo acotado y comunica cobertura parcial. Solo se muestran datos retenidos en los últimos treinta días. Las medias no equivalen a lecturas instantáneas ni a evaluaciones clínicas.

`available_readings` contiene `count`, `first_at` y `latest_at` de los paquetes retenidos del mismo paciente y origen, aunque queden fuera del intervalo seleccionado. Permite ofrecer **View latest saved day** cuando el día actual está vacío. Estas fechas no afirman que todos los sensores del paquete produjeran una medición válida. Con acceso revocado o ajeno, la API devuelve `404` sin revelar la existencia de la persona. El cliente Android valida paciente, métrica, periodo y origen de cada respuesta; la consulta de históricos admite hasta quince segundos de lectura para la preparación de registros antiguos.

### Ejercicios de aprendizaje móvil

`GET /patients/{id}/learning/readings` devuelve los últimos treinta ejercicios (hasta cien con `limit`), los rangos del modelo y tres ejemplos trazables al CSV. La persona mayor consulta los suyos y un cuidador requiere vínculo aprobado vigente. `can_add` es falso para cuidadores.

`POST /patients/{id}/learning/readings` es exclusivo de la persona mayor propietaria. Recibe `sample_id` UUID, `source:"synthetic"` y el mismo objeto `features` del clasificador. Devuelve clase, confianza, modelo, características y fecha, guardados cifrados por treinta días en `learning_records`. Repetir identificador y características devuelve el registro original; cambiarlas produce `409`. Datos ausentes, no finitos, fuera de rango, antiguos o de origen físico se rechazan. Un identificador expirado requiere otro UUID.

Estos ejercicios se muestran en **Settings → Learning insights** para ambos roles. Su almacenamiento es independiente de `readings`, no genera alertas ni notificaciones y no convierte temperatura superficial en corporal. Revocar el vínculo retira el acceso tanto a los ejercicios como a los históricos por métrica.

SQLite usa claves foráneas, WAL, transacciones y permisos locales privados. AES-256-GCM protege perfiles, lecturas, eventos, alertas, recordatorios, coordenadas y contenido de notificaciones. Identificadores, roles, relaciones, índices y fechas permanecen como metadatos necesarios para autorización/programación. Las lecturas, eventos y notificaciones se eliminan después de treinta días; las ubicaciones, después de 24 horas. La auditoría se conserva noventa días y las sesiones vencidas se limpian después de siete días adicionales. El programador realiza estas limpiezas mientras el servidor funciona. Las contraseñas usan Argon2id. Las búsquedas de correo y código emplean HMAC con la clave local. La auditoría contiene acciones e IDs; omite textos de salud, correo, contraseñas, códigos y tokens.

La clave se crea de manera explícita al inicializar el servidor, en `CAREMONITOR_HOME/encryption.key` con permisos `0600`. Importar la aplicación no crea archivos, inicia servicios ni toca hardware. Guardar la base y la clave juntas en una copia sin cifrar elimina la utilidad del cifrado frente al robo de esa copia. La guía del servidor documenta el respaldo cifrado y la gestión de certificados.

Códigos habituales: `401` sesión inválida; `403` rol, dispositivo o consentimiento no permitido; `404` recurso no accesible (incluye pacientes ajenos); `409` conflicto/idempotencia o invitación consumida; `413` tamaño excesivo; `415` formato no admitido; `422` contrato inválido; `429` demasiados intentos; `503` almacenamiento/modelo temporalmente no disponible.

Pruebas de invariantes: [tests/test_api.py](../tests/test_api.py). La suite usa bases y claves temporales, sin red externa ni hardware.

## Experimentos ML autenticados

`GET /api/v1/ai/model` describe el modelo educativo de salud. `POST /api/v1/ai/classify` acepta exclusivamente `{source:"synthetic", features:{heart_rate_bpm,spo2_percent,body_temp_c,sample_age_seconds}}`; la antigüedad debe estar entre 0 y 10 segundos y los rangos deben pertenecer al soporte del dataset. `body_temp_c` proviene del CSV sintético y no se reemplaza por temperatura superficial del GY906.

`GET /api/v1/ai/fall/examples` entrega dos fixtures del conjunto público de prueba. `POST /api/v1/ai/fall-classify` acepta `{source:"dataset_replay",sample_rate_hz:200,record_complete:true,features:{magnitude_max_g,magnitude_min_g,magnitude_mean_g,magnitude_std_g,magnitude_p95_g,jerk_rms_g_s,orientation_change_deg}}`. Clasifica un archivo completo como `fall_record` o `adl_record`; no admite snapshots de 1 Hz ni telemetría de dispositivo y no crea alertas de pacientes. Las probabilidades no están calibradas para uso clínico. Hay límites por cuenta y concurrencia; muestras incompatibles son rechazadas, sin estimaciones fabricadas.
