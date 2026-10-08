package com.grabafondo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Resolution(val label: String) {
    HD_720("720p"),
    FHD_1080("1080p"),
}

enum class CameraFacing(val label: String) {
    FRONT("Frontal"),
    BACK("Trasera"),
}

data class RecordingSettings(
    val resolution: Resolution = Resolution.HD_720,
    val videoBitrateMbps: Int = 4,
    val audioEnabled: Boolean = true,
    val segmentMinutes: Int = 10,
    val camera: CameraFacing = CameraFacing.FRONT,
    val showPreview: Boolean = true,
) {
    val videoBitrateBps: Int get() = videoBitrateMbps * 1_000_000

    val segmentMillis: Long get() = segmentMinutes * 60_000L

    /** Estimación conservadora de bytes por hora (vídeo + audio AAC + ~2 % de contenedor MP4). */
    val estimatedBytesPerHour: Long
        get() {
            val bitsPerSecond = videoBitrateBps.toLong() + if (audioEnabled) AUDIO_BITRATE_BPS else 0L
            return (bitsPerSecond / 8L * 3600L * 102L) / 100L
        }

    val summary: String get() = "${resolution.label} · $videoBitrateMbps Mbps" + if (audioEnabled) " · con audio" else " · sin audio"

    companion object {
        const val AUDIO_BITRATE_BPS = 160_000L
        val BITRATE_OPTIONS_MBPS = listOf(2, 3, 4, 6, 8, 12)
        val SEGMENT_OPTIONS_MIN = listOf(5, 10, 15, 30, 60)
    }
}

/** Ajustes persistidos en SharedPreferences y expuestos como StateFlow para la UI y el servicio. */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    fun update(transform: (RecordingSettings) -> RecordingSettings) {
        val updated = transform(_settings.value)
        save(updated)
        _settings.value = updated
    }

    /**
     * true mientras hay una sesión de grabación. Si la app arranca y sigue en true sin servicio
     * vivo, el sistema mató el proceso a mitad de grabación.
     */
    var sessionActive: Boolean
        get() = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SESSION_ACTIVE, value).apply()
        }

    private fun load(): RecordingSettings {
        val defaults = RecordingSettings()
        return RecordingSettings(
            resolution = enumOrDefault(prefs.getString(KEY_RESOLUTION, null), defaults.resolution),
            videoBitrateMbps = prefs.getInt(KEY_BITRATE, defaults.videoBitrateMbps),
            audioEnabled = prefs.getBoolean(KEY_AUDIO, defaults.audioEnabled),
            segmentMinutes = prefs.getInt(KEY_SEGMENT, defaults.segmentMinutes),
            camera = enumOrDefault(prefs.getString(KEY_CAMERA, null), defaults.camera),
            showPreview = prefs.getBoolean(KEY_PREVIEW, defaults.showPreview),
        )
    }

    private fun save(settings: RecordingSettings) {
        prefs.edit()
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .putInt(KEY_BITRATE, settings.videoBitrateMbps)
            .putBoolean(KEY_AUDIO, settings.audioEnabled)
            .putInt(KEY_SEGMENT, settings.segmentMinutes)
            .putString(KEY_CAMERA, settings.camera.name)
            .putBoolean(KEY_PREVIEW, settings.showPreview)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_BITRATE = "bitrate_mbps"
        private const val KEY_AUDIO = "audio"
        private const val KEY_SEGMENT = "segment_minutes"
        private const val KEY_CAMERA = "camera"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_SESSION_ACTIVE = "session_active"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
