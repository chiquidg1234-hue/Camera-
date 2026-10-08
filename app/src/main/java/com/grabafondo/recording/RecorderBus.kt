package com.grabafondo.recording

import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado compartido (en el mismo proceso) entre [RecordingService] y la UI.
 * El servicio es la única fuente de verdad; la UI solo observa y publica su superficie de vista previa.
 */
object RecorderBus {

    enum class Phase { IDLE, STARTING, RECORDING, RECOVERING, STOPPING }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** System.currentTimeMillis() al iniciar la sesión (base del cronómetro). */
        val sessionStartMs: Long = 0L,
        /** Número de la parte que se está grabando (1, 2, 3…). */
        val segmentIndex: Int = 0,
        /** Segmentos cerrados correctamente en esta sesión. */
        val savedSegments: Int = 0,
        /** Nombre (sin extensión) del archivo que se está escribiendo ahora mismo. */
        val currentSegmentName: String? = null,
        /** Aviso temporal: llamada en curso, reintentando cámara, etc. */
        val message: String? = null,
        val audioSilenced: Boolean = false,
        val freeBytes: Long = -1L,
        val hoursLeft: Double = -1.0,
        /** true cuando terminó una sesión y la UI aún no ha descartado el resumen. */
        val lastSessionEnded: Boolean = false,
        /** Motivo por el que terminó la última sesión (null si la detuvo el usuario). */
        val lastStopReason: String? = null,
        val lastSessionSegments: Int = 0,
    ) {
        val isActive: Boolean get() = phase != Phase.IDLE
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _recordingsVersion = MutableStateFlow(0)
    /** Cambia cada vez que se cierra un segmento, para refrescar la lista de grabaciones. */
    val recordingsVersion: StateFlow<Int> = _recordingsVersion.asStateFlow()

    private val _previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)
    /** Superficie de la vista previa de la Activity mientras está visible (null si no hay). */
    val previewSurface: StateFlow<Preview.SurfaceProvider?> = _previewSurface.asStateFlow()

    fun update(transform: (State) -> State) = _state.update(transform)

    fun notifyRecordingsChanged() = _recordingsVersion.update { it + 1 }

    fun attachPreview(provider: Preview.SurfaceProvider) {
        _previewSurface.value = provider
    }

    fun detachPreview(provider: Preview.SurfaceProvider) {
        _previewSurface.update { current -> if (current === provider) null else current }
    }

    fun clearLastSession() = _state.update { it.copy(lastSessionEnded = false, lastStopReason = null, lastSessionSegments = 0) }
}
