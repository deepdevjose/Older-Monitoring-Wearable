# Revisión del borrador y cobertura de la retroalimentación

Revisión editorial del 31 de octubre de 2026. La referencia de la profesora es el [borrador enviado](../../enviado.pdf), no una versión posterior del informe. Se contrastó con [su revisión](<../../IndiCare Strengths (teacher review).odt>) y con las fuentes/evidencias del informe final. Este registro describe cobertura y límites; no garantiza una calificación.

## Contenido sólido que se conserva

- **Verificación trazable:** requisitos, componentes, métodos y resultados separados por capa. Se mantiene la tabla de requisitos y se distingue la nueva campaña real de los fixtures.
- **Fiabilidad:** identificadores estables, idempotencia, cola persistente de 200 elementos, reintentos, transacciones e independencia de las confirmaciones de cada cuidador. Se conservan la secuencia de ingestión y la máquina de estados.
- **Seguridad:** Argon2id, sesiones, AES-GCM, verificación HTTPS y autorización mediante vínculo aprobado. Se mantiene la distinción entre contenido cifrado y metadatos visibles.
- **Comparación de aprendizaje automático:** se conserva la sección 6 del borrador: datos sintéticos, partición agrupada, selección por validación, LR frente a Random Forest, matrices, denominadores y significado limitado de la confianza. El resultado perfecto no se presenta como validación clínica.
- **Mejoras medidas:** se conserva el experimento de persistencia de 40 filas a 20 registros únicos y los tiempos de servidor del 26 de octubre, identificados como línea base.

## Los siete puntos solicitados

| Petición de la profesora | Ubicación final y evidencia | Cobertura y límite |
|---|---|---|
| 1. Arquitectura completa | Sección 2, figura `fig:architecture`; apéndice F, protocolos y componentes. Fuentes: `diagram_system.tex`, `diagram_hardware.tex`, `diagram_mobile.tex`. | Wearable, teléfono, PC/FastAPI, SQLite y aprendizaje se representan con sus conexiones reales. La adquisición física, las reglas sintéticas y la inferencia sintética permanecen separadas. |
| 2. Historia y clase/confianza en Android | Sección 4; capturas de ambos roles en `fig:role-screens` y apéndice E. Archivos `evidence/current/ANDROID_HISTORY_UI_EVIDENCE.json` y `DEVICE_SYSTEM_USB_CAPTURE_EVIDENCE.json`. | Código 20: historia accesible desde tarjetas y resultados sintéticos guardados. Hay fixtures a 100 %/200 % de texto y capturas posteriores con cuentas/API reales en un paquete aislado; no son la misma campaña. |
| 3. MVC y responsabilidades | Sección 4, tabla `tab:mobile-responsibilities`, figuras de capas/clases y métodos de `MainActivity`, `LearningActivity`, `MetricHistoryChartView`, `ApiClient`, `ApiSession` y `SecureStore`. | Se explica qué compone vistas, coordina estado, consulta datos, persiste y adquiere Bluetooth. Las Activities combinan vista/controlador; no se afirma una separación MVC/MVVM completa. |
| 4. Sensor → API → móvil | Sección 5 y apéndice E, figura `fig:physical-trace`. `evidence/current/DEVICE_SYSTEM_USB_EVIDENCE.json`, `DEVICE_SYSTEM_USB_CAPTURE_EVIDENCE.json` y `USB_RUN.tex`. | La campaña retuvo 76 lecturas físicas y llegó a cero pendientes; la cadena conserva identidad de muestra/registro y vistas nativas obtenidas por HTTPS. El transporte PC–teléfono de esa campaña fue USB; no se transforma en una prueba LAN. |
| 5. Contribución individual | Sección 7 y matriz del apéndice D: nombres, IDs, tareas individuales y compartidas, artefactos asociados y cronología. Fuente: `evidence/team_contributions.json`. | José confirmó la distribución el 30 de octubre (America/Los_Angeles). Se reconocen pruebas de sensores y caída, reporte, UI/UX, firmware y backend. Las pruebas exploratorias de Nathan no se confunden con los datos públicos SisFall ni con las métricas sintéticas. Los commits registran integración; no se presentan como prueba independiente de autoría individual ni se inventan porcentajes. |
| 6. Latencia y fiabilidad de entrega | Sección 8, apéndice E, tabla `tab:usb-latency`; `evidence/usb_performance.json` y arrays originales de las campañas. | API móvil: N=30, p95 77,04 ms. Publicación nativa: 21/30 antes y 30/30 después; p95 final 1.094,37 ms. Son notificaciones nativas en un S22 encendido y roles sucesivos; no prueba entre dos teléfonos, pantalla bloqueada, audibilidad ni respuesta humana. Los nueve timeouts iniciales permanecen en el denominador. |
| 7. Demostración integrada documentada | Apéndice E: resumen de campañas completadas, reproducción, observaciones, tiempos y capturas separadas para adulto/cuidador. `evidence/system_screenshots.json` conserva procedencia de imágenes. | Se aportan capturas nativas, registro persistido y resultados. Se distinguen el APK instalado, el preview, el paquete de QA con código de producción y las pruebas independientes de modelo/servidor. |

## Correcciones de coherencia

- Los tiempos que excluyen TLS/red/renderizado se identifican expresamente como la línea base del 26 de octubre; la campaña posterior sí mide el cliente HTTPS y la publicación nativa.
- La tabla de requisitos añade **Q** para la campaña del 30 de octubre con cuentas reales, código/recursos de producción y aplicación/base de datos aisladas. Q no equivale a aceptación completa del APK instalado ni de dos teléfonos.
- El apéndice D conserva los cuatro commits del borrador y añade los commits finales verificados. Los hashes de compilación/campaña identifican los artefactos ejecutados sin asignarles cambios editoriales posteriores.
- La declaración posterior de José completa la matriz de contribuciones y sustituye el estado anterior de atribución pendiente. La instantánea histórica `report_inputs.json` se conserva sin modificar; el registro editorial identifica la actualización.
- El apéndice E describe lo ejecutado y su reproducción, en vez de limitarse a una propuesta de demostración futura.
- La conclusión recupera los logros cuantificados: comparación de modelos, eliminación de duplicados, adquisición física y mejora de publicación de avisos.

## Límites conservados

Los sensores actuales no miden deshidratación ni temperatura corporal central. El MPU6050 aporta movimiento; no se atribuye a la fuente actual un detector autónomo de caídas o una alarma de respaldo implementada. La imagen histórica del 30 de octubre y la actualización de aplicación del 31 de octubre sin módulos de luz/micrófono se identifican por separado. Las imágenes históricas no se alteran para simular el montaje actual.

## Actualización de interfaz y capturas del 31 de octubre

El apéndice F añade 38 pantallas seleccionadas de la interfaz code 22 y explica cada área para adulto y cuidador. Los 85 PNG publicados conservan hashes y procedencia en `evidence/system_screenshots.json`: 83 de la campaña física (305 comprobaciones, 78 registros) y dos de una pasada complementaria de modelos (29 comprobaciones, sin wearable). Las formas de registro se muestran antes de enviarlas; los valores de sensores proceden del montaje real. Los benchmarks y denominadores del 30 de octubre conservan su identidad.
