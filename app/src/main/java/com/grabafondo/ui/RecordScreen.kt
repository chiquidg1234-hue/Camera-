package com.grabafondo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabafondo.data.CameraFacing
import com.grabafondo.data.DeviceStatus
import com.grabafondo.data.RecordingSettings
import com.grabafondo.data.Resolution
import com.grabafondo.recording.RecorderBus
import com.grabafondo.recording.RecordingService
import com.grabafondo.ui.theme.RecordRed
import com.grabafondo.ui.theme.StopGray
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(vm: MainViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.recorderState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val interrupted by vm.interruptedSession.collectAsStateWithLifecycle()
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    fun launchRecording() {
        when (val check = vm.checkCanStart()) {
            is MainViewModel.StartCheck.Blocked -> blockedMessage = check.message
            MainViewModel.StartCheck.Ok -> {
                // Se inicia desde aquí, con la Activity en primer plano: Android no permite
                // arrancar un servicio de cámara desde segundo plano.
                RecordingService.start(context)
                scope.launch { snackbar.showSnackbar(vm.startSummary(), duration = SnackbarDuration.Long) }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (!SystemIntents.hasPermission(context, Manifest.permission.CAMERA)) {
            scope.launch {
                val result = snackbar.showSnackbar(
                    "Sin permiso de cámara no se puede grabar.",
                    actionLabel = "Ajustes",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) SystemIntents.openAppSettings(context)
            }
            return@rememberLauncherForActivityResult
        }
        if (settings.audioEnabled && !SystemIntents.hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            scope.launch { snackbar.showSnackbar("Sin permiso de micrófono: se grabará sin audio.") }
        }
        launchRecording()
    }

    fun onStartClicked() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (settings.audioEnabled) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot { SystemIntents.hasPermission(context, it) }
        if (needed.isEmpty()) launchRecording() else permissionLauncher.launch(needed.toTypedArray())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GrabaFondo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        if (interrupted) {
            NoticeCard(
                icon = Icons.Filled.Warning,
                title = "La última grabación se cortó",
                body = "Android cerró la app mientras grababa. Los segmentos terminados están guardados; " +
                    "el último puede aparecer como \"incompleto\". Desactivar la optimización de batería " +
                    "(abajo) reduce el riesgo.",
                onDismiss = vm::dismissInterrupted,
            )
        }

        if (state.lastSessionEnded && !state.isActive) {
            val reason = state.lastStopReason
            NoticeCard(
                icon = if (reason == null) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                title = if (reason == null) "Grabación guardada" else "La grabación se detuvo",
                body = listOfNotNull(
                    reason,
                    "${state.lastSessionSegments} segmento(s) guardado(s) en Movies/GrabaFondo.",
                ).joinToString("\n"),
                onDismiss = vm::dismissLastSession,
            )
        }

        if (!device.notificationsEnabled) {
            NoticeCard(
                icon = Icons.Filled.Info,
                title = "Notificaciones desactivadas",
                body = "La grabación funciona igual, pero no verás la notificación con el tiempo ni el botón Detener.",
                actionLabel = "Activar",
                onAction = { SystemIntents.openAppSettings(context) },
            )
        }

        StatusCard(state, settings)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RecordButton(
                phase = state.phase,
                onStart = ::onStartClicked,
                onStop = { RecordingService.stop(context) },
            )
        }

        StorageCard(device, settings)

        if (settings.showPreview) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CameraPreview(
                        recording = state.isActive,
                        facing = settings.camera,
                        modifier = Modifier
                            .size(width = 132.dp, height = 176.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (state.isActive) {
                            "Vista previa en directo. Al salir de la app se pausa la vista previa, " +
                                "pero la grabación continúa."
                        } else {
                            "Encuadre con la cámara ${settings.camera.label.lowercase()}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SettingsCard(
            settings = settings,
            enabled = !state.isActive,
            onChange = vm::updateSettings,
        )

        BatteryOptimizationCard(
            ignoring = device.ignoringBatteryOptimizations,
            onRequest = { SystemIntents.requestIgnoreBatteryOptimizations(context) },
            onMoreInfo = { SystemIntents.openDontKillMyApp(context) },
        )

        Spacer(Modifier.height(8.dp))
    }

    blockedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { blockedMessage = null },
            confirmButton = { TextButton(onClick = { blockedMessage = null }) { Text("Entendido") } },
            title = { Text("No se puede grabar ahora") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun StatusCard(state: RecorderBus.State, settings: RecordingSettings) {
    val now by produceState(System.currentTimeMillis(), state.isActive) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = when (state.phase) {
                RecorderBus.Phase.IDLE -> "Listo para grabar"
                RecorderBus.Phase.STARTING -> "Iniciando cámara…"
                RecorderBus.Phase.RECORDING -> "● Grabando"
                RecorderBus.Phase.RECOVERING -> "Interrumpida · reintentando"
                RecorderBus.Phase.STOPPING -> "Guardando…"
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (state.phase == RecorderBus.Phase.RECORDING) RecordRed else MaterialTheme.colorScheme.onSurface,
            )
            if (state.isActive) {
                Text(
                    DeviceStatus.formatDuration(now - state.sessionStartMs),
                    fontSize = 44.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Parte ${state.segmentIndex.coerceAtLeast(1)} · ${state.savedSegments} guardada(s) · " +
                        "partes de ${settings.segmentMinutes} min",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (state.audioSilenced) {
                    Text(
                        "El audio está silenciado por otra app (por ejemplo, una llamada).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Text(
                    "${settings.summary} · partes de ${settings.segmentMinutes} min · cámara ${settings.camera.label.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Pulsa Iniciar y luego puedes cambiar de app o apagar la pantalla: seguirá grabando.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordButton(phase: RecorderBus.Phase, onStart: () -> Unit, onStop: () -> Unit) {
    val active = phase != RecorderBus.Phase.IDLE
    val busy = phase == RecorderBus.Phase.STOPPING
    Button(
        onClick = { if (active) onStop() else onStart() },
        enabled = !busy,
        shape = CircleShape,
        modifier = Modifier.size(150.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) StopGray else RecordRed,
            contentColor = Color.White,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(if (active) RoundedCornerShape(4.dp) else CircleShape)
                        .background(Color.White),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    busy -> "Guardando"
                    active -> "Detener"
                    else -> "Iniciar"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun StorageCard(device: MainViewModel.DeviceInfo, settings: RecordingSettings) {
    val hours = DeviceStatus.estimatedHours(device.freeBytes, settings.estimatedBytesPerHour)
    val lowSpace = device.freeBytes in 0 until DeviceStatus.MIN_FREE_BYTES * 2
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Almacenamiento y batería", style = MaterialTheme.typography.titleSmall)
            Text(
                "Espacio libre: ${DeviceStatus.formatBytes(device.freeBytes)}",
                color = if (lowSpace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Caben ≈ ${DeviceStatus.formatHours(hours)} de grabación " +
                    "(${DeviceStatus.formatBytes(settings.estimatedBytesPerHour)}/hora con la configuración actual).",
                style = MaterialTheme.typography.bodyMedium,
            )
            val battery = device.battery
            if (battery.percent >= 0) {
                Text(
                    "Batería: ${battery.percent} %" + if (battery.charging) " (cargando)" else "",
                    color = if (battery.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "Se detiene sola si quedan menos de 500 MB o la batería baja del 10 % sin cargar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    settings: RecordingSettings,
    enabled: Boolean,
    onChange: ((RecordingSettings) -> RecordingSettings) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ajustes", style = MaterialTheme.typography.titleSmall)
            if (!enabled) {
                Text(
                    "Detén la grabación para cambiar los ajustes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingLabel("Resolución")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Resolution.entries.forEach { option ->
                    FilterChip(
                        selected = settings.resolution == option,
                        onClick = { onChange { it.copy(resolution = option) } },
                        enabled = enabled,
                        label = { Text(option.label) },
                    )
                }
            }

            SettingLabel("Bitrate de vídeo")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingSettings.BITRATE_OPTIONS_MBPS.forEach { mbps ->
                    FilterChip(
                        selected = settings.videoBitrateMbps == mbps,
                        onClick = { onChange { it.copy(videoBitrateMbps = mbps) } },
                        enabled = enabled,
                        label = { Text("$mbps Mbps") },
                    )
                }
            }
            Text(
                "Para 720p bastan 3–4 Mbps; para 1080p, 6–8 Mbps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingLabel("Duración de cada segmento")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingSettings.SEGMENT_OPTIONS_MIN.forEach { minutes ->
                    FilterChip(
                        selected = settings.segmentMinutes == minutes,
                        onClick = { onChange { it.copy(segmentMinutes = minutes) } },
                        enabled = enabled,
                        label = { Text("$minutes min") },
                    )
                }
            }

            SettingLabel("Cámara")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraFacing.entries.forEach { option ->
                    FilterChip(
                        selected = settings.camera == option,
                        onClick = { onChange { it.copy(camera = option) } },
                        enabled = enabled,
                        label = { Text(option.label) },
                    )
                }
            }

            HorizontalDivider()
            SwitchRow(
                title = "Grabar audio",
                subtitle = "Usa el micrófono. Durante las llamadas Android lo silencia.",
                checked = settings.audioEnabled,
                enabled = enabled,
                onCheckedChange = { checked -> onChange { it.copy(audioEnabled = checked) } },
            )
            SwitchRow(
                title = "Vista previa",
                subtitle = "Muestra la cámara en esta pantalla. Desactívala para ahorrar algo de batería.",
                checked = settings.showPreview,
                enabled = true,
                onCheckedChange = { checked -> onChange { it.copy(showPreview = checked) } },
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun BatteryOptimizationCard(ignoring: Boolean, onRequest: () -> Unit, onMoreInfo: () -> Unit) {
    if (ignoring) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Optimización de batería desactivada para GrabaFondo.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recomendado: desactiva la optimización de batería", style = MaterialTheme.typography.titleSmall)
            Text(
                "La grabación corre en un servicio en primer plano con notificación, que Android respeta, " +
                    "pero con la pantalla apagada el ahorro de batería (Doze) y los gestores de muchos " +
                    "fabricantes (Xiaomi, Samsung, Huawei, Oppo…) pueden congelar o cerrar la app y cortar la " +
                    "grabación. Excluir GrabaFondo evita la mayoría de esos cortes. Solo afecta a esta app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRequest) { Text("Desactivar optimización") }
                TextButton(onClick = onMoreInfo) { Text("Más info") }
            }
            Text(
                "En algunas marcas revisa también \"Inicio automático\" o \"Apps en suspensión\" en Ajustes.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text("Cerrar") }
                    }
                }
            }
        }
    }
}
