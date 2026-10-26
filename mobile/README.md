# CareMonitor Android 1.0.0

Esta carpeta contiene **la única fuente Android** del proyecto. El APK generado se entrega en `../release/CareMonitor-1.0.0.apk`; no contiene un servidor, credenciales compartidas ni un firmware alternativo. Aplicación nativa Java, paquete `org.caremonitor.demo`, versión 1.0.0/código 11, Android mínimo 7/API 24, objetivo Android 17/API 37.

## Preparar el teléfono

1. Inicia el servidor siguiendo `../docs/SERVER.md` y conecta el Samsung y la computadora a la misma red Wi-Fi accesible. El modo aislamiento de clientes de algunos routers impide la conexión entre dispositivos.
2. Instala el APK. Desde una computadora con Android Platform Tools: `adb install ../release/CareMonitor-1.0.0.apk`. También puedes copiar el archivo al teléfono y autorizar temporalmente a ese gestor de archivos para instalarlo.
3. Abre CareMonitor. Escribe la dirección HTTPS que muestra la CLI, terminada en `/api/v1`. En esta preparación: `https://192.168.3.3:8443/api/v1`. La dirección IP puede cambiar; debe aparecer en el SAN del certificado del servidor.
4. Crea una cuenta ficticia para la **persona mayor**, con correo de prueba, contraseña de al menos 12 caracteres y aceptación académica. Concede notificaciones, dispositivos cercanos y el permiso de red local cuando se soliciten. Android 17 bloquea el acceso LAN sin este permiso.
5. Abre los ajustes Bluetooth, empareja `ElderSensors-elder-demo-01` y vuelve a **Hoy → Conectar mi wearable → Buscar dispositivos Bluetooth**. Selecciónalo. La recepción y la cantidad de muestras pendientes aparecen en la pantalla y en una notificación persistente con **Stop**.
6. Crea cuentas ficticias de **cuidador** en otros teléfonos. La persona mayor genera un código de 4 dígitos en **Cuidado**. Cada cuidador solicita acceso con ese código y la persona mayor revisa y aprueba su identidad. Un código no concede acceso directamente. Para añadir otro cuidador se genera una nueva invitación.
7. En **Avisos** o **Cuenta**, pulsa **Activar avisos locales**. Este canal mantiene una conexión HTTPS al servidor de tu computadora y muestra una notificación permanente con **Detener**. No necesita servicios de notificación externos. Cada teléfono debe activarlo individualmente.
8. El cuidador selecciona a la persona vinculada y crea recordatorios desde **Cuidado**. La persona mayor confirma la toma cuando el aviso ya venció. La confirmación registra una declaración; no verifica ingestión. El botón de agua crea un recordatorio educativo, sin detectar deshidratación ni recomendar cantidades.

No hay teléfono conectado durante esta preparación y no se afirma validación en un Galaxy S26 Ultra/One UI 9. La compatibilidad se ha preparado y compilado contra API 37. Emparejamiento, permisos del fabricante, legibilidad con TalkBack/texto grande, comportamiento con pantalla bloqueada y entrega en segundo plano requieren comprobarse en el dispositivo.

## Qué hace y qué limita

- Cuatro áreas: Hoy, Avisos, Cuidado y Cuenta. Controles de al menos 56 dp, texto principal de 18 sp, desplazamiento y nombres claros. La interfaz sigue un lenguaje sobrio de tarjetas, tipografía amplia y controles azules.
- El backend autentica, autoriza, interpreta y almacena. El teléfono conserva indicadores de validez y transmite los JSON diagnósticos del firmware. La interfaz nunca convierte un valor inválido en normal.
- La cola usa UUID por muestra, persistido antes de enviar. Un reintento conserva el UUID y el servidor deduplica. Es una cola AES-GCM en almacenamiento privado con clave AndroidKeyStore; capacidad 200 muestras. Si se llena, rechaza las nuevas y muestra la pérdida. No garantiza una historia completa tras una desconexión larga.
- La cola está ligada a dirección del servidor e identidad de la persona mayor. Cambiar de cuenta o servidor bloquea su entrega hasta volver a su propietario o borrarla expresamente. Cerrar sesión detiene los servicios y conserva la cola privada. La sesión vencida pausa el envío.
- El relay de sensores y las demás consultas HTTPS usan la red predeterminada que elige Android: Wi-Fi, datos móviles o una VPN configurada. No se fija una interfaz física ni se evita la VPN. Para acceder fuera de casa, el túnel privado debe incluir la dirección LAN del servidor en sus rutas permitidas. El envío de muestras a aproximadamente 1 Hz puede consumir datos móviles; se aplican las restricciones de red que configure el usuario en Android. No se instala un certificado global ni se desactiva la verificación de nombres.
- Los cuidadores tienen buzones independientes en el servidor. El canal local, activado por el usuario, usa espera larga HTTPS autenticada de hasta 20 s, con heartbeat, reconexión gradual y una notificación persistente con Detener. Funciona con el servidor propio: no usa infraestructura externa de push ni cuentas de terceros. Al cerrar sesión, vencer la sesión o revocar permisos, se detiene. Si no está activo, la app consulta aproximadamente cada 15 s abierta y Android JobScheduler sirve de respaldo con un mínimo de 15 minutos. Batería, red y cierre forzado pueden retrasar o impedir cualquier modalidad. **No hay entrega garantizada ni servicio de emergencia.**
- La app marca ventanas y notificaciones como privadas. Sesiones, coordenadas de casa, consentimientos locales, cursores del buzón y lecturas pendientes se cifran. Las copias de seguridad Android de esta app están desactivadas. No se guardan contraseñas.
- La navegación cotidiana no incluye insignias de demo ni escenarios precargados. Los experimentos de salud sintética y de archivos SisFall permanecen en las herramientas y endpoints del servidor, fuera de la interfaz de cuidado. No se presentan como detección clínica en el wearable. Cuando un registro procede de una prueba, la app conserva su procedencia como «Datos de prueba».
- Presión arterial y deshidratación no están disponibles como diagnósticos. La luz se muestra como ADC sin calibrar; no demuestra por sí sola que el reloj esté puesto. El GY906 mide superficie, no temperatura corporal central. El usuario puede confirmar un aviso de caída o ayuda; no se afirma detección automática validada.

