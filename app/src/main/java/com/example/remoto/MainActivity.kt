package com.example.remoto

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(prefs)
            }
        }
    }
}

private enum class Screen { DEVICES, REMOTE, TOUCHPAD }

private fun hasBtPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

@Composable
fun App(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { BtHidController(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    var screen by remember { mutableStateOf(Screen.DEVICES) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    var lastAddress by remember { mutableStateOf(prefs.getString("last", null)) }
    var tick by remember { mutableIntStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }

    // Permiso de Bluetooth
    var granted by remember { mutableStateOf(hasBtPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result[Manifest.permission.BLUETOOTH_CONNECT] == true || hasBtPermission(context)
    }
    fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        }
    }
    LaunchedEffect(Unit) {
        val missing = Build.VERSION.SDK_INT >= 31 && (
            !hasBtPermission(context) ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
            )
        if (missing) requestBtPermissions()
    }
    LaunchedEffect(granted) { if (granted) controller.start() }

    // Al volver a la app (por ejemplo de los ajustes de Bluetooth) se actualiza la lista
    DisposableEffect(Unit) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val devices = remember(granted, tick, controller.ready) { if (granted) controller.bonded() else emptyList() }

    fun connectTo(d: BluetoothDevice) {
        selected = d
        lastAddress = d.address
        prefs.edit().putString("last", d.address).apply()
        controller.connect(d)
        if (controller.connectedAddress == d.address) screen = Screen.REMOTE
    }

    // Se abre el control solo cuando la conexión se establece de verdad
    LaunchedEffect(controller.connectedAddress) {
        val addr = controller.connectedAddress
        if (addr != null && screen == Screen.DEVICES) {
            selected = controller.bonded().firstOrNull { it.address == addr } ?: selected
            screen = Screen.REMOTE
        }
    }

    // "Auto": al estar listo, se conecta solo al último dispositivo usado
    LaunchedEffect(controller.ready) {
        if (controller.ready) {
            val last = prefs.getString("last", null)
            controller.bonded().firstOrNull { it.address == last }?.let { connectTo(it) }
        }
    }

    // Al abrir la app: ¿hay una versión más nueva en GitHub?
    LaunchedEffect(Unit) { update = Updater.check() }

    when (screen) {
        Screen.DEVICES -> DevicesScreen(
            status = controller.status,
            ready = controller.ready,
            failed = controller.failed,
            permissionGranted = granted,
            devices = devices,
            lastAddress = lastAddress,
            onRequestPermission = { requestBtPermissions() },
            onRetry = { controller.retry() },
            onPair = {
                val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                try { context.startActivity(i) } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            },
            onSettingsBt = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            onHelp = { showHelp = true },
            onSettings = { showSettings = true },
            onPick = { connectTo(it) }
        )
        Screen.REMOTE -> {
            BackHandler { screen = Screen.DEVICES }
            RemoteScreen(
                deviceName = selected?.safeName() ?: "Control",
                status = controller.status,
                controller = controller,
                onMouse = { screen = Screen.TOUCHPAD },
                onKeyboard = { showKeyboard = true },
                onDevices = { screen = Screen.DEVICES },
                onDisconnect = { controller.disconnect(); screen = Screen.DEVICES }
            )
        }
        Screen.TOUCHPAD -> TouchpadScreen(controller) { screen = Screen.REMOTE }
    }

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

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Ayuda") },
            text = {
                Text(
                    "1. Toca \"Hacer visible este celular\" y, en tu TV o el otro teléfono, busca este celular en " +
                        "los ajustes de Bluetooth y empareja.\n\n" +
                        "2. Vuelve aquí y toca el dispositivo en la lista. El control se abre cuando conecta.\n\n" +
                        "3. Si no conecta, olvida este celular en el otro dispositivo y empareja de nuevo.\n\n" +
                        "Cierra otras apps de control remoto Bluetooth (Ajustes > Apps > Forzar detención): " +
                        "solo una puede usar el Bluetooth a la vez."
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Entendido") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Ajustes") },
            text = { Text("Versión ${BuildConfig.VERSION_NAME}") },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    scope.launch {
                        val u = Updater.check()
                        if (u != null) update = u
                        else Toast.makeText(context, "Ya tienes la última versión.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Buscar actualización") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cerrar") } }
        )
    }

    val u = update
    if (u != null) {
        val p = progress
        if (p == null) {
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("Actualización disponible") },
                text = { Text("Hay una nueva versión (${u.versionName}). ¿Desea descargar esta actualización?") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            progress = 0
                            try {
                                val apk = Updater.download(context, u) { progress = it }
                                progress = null
                                update = null
                                if (!Updater.install(context, apk)) {
                                    Toast.makeText(
                                        context,
                                        "Permite instalar desde esta app y vuelve a abrirla para actualizar.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (e: Exception) {
                                progress = null
                                Toast.makeText(context, "No se pudo descargar la actualización.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("Aceptar") }
                },
                dismissButton = { TextButton(onClick = { update = null }) { Text("Rechazar") } }
            )
        } else {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Descargando actualización…") },
                text = { LinearProgressIndicator(progress = { p / 100f }) },
                confirmButton = {}
            )
        }
    }
}
