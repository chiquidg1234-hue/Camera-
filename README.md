# GrabaFondo

App Android nativa (Kotlin + Jetpack Compose + CameraX) para grabarte a ti mismo mientras usas
otras apps. La cámara sigue grabando en segundo plano dentro de un *foreground service* de tipo
`camera`, con notificación persistente y el indicador de cámara del sistema siempre visibles.

- minSdk 29 (Android 10), targetSdk/compileSdk 35 (Android 15)
- Vídeo MP4 en segmentos (10 min por defecto) en `Movies/GrabaFondo` vía MediaStore

## Arquitectura

```
ui/MainActivity ──(start/stop)──► recording/RecordingService  (LifecycleService, FGS camera|microphone)
      │                                   │  CameraX: VideoCapture<Recorder> (+ Preview opcional)
      │ observa                           │  segmentos → MediaStore (Movies/GrabaFondo)
      ▼                                   ▼
recording/RecorderBus (StateFlow en proceso: fase, parte, avisos, superficie de vista previa)
```

| Archivo | Qué hace |
|---|---|
| `recording/RecordingService.kt` | Núcleo. Arranca en primer plano, vincula CameraX a su propio ciclo de vida, graba por segmentos, reintenta tras interrupciones, vigila espacio/batería/llamadas/orientación, wake lock parcial. |
| `recording/RecorderBus.kt` | Estado compartido servicio ↔ UI y "préstamo" de la superficie de vista previa. |
| `recording/RecordingNotifications.kt` | Canales, notificación fija con cronómetro y botón **Detener**, aviso cuando se detiene sola. |
| `data/RecordingSettings.kt` | Ajustes (resolución, bitrate, audio, segmento, cámara, vista previa) en SharedPreferences. |
| `data/DeviceStatus.kt` | Espacio libre, batería, horas estimadas, estado de optimización de batería. |
| `data/RecordingsRepository.kt` | Lista y borrado de vídeos en MediaStore (incluye segmentos que quedaron a medias). |
| `ui/RecordScreen.kt` | Pantalla principal: botón Iniciar/Detener, estado, espacio, vista previa, ajustes, guía de batería. |
| `ui/CameraPreview.kt` | Vista previa: propia de la Activity sin grabar; prestada al servicio mientras graba. |
| `ui/RecordingsScreen.kt` | Lista de grabaciones con miniatura, reproductor integrado, compartir y borrar. |

### Decisiones clave

- **Inicio solo desde la Activity.** Android 11+ no deja abrir la cámara desde segundo plano, así que el
  servicio solo arranca al pulsar *Iniciar*. Devuelve `START_NOT_STICKY`: si el sistema lo mata no
  intenta reabrirse a escondidas; al volver a la app verás un aviso de que la grabación se cortó.
- **Segmentos.** Cada parte es un MP4 independiente. Al cumplirse la duración se cierra la parte y se
  encola la siguiente en el mismo `Recorder` (el hueco entre partes es mínimo). Si Android mata la app,
  solo se pierde la parte en curso (aparece como "Incompleto" en la lista y se puede borrar).
- **Micrófono en segundo plano.** Además de `FOREGROUND_SERVICE_CAMERA` se declara
  `FOREGROUND_SERVICE_MICROPHONE` y el tipo `camera|microphone`: sin él, Android 11+ graba silencio
  cuando la app no está visible.
- **Interrupciones:**
  - *Otra app usa la cámara* (videollamada, app de cámara): se cierra la parte, se espera a que CameraX
    reabra la cámara y se empieza una parte nueva, sin límite de espera mientras la otra app la tenga.
  - *Errores del grabador/cámara*: se cierra la parte y se reintenta con espera creciente
    (1 s → 15 s), re-vinculando CameraX si hace falta; tras 40 fallos seguidos se detiene.
  - *Llamadas*: Android silencia el micrófono de las demás apps. Al empezar y al terminar la llamada
    se cierra la parte para que el tramo sin audio quede separado. La notificación avisa del silencio.
  - *Rotación*: la orientación de un MP4 se fija al empezar el archivo; si el teléfono gira y se
    mantiene 3 s, se abre una parte nueva con la orientación correcta. La Activity no se recrea al girar.
