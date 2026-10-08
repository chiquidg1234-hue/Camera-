package com.grabafondo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grabafondo.R
import com.grabafondo.recording.RecorderBus
import com.grabafondo.ui.theme.GrabaFondoTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Lo envía el botón de Ajustes rápidos: abrir la app y empezar a grabar. */
        const val ACTION_QUICK_START = "com.grabafondo.action.QUICK_START"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            GrabaFondoTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Con la app a la vista se ocultan la luz roja y la ventanita flotantes.
        RecorderBus.setAppVisible(true)
    }

    override fun onStop() {
        RecorderBus.setAppVisible(false)
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_QUICK_START) {
            intent.action = null
            RecorderBus.requestQuickStart()
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val quickStart by RecorderBus.quickStart.collectAsStateWithLifecycle()

    // El botón rápido abre la pestaña Grabar, que se encarga de empezar.
    LaunchedEffect(quickStart) {
        if (quickStart) tab = 0
    }

    // Al volver de Ajustes (permisos, batería) o de otra app, refrescar estado y lista.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshDevice()
        vm.refreshRecordings()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(painterResource(R.drawable.ic_stat_record), contentDescription = null) },
                    label = { Text("Grabar") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Grabaciones") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> RecordScreen(vm, snackbar, Modifier.padding(padding))
            else -> RecordingsScreen(vm, snackbar, Modifier.padding(padding))
        }
    }
}
