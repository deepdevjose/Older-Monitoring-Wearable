# Entrega 1.0.0 y aceptación

Estado de la revisión de interfaz del **29 de octubre de 2026**, código **19**. Esta es la única base activa. La identidad del APK compilado e instalado se consulta en `release/ANDROID_BUILD.json` y `ANDROID_UI_EVIDENCE.json`; cada comprobación mantiene su fecha, dispositivo y alcance. Las pruebas del código 16 y del recorrido Bluetooth del código 17 se conservan como antecedentes separados.

## Interacción por rol

- **Today**, para la persona mayor: conexión del wearable expresada con texto, pedir ayuda, próximo recordatorio e indicadores con acceso a históricos.
- **Today**, para el cuidador: persona aprobada seleccionada, avisos que necesitan revisión, indicadores, plan y ubicación compartida cuando está autorizada.
- **Updates** reúne avisos y recordatorios. **Care** contiene vínculos, aprobaciones y plan de cuidado. **Settings** agrupa configuración y acceso a detalles.
- **Recent readings** deja de ser una tarjeta del inicio. **Settings → Reading details** muestra lecturas guardadas, antigüedad, procedencia y estado del wearable.
- Seis métricas abren históricos **Day / Week / Month** para ambos roles. Agregados horarios cifrados, límites exactos, huecos para observaciones inválidas y retención de treinta días.
- Texto amplio, controles grandes y navegación estable. Las lecturas y su antigüedad se actualizan sin reconstruir toda la pantalla. Las transiciones breves y el pulso limitado de conexión respetan **Reduce motion**, animaciones del sistema y ahorro de batería; no interpolan mediciones.
- Registro progresivo con rol explícito. Solo la persona mayor conecta sensores. La dirección del servidor se configura tras iniciar sesión; cambiarla requiere autenticarse en el nuevo destino.

