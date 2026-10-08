package com.grabafondo.recording

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.grabafondo.ui.MainActivity

/**
 * Botón "GrabaFondo" en los Ajustes rápidos (la persiana de arriba).
 * - Grabando: un toque detiene y guarda.
 * - Sin grabar: abre la app y empieza a grabar (Android exige que la cámara se active con la app
 *   en primer plano, por eso no puede empezar a escondidas).
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderBus.state.value.isActive) {
            try {
                RecordingService.stop(this)
            } catch (e: Exception) {
                Log.w("GrabaFondo", "No se pudo detener desde el botón rápido", e)
            }
            refreshTile()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = RecorderBus.state.value.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "GrabaFondo"
        tile.subtitle = if (active) "Toca para detener" else "Toca para grabar"
        tile.updateTile()
    }

    companion object {
        /** Pide al sistema que vuelva a dibujar el botón (al empezar o terminar de grabar). */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, RecordTileService::class.java))
            } catch (e: Exception) {
                // El botón no está añadido o el sistema no lo admite: no pasa nada.
            }
        }
    }
}
