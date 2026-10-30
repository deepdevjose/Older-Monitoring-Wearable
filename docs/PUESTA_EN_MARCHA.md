# Poner en marcha CareMonitor y usar cada rol

**Preparado el 30 de octubre de 2026 para la nueva red «Network», sin acceso a internet.** La computadora se conserva en su red con internet; Network tiene la contraseña actualizada y la conexión automática desactivada. Conéctala manualmente solo cuando vayas a hacer la prueba. Las credenciales quedan en la configuración privada y no se publican aquí.

El servidor, la app y sus herramientas ya están instalados: el arranque no descarga dependencias. El teléfono y esta computadora deben compartir Network para comunicarse por Wi-Fi; internet no es necesario para la API local. La herramienta de arranque no cambia la Wi-Fi ni desconecta la VPN.

## Arranque rápido cuando estés listo

1. **Conecta y desbloquea el Galaxy S22 por USB.** Acepta «Permitir depuración USB» si Android lo solicita. Deja la ESP32 alimentada.
2. **Conecta el S22 a Network**, con la contraseña que proporcionaste. Si Android avisa que no tiene internet, elige mantener la conexión. Esta contraseña es la de Wi-Fi, no la de tu cuenta de CareMonitor.
3. **Conecta entonces la computadora a Network**, desde el selector de Wi-Fi o con el siguiente comando. La contraseña ya está guardada; este paso cambia de red y puede interrumpir el acceso a internet de la computadora:

   ```bash
   nmcli --wait 45 connection up Network ifname wlp0s20f3
   ```

4. **Abre una terminal y ejecuta:**

   ```bash
   cd "/home/deepdevjose/Documents/GitHub Repositories/Older-Monitoring-Wearable"
   ./tools/start_offline.sh --serial R5CT20D7X6D
   ./carectl status
   ```

   No vuelvas a instalar Python, paquetes ni el SDK. No ejecutes `carectl init`: la base, cuentas, claves y certificados existentes se conservan. El arranque detecta la nueva IP, adapta el certificado bajo la misma CA, prepara e instala el APK si su dirección cambió y levanta HTTPS en el puerto **8443**. Todo se hace con las herramientas locales, sin desinstalar la app ni borrar sus datos.

5. **Copia la API que imprima el comando**, con este formato:

   ```text
   https://IP_ACTUAL_DE_LA_LAPTOP:8443/api/v1
   ```

   En `./carectl status` debe aparecer **`"reachable": true`**. La IP depende de Network; no utilices la dirección antigua `192.168.3.3` por costumbre.
6. **Abre CareMonitor.** Si estás en el acceso y aparece **Update my connection**, púlsalo. Si tienes una sesión abierta con la dirección anterior, abre **Settings → Server connection**, introduce la API impresa y vuelve a iniciar sesión con tu cuenta existente. Si tienes muestras pendientes en el destino anterior, consulta **«2. Retomar con tu teléfono principal»** antes de cambiarlo; no borres la cola.
7. **Desde la cuenta de persona mayor**, retoma **Connect my wearable**. Comprueba que aumentan las muestras recibidas y que se vacían los pendientes con confirmación del servidor. El apartado **«5. Emparejar la ESP32 por Bluetooth»** detalla el emparejamiento y la clave privada si es la primera conexión.

Después de preparar el APK puedes retirar el USB del Samsung: el envío al servidor usa Network. La ESP32 debe seguir encendida y el servidor abierto en esta computadora. No necesitas consultar al asistente para ejecutar estos pasos.

Si la VPN impide acceder a la LAN, permite el tráfico local en ella o páusala manualmente para la prueba. Si los equipos del punto de acceso están aislados y no puedes modificarlo, utiliza **«3. Alternativa USB»**. Al preparar esta guía, firewalld ya permitía TCP 8443 en la zona de Wi-Fi; no se modificó su política.