## Ubicación y micrófono

Se activan expresamente desde **Cuenta**, después del consentimiento y del permiso Android. Ambos muestran un servicio en primer plano con una acción **Detener** y paran al cerrar sesión. No solicitan ubicación permanente en segundo plano ni comienzan automáticamente al reiniciar.

Para ubicación, configura coordenadas ficticias de casa y un radio de 100 a 10 000 m. Esas coordenadas se cifran y permanecen en el teléfono. La app solo envía un punto cuando su distancia menos la incertidumbre de precisión supera por completo el radio; rechaza puntos antiguos, simulados por el proveedor Android o con precisión peor de 100 m. Envía como máximo aproximadamente un punto por minuto. Se usa exclusivamente `LocationManager.GPS_PROVIDER`, sin proveedor de red, geocodificación ni SDK de ubicación externo. El cuidador ve coordenadas en una ventana nativa, sin abrir servicios de mapas. El sensor es el teléfono: dejarlo en otro lugar invalida cualquier interpretación de presencia del usuario. El servidor debe seguir siendo accesible fuera de la Wi-Fi local para recibir esos puntos; esta entrega no se ha comprobado físicamente.

El micrófono calcula RMS en una muestra breve de memoria, borra el búfer y nunca escribe archivos ni transmite sonido. Un sonido fuerte solo propone una pregunta. Requiere confirmación humana antes de enviar un evento educativo. No distingue accidentes, voces ni caídas. Los consentimientos del servidor se comprueban aproximadamente cada minuto; si están revocados el servicio para, y si no se pueden renovar durante cinco minutos también se detiene.

Revocar cualquiera de estos consentimientos detiene ambos servicios locales de forma conservadora. Para volver a usar la otra función hay que activarla explícitamente. Revocar el acceso de un cuidador impide consultas futuras, pero no puede retirar información que esa persona ya haya observado.

## Compilar y comprobar

Requisitos: JDK con `javac --release 8`, SDK Android con plataforma `android-37.0`, Build Tools `36.0.0`. No se descargan bibliotecas ni tipografías de red para la interfaz. El APK no incorpora servicios de terceros, analítica, publicidad ni SDK de notificación externo.

```bash
python3 mobile/scripts/test_reading_summary.py --jdk /usr
python3 mobile/scripts/build_apk.py \
  --sdk /home/deepdevjose/Android/Sdk \
  --jdk /usr \
  --keystore .local/dev-signing.jks
```

Ejecutar desde la raíz del repositorio. El builder reutiliza `mobile/build/current`, compila recursos y Java, genera DEX, alinea y firma el APK y verifica firma, metadatos, alineación y estructura ZIP. Deja `mobile/artifacts/BUILD_EVIDENCE.json` con hashes exactos y flags honestos de pruebas físicas. El empaquetador de la raíz entrega la versión en `release/`; los directorios generados se pueden borrar y regenerar. Las suites JVM verifican utilidades de framing/frescura, política de relay y la frontera GPS/aislamiento de cuentas; **no sustituyen pruebas Android, Keystore ni Bluetooth reales**.

La clave `.local/dev-signing.jks` es de desarrollo, no debe publicarse. Se ha generado una nueva firma; actualizar una instalación anterior exige su clave original. Desinstalar una aplicación previa borra su almacenamiento privado, incluida la cola. No uses desinstalación automática como solución de instalación.

Antes de compilar para otro despliegue, copia su **certificado público de CA**, nunca la clave privada, a `app/src/main/assets/server_ca.crt` y recompila. Regenerar el servidor con otra CA exige reinstalar un APK con esa CA. El certificado del servidor debe incluir el hostname/IP utilizado en su SAN. No se aceptan certificados arbitrarios, nombres distintos ni HTTP sin TLS.

## Permisos y canal de avisos autohospedado

La aplicación declara y solicita `ACCESS_LOCAL_NETWORK` antes de conectar con el servidor en Android 17. Denegar o revocar este permiso pausa las conexiones y conserva la cola cifrada. Se puede solicitar de nuevo en Cuenta; no se evita la decisión del usuario. Consulta la [documentación oficial del permiso de red local](https://developer.android.com/privacy-and-security/local-network-permission).

El servicio `NotificationChannelService` tiene tipo `specialUse`, permiso `FOREGROUND_SERVICE_SPECIAL_USE` y la propiedad `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` que explica la vigilancia del buzón privado habilitada por el usuario. Inicia solo desde la app visible, mantiene una notificación con parada y no se reinicia automáticamente después de un cierre forzado o reinicio. No se presenta como mensajería SMS, servicio médico validado ni servicio exento del sistema. El uso del tipo especial sigue el contrato de [tipos de servicio en primer plano de Android](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use); distribución por tiendas exigiría la evaluación correspondiente, fuera de este APK académico instalado directamente.

El servidor de la computadora debe ser alcanzable desde cada teléfono. Fuera de la misma LAN se necesita una red privada/VPN administrada por el proyecto y un certificado que corresponda a la dirección utilizada. Cambiar de red, suspender el servidor, restricciones del fabricante y batería pueden interrumpir el canal; se muestran reconexiones y los avisos permanecen en el buzón servidor. No se ha comprobado entrega física con pantalla bloqueada.
