# Informe IndiCare en LaTeX

Informe académico en inglés, revisado contra `rubrik.docx` y la implementación de CareMonitor 1.0.0. Incluye arquitectura, decisiones de diseño, resultados cuantitativos, fotografía del prototipo y evidencia de pruebas. El contador de prosa se genera automáticamente al compilar; excluye tablas, figuras, referencias y apéndices.

## Archivos

- `IndiCare_Report.pdf`: informe compilado y revisado visualmente.
- `IndiCare_Report.tex`: entrada principal del proyecto LaTeX.
- `metadata.tex`: cuatro integrantes con sus IDs, tutora Matilda Isaac y datos de portada.
- `sections/`: prosa, tablas, diagramas TikZ de despliegue, cableado, actores, flujo, clases, permisos, servidor, datos y seguridad, bibliografía y apéndices.
- `figures/`: fotografía real del prototipo, tres gráficos vectoriales de resultados, 85 capturas nativas actuales conservadas, 38 seleccionadas en el PDF, capturas de campañas anteriores y cinco imágenes históricas de desarrollo.
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

La revisión del **31 de octubre de 2026**, código **22**, prioriza ayuda y plan para el adulto, y persona seleccionada y avisos para el cuidador. Settings → Reading details reúne el diagnóstico técnico. Las cinco métricas abren históricos Day/Week/Month. Los números ópticos recibidos se muestran y conservan independientemente de sus banderas de calidad; las operaciones sin resultado permanecen ausentes. La animación no interpola datos de salud.

El apéndice **F** explica cada área de la interfaz con **38 capturas actuales**: entrada, cuatro pasos de cuenta, conexión del wearable, Today, cinco históricos, fechas, Updates, Care, Settings, notificaciones, privacidad, conexión, seguridad, About y Learning insights. Se muestran las tareas del adulto y del cuidador por separado. Las figuras principales también utilizan estas capturas.

La campaña física actual pasó **305 comprobaciones**, generó 83 archivos PNG únicos a partir de 89 operaciones y retuvo **78 lecturas físicas**, con cero pendientes. Ejecutó Java/recursos de producción en `org.caremonitor.reportqa`, nombres ficticios, cuentas reales controladas y HTTPS/SQLite aisladas. Las pantallas de registro son formularios antes del envío; las cuentas de adquisición se crearon por API. Las lecturas del adulto se capturaron durante SPP autenticado y entrega HTTPS; el cuidador consultó después las mismas observaciones guardadas. Son roles sucesivos en el S22, no dos teléfonos simultáneos. No se ensayaron nuevos tiempos de notificación en esta campaña.

Una pasada complementaria de Learning insights pasó **29 comprobaciones**, guardó tres ejercicios sintéticos y generó seis capturas; se publican dos adicionales, con Normal, Warning, Critical y sus puntuaciones visibles. No conectó el wearable. El manifiesto distingue la procedencia de los **85 PNG actuales** y verifica sus hashes contra los registros originales. Las imágenes no se retocaron para cambiar mediciones. El reloj visible del teléfono usa UTC+8; los registros conservan UTC.

El S22 SM-S901E / Android 13 conserva la cuenta y los datos de la aplicación principal. Se verificaron la identidad del APK code 22, recepción SPP y almacenamiento HTTPS. Las comprobaciones aisladas actuales son **248 pruebas Python, 3.381 aserciones JVM, 24 verificaciones HTTPS loopback y 7.198 controles de preview**, con 48 capturas al 100 %/200 %. La antigua campaña de 709 controles de onboarding conserva su identidad code 13.

La campaña de rendimiento del **30 de octubre**, code 20, se conserva por separado: pasó 119 comprobaciones y retuvo 76 lecturas físicas. Sus imágenes originales y resultados no se atribuyen a la nueva interfaz.

La línea base notificó 21/30; tras corregir el límite de tarjetas, dos campañas posteriores notificaron 30/30. La medición final fue p95 1.094,37 ms hasta publicación nativa, con pantalla encendida en el mismo S22 y HTTPS por USB. No mide sonido, respuesta humana, bloqueo de pantalla ni Wi-Fi. Se conserva el fallo inicial y su corrección en `evidence/usb_performance.json`.

Los conteos y versiones se centralizan en [CURRENT_REVISION.tex](evidence/CURRENT_REVISION.tex); la procedencia de capturas, en [system_screenshots.json](evidence/system_screenshots.json). Las evidencias fechadas están en [evidence/current/](evidence/current/) y [release/](../../release/). El atlas diferencia las capacidades implementadas de pasos, caídas autónomas y respaldo sonoro todavía no implementados en el firmware. No se deduce deshidratación de estos sensores.

Las pruebas físicas del **27 de octubre** se conservan aparte: `BT_PROTOCOL_EVIDENCE.json` registra 207 comprobaciones de autenticación y exclusividad; `APP_BLUETOOTH_SERVER_EVIDENCE.json` acredita el código 17 con 138 muestras y cero pendientes. No identifican el APK actual.

