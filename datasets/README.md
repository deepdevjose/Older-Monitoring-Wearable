# Datos y aprendizaje automático académico

Esta carpeta es la única ubicación de los datasets. No contiene registros de los usuarios de la app. El producto conserva el ML en demostraciones separadas: clasificación de salud sintética y replay offline de registros públicos SisFall. Ningún resultado de estos modelos puede activar un diagnóstico, indicar medicación ni sustituir una evaluación humana.

## CSV sintético existente

`synthetic_health.csv` contiene 60 000 filas y 13 columnas. Se recibió como parte del repositorio; no se ha acreditado el generador, el autor original, la licencia ni un protocolo clínico. Por eso los resultados describen únicamente este archivo y no deben difundirse como evaluación médica ni redistribuirse fuera del contexto autorizado sin aclarar su procedencia.

Las entradas del experimento son **Heart Rate (bpm)**, **SpO2 Level (%)** y **Body Temperature (°C)**. Estas tres columnas existen en el dataset, pero la tercera **no equivale a `gy906.object_temp_c`**. No se transforma una lectura infrarroja de superficie en temperatura central ni se conecta el clasificador a telemetría real.

Se usa una etiqueta educativa propia basada exclusivamente en las etiquetas originales:

| Número de `ABNORMAL` entre Heart Rate Alert, SpO2 Level Alert y Temperature Alert | Clase educativa | Filas |
|---|---|---:|
| 0 | Normal | 18 525 |
| 1 | Warning | 22 226 |
| 2 o 3 | Critical | 19 249 |

`Critical` significa acumulación de etiquetas sintéticas, no emergencia clínica. No se inventa un umbral médico ni se usa `Predicted Disease` como verdad clínica. Se excluyen como entradas: Patient Number, Predicted Disease, Data Accuracy, todas las columnas Alert, Fall Detection y ambas presiones arteriales. El hardware no dispone de un sensor de presión o hidratación.

## Experimento reproducible

```bash
python tools/train_models.py
python -m pytest -q tests/test_ml.py
```

Se necesitan NumPy, pandas, scikit-learn, joblib, matplotlib y pytest. Las versiones usadas quedan registradas en `models/synthetic-health-v1/metadata.json`. La semilla es 42 en todas las operaciones aleatorias y el bosque usa un solo hilo para mantener el resultado determinista. Para comparar ejecuciones sin sobrescribir los artefactos canónicos:

```bash
python tools/train_models.py --output .local/model-reproduction
```

Se agrupan las **18 939 tuplas de características únicas** antes de dividir: las repeticiones de una misma tupla nunca aparecen en particiones diferentes. No se encontraron tuplas con etiquetas contradictorias. Se divide aproximadamente 60/20/20 de forma estratificada; al llevar todas las repeticiones de cada grupo a su partición, quedan 36 090 filas de entrenamiento, 12 143 de validación y 11 767 de prueba. Esta separación reduce la memorización de duplicados; no crea independencia clínica ni diversidad de pacientes reales.

La regresión logística multinomial incluye `StandardScaler` ajustado únicamente con entrenamiento. El bosque aleatorio usa 120 árboles, profundidad máxima 14 y hoja mínima de 2. Ambos usan pesos de clase equilibrados. Se selecciona exclusivamente por F1 macro de **validación** y, en empate, recall de Critical; el conjunto de prueba no elige ni ajusta el modelo. El bosque fue seleccionado con F1 macro de validación 1.000 frente a 0.7534 de regresión logística.

Resultados de prueba:

| Modelo | Clase | Precision | Recall | F1 | Soporte |
|---|---|---:|---:|---:|---:|
| Regresión logística | Normal | 0.8439 | 0.8752 | 0.8593 | 3366 |
| Regresión logística | Warning | 0.6977 | 0.6532 | 0.6747 | 4544 |
| Regresión logística | Critical | 0.7340 | 0.7654 | 0.7493 | 3857 |
| Bosque aleatorio | Normal | 1.0000 | 1.0000 | 1.0000 | 3366 |
| Bosque aleatorio | Warning | 1.0000 | 1.0000 | 1.0000 | 4544 |
| Bosque aleatorio | Critical | 1.0000 | 1.0000 | 1.0000 | 3857 |

F1 macro de prueba: regresión logística **0.7611**, bosque **1.0000**. La reconstrucción perfecta del bosque concuerda con etiquetas deterministas y límites simples en este CSV: **no demuestra detección de enfermedad, seguridad, generalización a adultos mayores ni precisión clínica**. `confidence` es un score de clase sin calibración, no probabilidad de accidente o enfermedad.

En `models/synthetic-health-v1/` se conservan dos modelos propios `.joblib`, `metadata.json` con hashes SHA-256 del CSV y los modelos, métricas por clase, matrices numéricas, particiones y versiones, y cuatro matrices de confusión `.png`. Los PNG usan filas de clase verdadera y columnas de predicción en el orden Normal, Warning, Critical. La normalización de regresión logística vive dentro de su pipeline.

