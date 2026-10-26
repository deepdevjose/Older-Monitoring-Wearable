# Informe IndiCare en LaTeX

Informe académico en inglés, revisado contra `rubrik.docx` y la implementación de CareMonitor 1.0.0. Incluye arquitectura, decisiones de diseño, resultados cuantitativos, fotografía del prototipo y evidencia de pruebas. El contador de prosa se genera automáticamente al compilar; excluye tablas, figuras, referencias y apéndices.

## Archivos

- `IndiCare_Report.pdf`: informe compilado y revisado visualmente.
- `IndiCare_Report.tex`: entrada principal del proyecto LaTeX.
- `metadata.tex`: cuatro integrantes con sus IDs, tutora Matilda Isaac y datos de portada.
- `sections/`: prosa, tablas, cuatro diagramas TikZ, bibliografía y apéndices.
- `figures/`: fotografía real del prototipo, tres gráficos vectoriales de resultados y cinco capturas históricas recortadas.
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

La revisión incluye el APK 1.0.0, código de versión 13, instalado en un Samsung SM-S948B con Android 17. Se registran 709 comprobaciones de interfaz, incluido el registro con texto al 200 %, 2.830 comprobaciones JVM, 88 pruebas Python y 17 verificaciones de integración HTTPS por loopback. Son categorías distintas de evidencia: las comprobaciones de interfaz no prueban el recorrido físico completo entre wearable, Bluetooth, teléfono y servidor.

Los registros se conservan en [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json), [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json), [VALIDATION.json](evidence/VALIDATION.json), [jvm-current.txt](evidence/jvm-current.txt), [pytest-current.xml](evidence/pytest-current.xml) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json). Cada archivo conserva su contexto de ejecución; no se agrupan sus resultados como una única prueba de extremo a extremo.

La fotografía [hardware_prototype.jpg](figures/hardware_prototype.jpg) reproduce el archivo original aportado en `docs/steps_pictures/08603c4aa7d2d3b568362fc67e8d141a.jpg`; su procedencia se registra en [hardware_photo.json](evidence/hardware_photo.json). Documenta el montaje visible; no acredita por sí sola calibración, autonomía, entrega Bluetooth ni precisión clínica. Las cinco capturas de desarrollo conservan su identificación histórica.

Los resultados de clasificación corresponden a etiquetas sintéticas y a las particiones descritas en el informe. La API de clasificación y confianza se evalúa por separado de la interfaz móvil; `SensorChartView` no forma parte de la navegación montada. La procedencia institucional del CSV y la atribución individual de tareas no se deducen de los resultados ni de una identidad del historial Git.

El ODT, la rúbrica y las imágenes originales se conservan. Esta revisión documental no modifica firmware ni flashea hardware. Para repetir las pruebas o benchmarks se necesita el repositorio completo y sus dependencias. Los scripts `tools/benchmark_*.py` usan bases temporales y cuentas ficticias; generan archivos `*-rerun.json` sin reemplazar las mediciones documentadas. La comparación histórica utiliza el commit `6757c0a`. Los tiempos pueden variar entre ejecuciones; los gráficos usan las mediciones guardadas.

## Fotografías que complementarían el informe

La fotografía del prototipo ya está incorporada. Para ampliar la evidencia visual resultan útiles:

- Un primer plano nítido de la placa, sensores y conexiones, con los componentes visibles.
- El teléfono en el flujo de la persona mayor: registro por pasos y conexión del wearable.
- El teléfono del cuidador mostrando una alerta y su revisión, con cuentas y lecturas ficticias.

Cada imagen debe llevar una descripción breve de lo que muestra. Evitar contraseñas, tokens, identificadores privados y datos de pacientes reales. Una pantalla de lectura se presenta como captura del estado observado, sin atribuirle validación clínica.
