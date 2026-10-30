# Correspondencia del informe IndiCare con la rúbrica

Resumen del informe académico en inglés, revisado el **30 de octubre de 2026**. Relaciona [la rúbrica](../../rubrik.docx), el ODT original, el código y las pruebas realizadas. Describe el alcance de cada evidencia; no asigna ni garantiza una calificación.

## Entrega documental

- El original [IndiCare_Report_Rubric_Checked.odt](../reference-labs/IndiCare_Report_Rubric_Checked.odt) se conserva sin modificar.
- [IndiCare_Report.tex](IndiCare_Report.tex) es la entrada editable. El PDF y el ZIP incluyen fuentes, figuras y evidencia de apoyo.
- [metadata.tex](metadata.tex) contiene los cuatro integrantes con sus matrículas y la tutora Matilda Isaac. La portada dice **Team members** y no incluye un grupo sin confirmar.
- Diagramas vectoriales de cableado, finalidad de sensores, actores, permisos, clases, datos, seguridad y protocolos explican arquitectura, recepción y reintento, estados de alertas y organización móvil. Los diagramas actuales incluyen `LearningActivity` y `MetricHistoryChartView`.
- Tres gráficos cuantitativos presentan el dataset, las matrices de confusión y la comparación de modelos, con los resultados guardados de la evaluación sintética.
- La [fotografía del prototipo](figures/hardware_prototype.jpg) documenta el montaje visible; [hardware_photo.json](evidence/hardware_photo.json) conserva su procedencia. Cinco capturas recortadas mantienen su contexto de desarrollo histórico.
- Las tablas trazan requisitos, roles, métricas, pruebas y tiempos a componentes concretos. Los apéndices reúnen API, reproducción, instalación, demostración y el alcance de la autoría documentada.

## Correspondencia con la rúbrica

| Criterio | Peso | Contenido y evidencia incluidos | Alcance del resultado |
|---|---:|---|---|
| Requisitos y arquitectura | 10 % | Trazabilidad, arquitectura, fotografía y separación entre diagnósticos, reglas e IA sintética. | La fotografía acredita el montaje visible. Los diagramas describen las responsabilidades actuales, incluido el camino móvil de IA. |
| Interfaz y funcionalidad móvil | 25 % | Interfaz en inglés por tareas y roles; registro progresivo; detalles de lectura en Settings; históricos Day/Week/Month, movimiento reducido y Learning insights con confianza e historial. | La campaña actual de preview está registrada con hashes en evidence/current/. Usa Java y recursos de producción, texto al 100 %/200 % y estado ficticio; no sustituye autenticación, radio, TalkBack manual ni un estudio con mayores. |
| Programación y arquitectura móvil | 20 % | Capas nativas Java, gestión de sesión, separación de servicios y comprobaciones JVM registradas en CURRENT_REVISION.tex. | Las Activities conservan responsabilidades combinadas de vista y controlador. No se declara una arquitectura MVVM completa. |
| Clasificación asistida por IA | 10 % | Dos modelos, preparación, partición por grupos, selección por validación, métricas y modelo local servido a LearningActivity. | La persona mayor clasifica ejemplos CSV editables; el cuidador autorizado consulta resultados guardados. Las etiquetas y la confianza describen exclusivamente el modelo sintético. |
| REST e integración de datos | 10 % | 24 verificaciones HTTPS loopback, almacenamiento idempotente, histórico por métricas y ejercicios sintéticos persistidos. | La autorización, revocación y retención se ejercitan en backend. La prueba loopback no acredita el recorrido Bluetooth–teléfono–cuidador. |
| Seguridad y gestión de configuración | 10 % | Roles, consentimiento, cifrado, sesiones, TLS, autenticación privada del wearable y APK 1.0.0, código 20. | La revocación también bloquea históricos y ejercicios. Los registros sintéticos quedan separados de lecturas y avisos de cuidado. No se presenta una auditoría integral. |
| Pruebas y mejora medible | 10 % | Campañas actuales Python, JVM, HTTPS y preview aislado; pruebas físicas fechadas de Bluetooth y servidor, comparación de modelos e idempotencia. | Las unidades y campañas se presentan separadas. Las mediciones de rendimiento del 26 de octubre siguen siendo una línea base fechada, sin atribuirlas a la revisión nueva. |
| Demostración y documentación | 5 % | Informe reproducible, fuentes, fotografía, referencias y protocolo con ambos roles, históricos y clasificación/confianza móvil. | El protocolo describe una repetición controlada con cuentas ficticias; sus pasos no se cuentan como ejecuciones adicionales. |