**Reloj sin internet:** comprueba la fecha y hora de la computadora y del Samsung antes de cambiar de red. El servidor usa certificados HTTPS y necesita una hora correcta. La ESP32 intenta sincronizar con un NTP público y no podrá hacerlo en una red sin internet; cuando su mensaje no incluye fecha, la app añade la hora de recepción del teléfono. El enlace de sensores es Bluetooth y no requiere reflashear la placa ni conectarla a Wi-Fi para esta prueba. Las credenciales instaladas en la placa corresponden al montaje anterior; no se ha reflasheado con la nueva contraseña.

## Identificar la entrega y su conexión

La revisión actual es **1.0.0 / código 20**, instalada en el S22 SM-S901E / Android 13 con hash y acceso HTTPS comprobados. Pasó 7.073 controles aislados de preview y 119 comprobaciones del sistema real con producción Java/recursos en otro paquete. La sesión existente de la app principal se conservó; su onboarding no se volvió a ejecutar. Los registros están en `release/ANDROID_BUILD.json`, `ANDROID_UI_EVIDENCE.json`, `ANDROID_HISTORY_UI_EVIDENCE.json` y `DEVICE_SYSTEM_USB_EVIDENCE.json`. Para actualizar, conserva datos y firma: no desinstales ni vuelvas a crear una cuenta existente.

### Antecedentes comprobados el 27 de octubre de 2026

- Servidor HTTPS: `https://192.168.3.3:8443/api/v1`, escuchando en la IP de esta laptop. Cuentas, vínculos, claves y lecturas existentes conservados.
- S22 SM-S901E: la campaña código **16** comprobó la dirección LAN preparada y HTTPS real con el cliente instalado. El servidor rechazó un registro deliberadamente vacío con HTTP 422, sin crear cuentas: el endpoint es alcanzable.
- S22 y laptop se alcanzaron por Wi-Fi: tres pings, sin pérdida. La petición HTTPS usó la IP LAN y verificó la CA original y el nombre del certificado.
- El teléfono principal SM-S948B se actualizará cuando vuelva a conectarse. El arranque conserva su selección predeterminada; `--serial` permite usar temporalmente el S22 sin sobrescribirla.
- ESP32 conectada a Network como `192.168.3.8`, con NTP sincronizado. La última comprobación recibió 63 JSON válidos a 0,999 Hz: MPU6050 y GY906 online; óptico sin contacto. Respaldo privado de flash conservado; credenciales fuera de los archivos públicos.
- La campaña posterior **código 17** completó la conexión iniciada desde la app: 138 muestras, cero pendientes y confirmación del servidor. La prueba de protocolo rechazó una segunda radio mientras el Samsung mantuvo el enlace autenticado. Son pruebas fechadas de esas versiones, no una garantía de la conectividad actual.
- Las pruebas de interfaz emplean perfiles ficticios y no crean cuentas. Los datos de sensores son diagnósticos, no mediciones clínicas.

```text
ESP32 ── Bluetooth SPP ──► teléfono ── Wi-Fi Network ──► laptop HTTPS
  └── Wi-Fi 2,4 GHz: NTP con internet                 IP anterior: 192.168.3.3:8443
```

Este esquema y sus direcciones describen la comprobación histórica del 27 de octubre. La red actual se prepara con la IP que imprima el arranque. Que el teléfono llegue al servidor no acredita que la placa se haya asociado a Wi-Fi ni que todos sus sensores estén online.

## 1. Arrancar con el S22 de prueba

Desde la raíz del repositorio:

```bash
./tools/start_offline.sh --check --serial R5CT20D7X6D
./tools/start_offline.sh --serial R5CT20D7X6D
./carectl status
```

El S22 debe estar conectado por USB y autorizado durante la preparación para comprobar o instalar el APK. Después, la comunicación con el servidor utiliza **Wi-Fi**, por lo que no necesita el cable; la ESP32 debe seguir alimentada. Para este modo, la laptop y el Samsung ya deben estar conectados a Network. El comando no hace ese cambio por ti.

