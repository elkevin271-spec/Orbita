# ÓRBITA SPATIAL 1.0 — Android

Proyecto original de audio binaural 3D, programado en Java sin dependencias de reproducción externas.

## Qué incorpora

- **Reproductor interno con procesamiento real** antes de salida por `AudioTrack`.
- **Decodificación en flujo** mediante `MediaExtractor` + `MediaCodec`, con archivos MP3, M4A, AAC, FLAC, OGG y otros formatos soportados *por el Android del dispositivo*. También soporta PCM mono/estéreo y mezcla de hasta 8 canales donde lo permita el decodificador.
- **Modelo HRTF paramétrico**, con diferencias interaurales de tiempo e intensidad, filtrado por sombra de cabeza, señales espectrales frontal/trasera, distancia y primeras reflexiones. No se afirma que sean HRIR medidas de personas reales.
- **Tres presets**: Órbita 8D, Cine 3D, Realismo. Reguladores de movimiento, velocidad, reflejos, mezcla efecto/original, distancia y balance estéreo.
- **Demo original** de golpes de puerta, primero a la izquierda, después detrás, y a distintas distancias. No requiere conexión a internet.
- **Comparación A/B** con mezcla original, pausa, continuar, barra de búsqueda para archivos y parada.
- **Servicio foreground `mediaPlayback`**, notificación con controles, audio focus, detección de desconexión de auriculares, wakelock liberado al terminar y persistencia de acceso SAF al archivo.
- **Captura experimental** `MediaProjection` + `AudioPlaybackCapture` *únicamente cuando la app origen lo permite*, previa autorización explícita.
- **Aislamiento de recaptura** con política `ALLOW_CAPTURE_BY_NONE` para la salida propia.
- **Sin INTERNET permission, sin publicidad, sin rastreadores y sin SDK externos**.

## Limitaciones y precisión técnica

- Android no permite a una app ordinaria reemplazar universalmente el audio de YouTube y demás apps. La API de captura tiene condiciones y puede ofrecer **silencio** o **audio duplicado**; no hay bypass de DRM o de políticas de captura.
- La binauralidad más clara se percibe con **auriculares estéreo** y a volumen moderado; en altavoces de teléfono es mucho menos efectiva.
- El motor modela acústicamente la cabeza pero **no usa HRIR medidas ni personalizadas**. Para una versión profesional con HRIR/SOFA reales sería necesaria una base de respuestas impulsionales y su convolución, además de validación perceptiva con usuarios.
- La música estéreo NO está separada en stems (instrumentos individuales); son **dos fuentes virtuales preservando su contenido estéreo**.
- Reproducción interna y pruebas se programaron, pero **la aplicación completa aún no se ha compilado ni ejecutado en un teléfono** en el entorno de entrega. Los tests Java puros solo verifican el motor acústico.
- Requiere **Android 10 / API 29 o posterior**.

## Compilar la APK mediante GitHub Actions (sin Android Studio)

1. Crea un repositorio **privado** en GitHub.
2. Extrae el ZIP y sube **todo su contenido a la raíz** del repositorio, incluida la carpeta oculta `.github/workflows/`.
3. En GitHub abre **Actions → Compilar Orbita Spatial APK** y pulsa **Run workflow** (o realiza un `push` a `main`).
4. Tras un trabajo exitoso, entra a la ejecución y descarga el artefacto **Orbita-Spatial-1.0-APK**.
5. Extrae `app-debug.apk` del ZIP del artefacto e instálala. Es una **build de depuración** firmada automáticamente por el entorno de GitHub, suficiente para pruebas personales. No es una edición publicada en Play Store.

Para compilar localmente con Android Studio (JDK 17, Android SDK 35) abre el directorio raíz, sincroniza Gradle y usa `Build → Build Bundle(s)/APK(s) → Build APK(s)`.

La ID de aplicación es `hn.orbita.spatial`, diferente a Órbita 0.6 (`hn.orbita`), para **poder instalar ambas** sin errores de incompatibilidad de firma durante las pruebas.

## Prueba del motor en computadora

```bash
bash tests/test-engine.sh
```

Requiere Java 17+ con `javac`. Evalúa silencio, señal audible, diferenciación binaural, balance y síntesis original. También puedes producir una prueba estéreo de 13 segundos:

```bash
javac -encoding UTF-8 -cp build/test-classes -d build/test-classes tests/RenderDemo.java
java -cp build/test-classes RenderDemo prueba-binaural.wav
```

## Uso sugerido

1. Conecta auriculares estéreo y empieza con un volumen bajo/moderado.
2. Toca **Probar golpes binaurales 3D** para verificar que realmente se reproduce audio procesado.
3. Después elige un archivo de música desde el teléfono.
4. Selecciona **Realismo**, movimiento 20–45%, sala 15–35%, mezcla 75–90%, distancia 20–40%.
5. Minimiza Órbita, bloquea el teléfono y verifica los controles de la notificación.
6. En Android/Xiaomi, si el sistema suspende el servicio, revisa los ajustes de batería de la app y considera permitir funcionamiento sin restricciones mientras reproduces, si tu sistema ofrece esa opción.
7. Usa captura externa solo tras aceptar el cuadro oficial de Android; si no se oye, no significa que el reproductor esté roto.

## Arquitectura

```
app/src/main/java/hn/orbita/
  MainActivity.java         -> interfaz y permisos explícitos
  SpatialStageView.java     -> visualización orientativa 360°
  OrbitService.java         -> servicio foreground, decoder, AudioTrack, AudioRecord
  SpatialEngine.java       -> DSP binaural puro, estereo, distancia, balance, reverb
  DemoKnocks.java          -> golpes originales / prueba de localización

tests/
  AudioEngineTest.java     -> comprobaciones de audio automáticas
  RenderDemo.java          -> renderizado offline a WAV estéreo
  test-engine.sh           -> ejecutor de tests

.github/workflows/
  compilar-apk.yml         -> construcción de app-debug.apk en GitHub Actions
```

## Privacidad y seguridad

La captura utiliza el flujo de autorización de Android, no ocurre silenciosamente y puede terminarse desde la aplicación o notificación. No se recopilan datos ni se envían archivos a ningún servidor. El audio de archivos seleccionados se procesa en memoria de manera local. Usa volumen moderado para las pruebas binaurales.
