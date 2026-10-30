# Arquitectura y conexiones actuales

La fuente activa es `firmware/esp32`, `mobile/app` y `server/caremonitor`. Los diagramas editables están en `docs/report/sections/diagram_{system,hardware,mobile}.tex`; el atlas del informe incluye actores, navegación, permisos, clases Android, componentes y clases del servidor, dominios SQLite y autenticación.

## Recorrido de datos

1. MAX30102 (0x57), GY906/MLX90614 (0x5A) y MPU6050 (0x68) comparten I²C/SMBus: SDA GPIO21 y SCL GPIO22, a 100 kHz. La alimentación depende del breakout real; lógica y pull-ups compatibles con 3,3 V. El número de pin no certifica la posición del header de cada revisión KEYES.
2. ESP32 transmite JSON delimitado por nueva línea mediante Bluetooth Classic SPP/RFCOMM. La autenticación CM1 usa desafío/respuesta HMAC-SHA256 y clave privada de instalación; un único cliente autenticado. Este firmware no anuncia BLE.
3. Android recibe y valida, asigna una identidad estable y conserva una cola cifrada con Android Keystore/AES-GCM. ServerRelay sube por HTTPS REST/JSON y reintenta con idempotencia. La app distingue validez óptica, pérdida de contacto y conexión.
4. FastAPI/Pydantic valida usuario, rol, consentimiento, origen y recurso. SQLite con WAL conserva registros; Vault cifra campos sensibles con AES-256-GCM. Los tokens aleatorios se verifican por SHA-256 y las contraseñas usan Argon2id. No hay Firebase ni servicios de nube.
5. El cuidador solicita un vínculo y el adulto lo aprueba. La app consulta eventos por una conexión HTTPS de larga espera y crea notificaciones Android; no usa un proveedor push externo. La tarjeta nativa puede retirarse sin borrar el evento ni marcarlo revisado.

## Ensamble

| Módulo | Conexión de señal | Uso y límite |
|---|---|---|
| MAX30102 | SDA21/SCL22; 0x57 | Estimaciones de pulso/SpO₂ con indicadores de contacto y validez. No certifica precisión clínica. |
| GY906 | SDA21/SCL22; 0x5A | Temperatura superficial/objeto y ambiente local del sensor; no temperatura central. |
| MPU6050 | SDA21/SCL22; 0x68 | Aceleración y giro; no implica un clasificador de caídas ni contador de pasos activo. |
| Buzzer | Señal GPIO25; tierra común | Inicializado LOW. Requiere driver/alimentación apropiados y lógica de alarma para actuar como respaldo. |

Se retiraron micrófono y luz del montaje y del firmware fuente actual; sus referencias analógicas se eliminaron y el código compiló. No se flasheó en esta revisión. Campos antiguos del binario instalado y contratos de API históricos se conservan como compatibilidad, no como sensores actuales.

## Teléfono y límites

Today ofrece cinco métricas actuales. Settings → Reading details contiene diagnóstico y lecturas recientes; cada tarjeta abre historia. El mayor prioriza ayuda y su plan; el cuidador, la persona seleccionada y avisos por revisar. GPS requiere permiso y consentimiento. La notificación sonora depende de canal, permiso, volumen y estado Android. Los sensores no miden deshidratación: la app ofrece recordatorios. Ausencia de lecturas no demuestra por sí sola que el wearable no se lleve puesto.

## Ejecución comprobada

El 30 de octubre, S22 / Android 13, código 20 instalado: hash y cliente HTTPS de solo lectura comprobados. Una app aislada con exactamente los Java/recursos de producción completó 119 controles usando cuentas reales controladas, SPP físico, HTTPS por USB, 76 registros retenidos y cero pendientes. El informe conserva 32 capturas nativas sin alterar. Esta ruta no acredita Wi-Fi, dos teléfonos simultáneos, alarmas audibles o detección automática de caídas. `release/DEVICE_SYSTEM_USB_EVIDENCE.json` conserva tiempos, muestras y procedencia; `DEVICE_SYSTEM_USB_CAPTURE_EVIDENCE.json`, la captura posterior en inglés.

La línea base notificó 21/30 por el límite de tarjetas del S22. Tras limitar las tarjetas de eventos a doce sin borrar el buzón durable, dos campañas posteriores notificaron 30/30. Mejoró la completitud, no la latencia de los casos exitosos. Los resultados de otros APK permanecen identificados por versión y fecha.
