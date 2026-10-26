# Entrega 1.0.0 y aceptación

Esta es la única entrega activa del demostrador académico. La app se ha instalado y abierto por USB en el Samsung SM-S948B con Android 17. Esto no significa certificación médica ni aceptación del flujo completo de sensores y servidor.

## Implementado

- Backend local HTTPS con cuentas de adulto/cuidador, cifrado de campos, autorización por vínculo, código temporal y aprobación, revocación y auditoría.
- Recepción idempotente de snapshots, separación `synthetic` / `device_diagnostic`, estado de antigüedad e historial.
- Episodios educativos y revisión por cuidador; avisos persistentes a todos los miembros autorizados, incluyendo teléfonos que mantienen cursores locales distintos.
- Canal propio de avisos HTTPS long polling y servicio Android visible; sin proveedor de nube.
- Recordatorios creados por cuidadores, repetición, confirmación declarada de toma, cancelación y bloqueo al revocar al autor.
- GPS local fuera de casa, consentimiento revocable, retención corta; intensidad de audio exclusivamente en RAM y confirmación humana.
- UI nativa con títulos amplios, superficies blancas, controles grandes, navegación con iconos y Ajustes agrupados. Registro con acción fija y texto ampliado comprobado; TalkBack y pruebas de uso con personas siguen pendientes.
- Entrada guiada con rol explícito y registro por pasos. Solo la persona mayor conecta el wearable mediante una guía que puede retomar. La dirección del servidor se configura en Ajustes tras iniciar sesión; cambiarla exige nueva autenticación.
- IA de salud sintética: dos clasificadores comparados y artefactos reproducibles. Replay SisFall: dos modelos, separación por sujeto y exclusiones documentadas. Ninguno es detector clínico online.
- CLI de arranque/parada/estado/diagnóstico, respaldo cifrado/restauración y generación local de configuraciones WireGuard.

## Evidencia vigente

`release/VALIDATION.json` contiene las ejecuciones de software y hashes de los inputs. `release/ANDROID_BUILD.json` acredita compilación, versión, firma y alineación del APK. `release/INTEGRATION_EVIDENCE.json` acredita el flujo HTTPS con cuentas ficticias y dos cuidadores en un servidor loopback aislado. `release/FIRMWARE_DIAGNOSTIC.json` contiene únicamente agregados de la lectura USB. `release/MANIFEST.json` y `SHA256SUMS` corresponden al paquete de entrega.

La revisión de registro, roles, Ajustes y navegación del código Android 13 pasó 709 comprobaciones nativas en el Samsung, descritas en `release/ANDROID_UI_EVIDENCE.json`. Incluyó formularios y acción principal con texto al 200 %, contraseña oculta inicialmente y conmutación mostrar/ocultar. No registró cuentas, cambió permisos ni activó sensores; comprobó que preferencias, sesión, cola y configuración de texto no cambiaran. Las pruebas de software vigentes suman 88 casos Python y 2.830 comprobaciones JVM. La rotación durante autenticación real y el flujo Bluetooth–servidor no están acreditados por estas pruebas de interfaz.

El diagnóstico actual de ESP32 recibió 25 JSON a aproximadamente 1 Hz: óptico `no_finger`, MPU6050 offline y GY906 online. No se retuvieron valores fisiológicos. El fuente canónico compila; la identidad del binario instalado no está acreditada. No se flasheó ni cambió NVS.

## Aceptación física pendiente

1. Probar red local, permisos, TalkBack y uso con personas en el Samsung; instalación, apertura y geometría del registro con texto al 200 % ya se comprobaron.
2. Emparejar SPP, recibir snapshots completos, comprobar `null` y diez segundos de antigüedad; cortar/reanudar Bluetooth y Wi-Fi.
3. Iniciar el canal de avisos en cada teléfono, bloquear pantallas y verificar avisos con varias cuentas. Probar permisos revocados, logout, batería, reinicio y fuerza de cierre.
4. Confirmar ubicación fuera de radio y su no envío dentro, revocación y apagado. Usar exclusivamente un procedimiento académico autorizado; no recolectar salud de participantes ni ensayar caídas.
5. Si se requiere acceso exterior, instalar la VPN propia, configurar el router y comprobar desde otra red. La generación de archivos WireGuard por sí sola no establece conectividad exterior.
6. Aclarar el estado del MPU6050 y verificar el óptico con un procedimiento autorizado antes de atribuirles funcionamiento físico.

Presión arterial, diagnóstico de deshidratación, temperatura central, identificación automática de accidentes y detección online validada de caídas no se atribuyen al hardware disponible. Las capacidades y el tratamiento ético están detallados en `SECURITY_ETHICS.md`.
