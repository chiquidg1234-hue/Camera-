package com.grabafondo.ui

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.grabafondo.R
import com.grabafondo.recording.RecordTileService

object SystemIntents {

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Abre el diálogo del sistema "¿Permitir que GrabaFondo se ejecute siempre en segundo plano?".
     * Si el fabricante lo ha quitado, abre la lista de optimización de batería o los ajustes de la app.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(context),
        )
        startFirst(context, candidates)
    }

    /** Pantalla del sistema "Mostrar sobre otras apps" para GrabaFondo. */
    fun openOverlayPermission(context: Context) {
        startFirst(
            context,
            listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                appDetailsIntent(context),
            ),
        )
    }

    /**
     * Android 13+: el sistema pregunta si añadir el botón "GrabaFondo" a los Ajustes rápidos.
     * En versiones anteriores (o si la marca no lo admite) se explica cómo añadirlo a mano.
     */
    fun requestAddQuickTile(context: Context, onResult: (String) -> Unit) {
        val manual = "Añádelo a mano: baja la persiana de arriba del todo, toca el lápiz ✏️ " +
            "y arrastra el botón \"GrabaFondo\" a la zona de botones."
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(manual)
            return
        }
        try {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, RecordTileService::class.java),
                "GrabaFondo",
                Icon.createWithResource(context, R.drawable.ic_stat_record),
                context.mainExecutor,
            ) { result ->
                onResult(
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            "Listo: el botón GrabaFondo está en tus Ajustes rápidos."
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "No se añadió. $manual"
                        else -> manual
                    },
                )
            }
        } catch (e: Exception) {
            onResult(manual)
        }
    }

    fun openAppSettings(context: Context) {
        startFirst(context, listOf(appDetailsIntent(context)))
    }

    fun openDontKillMyApp(context: Context) {
        startFirst(context, listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/"))))
    }

    fun openVideo(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startFirst(context, listOf(Intent.createChooser(intent, "Abrir con")))
    }

    fun shareVideo(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startFirst(context, listOf(Intent.createChooser(intent, "Compartir vídeo")))
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun startFirst(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // probar la siguiente opción
            } catch (e: SecurityException) {
                // probar la siguiente opción
            }
        }
    }
}
