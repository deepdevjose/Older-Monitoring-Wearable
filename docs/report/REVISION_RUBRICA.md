# Correspondencia del informe IndiCare con la rúbrica

Resumen del informe académico en inglés, revisado el **27 de octubre de 2026**. Relaciona [la rúbrica](../../rubrik.docx), el ODT original, el código y las pruebas realizadas. Describe el alcance de cada evidencia; no asigna ni garantiza una calificación.

## Entrega documental

- El original [IndiCare_Report_Rubric_Checked.odt](../reference-labs/IndiCare_Report_Rubric_Checked.odt) se conserva sin modificar.
- [IndiCare_Report.tex](IndiCare_Report.tex) es la entrada editable. El PDF y el ZIP incluyen fuentes, figuras y evidencia de apoyo.
- [metadata.tex](metadata.tex) contiene los cuatro integrantes con sus matrículas y la tutora Matilda Isaac. La portada dice **Team members** y no incluye un grupo sin confirmar.
- Cuatro diagramas vectoriales explican arquitectura, recepción y reintento, estados de alertas y organización móvil. Los diagramas actuales incluyen `LearningActivity` y `MetricHistoryChartView`.
- Tres gráficos cuantitativos presentan el dataset, las matrices de confusión y la comparación de modelos, con los resultados guardados de la evaluación sintética.
- La [fotografía del prototipo](figures/hardware_prototype.jpg) documenta el montaje visible; [hardware_photo.json](evidence/hardware_photo.json) conserva su procedencia. Cinco capturas recortadas mantienen su contexto de desarrollo histórico.
- Las tablas trazan requisitos, roles, métricas, pruebas y tiempos a componentes concretos. Los apéndices reúnen API, reproducción, instalación, demostración y el alcance de la autoría documentada.

## Correspondencia con la rúbrica

| Criterio | Peso | Contenido y evidencia incluidos | Alcance del resultado |
|---|---:|---|---|
| Requisitos y arquitectura | 10 % | Trazabilidad, arquitectura, fotografía y separación entre diagnósticos, reglas e IA sintética. | La fotografía acredita el montaje visible. Los diagramas describen las responsabilidades actuales, incluido el camino móvil de IA. |
| Interfaz y funcionalidad móvil | 25 % | Interfaz en inglés; registro progresivo; servidor dentro de Ajustes; seis tarjetas con históricos Day/Week/Month; Learning insights con clase, confianza e historial para ambos roles. | 641 comprobaciones en un APK de vista previa aislado en Samsung S22 SM-S901E. Usa Java y recursos de producción, pero estado ficticio: no sustituye la autenticación, red o sensores reales. |
| Programación y arquitectura móvil | 20 % | Capas nativas Java, gestión de sesión, separación de servicios y 2.830 comprobaciones JVM. | Las Activities conservan responsabilidades combinadas de vista y controlador. No se declara una arquitectura MVVM completa. |
| Clasificación asistida por IA | 10 % | Dos modelos, preparación, partición por grupos, selección por validación, métricas y modelo local servido a LearningActivity. | La persona mayor clasifica ejemplos CSV editables; el cuidador autorizado consulta resultados guardados. Las etiquetas y la confianza describen exclusivamente el modelo sintético. |
| REST e integración de datos | 10 % | 24 verificaciones HTTPS loopback, almacenamiento idempotente, histórico por métricas y ejercicios sintéticos persistidos. | La autorización, revocación y retención se ejercitan en backend. La prueba loopback no acredita el recorrido Bluetooth–teléfono–cuidador. |
| Seguridad y gestión de configuración | 10 % | Roles, consentimiento, cifrado, sesiones, TLS y APK 1.0.0, código 16. | La revocación también bloquea históricos y ejercicios. Los registros sintéticos quedan separados de lecturas y avisos de cuidado. No se presenta una auditoría integral. |
| Pruebas y mejora medible | 10 % | 241 pruebas Python, 2.830 comprobaciones JVM, 24 verificaciones HTTPS y 641 comprobaciones de interfaz aislada; comparación de modelos e idempotencia. | Las unidades y campañas se presentan separadas. Las mediciones de rendimiento del 26 de octubre siguen siendo una línea base fechada, sin atribuirlas a la revisión nueva. |
| Demostración y documentación | 5 % | Informe reproducible, fuentes, fotografía, referencias y protocolo con ambos roles, históricos y clasificación/confianza móvil. | El protocolo describe una repetición controlada con cuentas ficticias; sus pasos no se cuentan como ejecuciones adicionales. |