La opción `--check` no cambia servidor, red ni APK. El arranque completo detecta la IP Wi-Fi por DHCP, verifica el APK instalado, adapta el certificado bajo la misma CA y arranca el servidor local. No descarga dependencias. Omite reinstalar si el teléfono ya tiene exactamente el APK preparado. No desinstala aplicaciones ni borra cuentas.

En la comprobación de Network, el firewall existente ya permitía el puerto 8443. El arranque reconoce puertos o rangos TCP ya autorizados y conserva esa política. Si otra configuración lo bloquea, propone una regla temporal limitada a la subred; no desactiva el firewall.

La IP puede cambiar por DHCP. Usa siempre la API que imprima el arranque; no copies una dirección histórica si la laptop recibe otra. No ejecutes `init` para cambiar de red: conserva `.local/`, la base y las claves.

## 2. Retomar con tu teléfono principal

Conecta y desbloquea el SM-S948B, autoriza depuración y ejecuta:

```bash
./tools/start_offline.sh
./carectl status
```

Sin `--serial`, se utiliza el teléfono principal guardado en la configuración privada. El comando prepara su actualización conservando datos. En una instalación sin sesión, **Update my connection** adopta la dirección preparada cuando hay una anterior guardada.

Si ya hay una sesión asociada al antiguo enlace USB, cambia la API desde **Settings → Server connection** a la dirección LAN impresa y vuelve a iniciar sesión con la cuenta existente. No vuelvas a registrarla. Cambiar de dirección exige autenticación; los tokens y la cola no se trasladan silenciosamente a otro destino.

Antes de cambiar la dirección de una sesión con capturas pendientes, termina sus envíos en el destino original y pulsa **Stop wearable monitoring**. Si todavía quedan muestras de USB, utiliza el modo del paso 3 para terminar de enviarlas; luego vuelve al arranque Wi-Fi y reanuda la conexión del wearable. No borres la cola para resolver un cambio de red.

En **Today**, toca una métrica para abrir **Day / Week / Month**, también si aún no tiene un valor actual. **Settings → Reading details** contiene las lecturas guardadas, origen, antigüedad y estado del wearable; la tarjeta técnica Recent readings ya no ocupa el inicio. **Settings → Reduce motion** desactiva las transiciones. En **Settings → Learning insights**, explora clases y confianza usando exclusivamente muestras sintéticas. El cuidador consulta a la persona aprobada seleccionada y no añade ejercicios en su nombre.

## 3. Alternativa USB

Si necesitas aislar un problema de Wi-Fi, conserva el cable autorizado y ejecuta:

```bash
# S22 de prueba:
./tools/start_offline.sh --usb --serial R5CT20D7X6D

# O teléfono principal:
./tools/start_offline.sh --usb
```

Este modo cambia el servidor a `https://127.0.0.1:19443/api/v1` y establece `adb reverse` para ese teléfono. La app conserva los datos, pero cambiar la dirección de una sesión requiere volver a autenticarla. El cable debe permanecer conectado; este modo no permite acceso inalámbrico a otros cuidadores.

Para regresar al modo Wi-Fi, conecta ambos equipos a Network, ejecuta el arranque sin `--usb` y adopta la API LAN impresa. Ninguno de estos comandos cambia Wi-Fi ni VPN. Los perfiles Network y Test quedan sin conexión automática; se activan solo cuando los eliges.

## 4. Crear tu cuenta y comprobar el acceso desde el teléfono

1. Abre **CareMonitor** en el Samsung.
2. Pulsa **Create an account**.
3. Elige tu rol:
   - **I'm the older adult:** esta cuenta conecta la ESP32 y comparte sus lecturas con las personas que apruebe.
   - **I'm a caregiver:** esta cuenta solicita acceso a una persona mayor y consulta lo que esa persona autorice.
