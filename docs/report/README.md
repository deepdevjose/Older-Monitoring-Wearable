# Informe IndiCare en LaTeX

Informe académico en inglés, revisado contra `rubrik.docx` y la implementación de CareMonitor 1.0.0. Incluye arquitectura, decisiones de diseño, resultados cuantitativos, fotografía del prototipo y evidencia de pruebas. El contador de prosa se genera automáticamente al compilar; excluye tablas, figuras, referencias y apéndices.

## Archivos

- `IndiCare_Report.pdf`: informe compilado y revisado visualmente.
- `IndiCare_Report.tex`: entrada principal del proyecto LaTeX.
- `metadata.tex`: cuatro integrantes con sus IDs, tutora Matilda Isaac y datos de portada.
- `sections/`: prosa, tablas, cuatro diagramas TikZ, bibliografía y apéndices.
- `figures/`: fotografía real del prototipo, tres gráficos vectoriales de resultados, dos vistas actuales de Today con perfiles ficticios y cinco capturas históricas recortadas.
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

La revisión de interfaz del **29 de octubre de 2026**, código **19**, organiza Today por rol: ayuda y próximo recordatorio para la persona mayor; persona aprobada, avisos pendientes y lecturas para el cuidador. Updates y Care mantienen navegación estable. La tarjeta técnica Recent readings se traslada a **Settings → Reading details**. Las seis métricas conservan Day/Week/Month y los ejercicios de Learning insights permanecen separados de las lecturas físicas.

La actualización de valores y antigüedad conserva las vistas y el foco. El movimiento de conexión es breve, se puede reducir y no interpola mediciones. Son decisiones de diseño orientadas a tareas de mayores y cuidadores; no se atribuyen a un estudio de usabilidad con participantes ni a evaluación manual con TalkBack.

El código 19 quedó instalado en el S22 SM-S901E / Android 13 con identidad de APK comprobada. La revisión pasó **229 pruebas Python**, **3.240 comprobaciones JVM**, **24 verificaciones HTTPS loopback**, **878 comprobaciones nativas** y **7.273 comprobaciones de preview**, con 48 capturas y texto al 100 %/200 %. Las dos vistas de Today del informe son fixtures sintéticos; no muestran pacientes ni sesiones reales.

Los conteos ejecutados, versión, hash y alcance de la instalación están centralizados en [CURRENT_REVISION.tex](evidence/CURRENT_REVISION.tex). Los registros de la revisión están en [evidence/current/](evidence/current/) y [release/](../../release/). La campaña de preview utiliza los mismos Java y recursos de producción en una app aislada, con ambos roles y texto al 100 %/200 %. Sus fixtures no autentican cuentas ni se conectan a sensores o al servidor.

Las pruebas físicas del **27 de octubre** se conservan aparte: `BT_PROTOCOL_EVIDENCE.json` registra 207 comprobaciones de autenticación y exclusividad; `APP_BLUETOOTH_SERVER_EVIDENCE.json` acredita el flujo normal del **código 17**, con 138 muestras, cero pendientes y confirmación del servidor. Su fecha y versión no se trasladan al APK posterior. `ANDROID_CODE16_BASELINE.json` conserva las 759 comprobaciones nativas y HTTPS de esa versión.

Los archivos [ANDROID_BUILD.json](evidence/ANDROID_BUILD.json), [ANDROID_UI_EVIDENCE.json](evidence/ANDROID_UI_EVIDENCE.json), [VALIDATION.json](evidence/VALIDATION.json), [jvm-current.txt](evidence/jvm-current.txt), [pytest-current.xml](evidence/pytest-current.xml) e [INTEGRATION_EVIDENCE.json](evidence/INTEGRATION_EVIDENCE.json) se conservan como **instantáneas de la línea base del 26 de octubre**: código 13, 709 comprobaciones de interfaz, 88 pruebas Python y 17 verificaciones HTTPS. No identifican el APK actual. Los tiempos de clasificación, episodios e idempotencia también mantienen su fecha y condiciones originales; no se han vuelto a medir para esta revisión.

Los históricos utilizan rollups horarios cifrados, medias ponderadas por observaciones válidas y límites de intervalo exactos. Datos inválidos o ausentes producen huecos; la aplicación informa de cobertura incompleta mientras se indexan registros antiguos. La separación por paciente, autorización y origen se comprueba en [test_history.py](../../tests/test_history.py). Los ejercicios sintéticos, su retención e idempotencia se comprueban en [test_learning.py](../../tests/test_learning.py).

La fotografía [hardware_prototype.jpg](figures/hardware_prototype.jpg) reproduce el original aportado en `docs/steps_pictures/08603c4aa7d2d3b568362fc67e8d141a.jpg`; [hardware_photo.json](evidence/hardware_photo.json) registra su procedencia. Documenta el montaje visible, sin acreditar calibración ni precisión clínica. Las cinco capturas de desarrollo conservan su identificación histórica.

Las métricas de clasificación corresponden a las etiquetas y particiones sintéticas descritas. La temperatura superficial del GY906 nunca se transforma en temperatura corporal para este modelo. La procedencia institucional del CSV y la atribución individual de tareas no se deducen de los resultados ni de una identidad Git.

El ODT, la rúbrica y las imágenes originales se conservan. Esta revisión documental no modifica firmware ni flashea hardware. Las pruebas y benchmarks requieren el repositorio completo y sus dependencias. Los scripts `tools/benchmark_*.py` usan bases temporales y cuentas ficticias; generan archivos `*-rerun.json` sin reemplazar las mediciones documentadas. La comparación histórica utiliza el commit `6757c0a`. No ejecutes la instrumentación de onboarding sobre una sesión real ni borres sus datos para superar la protección de sesión activa.

## Fotografías que complementarían el informe

La fotografía del prototipo ya está incorporada. Para ampliar la evidencia visual resultan útiles:

- Un primer plano nítido de la placa, sensores y conexiones, con los componentes visibles.
- El teléfono en el flujo de la persona mayor: registro por pasos y conexión del wearable.
- El teléfono del cuidador mostrando una alerta y su revisión, con cuentas y lecturas ficticias.

Cada imagen debe llevar una descripción breve de lo que muestra. Evitar contraseñas, tokens, identificadores privados y datos de pacientes reales. Una pantalla de lectura se presenta como captura del estado observado, sin atribuirle validación clínica.

El alcance del APK nuevo y la retirada de los paquetes auxiliares quedan registrados en la evidencia de su propia campaña. El teléfono principal se actualiza al volver a conectarse; no se infiere su versión de las pruebas del S22.