## Lectura de la evidencia

[CURRENT_REVISION.tex](evidence/CURRENT_REVISION.tex) centraliza fecha, versión, hash y alcance actual. Los registros completos están en [release/](../../release/). La revisión pasa **241 pruebas Python**, **2.830 comprobaciones JVM** y **24 comprobaciones HTTPS reales en loopback**. Los históricos tienen **22 pruebas específicas** y el almacenamiento de ejercicios sintéticos **14**; forman parte del total Python y no se suman de nuevo.

El APK de producción código **16** está instalado en el Galaxy S22 SM-S901E con Android 13. Pasó **759 comprobaciones nativas** de registro, diseño y navegación, y HTTPS por Wi-Fi con el cliente instalado; los paquetes auxiliares se retiraron y se conservaron preferencias, permisos y estado. El teléfono principal SM-S948B se actualizará cuando vuelva a conectarse.

Las **641 comprobaciones de interfaz actuales** se ejecutaron en un APK de vista previa aislado en Samsung S22 SM-S901E con Android 17, compilado a partir de los mismos Java y recursos que producción. El fixture utiliza datos ficticios; no crea cuentas reales ni prueba el enlace de radio o las notificaciones del cuidador. La instalación del APK de producción se declara únicamente con el equipo y alcance registrados en la evidencia final.

Los JSON conservados directamente en `docs/report/evidence/`, como [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json), [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json), [VALIDATION.json](evidence/VALIDATION.json) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json), corresponden a la **línea base del 26 de octubre**. Acreditan código 13, 709 comprobaciones de onboarding, 88 pruebas Python y 17 verificaciones HTTPS. La campaña posterior del código 14 registró 739 comprobaciones; no se trasladan esos resultados al APK 16.

Las seis tarjetas consultan agregados válidos separados por paciente y origen. Los periodos son ventanas de 24 horas, siete días y treinta días. Los rollups horarios cifrados permiten calcular medias ponderadas por número de observaciones; los datos ausentes o inválidos quedan como huecos. Los límites parciales de hora se leen de forma exacta y una indexación antigua incompleta se comunica en la respuesta. Se comprueban autorización, revocación, retención, separación de origen y límites en [test_history.py](../../tests/test_history.py).

**Learning insights** consume el modelo sintético local, muestra Normal/Warning/Critical y confianza y guarda resultados cifrados durante treinta días. La persona mayor puede añadir ejercicios y un cuidador aprobado puede leerlos. Las muestras poseen identificadores idempotentes. El almacenamiento es independiente de las lecturas físicas y no abre episodios ni genera avisos de cuidado. [test_learning.py](../../tests/test_learning.py) comprueba estas propiedades. La temperatura superficial del GY906 no se transforma en temperatura corporal para el modelo.

El holdout local no se presenta como prueba oculta del evaluador. Las métricas no se trasladan a pacientes ni a caídas reales. La procedencia disponible del CSV no documenta por sí sola aprobación institucional. Los nombres e IDs identifican al equipo; no se inventan responsabilidades, contribuciones ni factores individuales a partir de una identidad Git compartida.

## Reproducción

Desde la raíz del repositorio, con LaTeX y el entorno Python del proyecto disponibles:

```bash
bash docs/report/build.sh
.venv/bin/python -m pytest tests -p no:cacheprovider
.venv/bin/python mobile/scripts/test_reading_summary.py --jdk /usr
```

El primer comando actualiza el contador de prosa y compila el PDF. Los siguientes ejecutan las pruebas Python y JVM del código presente. El contador debe quedar entre 2.700 y 3.300 palabras de prosa principal; tablas, figuras y apéndices mantienen su exclusión indicada en la rúbrica. Figuras, referencias, tablas y saltos se revisan visualmente en el PDF generado.

El ZIP permite compilar el documento sin el resto del proyecto. Reproducir software y benchmarks exige el repositorio completo. Los benchmarks usan bases temporales y cuentas ficticias, conservan las mediciones originales y no modifican el servidor activo. La instrumentación no debe usarse para sobrescribir una sesión real: utiliza un dispositivo de pruebas o el APK aislado y conserva la protección de sesión activa. Ninguno de estos comandos flashea hardware ni cambia la red Wi-Fi de la laptop.