4. Pulsa **Continue** y completa tu nombre, correo y contraseña en los pasos guiados. La contraseña debe tener entre 12 y 128 caracteres. Para la prueba académica, utiliza una identidad de prueba y la aceptación que presenta la app. No hay cuentas ni contraseñas predeterminadas.
5. Pulsa **Create my account**. Si ya tenías cuenta en este servidor, utiliza **Sign in** con tus credenciales existentes.

Poder crear la cuenta o iniciar sesión comprueba la comunicación **Samsung → HTTPS → servidor**, además del funcionamiento local del servidor.

En Android, concede **red local** cuando se solicite. Si lo rechazaste, revisa los permisos de CareMonitor en los ajustes del Samsung y vuelve a intentarlo.

La dirección del servidor se administra después de iniciar sesión en **Settings → Server connection**. Debe coincidir con la API que imprimió el comando de arranque, incluida la terminación `/api/v1`. Cambiar de servidor cierra la sesión y requiere autenticarte en el destino; no mezcla cuentas ni colas de servidores distintos.

**Si cambió la IP y no puedes iniciar sesión:** cuando la conexión guardada difiere de la incluida en el APK actualizado, la pantalla de acceso ofrece **Update my connection**. Púlsalo para adoptar la dirección preparada por este arranque y vuelve a iniciar sesión. La app conserva la anterior mediante **Restore my previous connection** mientras corresponde; no borra tu cuenta ni la cola. Si ya estás dentro de una cuenta, abre **Settings** para administrar su conexión.

## 5. Emparejar la ESP32 por Bluetooth

Este paso se hace desde la cuenta de **persona mayor**. La conexión es **Bluetooth Classic SPP**, no BLE.

1. Entra en CareMonitor con la cuenta de la persona mayor y pulsa **Connect my wearable**, o abre **Settings → Set up my wearable**.
2. Sigue **I'm ready**. Activa Bluetooth y acepta en el teléfono la solicitud de emparejamiento que muestre Android. La ESP32 se llama **ElderSensors-elder-demo-01**. Ya no se necesita escribir `Y`, comparar códigos con una computadora ni abrir un monitor serial.
3. Continúa con **Search for my wearable**, concede **Nearby devices** y selecciona tu wearable.
4. La primera vez, la app pide **Wearable setup key**. En esta computadora, abre el archivo privado `.local/offline/wearable-setup-key.txt`, copia su clave de 32 caracteres y pulsa **Save and connect**. La clave no está en el APK ni en el paquete del reporte; no la publiques. La app la guarda cifrada para esta cuenta, servidor y wearable. Si necesitas corregirla, usa **Update setup key** debajo del dispositivo.
5. Espera a que aumente el contador de muestras y pulsa **Check connection → Go to Today**. En conexiones posteriores se reutiliza la clave guardada.

Solo se envían lecturas después de comprobar mutuamente la app y el wearable mediante un reto nuevo en cada conexión. Una clave incorrecta o la falta de autenticación cierra el enlace. Mientras un teléfono mantiene el enlace, la ESP32 deja de anunciarse y rechaza nuevas conexiones; otro teléfono podrá conectarse cuando el primero se desconecte. Una vinculación guardada en los ajustes de Android no equivale a una conexión activa ni autoriza por sí sola a leer sensores.

Para cambiar de teléfono, pulsa **Stop wearable monitoring** en el anterior o apaga su Bluetooth. En el nuevo, inicia sesión como persona mayor y repite la configuración con la clave privada. Si Android conserva una vinculación que falla tras la actualización, usa **Forget / Unpair** solamente para **ElderSensors-elder-demo-01** y vuelve a buscarlo. No borres los datos de CareMonitor.

La ESP32 solo necesita alimentación durante el uso Bluetooth. El USB queda disponible para diagnóstico opcional, sin enviar aceptaciones:

```bash
.venv/bin/python tools/pair_spp.py --seconds 120
```

No abras dos lectores seriales a la vez. `/dev/ttyACM0` corresponde al Samsung; la ESP32 usa `/dev/serial/by-id/usb-1a86_USB_Serial-if00-port0`.

