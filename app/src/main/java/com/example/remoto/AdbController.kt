package com.example.remoto

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Envía las teclas por ADB sobre wifi (TCP). Sirve para TVs Android/Google TV y teléfonos Android
 * que tengan ADB por red activado. No hay que instalar nada en el dispositivo controlado.
 */
class AdbController(
    private val device: Device,
    private val keysDir: File,
    private val onStatus: (String) -> Unit
) : RemoteController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var dadb: Dadb? = null

    init {
        scope.launch { for (cmd in queue) run(cmd) }
    }

    private fun connect(): Dadb {
        dadb?.let { return it }
        val priv = File(keysDir, "adbkey")
        val pub = File(keysDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        onStatus("Conectando a ${device.host}:${device.port}…")
        val d = Dadb.create(device.host, device.port, AdbKeyPair.read(priv, pub))
        dadb = d
        onStatus("Conectado")
        return d
    }

    private fun run(cmd: String) {
        try {
            connect().shell(cmd)
        } catch (e: Exception) {
            runCatching { dadb?.close() }
            dadb = null
            onStatus("Sin conexión: ${e.message ?: "error"}. Toca un botón para reintentar.")
        }
    }

    override fun send(key: Key) {
        queue.trySend("input keyevent ${key.code}")
    }

    override fun sendText(text: String) {
        // `input text` usa %s para el espacio; se escapa lo demás con comillas simples.
        val safe = text.replace(" ", "%s").replace("'", "'\\''")
        queue.trySend("input text '$safe'")
    }

    fun close() {
        scope.cancel()
        runCatching { dadb?.close() }
    }
}
