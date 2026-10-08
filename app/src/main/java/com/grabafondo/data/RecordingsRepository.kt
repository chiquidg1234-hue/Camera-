package com.grabafondo.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecordingItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedMs: Long,
    /** true si el archivo quedó a medias (la app se cerró antes de terminar el segmento). */
    val pending: Boolean,
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    /** El sistema pide confirmación al usuario; si [retryAfterApproval] hay que volver a borrar después. */
    data class NeedsConfirmation(val intentSender: IntentSender, val retryAfterApproval: Boolean) : DeleteResult
    data class Failed(val message: String) : DeleteResult
}

/** Lee y borra las grabaciones guardadas en Movies/GrabaFondo a través de MediaStore. */
class RecordingsRepository(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    suspend fun load(): List<RecordingItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.IS_PENDING,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$RELATIVE_DIR%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        // Se incluyen los archivos "pendientes": son segmentos que no llegaron a cerrarse.
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, queryArgs, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, sortOrder)
        }

        val items = mutableListOf<RecordingItem>()
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val pendingCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                items += RecordingItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "video_$id.mp4",
                    sizeBytes = c.getLong(sizeCol),
                    durationMs = c.getLong(durationCol),
                    dateAddedMs = c.getLong(dateCol) * 1000L,
                    pending = c.getInt(pendingCol) == 1,
                )
            }
        }
        items
    }

    suspend fun delete(uri: Uri): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (resolver.delete(uri, null, null) > 0) DeleteResult.Deleted
            else DeleteResult.Failed("El archivo ya no existe")
        } catch (e: SecurityException) {
            // Pasa si el archivo no es "nuestro" (por ejemplo, tras reinstalar la app).
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> DeleteResult.NeedsConfirmation(
                    MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender,
                    retryAfterApproval = false,
                )
                e is RecoverableSecurityException -> DeleteResult.NeedsConfirmation(
                    e.userAction.actionIntent.intentSender,
                    retryAfterApproval = true,
                )
                else -> DeleteResult.Failed(e.message ?: "Sin permiso para borrar")
            }
        }
    }

    companion object {
        /** Carpeta relativa donde se guardan los vídeos: Movies/GrabaFondo/ */
        val RELATIVE_DIR: String = Environment.DIRECTORY_MOVIES + "/GrabaFondo/"
    }
}