## 6. Comprobar cada tramo del recorrido

| Comprobación | Qué debes observar | Qué confirma |
|---|---|---|
| `./carectl status` | `reachable: true` | Servidor HTTPS accesible desde esta computadora. |
| Registro o inicio de sesión en el Samsung | Entrada a la cuenta sin error de red | Samsung y servidor se comunican por HTTPS. |
| Pantalla de conexión del wearable | Contador de muestras que aumenta y datos recientes | ESP32 y Samsung se comunican por Bluetooth. |
| Estado de envío del wearable | Confirmación del servidor y pendientes que se vacían | Las muestras recibidas se entregan al servidor. |
| **Today** y una cuenta de cuidador vinculada | Lecturas recientes del adulto correcto | Consulta autorizada desde el servidor. |

Un contador Bluetooth que aumenta **no demuestra** que los datos llegaron al servidor. Si crecen los pendientes, revisa la red local, el permiso de red, la sesión y la dirección HTTPS. La cola es cifrada y tiene capacidad limitada; no se debe dejar acumulando indefinidamente ni borrarla para ocultar un fallo de conexión.

Para comprobar únicamente la salida USB de la ESP32, con la herramienta de emparejamiento ya cerrada:

```bash
./carectl firmware-check \
  --port /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0 \
  --seconds 20
```

Este diagnóstico conserva solo resultados agregados. No sustituye la comprobación Bluetooth ni el envío por HTTPS.

Para diagnóstico técnico opcional está `mobile/scripts/check_server_device.py`: comprueba la salud HTTPS con el cliente instalado del Samsung. No es necesario para el acceso normal. Utilízalo únicamente antes de activar Bluetooth u otros servicios de CareMonitor; la instrumentación puede reiniciar el proceso y la herramienta se niega a ejecutar si detecta servicios activos. Su resultado acredita solo esa petición HTTPS; el recorrido completo tiene su registro físico independiente del 27 de octubre, código 17.

En la comprobación del **27 de octubre de 2026**, tras la carga de aplicación, la ESP32 se asoció al módem y sincronizó el reloj. Emitió 63 JSON válidos aproximadamente una vez por segundo y declaró MPU6050 y GY906 en línea. El óptico indicó `no_finger`: no había una lectura válida de pulso u oxígeno. Que un sensor se declare en línea acredita comunicación observada, no precisión ni validez médica. Los valores ópticos no válidos deben aparecer como ausencia de dato; no deben interpretarse como cero ni como una condición saludable.

## 7. Añadir cuidadores y activar avisos

Para otros teléfonos, utiliza el **modo Wi-Fi del paso 1** en una red que permita acceder al servidor. La alternativa USB del paso 3 deja el servidor en loopback y proporciona el enlace al Samsung conectado; no da acceso inalámbrico a otros cuidadores.

1. En el teléfono de la persona mayor, abre **Care → Create an invitation code**.
2. En otro teléfono, instala la app preparada para este servidor, conecta a la misma **Network** y crea una cuenta con el rol de cuidador.
3. En ese cuidador, abre **Care**, escribe el código de cuatro cifras y pulsa **Request access**.
4. En la cuenta de la persona mayor, comprueba el nombre y aprueba únicamente a la persona correcta con **Approve access**.
5. En **cada teléfono**, abre **Settings → Notifications**, concede el permiso de notificaciones y pulsa **Enable local notifications**. Debe aparecer la notificación persistente del canal con su control para detenerlo.
6. Comprueba que el cuidador ve las lecturas del adulto autorizado. Prueba por separado la recepción de avisos con pantalla bloqueada y la recuperación al volver a conectar la Wi-Fi.

Todos esos teléfonos necesitan alcanzar esta computadora. Salir de la red local o apagar el servidor interrumpe esa ruta. Una conexión local por sí sola no proporciona acceso remoto desde datos móviles. Si necesitas eso más adelante, utiliza la VPN propia descrita en [SERVER.md](SERVER.md); no es necesario configurarla para esta sesión local.