Los archivos [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json), [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json), [VALIDATION.json](evidence/VALIDATION.json), [jvm-current.txt](evidence/jvm-current.txt), [pytest-current.xml](evidence/pytest-current.xml) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json) se conservan como **instantáneas de la línea base del 26 de octubre**: código 13, 709 comprobaciones de interfaz, 88 pruebas Python y 17 verificaciones HTTPS. No identifican el APK actual. Los tiempos de clasificación, episodios e idempotencia también mantienen su fecha y condiciones originales; no se han vuelto a medir para esta revisión.

Los históricos utilizan rollups horarios cifrados, medias ponderadas por observaciones numéricas disponibles y límites de intervalo exactos. Las operaciones sin resultado producen huecos; las estimaciones numéricas ópticas se conservan con sus banderas de calidad. la aplicación informa de cobertura incompleta mientras se indexan registros antiguos. La separación por paciente, autorización y origen se comprueba en [test_history.py](../../tests/test_history.py). Los ejercicios sintéticos, su retención e idempotencia se comprueban en [test_learning.py](../../tests/test_learning.py).

La fotografía [hardware_prototype.jpg](figures/hardware_prototype.jpg) reproduce el original aportado en `docs/steps_pictures/08603c4aa7d2d3b568362fc67e8d141a.jpg`; [hardware_photo.json](evidence/hardware_photo.json) registra su procedencia. Documenta el montaje visible, sin acreditar calibración ni precisión clínica. Las cinco capturas de desarrollo conservan su identificación histórica.

Las métricas de clasificación corresponden a las etiquetas y particiones sintéticas descritas. La temperatura superficial del GY906 nunca se transforma en temperatura corporal para este modelo. La procedencia institucional del CSV no se deduce de los resultados. La matriz de contribuciones del apéndice D se basa en la asignación de tareas proporcionada por José; los artefactos asociados sitúan los entregables y Git registra su integración.

El ODT, la rúbrica y las imágenes originales se conservan. El firmware actual elimina las entradas retiradas de micrófono y luz. El 31 de octubre se compiló y escribió únicamente su aplicación, conservando particiones y NVS; el registro `evidence/current/FIRMWARE_RAW_ESTIMATES_EVIDENCE.json` identifica esa operación. La campaña histórica del 30 de octubre conserva sus campos heredados y su fecha. Las pruebas y benchmarks requieren el repositorio completo y sus dependencias. Los scripts `tools/benchmark_*.py` usan bases temporales y cuentas ficticias; generan archivos `*-rerun.json` sin reemplazar las mediciones documentadas. La comparación histórica utiliza el commit `6757c0a`. No ejecutes la instrumentación de onboarding sobre una sesión real ni borres sus datos para superar la protección de sesión activa.

## Evidencia fotográfica complementaria

El informe ya incluye los dos roles, datos retenidos y diagramas. Un primer plano actualizado con la placa KEYES y las cuatro conexiones de módulo visibles documentaría el cableado físico con más detalle que la fotografía anterior. No se ha alterado la foto para simular la retirada de módulos.

La identidad y ejecución descritas corresponden al S22 conectado; no se infiere la versión de otro teléfono. No ejecutes onboarding sobre una sesión real ni borres datos para superar su protección.

## Revisión editorial del 31 de octubre

Se cotejó el borrador real `enviado.pdf` con la revisión de la Dra. Matilda Isaac. La sección de análisis de modelos se conserva íntegra; se mantienen fiabilidad, seguridad, métricas y condiciones de las campañas. Se aclaran las adiciones posteriores: históricos y confianza nativos, responsabilidades por método, cadena sensor–API–móvil, publicación de avisos y cronología final. [FEEDBACK_COVERAGE.md](FEEDBACK_COVERAGE.md) registra la correspondencia de los siete puntos.

Los diagramas comparten [diagram_styles.tex](sections/diagram_styles.tex): azul pizarra, verde apagado, fondos claros, tipografía sin escalado y leyendas locales. Se rediseñaron despliegue, cableado, finalidad de sensores, actores, flujo, clases, permisos, servidor, datos, seguridad, secuencia y estados. El registro [editorial_revision.json](evidence/editorial_revision.json) identifica el borrador y la revisión por SHA-256; no traslada sus resultados a una campaña nueva. La revisión editorial inicial se conserva en su registro; la actualización de capturas posterior añade sus propios registros de ejecución y versiones, sin repetir los benchmarks históricos.

## Contribuciones confirmadas el 30 de octubre

José proporcionó la distribución individual y el trabajo compartido de los cuatro integrantes. Se incorporan en la sección 7 y en la matriz del apéndice D; [team_contributions.json](evidence/team_contributions.json) conserva la declaración estructurada. Las pruebas exploratorias de caída en las que participó Nathan se distinguen de los datos sintéticos y del archivo público SisFall usados en los experimentos documentados. No se atribuyen métricas a esas pruebas ni mejoras numéricas no medidas a la simplificación del JSON. Esta confirmación sustituye únicamente el estado de contribuciones incompletas de la instantánea histórica `report_inputs.json`.