- **Seguridad de datos:** se para limpiamente (cerrando el MP4) si quedan < 500 MB o la batería baja del
  10 % sin cargar; se comprueba cada 15 s y también antes de empezar.
- **Vista previa:** opcional. Al salir de la app se retira (la grabación continúa). Añadir/quitar la vista
  previa reconfigura la sesión de cámara y puede dejar un salto de una fracción de segundo en el vídeo;
  si quieres el vídeo más continuo posible, desactívala.

## Compilar

Requisitos: JDK 17+ y Android SDK con `platforms;android-35` y `build-tools;35.0.0`.

```bash
# Indica dónde está el SDK (o define ANDROID_HOME)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Instalar en tu celular

**Opción A — con cable (adb):**
1. En el teléfono: *Ajustes → Información del teléfono →* toca 7 veces *Número de compilación* para
   activar las opciones de desarrollador. Luego *Opciones de desarrollador → Depuración USB*.
2. Conecta el cable y acepta la huella del ordenador en el teléfono.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (o `./gradlew installDebug`).

**Opción B — sin cable:** copia `app-debug.apk` al teléfono (Drive, Telegram, USB…), ábrelo desde
*Archivos* y permite *Instalar apps desconocidas* para esa app cuando lo pida. Play Protect puede avisar
de que la app no es conocida: elige *Instalar de todas formas*.

**Primera vez:** pulsa *Iniciar* y concede cámara, micrófono y notificaciones. Después pulsa
*Desactivar optimización* en la tarjeta de batería (recomendado).

## Pruebas manuales

Antes de cada prueba: optimización de batería desactivada, ~2 GB libres, batería > 30 %.

1. **30 min con la pantalla apagada**
   - Ajustes: 720p, 4 Mbps, audio sí, segmentos de 10 min, cámara frontal.
   - Iniciar, comprobar la notificación (cronómetro + *Detener*) y el punto verde de cámara. Apagar la
     pantalla 30 min.
   - Esperado: al encender, el cronómetro marca ~30 min; en *Grabaciones* hay 3 partes de ~10 min
     (+ la 4.ª en curso), todas reproducibles, con audio, ~300 MB cada una.
2. **Cambiar de app**
   - Grabando, abrir 3–4 apps (navegador, correo, YouTube en pausa), volver a GrabaFondo y repetir.
   - Esperado: la grabación no se corta, la vista previa vuelve al regresar. Girar el teléfono a
     horizontal 5 s: se abre una parte nueva y ese vídeo se ve derecho.
   - Pulsar *Detener* desde la notificación estando en otra app: la notificación desaparece y la última
     parte se guarda.
3. **Llamada entrante**
   - Grabando con audio, recibir una llamada (desde otro teléfono), hablar ~1 min y colgar.
   - Esperado: durante la llamada la notificación indica que el audio está silenciado; al terminar hay
     una parte separada que cubre la llamada (vídeo con silencio) y la siguiente vuelve a tener audio.
   - Variante: videollamada de WhatsApp/Meet con la cámara frontal → estado *Interrumpida ·
     reintentando* / "La cámara está en uso por otra app"; al colgar se reanuda sola en una parte nueva.
4. **Poco espacio**
   - Llenar el almacenamiento hasta dejar ~600–700 MB libres (p. ej., copiando vídeos grandes) e
     iniciar a 12 Mbps.
   - Esperado: al bajar de 500 MB la grabación se detiene sola en ≤ 15 s, aparece el aviso
     "Grabación detenida" y la última parte se puede reproducir. Con < 500 MB libres, *Iniciar* muestra
     "No se puede grabar ahora".
5. **Batería baja (opcional):** con < 10 % y sin cargador, *Iniciar* se bloquea; si baja de 10 %
   grabando, se detiene sola y avisa.
6. **App cerrada por el sistema (opcional):** grabando, `adb shell am kill com.grabafondo` no basta
   (servicio en primer plano); usa *Ajustes → Apps → GrabaFondo → Forzar detención*. Al abrir la app
   aparece "La última grabación se cortó" y la parte en curso figura como *Incompleto*.
