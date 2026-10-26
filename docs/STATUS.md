# Entrega 1.0.0 y aceptación

Esta es la única entrega activa del demostrador académico. El número de versión no significa certificación médica ni validación de un Samsung que todavía no está conectado.

## Implementado

- Backend local HTTPS con cuentas de adulto/cuidador, cifrado de campos, autorización por vínculo, código temporal y aprobación, revocación y auditoría.
- Recepción idempotente de snapshots, separación `synthetic` / `device_diagnostic`, estado de antigüedad e historial.
- Episodios educativos y revisión por cuidador; avisos persistentes a todos los miembros autorizados, incluyendo teléfonos que mantienen cursores locales distintos.
- Canal propio de avisos HTTPS long polling y servicio Android visible; sin proveedor de nube.
- Recordatorios creados por cuidadores, repetición, confirmación declarada de toma, cancelación y bloqueo al revocar al autor.
- GPS local fuera de casa, consentimiento revocable, retención corta; intensidad de audio exclusivamente en RAM y confirmación humana.
- UI nativa clara con controles grandes, tarjetas, contraste y cuentas separadas; pruebas de accesibilidad y comportamiento real pendientes en el dispositivo.
- IA de salud sintética: dos clasificadores comparados y artefactos reproducibles. Replay SisFall: dos modelos, separación por sujeto y exclusiones documentadas. Ninguno es detector clínico online.
- CLI de arranque/parada/estado/diagnóstico, respaldo cifrado/restauración y generación local de configuraciones WireGuard.

## Evidencia vigente

`release/VALIDATION.json` contiene las ejecuciones de software y hashes de los inputs. `release/ANDROID_BUILD.json` acredita compilación, versión, firma y alineación del APK. `release/INTEGRATION_EVIDENCE.json` acredita el flujo HTTPS con cuentas ficticias y dos cuidadores en un servidor loopback aislado. `release/FIRMWARE_DIAGNOSTIC.json` contiene únicamente agregados de la lectura USB. `release/MANIFEST.json` y `SHA256SUMS` corresponden al paquete de entrega.

El diagnóstico actual de ESP32 recibió 25 JSON a aproximadamente 1 Hz: óptico `no_finger`, MPU6050 offline y GY906 online. No se retuvieron valores fisiológicos. El fuente canónico compila; la identidad del binario instalado no está acreditada. No se flasheó ni cambió NVS.

## Aceptación física pendiente

1. Instalar el APK y probar red local, permisos y fuente grande en el Samsung real.
2. Emparejar SPP, recibir snapshots completos, comprobar `null` y diez segundos de antigüedad; cortar/reanudar Bluetooth y Wi-Fi.
3. Iniciar el canal de avisos en cada teléfono, bloquear pantallas y verificar avisos con varias cuentas. Probar permisos revocados, logout, batería, reinicio y fuerza de cierre.
4. Confirmar ubicación fuera de radio y su no envío dentro, revocación y apagado. Usar exclusivamente un procedimiento académico autorizado; no recolectar salud de participantes ni ensayar caídas.
5. Si se requiere acceso exterior, instalar la VPN propia, configurar el router y comprobar desde otra red. La generación de archivos WireGuard por sí sola no establece conectividad exterior.
6. Aclarar el estado del MPU6050 y verificar el óptico con un procedimiento autorizado antes de atribuirles funcionamiento físico.

Presión arterial, diagnóstico de deshidratación, temperatura central, identificación automática de accidentes y detección online validada de caídas no se atribuyen al hardware disponible. Las capacidades y el tratamiento ético están detallados en `SECURITY_ETHICS.md`.
