# Informe IndiCare en LaTeX

Informe académico en inglés, revisado contra `rubrik.docx` y la implementación de CareMonitor 1.0.0. Incluye arquitectura, decisiones de diseño, resultados cuantitativos, fotografía del prototipo y evidencia de pruebas. El contador de prosa se genera automáticamente al compilar; excluye tablas, figuras, referencias y apéndices.

## Archivos

- `IndiCare_Report.pdf`: informe compilado y revisado visualmente.
- `IndiCare_Report.tex`: entrada principal del proyecto LaTeX.
- `metadata.tex`: cuatro integrantes con sus IDs, tutora Matilda Isaac y datos de portada.
- `sections/`: prosa, tablas, diagramas TikZ de despliegue, cableado, actores, flujo, clases, permisos, servidor, datos y seguridad, bibliografía y apéndices.
- `figures/`: fotografía real del prototipo, tres gráficos vectoriales de resultados, 32 capturas nativas actuales de ambos roles con cuentas controladas y cinco capturas históricas recortadas.
- `evidence/`: instantáneas de compilación y pruebas, metadatos de modelos, muestras de tiempos, comparación inicial/final y procedencia de imágenes.
- `REVISION_RUBRICA.md`: correspondencia entre criterios, contenido del informe y alcance de la evidencia.

Los archivos `.tex` son las fuentes editables. El PDF y `../IndiCare_LaTeX_Package.zip` se generan a partir de ellas; el ZIP reúne las fuentes, figuras y evidencia necesarias para compilar la documentación.

## Compilar

Se necesita una distribución TeX Live con `latexmk`, pdfLaTeX, TikZ, `newtx`, `titlesec`, `listings`, `caption`, `microtype` y los paquetes comunes indicados en el preámbulo. Las figuras ya están generadas: no se necesita Python científico para compilar el PDF.

Desde esta carpeta:

```bash
bash build.sh
```

O directamente:

```bash
python3 tools/count_words.py
latexmk -pdf -interaction=nonstopmode -halt-on-error -outdir=build IndiCare_Report.tex
cp build/IndiCare_Report.pdf IndiCare_Report.pdf
```

Para regenerar los gráficos, con `numpy` y `matplotlib` instalados:

```bash
python3 tools/make_figures.py
```

## Evidencia y alcance

La revisión del **30 de octubre de 2026**, código **20**, prioriza ayuda y plan para el adulto, y persona seleccionada y avisos para el cuidador. Settings → Reading details contiene el diagnóstico técnico. Las cinco métricas actuales abren Day/Week/Month. El movimiento de conexión es breve y reducible; no interpola datos ni atribuye resultados a un estudio de usabilidad no realizado.

El S22 SM-S901E / Android 13 conserva su sesión. La identidad del APK y su cliente HTTPS se comprobaron sin crear cuentas ni borrar datos; el onboarding actual se omitió por sesión existente. Las 878 comprobaciones de onboarding pertenecen al código 19. La revisión actual pasó **229 pruebas Python, 3.276 comprobaciones JVM, 24 verificaciones HTTPS loopback y 7.073 controles de preview**, con 48 capturas al 100 %/200 %.

Una campaña separada ejecutó **119 comprobaciones del sistema real**, con producción Java/recursos en `org.caremonitor.reportqa`, cuentas controladas y SQLite aislada. Recibió el wearable por SPP autenticado, retuvo 76 lecturas y alcanzó cero pendientes. Las **32 capturas nativas originales** muestran respuestas HTTPS reales: historia y Learning por ambos roles, vínculo aprobado, recordatorio y aviso de ayuda de prueba. Los ejercicios de IA siguen siendo sintéticos. No son fixtures en memoria, pacientes reales ni dos teléfonos simultáneos. La fecha visible UTC+8 del teléfono puede diferir de los registros UTC.

