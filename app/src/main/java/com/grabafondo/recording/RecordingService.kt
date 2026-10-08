package com.grabafondo.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.AudioStats
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.grabafondo.data.CameraFacing
import com.grabafondo.data.DeviceStatus
import com.grabafondo.data.RecordingSettings
import com.grabafondo.data.RecordingsRepository
import com.grabafondo.data.Resolution
import com.grabafondo.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Servicio en primer plano (tipo cámara) que graba en segmentos con CameraX.
 *
 * Todo se ejecuta en el hilo principal (callbacks de CameraX con el executor principal y
 * corrutinas de lifecycleScope), así que no hace falta sincronización.
 *
 * Flujo de un segmento: startSegment() → Start → (temporizador) → rollover() → Finalize.
 * Si la cámara o el grabador fallan, el segmento se cierra y scheduleRecovery() reintenta.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.grabafondo.action.START"
        const val ACTION_STOP = "com.grabafondo.action.STOP"

        private const val TAG = "GrabaFondo"
        private const val NO_TOKEN = -1
        private const val RESOURCE_CHECK_MS = 15_000L
        private const val ROTATION_STABLE_MS = 3_000L
        private const val CALL_DEBOUNCE_MS = 1_500L
        private const val MIN_SEGMENT_FOR_ROLLOVER_MS = 5_000L
        private const val MAX_RECOVERY_ATTEMPTS = 40
        private const val CAMERA_OPEN_TIMEOUT_MS = 10_000L
        private const val CAMERA_BUSY_RECHECK_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 8_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L

        /** Debe llamarse desde la Activity en primer plano. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val settingsRepo by lazy { SettingsRepository.get(this) }
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    /** Copia de los ajustes al iniciar: cambiar ajustes durante la grabación no afecta a la sesión. */
    private var settings = RecordingSettings()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var cameraNotice: String? = null

    private var activeRecording: Recording? = null
    private var currentToken = NO_TOKEN
    private var startedToken = NO_TOKEN
    private var nextToken = 0
    private var pendingFinalizations = 0
    private var startNextOnFinalize = false
    private var segmentNumber = 0
    private var savedSegments = 0
    private var segmentStartedAt = 0L
    private var segmentRotation = Surface.ROTATION_0

    private var sessionActive = false
    private var sessionId = 0
    private var stopRequested = false
    private var stopCompleted = false
    private var stopReason: String? = null
    private var sessionStamp = ""
    private var audioForSession = false

    private var recoveryAttempts = 0
    private var recoveryReason = ""
    private var needsRebind = false

    private var rolloverJob: Job? = null
    private var recoveryJob: Job? = null
    private var monitorJob: Job? = null
    private var stopTimeoutJob: Job? = null
    private var startWatchdogJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var orientationListener: OrientationEventListener? = null
    private var sensorRotation: Int? = null
    private var sensorRotationSince = 0L
    private var targetRotation = Surface.ROTATION_0

    private var inCall = false
    private var callCandidate = false
    private var callCandidateSince = 0L

    override fun onCreate() {
        super.onCreate()
        // La Activity publica su superficie de vista previa al estar visible y la retira al salir.
        lifecycleScope.launch {
            RecorderBus.previewSurface.collect { provider -> updatePreview(provider) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> if (sessionActive) finishSession(null) else stopSelf()
            else -> if (!sessionActive) stopSelf()
        }
        // Si el sistema mata el servicio no se reinicia solo: Android no deja abrir la cámara
        // desde segundo plano, así que hay que volver a pulsar "Iniciar" en la app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (sessionActive && !stopCompleted) {
            // Destrucción sin pasar por "Detener": se intenta cerrar el archivo actual.
            activeRecording?.stop()
            activeRecording = null
            orientationListener?.disable()
            releaseWakeLock()
            settingsRepo.sessionActive = false
            RecorderBus.update {
                RecorderBus.State(
                    lastSessionEnded = true,
                    lastStopReason = "El sistema cerró el servicio de grabación.",
                    lastSessionSegments = savedSegments,
                )
            }
            RecorderBus.notifyRecordingsChanged()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Inicio y fin de sesión
    // ---------------------------------------------------------------------------------------

    private fun handleStart() {
        if (sessionActive) {
            // Ya grabando: aun así hay que llamar a startForeground tras startForegroundService().
            startInForeground()
            return
        }
        settings = settingsRepo.settings.value
        audioForSession = settings.audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)
        sessionActive = true
        val session = ++sessionId
        stopRequested = false
        stopCompleted = false
        stopReason = null
        segmentNumber = 0
        savedSegments = 0
        pendingFinalizations = 0
        recoveryAttempts = 0
        needsRebind = false
        startNextOnFinalize = false
        currentToken = NO_TOKEN
        cameraNotice = null
        inCall = isInCall()
        callCandidate = inCall
        sessionStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        RecorderBus.update {
            RecorderBus.State(phase = RecorderBus.Phase.STARTING, sessionStartMs = System.currentTimeMillis())
        }

        // startForeground() es obligatorio tras startForegroundService(), incluso si luego se aborta.
        val inForeground = startInForeground()
        if (!inForeground || !hasPermission(Manifest.permission.CAMERA)) {
            if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            sessionActive = false
            RecorderBus.update {
                RecorderBus.State(
                    lastSessionEnded = true,
                    lastStopReason = "No se pudo iniciar: falta el permiso de cámara o Android bloqueó el servicio.",
                )
            }
            stopSelf()
            return
        }

        settingsRepo.sessionActive = true
        acquireWakeLock()
        startOrientationListener()
        updateResources()
        startMonitor()

        lifecycleScope.launch {
            val provider = try {
                awaitCameraProvider()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessCameraProvider no disponible", e)
                finishSession("No se pudo inicializar la cámara.")
                return@launch
            }
            if (session != sessionId || stopRequested) return@launch
            cameraProvider = provider
            // Espera breve a la primera lectura del sensor para grabar ya con la orientación correcta.
            withTimeoutOrNull(800) { while (sensorRotation == null) delay(50) }
            targetRotation = sensorRotation ?: displayRotation()
            if (session != sessionId || stopRequested) return@launch
            if (!bindCamera()) {
                finishSession("No se pudo abrir la cámara.")
                return@launch
            }
            if (!startSegment()) scheduleRecovery("No se pudo iniciar el grabador", rebind = true)
        }
    }

    private fun startInForeground(): Boolean {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (audioForSession) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            ServiceCompat.startForeground(this, RecordingNotifications.ONGOING_ID, buildNotification(), types)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground falló", e)
            false
        }
    }

    /** Detiene la sesión de forma ordenada: cierra el segmento actual y espera a que se guarde. */
    private fun finishSession(reason: String?) {
        if (!sessionActive || stopRequested) return
        stopRequested = true
        stopReason = reason
        rolloverJob?.cancel()
        recoveryJob?.cancel()
        monitorJob?.cancel()
        startWatchdogJob?.cancel()
        RecorderBus.update {
            it.copy(phase = RecorderBus.Phase.STOPPING, message = reason ?: "Guardando el último segmento…")
        }
        refreshNotification()

        val recording = activeRecording
        activeRecording = null
        currentToken = NO_TOKEN
        recording?.stop()
        if (pendingFinalizations <= 0) {
            completeStop()
        } else {
            stopTimeoutJob = lifecycleScope.launch {
                delay(STOP_TIMEOUT_MS)
                completeStop()
            }
        }
    }

    private fun completeStop() {
        if (stopCompleted) return
        stopCompleted = true
        stopTimeoutJob?.cancel()
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
        preview = null
        orientationListener?.disable()
        orientationListener = null
        releaseWakeLock()
        settingsRepo.sessionActive = false
        sessionActive = false

        val reason = stopReason
        RecorderBus.update {
            RecorderBus.State(
                freeBytes = it.freeBytes,
                hoursLeft = it.hoursLeft,
                lastSessionEnded = true,
                lastStopReason = reason,
                lastSessionSegments = savedSegments,
            )
        }
        RecorderBus.notifyRecordingsChanged()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (reason != null) RecordingNotifications.showStopped(this, reason)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------
    // Cámara
    // ---------------------------------------------------------------------------------------

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, mainExecutor)
        }

    /** (Re)vincula VideoCapture al ciclo de vida del servicio. Desvincula también la vista previa de la Activity. */
    private fun bindCamera(): Boolean {
        val provider = cameraProvider ?: return false
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        provider.unbindAll()
        camera = null
        videoCapture = null
        preview = null

        val selector = chooseCamera(provider) ?: return false
        val quality = if (settings.resolution == Resolution.FHD_1080) Quality.FHD else Quality.HD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .setTargetVideoEncodingBitRate(settings.videoBitrateBps)
            .build()
        val capture = VideoCapture.Builder(recorder)
            .setTargetRotation(targetRotation)
            .build()

        return try {
            val cam = provider.bindToLifecycle(this, selector, capture)
            camera = cam
            cameraSelector = selector
            videoCapture = capture
            cam.cameraInfo.cameraState.observe(this) { state -> onCameraState(state) }
            RecorderBus.previewSurface.value?.let { updatePreview(it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle falló", e)
            false
        }
    }

    private fun chooseCamera(provider: ProcessCameraProvider): CameraSelector? {
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val (preferred, fallback) = if (settings.camera == CameraFacing.FRONT) front to back else back to front
        return when {
            provider.safeHasCamera(preferred) -> preferred
            provider.safeHasCamera(fallback) -> {
                cameraNotice = "No hay cámara ${settings.camera.label.lowercase()}; se usa la otra."
                fallback
            }
            else -> null
        }
    }

    private fun ProcessCameraProvider.safeHasCamera(selector: CameraSelector): Boolean =
        try {
            hasCamera(selector)
        } catch (e: CameraInfoUnavailableException) {
            false
        }

    /** Añade o quita el caso de uso Preview sin tocar VideoCapture (la grabación sigue). */
    private fun updatePreview(surfaceProvider: Preview.SurfaceProvider?) {
        val provider = cameraProvider ?: return
        if (camera == null || stopRequested) return
        val current = preview
        if (surfaceProvider == null) {
            if (current != null) {
                provider.unbind(current)
                preview = null
            }
            return
        }
        if (current != null) {
            current.setSurfaceProvider(surfaceProvider)
            return
        }
        val newPreview = Preview.Builder().build()
        newPreview.setSurfaceProvider(surfaceProvider)
        try {
            provider.bindToLifecycle(this, cameraSelector, newPreview)
            preview = newPreview
        } catch (e: Exception) {
            // Algunas cámaras no admiten vista previa + vídeo a la vez: se graba sin vista previa.
            Log.w(TAG, "No se pudo añadir la vista previa", e)
        }
    }

    private fun onCameraState(state: CameraState) {
        state.error?.let { Log.w(TAG, "Cámara ${state.type}, error ${it.code}") }
        if (!sessionActive || stopRequested) return
        when (state.type) {
            CameraState.Type.OPEN -> {
                if (activeRecording == null && RecorderBus.state.value.phase == RecorderBus.Phase.RECOVERING) {
                    // La cámara vuelve a estar disponible: reanudar enseguida.
                    recoveryJob?.cancel()
                    recoveryJob = lifecycleScope.launch {
                        delay(500)
                        attemptRecovery()
                    }
                }
            }
            CameraState.Type.PENDING_OPEN -> {
                // Otra app con más prioridad se quedó la cámara (videollamada, app de cámara…).
                interruptCurrentSegment("La cámara está en uso por otra app", rebind = false)
            }
            CameraState.Type.CLOSED -> {
                val code = state.error?.code
                if (code != null) {
                    if (code == CameraState.ERROR_CAMERA_FATAL_ERROR || code == CameraState.ERROR_CAMERA_DISABLED) {
                        needsRebind = true
                    }
                    interruptCurrentSegment(cameraErrorText(code), rebind = needsRebind)
                }
            }
            else -> Unit
        }
    }

    private fun cameraErrorText(code: Int): String = when (code) {
        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "La cámara está en uso por otra app"
        CameraState.ERROR_CAMERA_DISABLED -> "La cámara está desactivada por el sistema"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "El modo No molestar bloquea la cámara"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "Error grave de la cámara"
        else -> "La cámara se cerró inesperadamente"
    }

    // ---------------------------------------------------------------------------------------
    // Segmentos
    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startSegment(): Boolean {
        if (stopRequested) return false
        val capture = videoCapture ?: return false
        val number = segmentNumber + 1
        val name = String.format(Locale.US, "GrabaFondo_%s_parte%03d", sessionStamp, number)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RecordingsRepository.RELATIVE_DIR)
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = capture.output.prepareRecording(this, output)
        if (audioForSession && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        val token = nextToken++
        return try {
            // Si el segmento anterior aún se está cerrando, el Recorder deja este en cola y lo
            // arranca en cuanto termina, así el hueco entre partes es mínimo.
            activeRecording = pending.start(mainExecutor) { event -> onRecordEvent(token, event) }
            currentToken = token
            pendingFinalizations++
            segmentNumber = number
            segmentRotation = targetRotation
            RecorderBus.update { it.copy(currentSegmentName = name) }
            armStartWatchdog(token)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar el segmento $number", e)
            false
        }
    }

    /** Cierra el segmento actual y abre el siguiente (límite de tiempo, llamada, rotación…). */
    private fun rollover(reason: String) {
        if (stopRequested) return
        val recording = activeRecording ?: return
        Log.i(TAG, "Cambio de segmento: $reason")
        rolloverJob?.cancel()
        activeRecording = null
        currentToken = NO_TOKEN
        recording.stop()
        if (!startSegment()) startNextOnFinalize = true
    }

    /** Cierra el segmento por una interrupción de la cámara y pasa a reintentar. */
    private fun interruptCurrentSegment(reason: String, rebind: Boolean) {
        if (stopRequested) return
        val recording = activeRecording
        if (recording != null) {
            activeRecording = null
            currentToken = NO_TOKEN
            rolloverJob?.cancel()
            startWatchdogJob?.cancel()
            recording.stop()
        }
        // Si ya hay un reintento programado, él se encarga; si no, se programa uno.
        if (recoveryJob?.isActive != true) {
            scheduleRecovery(reason, rebind)
        } else if (rebind) {
            needsRebind = true
        }
    }

    /** Si un segmento no llega a empezar (cámara colgada), se cierra y se reintenta. */
    private fun armStartWatchdog(token: Int) {
        startWatchdogJob?.cancel()
        startWatchdogJob = lifecycleScope.launch {
            delay(START_TIMEOUT_MS)
            if (token == currentToken && startedToken != token) {
                interruptCurrentSegment("La cámara no empezó a grabar", rebind = true)
            }
        }
    }

    private fun scheduleRollover() {
        rolloverJob?.cancel()
        rolloverJob = lifecycleScope.launch {
            delay(settings.segmentMillis)
            rollover("segmento de ${settings.segmentMinutes} min completo")
        }
    }

    private fun onRecordEvent(token: Int, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> if (token == currentToken) {
                startedToken = token
                onSegmentStarted()
            }
            is VideoRecordEvent.Status -> if (token == currentToken) onStatus(event)
            is VideoRecordEvent.Finalize -> onSegmentFinalized(token, event)
            else -> Unit
        }
    }

    private fun onSegmentStarted() {
        segmentStartedAt = SystemClock.elapsedRealtime()
        recoveryAttempts = 0
        recoveryJob?.cancel()
        startWatchdogJob?.cancel()
        RecorderBus.update {
            it.copy(
                phase = RecorderBus.Phase.RECORDING,
                segmentIndex = segmentNumber,
                message = baseMessage(),
                audioSilenced = false,
            )
        }
        scheduleRollover()
        refreshNotification()
    }

    private fun onStatus(event: VideoRecordEvent.Status) {
        val silenced = audioForSession &&
            event.recordingStats.audioStats.audioState == AudioStats.AUDIO_STATE_SOURCE_SILENCED
        if (silenced != RecorderBus.state.value.audioSilenced) {
            RecorderBus.update { it.copy(audioSilenced = silenced) }
            refreshNotification()
        }
    }

    private fun onSegmentFinalized(token: Int, event: VideoRecordEvent.Finalize) {
        pendingFinalizations--
        val error = event.error
        if (error != VideoRecordEvent.Finalize.ERROR_NONE) {
            Log.w(TAG, "Segmento cerrado con error $error", event.cause)
        }
        if (event.outputResults.outputUri != Uri.EMPTY && error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
            savedSegments++
        }
        RecorderBus.update { it.copy(savedSegments = savedSegments) }
        RecorderBus.notifyRecordingsChanged()

        val wasCurrent = token == currentToken
        if (wasCurrent) {
            activeRecording = null
            currentToken = NO_TOKEN
            rolloverJob?.cancel()
        }

        if (stopRequested) {
            if (pendingFinalizations <= 0) completeStop()
            return
        }

        if (error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
            finishSession("Se detuvo: no queda espacio de almacenamiento.")
            return
        }

        if (!wasCurrent) {
            // Segmento cerrado por nosotros (cambio de segmento o interrupción).
            if (startNextOnFinalize) {
                startNextOnFinalize = false
                if (!startSegment()) scheduleRecovery("No se pudo abrir el siguiente segmento", rebind = true)
            }
            return
        }

        // El segmento actual terminó sin que lo pidiéramos.
        when (error) {
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                if (!startSegment()) scheduleRecovery("No se pudo abrir el siguiente segmento", rebind = true)

            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ->
                finishSession("Se detuvo: no se pudo crear el archivo en Movies/GrabaFondo.")

            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                scheduleRecovery("La cámara se cerró (otra app la usa o el sistema la interrumpió)", rebind = false)

            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                scheduleRecovery("La cámara no entregó imagen", rebind = recoveryAttempts >= 2)

            else -> scheduleRecovery("Error del grabador (código $error)", rebind = true)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reintentos
    // ---------------------------------------------------------------------------------------

    private fun scheduleRecovery(reason: String, rebind: Boolean) {
        if (stopRequested) return
        rolloverJob?.cancel()
        recoveryJob?.cancel()
        recoveryAttempts++
        if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            finishSession("Se detuvo: no se pudo reanudar la cámara tras varios intentos ($reason).")
            return
        }
        recoveryReason = reason
        if (rebind) needsRebind = true
        RecorderBus.update {
            it.copy(phase = RecorderBus.Phase.RECOVERING, message = "$reason. Reintentando…", audioSilenced = false)
        }
        refreshNotification()
        val delayMs = when (recoveryAttempts) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            4 -> 8_000L
            else -> 15_000L
        }
        recoveryJob = lifecycleScope.launch {
            delay(delayMs)
            attemptRecovery()
        }
    }

    private fun attemptRecovery() {
        if (stopRequested || activeRecording != null) return
        if (needsRebind || camera == null) {
            if (!bindCamera()) {
                scheduleRecovery(recoveryReason, rebind = true)
                return
            }
            needsRebind = false
            // El observador de estado arranca el segmento cuando la cámara quede abierta.
            armRecoveryTimeout(CAMERA_OPEN_TIMEOUT_MS)
            return
        }
        when (camera?.cameraInfo?.cameraState?.value?.type) {
            CameraState.Type.OPEN ->
                if (!startSegment()) scheduleRecovery(recoveryReason, rebind = true)

            CameraState.Type.PENDING_OPEN -> {
                // Otra app tiene la cámara. CameraX la reabre sola cuando queda libre; mientras
                // tanto esperamos sin gastar intentos.
                RecorderBus.update {
                    it.copy(message = "La cámara está en uso por otra app. Se reanudará automáticamente al liberarse.")
                }
                refreshNotification()
                armRecoveryTimeout(CAMERA_BUSY_RECHECK_MS)
            }

            else -> armRecoveryTimeout(CAMERA_OPEN_TIMEOUT_MS)
        }
    }

    private fun armRecoveryTimeout(millis: Long) {
        recoveryJob?.cancel()
        recoveryJob = lifecycleScope.launch {
            delay(millis)
            if (camera?.cameraInfo?.cameraState?.value?.type == CameraState.Type.PENDING_OPEN) {
                attemptRecovery()
            } else {
                scheduleRecovery(recoveryReason, rebind = true)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Vigilancia: espacio, batería, llamadas y orientación
    // ---------------------------------------------------------------------------------------

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            var lastResourceCheck = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1_000)
                checkCallState()
                checkRotation()
                val now = SystemClock.elapsedRealtime()
                if (now - lastResourceCheck >= RESOURCE_CHECK_MS) {
                    lastResourceCheck = now
                    checkResources()
                }
            }
        }
    }

    private fun updateResources() {
        val free = DeviceStatus.freeBytes(this)
        RecorderBus.update {
            it.copy(freeBytes = free, hoursLeft = DeviceStatus.estimatedHours(free, settings.estimatedBytesPerHour))
        }
    }

    private fun checkResources() {
        if (stopRequested) return
        updateResources()
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) // renueva el plazo en sesiones muy largas
        val free = RecorderBus.state.value.freeBytes
        val battery = DeviceStatus.battery(this)
        when {
            free in 0 until DeviceStatus.MIN_FREE_BYTES -> finishSession(
                "Se detuvo para no llenar el almacenamiento: quedan ${DeviceStatus.formatBytes(free)} libres (mínimo 500 MB).",
            )
            battery.isLow -> finishSession(
                "Se detuvo por batería baja (${battery.percent} %) sin cargador conectado.",
            )
            else -> refreshNotification()
        }
    }

    private fun isInCall(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT))
    }

    /**
     * Durante una llamada Android silencia el micrófono para las demás apps. Se cierra el segmento
     * al empezar y al terminar la llamada para que el tramo sin audio quede en su propio archivo.
     */
    private fun checkCallState() {
        val now = SystemClock.elapsedRealtime()
        val detected = isInCall()
        if (detected != callCandidate) {
            callCandidate = detected
            callCandidateSince = now
            return
        }
        if (detected == inCall || now - callCandidateSince < CALL_DEBOUNCE_MS) return
        inCall = detected
        Log.i(TAG, if (inCall) "Llamada detectada" else "Fin de llamada")
        if (!audioForSession) return
        if (RecorderBus.state.value.phase == RecorderBus.Phase.RECORDING) {
            RecorderBus.update { it.copy(message = baseMessage()) }
            if (segmentAge() >= MIN_SEGMENT_FOR_ROLLOVER_MS) {
                rollover(if (inCall) "llamada en curso" else "fin de la llamada")
            }
            refreshNotification()
        }
    }

    private fun startOrientationListener() {
        orientationListener?.disable()
        sensorRotation = null
        orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return // teléfono plano: se mantiene la última
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != sensorRotation) {
                    sensorRotation = rotation
                    sensorRotationSince = SystemClock.elapsedRealtime()
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    /**
     * La orientación de un MP4 se fija al empezar el archivo. Si el teléfono gira y se queda así
     * unos segundos, se abre un segmento nuevo con la orientación correcta.
     */
    private fun checkRotation() {
        val rotation = sensorRotation ?: return
        val now = SystemClock.elapsedRealtime()
        if (rotation != targetRotation && now - sensorRotationSince >= ROTATION_STABLE_MS) {
            targetRotation = rotation
            videoCapture?.targetRotation = rotation
        }
        if (RecorderBus.state.value.phase == RecorderBus.Phase.RECORDING &&
            activeRecording != null &&
            segmentRotation != targetRotation &&
            segmentAge() >= MIN_SEGMENT_FOR_ROLLOVER_MS
        ) {
            rollover("rotación del teléfono")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------------

    private fun segmentAge(): Long = SystemClock.elapsedRealtime() - segmentStartedAt

    private fun baseMessage(): String? {
        val parts = listOfNotNull(
            cameraNotice,
            if (inCall && audioForSession) "Llamada en curso: el micrófono puede quedar en silencio." else null,
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun buildNotification() = RecordingNotifications.buildOngoing(
        this,
        RecorderBus.state.value,
        "${settings.summary} · partes de ${settings.segmentMinutes} min",
    )

    private fun refreshNotification() {
        if (sessionActive && !stopCompleted) {
            RecordingNotifications.notify(this, RecordingNotifications.ONGOING_ID, buildNotification())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrabaFondo:recording")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
