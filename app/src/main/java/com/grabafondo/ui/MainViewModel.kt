package com.grabafondo.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grabafondo.data.DeleteResult
import com.grabafondo.data.DeviceStatus
import com.grabafondo.data.RecordingItem
import com.grabafondo.data.RecordingSettings
import com.grabafondo.data.RecordingsRepository
import com.grabafondo.data.SettingsRepository
import com.grabafondo.recording.RecorderBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class DeviceInfo(
        val freeBytes: Long = -1L,
        val battery: DeviceStatus.Battery = DeviceStatus.Battery(-1, false),
        val ignoringBatteryOptimizations: Boolean = true,
        val notificationsEnabled: Boolean = true,
    )

    sealed interface StartCheck {
        data object Ok : StartCheck
        data class Blocked(val message: String) : StartCheck
    }

    private val settingsRepo = SettingsRepository.get(app)
    private val recordingsRepo = RecordingsRepository(app)

    val settings: StateFlow<RecordingSettings> = settingsRepo.settings
    val recorderState: StateFlow<RecorderBus.State> = RecorderBus.state

    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

    private val _device = MutableStateFlow(DeviceInfo())
    val device: StateFlow<DeviceInfo> = _device.asStateFlow()

    private val _interruptedSession = MutableStateFlow(false)
    /** La sesión anterior terminó sin pasar por "Detener" (el sistema mató la app). */
    val interruptedSession: StateFlow<Boolean> = _interruptedSession.asStateFlow()

    init {
        if (settingsRepo.sessionActive && !RecorderBus.state.value.isActive) {
            _interruptedSession.value = true
            settingsRepo.sessionActive = false
        }
        viewModelScope.launch {
            RecorderBus.recordingsVersion.collect { refreshRecordings() }
        }
        viewModelScope.launch {
            while (true) {
                refreshDevice()
                delay(10_000)
            }
        }
    }

    fun refreshDevice() {
        val context = getApplication<Application>()
        _device.value = DeviceInfo(
            freeBytes = DeviceStatus.freeBytes(context),
            battery = DeviceStatus.battery(context),
            ignoringBatteryOptimizations = DeviceStatus.isIgnoringBatteryOptimizations(context),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            _recordings.value = try {
                recordingsRepo.load()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun updateSettings(transform: (RecordingSettings) -> RecordingSettings) = settingsRepo.update(transform)

    fun dismissInterrupted() {
        _interruptedSession.value = false
    }

    fun dismissLastSession() = RecorderBus.clearLastSession()

    /** Comprobaciones previas: espacio mínimo y batería. */
    fun checkCanStart(): StartCheck {
        refreshDevice()
        val info = _device.value
        return when {
            info.freeBytes in 0 until DeviceStatus.MIN_FREE_BYTES -> StartCheck.Blocked(
                "Solo quedan ${DeviceStatus.formatBytes(info.freeBytes)} libres. " +
                    "Hacen falta al menos 500 MB para empezar a grabar.",
            )
            info.battery.isLow -> StartCheck.Blocked(
                "La batería está al ${info.battery.percent} % y no está cargando. Conecta el cargador para grabar.",
            )
            else -> StartCheck.Ok
        }
    }

    /** Texto que se muestra al iniciar: espacio libre y horas estimadas. */
    fun startSummary(): String {
        val info = _device.value
        val current = settings.value
        val hours = DeviceStatus.estimatedHours(info.freeBytes, current.estimatedBytesPerHour)
        return "Grabando. Libre: ${DeviceStatus.formatBytes(info.freeBytes)} · " +
            "caben ≈ ${DeviceStatus.formatHours(hours)} a ${current.resolution.label} y ${current.videoBitrateMbps} Mbps."
    }

    suspend fun delete(item: RecordingItem): DeleteResult {
        val result = recordingsRepo.delete(item.uri)
        if (result is DeleteResult.Deleted) refreshRecordings()
        return result
    }
}