La línea base notificó 21/30; tras corregir el límite de tarjetas, dos campañas posteriores notificaron 30/30. La medición final fue p95 1.094,37 ms hasta publicación nativa, con pantalla encendida en el mismo S22 y HTTPS por USB. No mide sonido, respuesta humana, bloqueo de pantalla ni Wi-Fi. Se conserva el fallo inicial y su corrección en `evidence/usb_performance.json`.

Los conteos y versiones se centralizan en [CURRENT_REVISION.tex](evidence/CURRENT_REVISION.tex); la procedencia de capturas, en [system_screenshots.json](evidence/system_screenshots.json). Las evidencias fechadas están en [evidence/current/](evidence/current/) y [release/](../../release/). El atlas diferencia las capacidades implementadas de pasos, caídas autónomas y respaldo sonoro todavía no implementados en el firmware. No se deduce deshidratación de estos sensores.

Las pruebas físicas del **27 de octubre** se conservan aparte: `BT_PROTOCOL_EVIDENCE.json` registra 207 comprobaciones de autenticación y exclusividad; `APP_BLUETOOTH_SERVER_EVIDENCE.json` acredita el código 17 con 138 muestras y cero pendientes. No identifican el APK actual.

Los archivos [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json), [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json), [VALIDATION.json](evidence/VALIDATION.json), [jvm-current.txt](evidence/jvm-current.txt), [pytest-current.xml](evidence/pytest-current.xml) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json) se conservan como **instantáneas de la línea base del 26 de octubre**: código 13, 709 comprobaciones de interfaz, 88 pruebas Python y 17 verificaciones HTTPS. No identifican el APK actual. Los tiempos de clasificación, episodios e idempotencia también mantienen su fecha y condiciones originales; no se han vuelto a medir para esta revisión.

Los históricos utilizan rollups horarios cifrados, medias ponderadas por observaciones válidas y límites de intervalo exactos. Datos inválidos o ausentes producen huecos; la aplicación informa de cobertura incompleta mientras se indexan registros antiguos. La separación por paciente, autorización y origen se comprueba en [test_history.py](../../tests/test_history.py). Los ejercicios sintéticos, su retención e idempotencia se comprueban en [test_learning.py](../../tests/test_learning.py).

La fotografía [hardware_prototype.jpg](figures/hardware_prototype.jpg) reproduce el original aportado en `docs/steps_pictures/08603c4aa7d2d3b568362fc67e8d141a.jpg`; [hardware_photo.json](evidence/hardware_photo.json) registra su procedencia. Documenta el montaje visible, sin acreditar calibración ni precisión clínica. Las cinco capturas de desarrollo conservan su identificación histórica.

Las métricas de clasificación corresponden a las etiquetas y particiones sintéticas descritas. La temperatura superficial del GY906 nunca se transforma en temperatura corporal para este modelo. La procedencia institucional del CSV y la atribución individual de tareas no se deducen de los resultados ni de una identidad Git.

El ODT, la rúbrica y las imágenes originales se conservan. Esta revisión elimina del firmware fuente las entradas retiradas de micrófono y luz; compiló sin flashear hardware. La imagen instalada conserva campos heredados. Las pruebas y benchmarks requieren el repositorio completo y sus dependencias. Los scripts `tools/benchmark_*.py` usan bases temporales y cuentas ficticias; generan archivos `*-rerun.json` sin reemplazar las mediciones documentadas. La comparación histórica utiliza el commit `6757c0a`. No ejecutes la instrumentación de onboarding sobre una sesión real ni borres sus datos para superar la protección de sesión activa.

## Evidencia fotográfica complementaria

El informe ya incluye los dos roles, datos retenidos y diagramas. Un primer plano actualizado con la placa KEYES y las cuatro conexiones de módulo visibles documentaría el cableado físico con más detalle que la fotografía anterior. No se ha alterado la foto para simular la retirada de módulos.

El teléfono principal se actualiza al volver a conectarse; no se infiere su versión de las pruebas del S22. No ejecutes onboarding sobre una sesión real ni borres datos para superar su protección.
