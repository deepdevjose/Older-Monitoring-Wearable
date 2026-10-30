# Entrega 1.0.0 y aceptación

Estado del **27 de octubre de 2026**. Esta es la única entrega activa del demostrador académico. La app código **16** está instalada y abierta en el Galaxy S22 SM-S901E con Android 13. El teléfono principal SM-S948B actualizará su instalación anterior cuando vuelva a conectarse. Las pruebas distinguen software, interfaz y transporte físico.

## Implementado

- Backend local HTTPS con cuentas de adulto/cuidador, cifrado de campos, autorización por vínculo, código temporal y aprobación, revocación y auditoría.
- Recepción idempotente de snapshots, separación `synthetic` / `device_diagnostic`, estado de antigüedad e historial.
- Seis tarjetas abren históricos Day/Week/Month para ambos roles. Agregados horarios cifrados, medias ponderadas, mínimos, máximos, última observación, huecos y retención de treinta días. La respuesta declara cobertura incompleta cuando hay indexación pendiente.
- Episodios educativos y revisión por cuidador; avisos persistentes a todos los miembros autorizados, incluyendo teléfonos que mantienen cursores locales distintos.
- Canal propio de avisos HTTPS long polling y servicio Android visible; sin proveedor de nube.
- Recordatorios creados por cuidadores, repetición, confirmación declarada de toma, cancelación y bloqueo al revocar al autor.
- GPS local fuera de casa, consentimiento revocable, retención corta; intensidad de audio exclusivamente en RAM y confirmación humana.
- UI nativa completamente en inglés, con títulos amplios, superficies blancas, controles grandes, navegación con iconos y Settings agrupados. Registro con acción fija y texto ampliado comprobado; la evaluación manual con TalkBack queda fuera de las comprobaciones automatizadas.
- Entrada guiada con rol explícito y registro por pasos. Solo la persona mayor conecta el wearable mediante una guía que puede retomar. La dirección del servidor se configura en Ajustes tras iniciar sesión; cambiarla exige nueva autenticación.
- IA de salud sintética: dos clasificadores comparados y artefactos reproducibles. Replay SisFall: dos modelos, separación por sujeto y exclusiones documentadas. Ninguno es detector clínico online.
- Settings → Learning insights consume el modelo local, presenta Normal/Warning/Critical y confianza, y guarda ejercicios sintéticos cifrados. La persona mayor puede añadirlos y el cuidador aprobado consultarlos. Los resultados no se mezclan con diagnósticos físicos ni generan avisos de cuidado.
- CLI de arranque/parada/estado/diagnóstico, respaldo cifrado/restauración y generación local de configuraciones WireGuard.

## Evidencia vigente

`release/VALIDATION.json` contiene las ejecuciones de software y hashes de los inputs. `release/ANDROID_BUILD.json` acredita compilación, versión, firma y alineación del APK. `release/INTEGRATION_EVIDENCE.json` acredita el flujo HTTPS con cuentas ficticias y dos cuidadores en un servidor loopback aislado. `release/FIRMWARE_DIAGNOSTIC.json` contiene únicamente agregados de la lectura USB. `release/MANIFEST.json` y `SHA256SUMS` corresponden al paquete de entrega.

La revisión del código **16** en el S22 pasó **759 comprobaciones nativas** de registro, roles, navegación, Settings, contraseña oculta, formularios al 200 % y recuperación de conexión. El APK código 16 instalado coincide por SHA-256 con el compilado. Los fixtures se restauraron; preferencias, sesión, cola, permisos y configuración de texto quedaron iguales. `ANDROID_UI_EVIDENCE.json` registra esta ejecución. `ANDROID_ONBOARDING_BASELINE.json` conserva las campañas anteriores del SM-S948B; no se atribuyen sus comprobaciones al APK nuevo.

`ANDROID_HISTORY_UI_EVIDENCE.json` registra otras **641 comprobaciones** en el S22 SM-S901E con Android 13. Un APK separado, sin permisos ni servicios, ejecutó los mismos Java y recursos de producción con fixtures sintéticos: ambos roles, históricos, huecos, errores, clasificación, confianza y texto al 100 %/200 %. Ese paquete se eliminó al terminar. Esta campaña no autenticó cuentas reales ni probó sensores o red.

