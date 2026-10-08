package com.grabafondo.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.util.Locale

/** Espacio libre, batería y estimaciones de duración. */
object DeviceStatus {

    private val SPANISH: Locale = Locale.forLanguageTag("es")

    /** Por debajo de esto no se empieza a grabar y una grabación en curso se detiene. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    /** Batería mínima (sin cargar) para grabar. */
    const val MIN_BATTERY_PERCENT = 10

    data class Battery(val percent: Int, val charging: Boolean) {
        val isLow: Boolean get() = percent in 0 until MIN_BATTERY_PERCENT && !charging
    }

    /** Bytes disponibles en el almacenamiento compartido principal (donde vive Movies/). */
    fun freeBytes(context: Context): Long {
        val dir = context.getExternalFilesDir(null) ?: Environment.getDataDirectory()
        return try {
            StatFs(dir.path).availableBytes
        } catch (e: IllegalArgumentException) {
            StatFs(Environment.getDataDirectory().path).availableBytes
        }
    }

    fun battery(context: Context): Battery {
        // Intent "sticky": no registra ningún receptor, solo lee el último estado.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(percent, charging)
    }

    /** true si el usuario ya excluyó la app de la optimización de batería (Doze/App Standby). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Horas de grabación que caben antes de llegar al margen de seguridad de 500 MB. */
    fun estimatedHours(freeBytes: Long, bytesPerHour: Long): Double {
        if (bytesPerHour <= 0) return 0.0
        val usable = (freeBytes - MIN_FREE_BYTES).coerceAtLeast(0)
        return usable.toDouble() / bytesPerHour.toDouble()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(SPANISH, "%.1f GB", bytes / gb)
            bytes >= mb -> String.format(SPANISH, "%.0f MB", bytes / mb)
            else -> String.format(SPANISH, "%.0f KB", bytes / kb)
        }
    }

    fun formatHours(hours: Double): String {
        if (hours.isNaN() || hours < 0) return "—"
        val totalMinutes = (hours * 60).toLong()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }
}