`server/caremonitor/ml.py` verifica el hash antes de deserializar el artefacto y rechaza cambios de versión de scikit-learn. Joblib puede ejecutar código: aceptar un hash desde un manifiesto también alterado no autentica el modelo. Proteger ambos archivos con permisos y desplegar únicamente artefactos generados localmente; nunca cargar modelos aportados por terceros. Si se cambia la versión de las dependencias, reentrenar localmente y comparar métricas.

Interface de uso:

```python
from server.caremonitor.ml import ModelService

result = ModelService().predict({
    "heart_rate_bpm": 80,
    "spo2_percent": 98,
    "body_temp_c": 36.8,
    "sample_age_seconds": 1,
}, source="synthetic")
```

La API rechaza datos reales, campos faltantes o adicionales, booleanos, cadenas, NaN/infinito, valores fuera del soporte del dataset y muestras con edad mayor a 10 s o negativa. No infiere frescura automáticamente de un número suministrado por el llamador: la ruta autenticada debe controlar el origen y el timestamp. Devuelve clasificación, confianza, model_id y limitaciones explícitas.

## SisFall

`sisfall.tar.gz.part01`, `.part02` y `.part03` conservan los bytes del conjunto recibido, con hashes y orden en `sisfall-manifest.json`. Los fragmentos tienen 90 MiB, 90 MiB y aproximadamente 35.75 MiB para poder versionarlos sin un blob mayor de 100 MiB. El archivo completo se prepara únicamente como caché ignorada en `.local/datasets/sisfall.tar.gz`; no es otra fuente editable. No se extrae masivamente ni se usa para el modelo de salud sintética anterior. Su `SisFall_dataset/Readme.txt` original describe 4510 archivos, códigos D de actividades diarias y F de caídas, 9 canales a **200 Hz**, acelerómetros ADXL345 y MMA8451Q y giroscopio ITG3200. El README contiene datos demográficos por sujeto: no copiar ni registrar esos datos como información de pacientes de CareMonitor.

Una división aleatoria por filas de una misma ventana o persona introduce fuga de información. El wearable actual emite aproximadamente un snapshot por segundo y usa MPU6050; no existe equivalencia probada con SisFall. Interpolar 1 Hz a 200 Hz no recupera impactos ni constituye validación. **El experimento offline descrito a continuación no se despliega como detector del wearable.** Un detector temporal futuro requeriría ventanas con anotaciones de eventos, calibración de posición/unidades y pruebas con sensores y frecuencias equivalentes.

Presión arterial, deshidratación y accidente por micrófono tampoco tienen ground truth validado ni sensores suficientes aquí. Deben presentarse como funciones no disponibles o investigación futura, nunca como diagnósticos calculados a partir de pulso, temperatura o ruido aislado.

## Clasificación offline de archivos SisFall

Este segundo experimento usa **archivos completos**, no muestras instantáneas ni ventanas débilmente etiquetadas. El objetivo binario es `fall_record` si el nombre empieza por F, o `adl_record` si empieza por D. La etiqueta identifica la actividad del archivo, sin afirmar cuándo ocurrió una caída ni asignarla a todas sus muestras. Los datos ya existían en el repositorio; no se adquirieron registros nuevos de personas.

