# Correspondencia del informe IndiCare con la rúbrica

Resumen en español del informe académico en inglés. Relaciona [la rúbrica](../../rubrik.docx), el ODT original, el código y la evidencia incluida en la revisión del 26 de octubre de 2026. Describe lo que documenta cada resultado y sus límites; no asigna una calificación.

## Entrega documental

- El original [IndiCare_Report_Rubric_Checked.odt](../reference-labs/IndiCare_Report_Rubric_Checked.odt) se conserva sin modificar.
- [IndiCare_Report.tex](IndiCare_Report.tex) es la entrada editable. El PDF y el ZIP incluyen fuentes, figuras y evidencia de apoyo.
- [metadata.tex](metadata.tex) contiene los cuatro integrantes con sus matrículas y la tutora Matilda Isaac.
- Cuatro diagramas vectoriales explican arquitectura, recepción/reintento, estados de alertas y organización móvil.
- Tres gráficos cuantitativos presentan balance y particiones del dataset, matrices de confusión y comparación de modelos, con valores derivados de los metadatos y resultados guardados.
- La [fotografía real del prototipo](figures/hardware_prototype.jpg) documenta el montaje visible; [hardware_photo.json](evidence/hardware_photo.json) conserva su procedencia. Cinco capturas recortadas mantienen su contexto de desarrollo histórico y no exponen secretos ni valores fisiológicos personales.
- Las tablas trazan requisitos, roles, métricas, pruebas y tiempos a componentes concretos. Los apéndices reúnen procedimientos de reproducción, instalación y alcance de la autoría documentada.

## Correspondencia con la rúbrica

| Criterio | Peso | Contenido y evidencia incluidos | Alcance del resultado |
|---|---:|---|---|
| Requisitos y arquitectura | 10 % | Matriz de trazabilidad, arquitectura actual, fotografía del prototipo y separación entre diagnósticos, reglas e IA. | La fotografía acredita el montaje observado; el diagrama y la trazabilidad describen responsabilidades del sistema. |
| Interfaz y funcionalidad móvil | 25 % | Registro progresivo con selección de rol, configuración del servidor en Ajustes y conexión del wearable exclusiva de la persona mayor; 709 comprobaciones de interfaz en Samsung con Android 17. | Se verifica el alcance de los recorridos ejercitados, incluido texto al 200 %. La clasificación/confianza y el componente de gráficos se describen según su integración real. |
| Programación y arquitectura móvil | 20 % | Diagrama de responsabilidades, justificación de capas, gestión de sesión, recepción de muestras y 2.830 comprobaciones JVM. | La arquitectura móvil es nativa Java. Las pruebas JVM validan lógica aislada y no reproducen todos los estados del sistema Android. |
| Clasificación asistida por IA | 10 % | Dos modelos, preparación, partición por grupos, selección por validación y resultados completos de evaluación. | Las métricas corresponden al experimento sintético descrito. No acreditan precisión clínica ni autorización institucional del CSV. |
| REST e integración de datos | 10 % | Contratos API, persistencia, idempotencia y 17 verificaciones HTTPS de integración con cuentas ficticias. | El loopback verifica el servicio y los contratos ejercitados; no mide el recorrido físico completo desde Bluetooth hasta el teléfono del cuidador. |
| Seguridad y gestión de configuración | 10 % | Roles, consentimiento, cifrado, sesiones, TLS y trazabilidad del APK 1.0.0, código de versión 13. | El APK instalado y la evidencia de software se distinguen de una auditoría de seguridad integral. La autoría individual no se infiere de una identidad Git. |
| Pruebas y mejora medible | 10 % | 88 pruebas Python, 2.830 comprobaciones JVM, 709 comprobaciones de interfaz y comparación medible de modelos e idempotencia. | Cada categoría conserva su unidad, condiciones y cobertura. Los tiempos locales no representan latencia móvil ni respuesta clínica. |
| Demostración y documentación | 5 % | Informe reproducible, fuentes editables, fotografía del hardware, referencias y procedimiento de demostración. | El procedimiento especifica qué observar y registrar; sus pasos no se cuentan como resultados ejecutados. |

## Lectura de la evidencia

La instalación documentada corresponde a CareMonitor 1.0.0, código de versión 13, en un Samsung SM-S948B con Android 17. [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json) identifica el artefacto y [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json) describe las comprobaciones de interfaz. Estas cubren el registro y los elementos ejercitados por la prueba; incluyen el tamaño de texto al 200 %. El registro por pasos solicita rol, nombre, correo y contraseña. Tras iniciar sesión, la configuración del servidor se encuentra en Ajustes. La conexión progresiva del wearable corresponde al rol de persona mayor.

Las pruebas del servicio y de la lógica móvil se presentan separadas de estas comprobaciones en teléfono: [VALIDATION.json](evidence/VALIDATION.json), [jvm-current.txt](evidence/jvm-current.txt), [pytest-current.xml](evidence/pytest-current.xml) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json). La clasificación devuelve etiqueta y confianza desde su API, mientras que la interfaz actual no consume ese resultado; `SensorChartView` existe como componente y no está montado en la navegación. La ausencia de una ejecución completa wearable–Bluetooth–teléfono–servidor limita las afirmaciones de integración física.

Los modelos se evalúan sobre datos sintéticos. El holdout local no se denomina prueba oculta del evaluador, y las métricas no se trasladan a pacientes reales ni a caídas reales del wearable. El informe describe la procedencia disponible del CSV sin atribuir una aprobación institucional que no documenta. Los nombres e IDs identifican al equipo; no se inventan responsabilidades, contribuciones ni factores de evaluación individuales.

La fotografía suministrada se incorpora como evidencia del prototipo y conserva el original. Su interpretación se limita a lo visible: una imagen del montaje no determina qué binario está cargado, si los sensores están calibrados ni si se entregaron lecturas al servidor.

## Reproducción

Desde la raíz del repositorio, con LaTeX y el entorno Python del proyecto disponibles:

```bash
bash docs/report/build.sh
.venv/bin/python -m pytest tests -p no:cacheprovider
.venv/bin/python mobile/scripts/test_reading_summary.py --jdk /usr
```

El primer comando actualiza el contador de prosa y compila el PDF. Los siguientes ejecutan las pruebas Python y JVM del repositorio; sus resultados corresponden al código y al entorno de esa ejecución. Las figuras, tablas, referencias y saltos de página se revisan visualmente en el PDF generado.

El ZIP permite compilar la documentación sin el resto del proyecto. La reproducción de pruebas y benchmarks requiere el repositorio completo. Los benchmarks del informe usan bases temporales y cuentas ficticias, conservan las mediciones originales y no modifican el servidor activo. Ninguno de estos comandos flashea hardware.