## Lectura de la evidencia

La revisión pasó **229 pruebas Python**, **3.276 comprobaciones JVM**, **24 verificaciones HTTPS loopback** y **7.073 comprobaciones de preview** con 48 capturas en S22 / Android 13. Código 20 instalado, hash y cliente HTTPS comprobados. El onboarding actual se omitió para conservar la sesión; las 878 comprobaciones corresponden al código 19.

Una campaña distinta completó **119 controles de sistema real** con producción Java/recursos y cuentas controladas en paquete/base aislados: SPP físico, 76 registros retenidos, cero pendientes y 32 capturas nativas originales. La entrega final de notificaciones fue 30/30 tras corregir un límite de tarjetas que causó nueve timeouts en la línea base. Esta prueba usa un S22 sucesivamente para ambos roles, pantalla encendida y HTTPS por USB; no acredita alarmas audibles, dos teléfonos simultáneos ni Wi-Fi.

[CURRENT_REVISION.tex](evidence/CURRENT_REVISION.tex) centraliza fecha, versión, hash y resultados de la revisión. Los JSON de [evidence/current/](evidence/current/) conservan el alcance exacto de software, instalación y fixtures. Los históricos y los ejercicios sintéticos forman parte del total Python y no se suman de nuevo. La jerarquía Today/Updates/Care/Settings se adapta al rol, mantiene selección de persona y actualiza valores en su lugar; no implica una reducción de esfuerzo medida con usuarios mayores.

La campaña de preview del S22 SM-S901E / Android 13 usa una app aislada con Java y recursos de producción, ambos roles y texto normal/ampliado. No crea cuentas ni prueba radio o notificaciones del cuidador. La instalación del APK de producción se declara solo con el dispositivo, versión y hash registrados en su evidencia. La campaña previa del código 16, con 759 comprobaciones, se conserva en `ANDROID_CODE16_BASELINE.json`.

`BT_PROTOCOL_EVIDENCE.json` registra 207 comprobaciones físicas del 27 de octubre, incluida exclusión de una radio independiente mientras el dueño permanece conectado. `APP_BLUETOOTH_SERVER_EVIDENCE.json` corresponde al **código 17**: flujo normal de la app, 138 muestras recibidas, cero pendientes y confirmación del servidor. No se atribuye esa campaña a código 20 ni a avisos entregados con pantalla bloqueada.

Los JSON directamente bajo `docs/report/evidence/`, como [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json), [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json), [VALIDATION.json](evidence/VALIDATION.json) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json), son instantáneas del **26 de octubre**: código 13, 709 comprobaciones de onboarding, 88 pruebas Python y 17 verificaciones HTTPS. La campaña posterior código 14 registró 739 comprobaciones; esos resultados tampoco se trasladan al APK nuevo.

Las cinco tarjetas consultan agregados válidos separados por paciente y origen. Los periodos son ventanas de 24 horas, siete días y treinta días. Los rollups horarios cifrados permiten calcular medias ponderadas por número de observaciones; los datos ausentes o inválidos quedan como huecos. Los límites parciales de hora se leen de forma exacta y una indexación antigua incompleta se comunica en la respuesta. Se comprueban autorización, revocación, retención, separación de origen y límites en [test_history.py](../../tests/test_history.py).

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

La revisión atiende la observación docente sobre arquitectura, clases/MVC, historia y confianza nativas, recorrido físico y mejora medible. El apéndice E registra ejecución y límites, y el F incluye el atlas. Los diagramas separan adquisición MPU6050 de clasificación autónoma de caídas, temperatura superficial de temperatura central y recordatorios de hidratación de medición de deshidratación. El firmware fuente actual compiló sin flashearse.
