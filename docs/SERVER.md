# Servidor, teléfonos y operación local

## 1. Una estructura y una entrada

Ejecutar todo desde la raíz del repositorio. `./carectl` es la CLI única. El código está en `server/`, `mobile/` y `firmware/esp32/`; ninguna carpeta de versión anterior es fuente activa. `.local/` contiene únicamente estado privado, respaldo histórico, claves y diagnósticos. No se distribuye.

Para el equipo ya preparado, seguir [PUESTA_EN_MARCHA.md](PUESTA_EN_MARCHA.md). El despliegue **Network**, del módem Huawei, se comprobó el 27 de octubre con `https://192.168.3.3:8443/api/v1`. La app código 17 del S22 completó entonces el recorrido Bluetooth–teléfono–HTTPS; su registro fechado no acredita una red o un APK posterior. El arranque `./tools/start_offline.sh --serial R5CT20D7X6D` prepara el S22; sin `--serial` conserva el teléfono principal configurado. La IP se detecta por DHCP y el certificado conserva la CA existente. La alternativa `--usb` prepara `127.0.0.1:19443` y exige mantener el cable. Ningún comando cambia la Wi-Fi o la VPN.

## 2. Instalación y arranque

Python 3.12 o posterior; las versiones exactas están en `pyproject.toml`. En una instalación nueva:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
./carectl init --host 192.168.3.3 --port 8443
./carectl doctor
./carectl start
./carectl status
```

En este equipo ya existe el entorno local y estado inicializado. Se utilizó `--system-site-packages` para reutilizar las dependencias instaladas; la guía anterior crea un entorno independiente para otras máquinas. `init` no reemplaza claves ni acepta silenciosamente un cambio de dirección o puerto.

`start` ejecuta en segundo plano y guarda PID y registro privado; `stop` comprueba la identidad del proceso y termina de forma ordenada. No configura arranque automático del sistema:

```bash
./carectl stop
./carectl start
```

Para ver el proceso directamente usar `./carectl serve` y detener con Ctrl+C. No iniciar otra copia mientras esté activo el proceso gestionado. `./carectl serve --docs` habilita OpenAPI solo durante esa ejecución; no es necesario en operación normal.

No usar el antiguo `uvicorn main:app` ni una API key compartida: ese servidor fue retirado. `start_server.sh` es únicamente un enlace al mismo comando `carectl serve`.

## 3. TLS, red y firewall

`init` genera CA local, certificado del servidor y clave AES-GCM bajo `.local/runtime`, con permisos privados. El certificado de servidor cubre la IP indicada, loopback y `caremonitor.local`; el nombre DNS solo funciona si tu red lo resuelve. No se usa un certificado autofirmado aceptado a ciegas: el APK incorpora la CA pública y verifica el nombre del servidor.

La CA dura un año y el certificado del servidor hasta 90 días. Revisar la fecha con `doctor`. Si cambia la IP, detener el proceso y ejecutar `./carectl prepare-network --host NUEVA_IP --port 8443`: reemite el certificado bajo la misma CA, conserva los SAN anteriores, las claves, las cuentas y las lecturas. Después ejecutar `./carectl start`. No regenera la CA ni cambia la red. Una CA vencida o sustituida requiere actualizar la confianza del APK; nunca desactivar la verificación para resolver errores.

Ese comando adapta únicamente el servidor. El teléfono también debe apuntar a la nueva dirección: prepararla en el APK antes del primer acceso, o cambiarla en Ajustes si ya hay sesión. En este equipo, `./tools/start_offline.sh` automatiza ambas partes en la red Network del módem Huawei. La herramienta exige que la laptop ya esté conectada a Network y se detiene si detecta otra SSID; no activa un perfil ni desconecta la VPN. El modo USB permite preparar el enlace local conservando la red actual. Si había una conexión guardada diferente, el acceso ofrece **Update my connection** para adoptar la preparada.

El proceso escucha en la IP LAN elegida, no en todas las interfaces. La política de firewall del equipo se mantiene: si bloquea el acceso desde el teléfono, permitir TCP 8443 **solo desde la subred LAN confiable** o desde la interfaz WireGuard. No abrir el puerto a todo Internet. Ejemplo Fedora, sustituyendo la subred por la real y revisando la zona adecuada:

```bash
sudo firewall-cmd --get-active-zones
sudo firewall-cmd --zone=home --add-rich-rule='rule family="ipv4" source address="192.168.3.0/24" port port="8443" protocol="tcp" accept'
```

El comando anterior es temporal; verificar antes de hacerlo permanente. La app no podrá comunicarse por una Wi-Fi con aislamiento de clientes, aunque ambos aparatos aparezcan conectados.

## 4. Instalar en los Samsung

El artefacto es `release/CareMonitor-1.0.0.apk`, revisión de interfaz código 19, compilada con API 37. `release/ANDROID_BUILD.json` identifica su hash; `ANDROID_UI_EVIDENCE.json` registra el equipo y el alcance de su instalación. El SM-S948B con Android 17 conserva su instalación anterior hasta volver a conectarse para actualizarlo. El recorrido Bluetooth–teléfono–servidor se comprobó el 27 de octubre con código 17 en el S22 SM-S901E / Android 13, mediante Network; no se atribuye automáticamente al APK nuevo.

1. Copiar el APK al teléfono e instalar desde una fuente local confiable. Autorizar temporalmente a esa aplicación de archivos para instalar APK. También puede usarse `adb install -r release/CareMonitor-1.0.0.apk` cuando USB debugging esté autorizado.
2. Si una instalación antigua tiene otro certificado, Android rechazará actualizarla. Conseguir la clave original o decidir conscientemente si se elimina esa instalación y sus datos locales. No se desinstala automáticamente nada.
3. Conectar a la Wi-Fi del servidor. La pantalla inicial usa la dirección HTTPS preparada en el APK (`ApiSession.DEFAULT_BASE`) o la última conexión guardada, sin mostrar un campo de servidor. Antes del primer acceso, el despliegue debe proporcionar una dirección alcanzable y cubierta por el certificado. En Android 17 conceder **red local** cuando se solicite. Tras iniciar sesión, cambiar la conexión en **Settings → Server connection** exige iniciar sesión en el nuevo servidor. Se puede volver a la conexión anterior si falla ese primer acceso.
4. Pulsar **Create an account**, elegir el rol y completar nombre, correo y contraseña en pasos separados. Usar cuentas ficticias, contraseña de doce a 128 caracteres y aceptación académica; no hay credenciales predeterminadas. La persona mayor recibe una guía para conectar el wearable y puede retomarla más tarde; el cuidador pasa a solicitar un vínculo.
5. Desde el adulto generar invitación de cuatro cifras. En el cuidador solicitar vínculo. Volver al adulto y aprobar la identidad correcta. Repetir con cada cuidador.
6. En el adulto abrir Bluetooth, dar permisos y seleccionar `ElderSensors-elder-demo-01`. Aceptar en Android cualquier solicitud del sistema y autorizar el wearable con su clave privada en la guía de CareMonitor. No se requiere confirmación Serial; una sola conexión activa recibe sensores.
7. Comprobar contador de mensajes, vigencia de la lectura, cola y confirmación del servidor. Los diagnósticos reales se identifican como `device_diagnostic` y no generan alertas fisiológicas.
8. En **cada teléfono**, dar permiso de notificaciones y pulsar **Enable local notifications**. Se mantiene una notificación visible con Detener. No basta con instalar el APK: hay que iniciar y permitir el canal.

**Uso diario:** Today prioriza pedir ayuda, recordatorios e indicadores para la persona mayor; el cuidador ve a la persona aprobada seleccionada y los avisos por revisar. Updates agrupa avisos, Care gestiona el círculo y los recordatorios y Settings contiene **Reading details**, conexión, privacidad y **Reduce motion**. Cada indicador abre su histórico. Las lecturas se actualizan en su lugar y los detalles técnicos no ocupan una tarjeta del inicio. Estas decisiones se comprueban con fixtures y no constituyen un estudio de usabilidad con mayores.

La ubicación y el sonido son opcionales, desactivados inicialmente y revocables. La casa se configura localmente; el GPS se solicita solo al activar la función y solo se envían puntos cuya incertidumbre queda completamente fuera del radio. No se utiliza Google Maps ni localización de red. El micrófono procesa intensidad en RAM, nunca conversaciones ni archivos. Usarlos únicamente dentro del alcance académico autorizado; no hacer pruebas de caídas ni medir salud de participantes.

## 5. Avisos sin nube

Este equipo contiene las cuentas, permisos, lecturas, avisos y recordatorios. El servidor crea una entrada privada por adulto/cuidador autorizado. El teléfono mantiene una espera HTTPS autenticada (`/notifications/wait`) y muestra avisos locales. Revalida sesión, revocación y membresía durante la espera. Los pendientes sobreviven a reinicios.

El canal se inicia con una acción visible y usa un foreground service `specialUse` con finalidad declarada. Si se detiene, queda recepción al abrir la app y un trabajo periódico de Android como respaldo; ese trabajo no entrega inmediatamente. Fuerza de cierre, reinicio, permisos, batería del fabricante y conectividad necesitan pruebas en cada teléfono. No se promete un aviso de emergencia con el teléfono apagado, sin red o con la app forzada a detenerse.

Varios teléfonos deben usar cuentas de cuidadores separadas. La revisión de una alerta es por cuidador; que uno la vea no la oculta a los demás. La notificación no sustituye el estado de lectura del buzón.

## 6. Fuera de casa: VPN propia

Una IP `192.168.*` no se alcanza desde datos móviles. Para que el módulo de ubicación y los cuidadores funcionen fuera de la LAN, se necesita una ruta privada de regreso a este equipo. Se incluye generación local de WireGuard, sin nube:

```bash
./carectl vpn-config --endpoint TU_IP_PUBLICA:51820 --peers 3
```

Se generan configuraciones **privadas** en `.local/wireguard/`. Nada se instala ni se activa automáticamente. `wg-caremonitor.conf` es del servidor y cada `phone-N.conf` corresponde a un único teléfono. No compartir claves entre personas ni incluirlas en un informe. Importar cada archivo localmente en un cliente WireGuard confiable; no subirlo a generadores QR web.

La persona que administre la red debe:

1. Instalar la configuración del servidor como interfaz WireGuard y autorizar ese servicio en el equipo.
2. Permitir y redirigir **solo UDP 51820** en el router al equipo; no publicar TCP 8443.
3. Permitir TCP 8443 desde la interfaz WireGuard en el firewall del servidor.
4. Verificar que la subred `10.77.0.0/24` no colisiona con otra red y que la IP LAN del servidor se mantiene.
5. Si la conexión tiene CGNAT o carece de IP pública entrante, gestionar una IP/ruta propia con el proveedor; esta preparación no instala un túnel de un tercero.

Los teléfonos enrutan únicamente la dirección del servidor y el extremo WireGuard, no toda su navegación. El endpoint HTTPS de CareMonitor puede seguir siendo `192.168.3.3:8443` dentro del túnel. Esta ruta está preparada mediante configuraciones, pero no se ha activado ni validado desde una red exterior.

## 7. Demostración verificable

Con una cuenta ficticia de adulto ya creada y cuidadores vinculados:

```bash
./carectl demo
```

La CLI solicita email y contraseña sin ponerlos en el historial de shell. Envía tres muestras **sintéticas**, con identificadores distintos, separadas por más de un segundo. La tercera debe crear el episodio educativo de oxígeno bajo; verificarlo en todos los cuidadores. No se modifica un sensor ni se induce una condición en una persona.

La evaluación de IA se ejecuta mediante las herramientas y endpoints del servidor, fuera de la navegación de cuidado. La confianza es una probabilidad de clase de un dataset simulado, no una probabilidad médica ni evidencia de exactitud sobre personas.

Para comprobar el ESP32 sin flashearlo:

```bash
./carectl firmware-check --port /dev/ttyUSB0 --seconds 20
```

El resultado conserva solo agregados de estados, validez y frecuencia. Ver `FIRMWARE.md` para compilación y límites.

## 8. Copias y recuperación

```bash
./carectl backup /ruta/privada/caremonitor.cmbackup
./carectl restore /ruta/privada/caremonitor.cmbackup --destination .local/restored
```

Se pide passphrase de al menos quince caracteres. La copia contiene base y clave de cifrado bajo cifrado autenticado; no contiene las claves TLS. Restaurar exige un destino vacío e invalida sesiones para no resucitar accesos revocados. Probar una restauración antes de depender del respaldo. Para arrancar el estado restaurado, generar TLS con `./carectl --data-dir .local/restored init --host IP` y usar la misma opción global en los demás comandos; actualizar la confianza móvil si cambia la CA.

## 9. Pruebas y reconstrucción

```bash
.venv/bin/python -m pytest tests
.venv/bin/python mobile/scripts/test_reading_summary.py
.venv/bin/python tools/train_models.py
.venv/bin/python mobile/scripts/build_apk.py --sdk "$HOME/Android/Sdk" --jdk /usr
.venv/bin/python tools/package_release.py
```

Mantener `.local/dev-signing.jks` para actualizar esta instalación de desarrollo. Antes de compilar, `mobile/app/src/main/assets/server_ca.crt` debe corresponder a la CA pública activa. No copiar una clave privada al APK. El manifiesto distingue compilación, lógica, fixtures Android, instalación y campañas físicas fechadas. El empaquetador rechaza un preview fallido o cuyos hashes no coincidan con las fuentes del APK; no convierte ese resultado en aceptación de sensores o entrega a cuidadores.
