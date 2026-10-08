package com.grabafondo.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.grabafondo.R
import com.grabafondo.data.DeviceStatus
import com.grabafondo.ui.MainActivity

object RecordingNotifications {

    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ALERTS = "alerts"
    const val ONGOING_ID = 1001
    private const val ALERT_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            context.getString(R.string.channel_recording_name),
            // LOW: visible siempre en la barra y la persiana, pero sin sonido ni vibración.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_recording_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_alerts_desc) }
        manager.createNotificationChannels(listOf(recording, alerts))
    }

    fun buildOngoing(context: Context, state: RecorderBus.State, settingsSummary: String): Notification {
        val title = when (state.phase) {
            RecorderBus.Phase.STARTING -> "Iniciando grabación…"
            RecorderBus.Phase.RECORDING -> "Grabando · parte ${state.segmentIndex}"
            RecorderBus.Phase.RECOVERING -> "Grabación interrumpida · reintentando"
            RecorderBus.Phase.STOPPING -> "Guardando grabación…"
            RecorderBus.Phase.IDLE -> "GrabaFondo"
        }
        val lines = buildList {
            state.message?.let { add(it) }
            if (state.audioSilenced) add("El audio está silenciado por otra app (p. ej. una llamada).")
            if (state.freeBytes >= 0) {
                add("Libre: ${DeviceStatus.formatBytes(state.freeBytes)} · quedan ≈ ${DeviceStatus.formatHours(state.hoursLeft)}")
            }
            add(settingsSummary)
        }
        val text = lines.joinToString("\n")

        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(ContextCompat.getColor(context, R.color.record_red))
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: settingsSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // El cronómetro del sistema muestra el tiempo grabado sin tener que actualizar cada segundo.
            .setWhen(state.sessionStartMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(R.drawable.ic_stop, "Detener", stopIntent(context))
            .build()
    }

    /** Aviso aparte cuando la grabación se detiene sola (poco espacio, batería, error). */
    fun showStopped(context: Context, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(ContextCompat.getColor(context, R.color.record_red))
            .setContentTitle("Grabación detenida")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, ALERT_ID, notification)
    }

    fun notify(context: Context, id: Int, notification: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (e: SecurityException) {
            // Sin permiso POST_NOTIFICATIONS: el servicio sigue funcionando igualmente.
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
