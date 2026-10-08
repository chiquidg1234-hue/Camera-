package com.grabafondo.ui

import android.app.Activity
import android.net.Uri
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabafondo.data.DeleteResult
import com.grabafondo.data.DeviceStatus
import com.grabafondo.data.RecordingItem
import com.grabafondo.data.RecordingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun RecordingsScreen(vm: MainViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by vm.recordings.collectAsStateWithLifecycle()
    val state by vm.recorderState.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<RecordingItem?>(null) }
    var toDelete by remember { mutableStateOf<RecordingItem?>(null) }
    var retryAfterApproval by remember { mutableStateOf<RecordingItem?>(null) }

    val deleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val retry = retryAfterApproval
        retryAfterApproval = null
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                if (retry != null) vm.delete(retry)
                vm.refreshRecordings()
                snackbar.showSnackbar("Grabación borrada")
            }
        }
    }

    fun delete(item: RecordingItem) {
        scope.launch {
            when (val result = vm.delete(item)) {
                DeleteResult.Deleted -> snackbar.showSnackbar("Grabación borrada")
                is DeleteResult.NeedsConfirmation -> {
                    retryAfterApproval = if (result.retryAfterApproval) item else null
                    deleteApproval.launch(IntentSenderRequest.Builder(result.intentSender).build())
                }
                is DeleteResult.Failed -> snackbar.showSnackbar("No se pudo borrar: ${result.message}")
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Grabaciones", style = MaterialTheme.typography.headlineSmall)
                val total = items.sumOf { it.sizeBytes }
                Text(
                    "${items.size} archivo(s) · ${DeviceStatus.formatBytes(total)} · ${RecordingsRepository.RELATIVE_DIR}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = vm::refreshRecordings) {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
            }
        }

        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Aún no hay grabaciones. Las partes aparecen aquí a medida que se cierran.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    val isCurrent = state.isActive && item.pending &&
                        state.currentSegmentName?.let { item.name.startsWith(it) } == true
                    RecordingRow(
                        item = item,
                        isCurrent = isCurrent,
                        onPlay = { playing = item },
                        onShare = { SystemIntents.shareVideo(context, item.uri) },
                        onDelete = { toDelete = item },
                    )
                }
            }
        }
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar grabación?") },
            text = { Text("${item.name}\nSe borrará del teléfono y no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    delete(item)
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }

    playing?.let { item -> PlayerDialog(item, onDismiss = { playing = null }) }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val playable = !item.pending
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = playable, onClick = onPlay),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(item.uri, playable)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(item.dateAddedMs))
                Text(
                    listOfNotNull(
                        date,
                        if (item.durationMs > 0) DeviceStatus.formatDuration(item.durationMs) else null,
                        DeviceStatus.formatBytes(item.sizeBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    isCurrent -> Text(
                        "● Grabando ahora",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    item.pending -> Text(
                        "Incompleto: la app se cerró antes de terminar esta parte (no se puede reproducir).",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (playable) {
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Compartir") }
            }
            IconButton(onClick = onDelete, enabled = !isCurrent) {
                Icon(Icons.Filled.Delete, contentDescription = "Borrar")
            }
        }
    }
}

@Composable
private fun Thumbnail(uri: Uri, enabled: Boolean) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, enabled) {
        if (!enabled) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, Size(320, 180), null).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (enabled) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun PlayerDialog(item: RecordingItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { start() }
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            true
                        }
                        setVideoURI(item.uri)
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
            )
            if (failed) {
                Text(
                    "No se puede reproducir aquí. Prueba \"Abrir con\".",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                }
                Text(
                    item.name,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { SystemIntents.openVideo(context, item.uri) }) {
                    Text("Abrir con", color = Color.White)
                }
            }
        }
    }
}
