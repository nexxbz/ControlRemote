package com.example.remoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = DeviceStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(store, filesDir)
            }
        }
    }
}

@Composable
fun App(store: DeviceStore, keysDir: File) {
    var devices by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<Device?>(null) }

    val current = selected
    if (current == null) {
        DeviceListScreen(
            devices = devices,
            onAdd = { devices = devices + it; store.save(devices) },
            onDelete = { d -> devices = devices - d; store.save(devices) },
            onOpen = { selected = it }
        )
    } else {
        RemoteHost(current, keysDir) { selected = null }
    }
}

@Composable
private fun RemoteHost(device: Device, keysDir: File, onBack: () -> Unit) {
    var status by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(false) }
    val controller = remember(device) { AdbController(device, keysDir) { status = it } }
    DisposableEffect(controller) { onDispose { controller.close() } }
    BackHandler { onBack() }

    RemoteScreen(device.name, status, controller, onKeyboard = { showKeyboard = true })

    if (showKeyboard) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyboard = false },
            title = { Text("Escribir en el dispositivo") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { controller.sendText(text); showKeyboard = false }) { Text("Enviar") }
            },
            dismissButton = { TextButton(onClick = { showKeyboard = false }) { Text("Cancelar") } }
        )
    }
}