Las pruebas actuales suman **241 casos Python y 2.830 comprobaciones JVM**, además de **24 verificaciones HTTPS loopback** con cuentas ficticias. Incluyen 22 casos de históricos y 14 de ejercicios sintéticos, ya contenidos en el total Python. `release/OFFLINE_PREFLIGHT.json` acredita HTTPS real desde el S22 por Wi-Fi a `192.168.3.3:8443`, con el cliente instalado, la CA original y verificación del nombre. Un POST exactamente vacío a `/auth/register` recibió HTTP 422, sin crear cuentas. La conexión guardada y la preparada coinciden. La preparación conservó los conteos existentes de cuentas, vínculos y lecturas; no exportó sus contenidos.

La red actual **Network** es el módem Huawei del dormitorio: laptop `192.168.3.3`, S22 `192.168.3.5`, puerta de enlace `192.168.3.1`. El servidor escucha únicamente en `192.168.3.3:8443`. El S22 verificó HTTPS con el ApiClient instalado y alcanzó el registro (POST vacío rechazado con 422), sin relay ni `adb reverse`. El arranque completo con `--serial R5CT20D7X6D` pasó y detectó el puerto ya autorizado por el firewall sin modificar su política. La laptop mantuvo su conexión; no se utilizó Test. La prueba anterior en un hotspot distinto con el mismo nombre y direcciones `10.109.243.*` no describe esta LAN. [PUESTA_EN_MARCHA.md](PUESTA_EN_MARCHA.md) documenta ambos teléfonos y la alternativa USB.

La ESP32 recibió una carga **solo de aplicación** en `0x10000`, con respaldo privado completo y sin borrar flash ni reescribir bootloader o particiones. La contraseña dictada no coincidía con la guardada en la conexión activa; se utilizó la credencial local que ya funcionaba, sin publicarla. Se añadió diagnóstico de desconexión Wi-Fi sin secretos, espera de reintento de treinta segundos y recuperación acotada de sensores tras un NACK transitorio. La compilación y la verificación de escritura pasaron.

`FIRMWARE_NETWORK_EVIDENCE.json` registra el arranque posterior: ESP32 `192.168.3.8`, NTP sincronizado, **63 JSON válidos a 0,999 Hz**, MPU6050 y GY906 online en todas las muestras y sin reinicios. Hubo 59 marcas de tiempo válidas, con error máximo observado de 0,55 s. El óptico indicó `waiting`/`no_finger`; no se obtuvieron ni retuvieron mediciones fisiológicas. Bluetooth quedó preparado, pero el vínculo y el recorrido completo hacia el teléfono siguen siendo comprobaciones separadas. El diagnóstico del 26 de octubre queda como antecedente histórico, no como evidencia del binario actual.

## Aceptación física pendiente

1. Completar permisos y evaluación manual con TalkBack; Wi-Fi HTTPS, instalación y geometría de registro al 200 % ya se comprobaron en el S22. Mantener los ensayos dentro del alcance académico sin participantes de salud reales.
2. Emparejar SPP, recibir snapshots completos, comprobar `null` y diez segundos de antigüedad; cortar/reanudar Bluetooth y Wi-Fi.
3. Iniciar el canal de avisos en cada teléfono, bloquear pantallas y verificar avisos con varias cuentas. Probar permisos revocados, logout, batería, reinicio y fuerza de cierre.
4. Confirmar ubicación fuera de radio y su no envío dentro, revocación y apagado. Usar exclusivamente un procedimiento académico autorizado; no recolectar salud de participantes ni ensayar caídas.
5. Si se requiere acceso exterior, instalar la VPN propia, configurar el router y comprobar desde otra red. La generación de archivos WireGuard por sí sola no establece conectividad exterior.
6. Evaluar la estabilidad de los sensores dentro de un procedimiento académico autorizado; la observación online actual no acredita precisión ni valida señal fisiológica.

Presión arterial, diagnóstico de deshidratación, temperatura central, identificación automática de accidentes y detección online validada de caídas no se atribuyen al hardware disponible. Las capacidades y el tratamiento ético están detallados en `SECURITY_ETHICS.md`.