GPS y micrófono del teléfono son opciones separadas. No necesitas activarlos ni conceder esos permisos para emparejar la ESP32 o entregar sus lecturas.

## 8. Volver a encender otro día

1. Enciende la computadora y la ESP32. Conecta y autoriza el Samsung por USB.
2. Para el enlace USB, ejecuta desde la raíz:

   ```bash
   ./tools/start_offline.sh --usb
   ./carectl status
   ```

3. Si vas a usar Wi-Fi, conecta la computadora y los teléfonos a Network y utiliza `./tools/start_offline.sh` sin `--usb`; añade `--serial R5CT20D7X6D` si usas el S22.
4. Abre CareMonitor e inicia sesión si hace falta. Retoma **Connect my wearable**. Normalmente no tendrás que repetir el emparejamiento si ambos equipos lo conservan.
5. Comprueba datos recientes, entrega al servidor y canal de avisos. Mantén la computadora encendida; configura su suspensión según la duración de tu prueba.

En modo Wi-Fi, si la IP del servidor no cambió y la app ya apunta a ella, para un reinicio ordinario del proceso basta con:

```bash
./carectl stop
./carectl start
./carectl status
```

Para terminar una sesión, detén el wearable desde la app y ejecuta `./carectl stop` antes de apagar la computadora. Detén también el canal de avisos en los teléfonos si no se va a mantener el servidor disponible.

## 9. Si algo falla

| Síntoma | Acción concreta |
|---|---|
| El modo USB dejó de comunicar | Conecta el cable del Samsung, desbloquéalo, autoriza depuración y repite `./tools/start_offline.sh --usb`. Mantén el cable conectado. |
| Network indica «Sin internet» | Es lo esperado en la red actual. Mantén ambos equipos conectados y comprueba la API local; no cambies automáticamente de Wi-Fi. |
| Contraseña Wi-Fi incorrecta | Actualiza la contraseña guardada de Network en los ajustes del equipo afectado y vuelve a conectar. La contraseña de Wi-Fi es distinta de la contraseña de tu cuenta. |
| `status` no alcanza el servidor | Revisa el resultado del comando de arranque, la IP Wi-Fi y que no haya otro proceso ocupando su dirección/puerto. No detengas servicios ajenos a CareMonitor para liberar un puerto sin identificarlos. |
| `status` funciona pero el Samsung no entra | Revisa la conexión guardada y el permiso de red local. En modo USB, conserva el cable y repite el arranque con `--usb`. En Wi-Fi, comprueba misma LAN, VPN, ruta y comunicación entre clientes. |
| El hotspot aísla dispositivos | Usa `./tools/start_offline.sh --usb` para continuar con el cable. Para Wi-Fi, desactiva el aislamiento si el equipo ofrece esa opción o usa un punto de acceso que permita tráfico entre equipos. |
| La VPN bloquea la LAN de Network | Usa su opción de permitir LAN o pausa esa VPN para la sesión local. Si tiene un bloqueo de conexiones fuera del túnel, revisa también esa opción. |
| HTTPS informa certificado inválido | Comprueba fecha y hora. Ejecuta `./carectl doctor` y vuelve a preparar la IP mediante el comando de arranque. No cambies a HTTP ni desactives la verificación. |
| La app conservó otra conexión | Si no puedes iniciar sesión, pulsa **Update my connection** tras instalar el APK preparado. Si tienes sesión, usa **Settings → Server connection**. No borres datos ni la cola. |
| No aparece el wearable | Comprueba alimentación, Bluetooth, permiso de dispositivos cercanos, cercanía y el nombre SPP. No selecciones el modo BLE para este firmware. |
| El emparejamiento vence | Confirma la solicitud en Android, verifica que ningún otro teléfono conserve el enlace y repite la guía. No se necesita monitor serial ni escribir `Y`. Si falla una vinculación anterior, olvida solo ElderSensors y vuelve a buscarlo. |
| La clave del wearable se rechaza | Introduce la clave privada de 32 caracteres de esa placa con **Update setup key**. La contraseña Wi-Fi y la contraseña de la cuenta no son esa clave. |
| Llegan muestras pero se acumulan pendientes | Revisa la sesión y la red del Samsung al servidor. Espera una confirmación real de almacenamiento; una conexión Bluetooth por sí sola no basta. |
| No hay pulso ni SpO₂ | Revisa el estado óptico. `no_finger` y valores `null` significan falta de señal válida; no arregles ese estado inventando valores. |
| El cuidador no ve lecturas | Comprueba su cuenta, solicitud aprobada, adulto seleccionado y conexión al mismo servidor. |
| No llegan avisos con pantalla apagada | Revisa permiso de notificaciones, canal activado, Wi-Fi, batería y que Android no haya forzado el cierre de la app. Prueba ese teléfono expresamente. |

