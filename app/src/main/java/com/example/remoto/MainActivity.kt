package com.example.remoto

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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

private enum class Screen { DEVICES, REMOTE, TOUCHPAD, RECEIVER }

@Composable
fun App(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PeerStore(context) }
    val controller = remember { WifiController() }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    var screen by remember { mutableStateOf(Screen.DEVICES) }
    var saved by remember { mutableStateOf(store.load()) }
    val discovered = remember { mutableStateListOf<Peer>() }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var lastName by remember { mutableStateOf(prefs.getString("last", null)) }
    var askPin by remember { mutableStateOf<Peer?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var pinText by remember { mutableStateOf(ReceiverPin.get(context)) }
    var showHelp by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var autoTried by remember { mutableStateOf(false) }

    // Al volver a la app (por ejemplo de Accesibilidad) se actualiza el estado
    DisposableEffect(Unit) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val ownIp = remember(tick) { localIpv4(context) }

    // Búsqueda de teléfonos en la red
    DisposableEffect(tick) {
        val d = WifiDiscovery(
            context,
            onFound = { p ->
                if (p.host != ownIp) {
                    val i = discovered.indexOfFirst { it.name == p.name }
                    if (i >= 0) discovered[i] = p else discovered.add(p)
                }
            },
            onLost = { name -> discovered.removeAll { it.name == name } }
        )
        d.start()
        onDispose { d.stop() }
    }

    val peers = mergePeers(saved, discovered.toList())
    val online = discovered.map { it.name }.toSet()

    fun connectPeer(p: Peer, pin: String) {
        controller.connect(p.host, p.port, pin) { ok, pinWrong ->
            if (ok) {
                saved = saved.filter { it.name != p.name } + Peer(p.name, p.host, p.port, pin)
                store.save(saved)
                lastName = p.name
                prefs.edit().putString("last", p.name).apply()
                selectedName = p.name
                screen = Screen.REMOTE
            } else if (pinWrong) {
                askPin = p
            }
        }
    }

    // "Auto": se conecta solo al último teléfono usado cuando aparece en la red
    LaunchedEffect(discovered.size) {
        if (!autoTried && screen == Screen.DEVICES) {
            val last = prefs.getString("last", null)
            val p = peers.firstOrNull { it.name == last && it.pin != null && it.name in online }
            val pin = p?.pin
            if (p != null && pin != null) {
                autoTried = true
                connectPeer(p, pin)
            }
        }
    }

    // Al abrir la app: ¿hay una versión más nueva en GitHub?
    LaunchedEffect(Unit) { update = Updater.check().update }

    when (screen) {
        Screen.DEVICES -> DevicesScreen(
            status = controller.status,
            peers = peers,
            online = online,
            lastName = lastName,
            onPick = { p ->
                val pin = p.pin
                if (pin != null) connectPeer(p, pin) else askPin = p
            },
            onForget = { p ->
                saved = saved.filter { it.name != p.name }
                store.save(saved)
            },
            onAdd = { p ->
                saved = saved.filter { it.name != p.name } + p
                store.save(saved)
            },
            onReceiver = { screen = Screen.RECEIVER },
            onHelp = { showHelp = true },
            onSettings = { showSettings = true }
        )
        Screen.REMOTE -> {
            BackHandler { screen = Screen.DEVICES }
            RemoteScreen(
                deviceName = selectedName ?: "Control",
                status = controller.status,
                controller = controller,
                onMouse = { screen = Screen.TOUCHPAD },
                onKeyboard = { showKeyboard = true },
                onDevices = { screen = Screen.DEVICES },
                onDisconnect = { controller.disconnect(); screen = Screen.DEVICES }
            )
        }
        Screen.TOUCHPAD -> TouchpadScreen(controller) { screen = Screen.REMOTE }
        Screen.RECEIVER -> {
            val enabled = remember(tick) { isServiceEnabled(context) }
            val canWrite = remember(tick) { Settings.System.canWrite(context) }
            ReceiverScreen(
                enabled = enabled,
                pin = pinText,
                ip = ownIp,
                canWriteSettings = canWrite,
                onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOpenAppInfo = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
                onOpenWriteSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
                onNewPin = { pinText = ReceiverPin.renew(context) },
                onBack = { screen = Screen.DEVICES }
            )
        }
    }

    val ap = askPin
    if (ap != null) {
        var pin by remember(ap) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPin = null },
            title = { Text("PIN de ${ap.name}") },
            text = {
                Column {
                    Text("Escribe el PIN de 6 dígitos que aparece en el otro teléfono, en \"Este teléfono\".")
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pin.length == 6, onClick = { askPin = null; connectPeer(ap, pin) }) { Text("Conectar") }
            },
            dismissButton = { TextButton(onClick = { askPin = null }) { Text("Cancelar") } }
        )
    }

    if (showKeyboard) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyboard = false },
            title = { Text("Escribir en el otro teléfono") },
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
                    "Instala esta misma app en los dos teléfonos y conéctalos a la misma wifi.\n\n" +
                        "En el teléfono que quieres controlar: toca \"Este teléfono\", activa el servicio en " +
                        "Accesibilidad y anota el PIN.\n\n" +
                        "En este teléfono: espera a que aparezca en la lista, tócalo y escribe el PIN. " +
                        "Si no aparece, agrégalo por su IP.\n\n" +
                        "Si el servicio no se deja activar, permite los ajustes restringidos en la info de la app."
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Entendido") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Ajustes") },
            text = { Text("Versión instalada: ${BuildConfig.VERSION_NAME}") },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    scope.launch {
                        val r = Updater.check()
                        if (r.update != null) update = r.update
                        else Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
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