La adquisición original utilizó ADXL345 en cintura, ±16 g, 13 bits y 200 Hz; la conversión de sus tres primeras columnas es `a_g = raw * 32 / 8192 = raw / 256`. La configuración está descrita por los autores en [SisFall, 2017](https://pmc.ncbi.nlm.nih.gov/articles/PMC5298771/) y en [su trabajo posterior de 2018](https://www.mdpi.com/1424-8220/18/4/1101). Este pipeline es un experimento propio de clasificación y no reproduce el algoritmo temporal de esos artículos.

```bash
python tools/prepare_sisfall.py
python tools/train_fall_model.py
python -m pytest -q tests/test_fall_ml.py
python tools/train_fall_model.py --output .local/sisfall-reproduction
```

`prepare_sisfall.py` verifica tamaño y SHA-256 de los tres fragmentos y del archivo final, ensambla mediante escritura temporal y sustitución atómica, y reutiliza la caché si ya coincide. No extrae TXT y no requiere Git LFS, nube ni descarga. El comando de entrenamiento lo invoca automáticamente cuando no se proporciona `--archive`; `--dataset` sigue siendo un alias compatible para un tar explícito. El archivo recibido originalmente se trasladó a esa misma caché, sin borrar sus bytes. Su hash es `91de73bb1ea5cc3499d80a97d063dc504b244cf5449f61fd29d7fae90db41571` y su tamaño exacto es 226 226 447 bytes; coincide con la procedencia de los modelos ya entrenados.

El tar se recorre en streaming y se procesa un registro a la vez en memoria, sin extraer miles de archivos. Se ignoran líneas vacías de formato. Se encontraron **cinco registros con identidad de sujeto ambigua**: `D17_SE15_R01.txt` a `D17_SE15_R05.txt` aparecen dentro de la carpeta `SA15`. Se excluyeron y documentaron en el manifiesto del modelo; no se adivinó una corrección. Quedaron **4505 registros de 38 sujetos**.

Se calculan siete características sobre todo el archivo, manteniendo la gravedad y sin filtrar/remuestrear:

| Característica de entrada | Definición |
|---|---|
| `magnitude_max_g` | Máximo de la norma euclídea de aceleración |
| `magnitude_min_g` | Mínimo de esa norma |
| `magnitude_mean_g` | Media de esa norma |
| `magnitude_std_g` | Desviación estándar poblacional, `ddof=0` |
| `magnitude_p95_g` | Percentil 95 con interpolación lineal |
| `jerk_rms_g_s` | RMS de la norma del vector `diff(a_g) * 200`, en g/s |
| `orientation_change_deg` | Ángulo entre los vectores de aceleración media del primer y último segundo |

Se excluyen duración, número de muestras, ID de sujeto, ensayo, código de actividad, nombre del archivo y demografía. La duración podría revelar el protocolo (muchas caídas duran 15 s y varias ADL 12/25/100 s), produciendo un atajo artificial. Aun excluyéndola, el resumen completo y la postura final facilitan la clasificación y utilizan información futura: los resultados no representan latencia, sensibilidad por evento ni tasa de falsas alarmas por hora.

`GroupShuffleSplit`, semilla 42, separa primero 60/40 de sujetos y después divide ese 40% en validación/prueba. No hay personas compartidas entre particiones ni se repite el split para mejorar métricas:

| Partición | Sujetos | Archivos ADL | Archivos F | Total |
|---|---:|---:|---:|---:|
| Entrenamiento | 22 | 1572 | 1050 | 2622 |
| Validación | 8 | 560 | 375 | 935 |
| Prueba | 8 | 570 | 373 | 943 |

Se comparan regresión logística con escalado ajustado solo en entrenamiento y bosque aleatorio de 160 árboles, profundidad máxima 12 y hoja mínima 3. Ambos usan pesos equilibrados. El criterio es F1 macro de validación, después recall de `fall_record`; en empate exacto se prefiere la regresión logística por simplicidad. Ambos obtuvieron F1 macro de validación **0.9988864** y el mismo recall; se seleccionó **regresión logística** sin consultar prueba para decidir.

Resultados de prueba de archivos completos:

| Modelo | Clase | Precision | Recall | F1 | Soporte |
|---|---|---:|---:|---:|---:|
| Regresión logística | adl_record | 0.99825 | 1.00000 | 0.99912 | 570 |
| Regresión logística | fall_record | 1.00000 | 0.99732 | 0.99866 | 373 |
| Bosque aleatorio | adl_record | 0.99650 | 1.00000 | 0.99825 | 570 |
| Bosque aleatorio | fall_record | 1.00000 | 0.99464 | 0.99731 | 373 |

F1 macro de prueba: **0.9988906** en regresión logística y **0.9977803** en bosque. Matriz de la seleccionada, filas verdaderas y columnas predichas en orden ADL/F: `[[570, 0], [1, 372]]`. Estas cifras pertenecen a actividades controladas y caídas simuladas; no permiten prometer detección de accidentes reales.

El holdout de sujetos mayores contiene **177 ADL de tres sujetos y ninguna caída**. Por ello **no puede estimarse su sensibilidad a caídas de adultos mayores**. Los ceros que `classification_report` escribe para una clase sin soporte son marcadores de cálculo, no una sensibilidad medida de 0%; el manifiesto también declara `fall_recall_evaluable: false` y las clases sin soporte.

Los artefactos propios viven exclusivamente en `models/sisfall-v1/`: dos `.joblib`, manifiesto con hashes/versiones/sujetos/descartes/métricas, cuatro PNG y `replay_examples.json`. Los dos ejemplos del replay son resúmenes de archivos públicos anónimos del conjunto de prueba, uno ADL y uno F, sin señales crudas ni datos de pacientes de la app.

```python
import json
from pathlib import Path
from caremonitor.fall_ml import FallModelService

example = json.loads(Path("models/sisfall-v1/replay_examples.json").read_text())["examples"][0]
result = FallModelService().predict(
    example["features"], source="dataset_replay", sample_rate_hz=200, record_complete=True,
)
```

`FallModelService` exige exactamente las siete características, valores finitos y coherentes, frecuencia entera de 200 Hz, archivo completo y origen `dataset_replay`. Rechaza `device_diagnostic`, frecuencia 1 Hz y snapshots. Verifica SHA-256 y la versión de scikit-learn antes de cargar los bytes del modelo; se aplican las mismas precauciones de confianza/permisos de Joblib indicadas arriba. El origen y el archivo completo son declaraciones del llamador, no una prueba criptográfica: la ruta autenticada debe conservar su uso como demostración. Su respuesta es educativa y no crea alertas reales ni confirma emergencias.