En modo Wi-Fi, si el firewall de esta computadora bloquea la conexión entrante, permite **TCP 8443 únicamente desde la subred local utilizada**, en la zona/interfaz que corresponda. El arranque imprime el comando concreto cuando necesita autorización. Su permiso temporal de firewalld dura ocho horas y desaparece al reiniciar; vuelve a ejecutar el arranque y la autorización si hace falta. El modo USB no necesita esa regla LAN. [SERVER.md](SERVER.md) explica cómo comprobar la política. No publiques el puerto en internet ni desactives todo el firewall para resolver el acceso local.

Los certificados también caducan sin internet: revisa `./carectl doctor` si la instalación llevaba tiempo apagada. Conserva la CA, sus claves y la base privada para preparar una renovación; una CA nueva exige actualizar la confianza de los teléfonos.

## 10. Qué está preparado y qué se comprueba al conectar

El entorno y las herramientas locales permiten preparar el sistema sin descargar dependencias. El recorrido físico del 27 de octubre está registrado para código 17; después de actualizar o cambiar de red, comprueba otra vez **los tres equipos conectados**, una cuenta de persona mayor, el vínculo Bluetooth y la confirmación real del servidor. El enlace Samsung–servidor puede ser USB o una Wi-Fi que permita tráfico entre ambos; comprobar uno no demuestra que funcione el otro.

No se atribuye al montaje una medición de presión arterial, diagnóstico de deshidratación ni detector de caídas validado. El GY906 mide superficie y ambiente. Las lecturas y los avisos de este prototipo académico no sustituyen atención médica ni servicios de emergencia.

## 11. Uso cotidiano: persona mayor y cuidador

Para la **persona mayor**, Today reúne **Ask for help**, el próximo recordatorio y los indicadores. La petición de ayuda exige confirmar y avisa al círculo aprobado; no llama a emergencias. Abre una métrica para revisar sus cambios, o Updates para consultar avisos. La conexión del wearable se retoma con **Connect my wearable** y no exige configurar un servidor desde el inicio.

Para el **cuidador**, elige primero a la persona aprobada. Today indica qué avisos necesitan revisión, sus lecturas, próximo recordatorio y ubicación compartida si está autorizada. Usa **Review updates** para abrir el aviso y **Care** para administrar recordatorios y vínculos. La selección de una persona controla también sus históricos y detalles; una cuenta sin vínculo aprobado no ve sus datos.

En **Settings**, ambos roles encuentran **Reading details**, notificaciones y conexión. La persona mayor controla además wearable y privacidad. **Reduce motion** reduce las animaciones; los valores se actualizan sin inventar una señal continua. La jerarquía mantiene las tareas cotidianas delante y los detalles técnicos en un segundo nivel. Las pruebas de texto ampliado y navegación no sustituyen una evaluación de uso con adultos mayores ni la lectura manual con TalkBack.