El fundamento de diseño combina [W3C WAI para usuarios mayores](https://www.w3.org/WAI/older-users/developing/) y [Apple Human Interface Guidelines: Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility): texto ampliable, controles legibles, navegación predecible y movimiento que pueda reducirse. Aplicamos esos principios a las tareas de cada rol en Android. Esto no prueba que todas las personas mayores compartan un modelo mental, ni acredita conformidad completa con una norma, un estudio de usabilidad o evaluación manual con TalkBack.

## Capacidades del sistema

Backend local HTTPS con roles, vínculos aprobados, revocación y cifrado; snapshots idempotentes y cola privada; avisos persistentes para cada miembro autorizado; recordatorios y confirmación declarada de toma; ubicación opcional fuera de casa y audio procesado en RAM. El canal de avisos usa HTTPS long polling y un servicio visible, sin proveedor de nube.

**Settings → Learning insights** muestra clasificación y confianza del modelo sintético, con ejercicios cifrados separados de las lecturas físicas. La persona mayor añade ejemplos; el cuidador autorizado los consulta. La evaluación de dos clasificadores y el replay SisFall se mantienen fuera de una afirmación de detección clínica. CLI local para arranque, diagnóstico, respaldo cifrado y preparación de WireGuard.

## Evidencia vigente y antecedentes

El APK **1.0.0 / código 19** quedó instalado conservando datos en el S22 SM-S901E / Android 13. SHA-256: `afdc41baa3938caab845839f5f636e4960f08f3921f125559e6f8e6d6425ba2a`. La revisión pasó **229 pruebas Python**, **3.240 comprobaciones JVM**, **24 verificaciones HTTPS loopback**, **878 comprobaciones nativas** de registro/diseño y **7.273 comprobaciones de preview**, con 48 capturas de ambos roles y texto al 100 %/200 %. La campaña nativa conservó preferencias, sesión, cola y permisos y retiró su paquete auxiliar. Las imágenes públicas de `docs/ui-preview/` son fixtures ficticios.

`release/VALIDATION.json` registra las pruebas Python/JVM y hashes exactos. `ANDROID_BUILD.json` identifica versión, firma y APK; `ANDROID_UI_EVIDENCE.json` registra instalación y comprobaciones de ese APK. `ANDROID_HISTORY_UI_EVIDENCE.json` ejecuta Java y recursos de producción dentro de un paquete aislado, sin permisos ni servicios, con datos ficticios de ambos roles y texto al 100 %/200 %. Su alcance no incluye cuentas, Bluetooth ni servidor reales. `MANIFEST.json` y `SHA256SUMS` identifican la entrega distribuida.

`ANDROID_CODE16_BASELINE.json` conserva la campaña del S22 SM-S901E / Android 13 con 759 comprobaciones nativas y HTTPS por Wi-Fi. `ANDROID_ONBOARDING_BASELINE.json` conserva los registros anteriores del SM-S948B. Sus resultados no se trasladan a versiones posteriores.

Las pruebas físicas del **27 de octubre** están publicadas por separado:

- `BT_PROTOCOL_EVIDENCE.json`: 207 comprobaciones con autenticación HMAC mutua, clave incorrecta y prueba antigua rechazadas, cierre de una conexión no autenticada y reconexión tras liberar el enlace. Una radio Linux independiente no pudo conectarse mientras el Samsung conservó el enlace autenticado.
- `APP_BLUETOOTH_SERVER_EVIDENCE.json`: app de producción **código 17**, conexión iniciada desde la UI normal, 138 muestras recibidas, cero pendientes y confirmación del servidor. Las lecturas almacenadas pasaron de 156 a 295, sin crear una cuenta ni modificar la sesión. Se conservaron solo agregados de evidencia.

Estas campañas acreditan sus versiones y condiciones; no sustituyen la comprobación del recorrido completo después de una actualización posterior ni la entrega a cuidadores con pantalla bloqueada. El flasher no se ejecuta para un cambio exclusivamente de interfaz.

`FIRMWARE_NETWORK_EVIDENCE.json` conserva la puesta en marcha anterior del mismo día: aplicación escrita en `0x10000`, respaldo privado completo, bootloader y tabla conservados, Wi-Fi/NTP y 63 JSON válidos a 0,999 Hz con MPU6050 y GY906 online. Esa medición pertenece al binario identificado en su registro y precede al cambio de autenticación Bluetooth. El óptico indicó ausencia de contacto; no se atribuye precisión fisiológica.

## Red y operación

El despliegue comprobado el 27 de octubre utilizó el módem Huawei **Network**: laptop `192.168.3.3`, S22 `192.168.3.5`, ESP32 `192.168.3.8`, puerta de enlace `192.168.3.1` y HTTPS `192.168.3.3:8443`. Es un registro fechado, no una afirmación de conectividad actual. Una red distinta o una VPN puede impedir alcanzar esa IP.

[La guía de arranque](PUESTA_EN_MARCHA.md) detecta la dirección de la LAN elegida, conserva CA, claves, cuentas y lecturas y ofrece USB como alternativa. Ningún comando cambia automáticamente Wi-Fi ni VPN. La contraseña real de Network y la clave privada del wearable permanecen fuera de Git y de los paquetes. El teléfono principal se actualiza únicamente al conectarlo y ejecutar el arranque correspondiente.

## Aceptación por comprobar en su contexto

1. Uso manual con TalkBack y evaluación de las tareas con adultos mayores/cuidadores dentro de un procedimiento académico autorizado.
2. Recorrido físico completo del APK identificado como actual, incluida recuperación tras cortar Bluetooth y Wi-Fi, sin atribuirle automáticamente la campaña del código 17.
3. Canal de avisos en cada teléfono con pantalla bloqueada, varios cuidadores, permisos revocados, logout, batería, reinicio y cierre forzado.
4. Ubicación fuera del radio, ausencia de envío dentro, revocación y apagado. No recolectar salud de participantes ni ensayar caídas.
5. VPN propia y acceso desde otra red si se requiere uso exterior; generar archivos WireGuard no establece conectividad exterior.
6. Estabilidad y calibración de sensores dentro del alcance autorizado. Una respuesta online no valida señal fisiológica.

Presión arterial, diagnóstico de deshidratación, temperatura central, identificación automática de accidentes y detección online validada de caídas no se atribuyen al hardware disponible. Véase `SECURITY_ETHICS.md`.
