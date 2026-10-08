package com.grabafondo.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.grabafondo.data.CameraFacing
import com.grabafondo.recording.RecorderBus

/**
 * Vista previa pequeña.
 * - Sin grabar: la Activity vincula su propio Preview a su ciclo de vida para encuadrar.
 * - Grabando: la cámara la tiene el servicio; aquí solo se le presta la superficie mientras
 *   la Activity está visible (ON_START..ON_STOP). Al salir de la app se retira y el servicio
 *   sigue grabando sin vista previa.
 */
@Composable
fun CameraPreview(recording: Boolean, facing: CameraFacing, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView: la superficie sobrevive a que la ventana se oculte, lo que evita
            // errores de "superficie abandonada" mientras el servicio sigue grabando.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Durante la grabación: prestar la superficie al servicio.
    DisposableEffect(recording, lifecycleOwner) {
        if (!recording) return@DisposableEffect onDispose { }
        val surfaceProvider = previewView.surfaceProvider
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> RecorderBus.attachPreview(surfaceProvider)
                Lifecycle.Event.ON_STOP -> RecorderBus.detachPreview(surfaceProvider)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            RecorderBus.detachPreview(surfaceProvider)
        }
    }

    // Sin grabar: vista previa propia para encuadrar.
    DisposableEffect(recording, facing, lifecycleOwner) {
        if (recording) return@DisposableEffect onDispose { }
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed || RecorderBus.state.value.isActive) return@addListener
            try {
                val cameraProvider = future.get()
                val selector = if (facing == CameraFacing.FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val newPreview = Preview.Builder().build()
                newPreview.setSurfaceProvider(previewView.surfaceProvider)
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, newPreview)
                provider = cameraProvider
                preview = newPreview
            } catch (e: Exception) {
                Log.w("GrabaFondo", "Vista previa no disponible", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            preview?.let { provider?.unbind(it) }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
