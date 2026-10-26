# Seguridad, privacidad y límites éticos

Este proyecto funciona como **demostrador académico**. Esta decisión fue confirmada por el propietario el 25 de octubre de 2026. El documento de rúbrica incluido en `reference-labs.zip` exige datos sintéticos o anonimizados aprobados y no autoriza reclutar participantes, provocar caídas ni recolectar salud personal. No es un producto médico ni un sistema de respuesta de emergencias.

## Tríada CIA y amenazas

| Objetivo | Controles implementados | Límite que permanece |
|---|---|---|
| Confidencialidad | HTTPS con comprobación de nombre/certificado; contraseñas Argon2id; sesiones aleatorias almacenadas como hash; permisos por adulto; campos sensibles AES-256-GCM en SQLite y AndroidKeyStore en teléfono; pantalla protegida de capturas; ubicación cifrada de 24 h; audio exclusivamente RAM | Roles, identificadores, tipos de eventos y tiempos son metadatos visibles en SQLite. La clave de cifrado vive en el equipo: proteger cuenta, disco y copias de seguridad sigue siendo necesario. No equivale a cifrado de disco ni elimina un equipo comprometido. |
| Integridad | AEAD con AAD por registro; validación JSON y límites; transacciones; identificador estable de muestra e idempotencia; aprobación explícita del adulto; auditoría; firma del APK y manifiestos de hashes | Un dispositivo o cuenta comprometidos pueden fabricar muestras. La telemetría serial no acredita la identidad del binario instalado. Los hashes de modelos no sustituyen una firma si un atacante modifica también el manifiesto. |
| Disponibilidad | Cola persistente y cifrada del teléfono; reintento; límites de carga; SQLite WAL; buzón durable; fanout a cuidadores; servicio de avisos local opcional; copias cifradas; diagnóstico y estado de fallo visibles | Equipo apagado, red caída, Bluetooth fuera de alcance, permisos denegados, fuerza de cierre o restricciones de Android pueden interrumpir el sistema. No existe garantía de entrega de emergencias ni alta disponibilidad entre varios servidores. |

No hay Firebase, Google Cloud, analítica, anuncios, mapas externos, geocodificación ni servicios SaaS. Android SDK/JDK y librerías se utilizan para compilar localmente. El canal de datos y avisos termina en este servidor.

## Roles y consentimiento

- Cada persona usa una cuenta y sesión propias. No hay usuario, contraseña administrativa ni API key compartida por defecto.
- El adulto crea un código de cuatro cifras de diez minutos. Un cuidador autenticado presenta el código para **solicitar** acceso; el adulto revisa su identidad y aprueba. El código no autentica a la persona ni concede datos por sí solo. Hay límites de intentos persistentes y uso único.
- Un adulto puede vincular varios cuidadores, generando una invitación por aprobación. Puede revocarlos. La revocación bloquea consultas y elimina avisos pendientes de ese vínculo.
- Ubicación y micrófono requieren consentimiento explícito, permisos Android y una acción visible para empezar. Detenerlos es accesible desde la app y la notificación. El cuidador no puede activarlos remotamente.
- El servidor recibe coordenadas solo con consentimiento vigente y `outside_home=true`; el teléfono compara localmente GPS, precisión y radio de casa. El servidor no puede comprobar que un cliente modificado diga la verdad sobre ese radio. La casa configurada se guarda cifrada en el teléfono.
- El audio no se graba, transmite ni conserva. Solo se calcula intensidad local; una señal fuerte pide confirmación humana y **no identifica accidentes**. Nunca se usa para escuchar conversaciones de terceros.

## Qué significa cada función

| Solicitud | Implementación y límite |
|---|---|
| Pulso alto/bajo y oxígeno bajo | Reglas transparentes de escenario sintético, con validez, frescura, persistencia y recuperación; los datos de dispositivo no generan diagnósticos. La regla de oxígeno del laboratorio es `<95`, sin significado clínico atribuido. |
| Oxígeno alto | Un valor superior a 100 % se trata como inválido. No se diagnostica hiperoxia con este sensor. |
| Temperatura corporal extrema | Se muestra temperatura **superficial** y ambiente; no se convierte a temperatura central, fiebre ni hipotermia. Los escenarios de alerta son educativos. |
| Temperatura ambiental extrema | Escenarios educativos separados de la superficie. No establece exposición clínica individual. |
| Wearable puesto/no puesto | Oscuridad puede provenir de una habitación, bolsillo o ropa. Se informa incertidumbre; contacto óptico puede corroborar, sin demostrar uso. |
| Presión arterial | No disponible: no existe sensor de presión arterial ni modelo clínico validado para inferirla. |
| Deshidratación | Recordatorio de agua, no detector. Estos sensores no establecen hidratación. |
| Caída/accidente | Confirmación humana y SOS. Los snapshots ESP32 de 1 Hz no permiten declarar un detector de caídas validado. Los experimentos ML offline no acreditan funcionamiento sobre el wearable o un teléfono en cualquier posición. |
| Medicación | Un cuidador vinculado puede transcribir un recordatorio acordado, corregirlo cancelándolo y crear otro. No prescribe, recomienda dosis ni verifica ingestión. Confirmar una toma registra una declaración, no adherencia demostrada. No se sugieren dosis compensatorias. |
| IA Normal/Warning/Critical | Clasificación exclusivamente sintética; probabilidades de clase sin calibración clínica. Las etiquetas se derivan de columnas de alertas del dataset, no de profesionales ni resultados reales. |

La ausencia de alertas significa que no hay eventos disponibles para mostrar; no significa que la persona esté bien. Revisar una alerta no resuelve la condición ni equivale a prestar atención médica.

## Retención y operación

El servidor elimina ubicaciones a las 24 horas, lecturas/eventos a los 30 días y auditoría a los 90 días. Los recordatorios y alertas constituyen el historial académico mientras su cuenta exista; exportación/borrado de una cuenta completa y una política legal de conservación deben definirse antes de uso con personas. Las copias `.cmbackup` requieren passphrase y contienen la base y su clave cifradas juntas; guardar la passphrase por separado. El respaldo local de consolidación también contiene archivos históricos y es privado; no distribuirlo ni subirlo a Git.

La firma actual del APK es de desarrollo y su clave se mantiene fuera del repositorio. Una revisión independiente de seguridad, pruebas en el Samsung real, revisión institucional y validación de sensores serían necesarias antes de ampliar el uso. No se afirma conformidad certificada con HIPAA, GDPR, PIPL ni una normativa médica: jurisdicción, responsabilidades y bases legales no han sido definidas.

## Fuentes primarias consultadas

- [OMS: Ethics and governance of artificial intelligence for health](https://www.who.int/publications/i/item/9789240029200): autonomía, seguridad, transparencia y supervisión humana.
- [OWASP: Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html) y [Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html): TLS, límites de intentos y contraseñas resistentes a ataques offline.
- [Android: tipos de foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types): funciones visibles y permisos por tipo de servicio.
- [Android 17: permiso de red local](https://developer.android.com/privacy-and-security/local-network-permission): requisito de permiso LAN para apps que apuntan a SDK 37.
- `reference-labs.zip`: pasos 1–6 y borrador de rúbrica del propio proyecto. Se usa como contexto académico, no como evidencia de funciones ejecutadas.
