package com.grabafondo.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

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
