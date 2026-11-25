package com.example.remoto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * El celular se hace pasar por un teclado + mouse + control multimedia Bluetooth (perfil HID).
 * El dispositivo a controlar (TV, otro celular, PC) solo necesita estar emparejado por Bluetooth.
 */
@SuppressLint("MissingPermission")
class BtHidController(private val context: Context) : RemoteController {

    var status by mutableStateOf("")
        private set
    var ready by mutableStateOf(false)
        private set
    var connectedAddress by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var started = false
    private var stopping = false
    private val timeout = Runnable {
        if (connectedAddress == null) {
            status = "No respondió. En el otro dispositivo abre sus ajustes de Bluetooth, olvida este celular " +
                "y empareja de nuevo. Aquí toca \"Hacer visible este celular\"."
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            ready = registered
            failed = !registered && !stopping
            if (registered) status = ""
            else if (!stopping) status = "Otra app de control remoto Bluetooth está usando el Bluetooth. Ciérrala (Ajustes > Apps > Forzar detención) y toca Reintentar."
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (device == null) return
            val name = device.name ?: device.address
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    main.removeCallbacks(timeout)
                    host = device
                    connectedAddress = device.address
                    status = "Conectado a $name"
                }
                BluetoothProfile.STATE_CONNECTING -> status = "Conectando a $name…"
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    if (connectedAddress == device.address) connectedAddress = null
                    status = "Desconectado de $name"
                }
            }
        }
    }

    fun start() {
        if (started) return
        val a = adapter
        if (a == null) {
            status = "Este teléfono no tiene Bluetooth."
            return
        }
        if (!a.isEnabled) {
            status = "Activa el Bluetooth y vuelve a abrir la app."
            return
        }
        started = true
        stopping = false
        failed = false
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Control Remoto", "Control remoto Bluetooth", "ControlRemoto",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
                )
                val registered = h.registerApp(sdp, null, null, ContextCompat.getMainExecutor(context), callback)
                if (!registered) {
                    failed = true
                    status = "Este teléfono no permite usar el control Bluetooth (HID) o está ocupado por otra app."
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                ready = false
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) status = "No se pudo iniciar el Bluetooth HID en este teléfono."
    }

    fun bonded(): List<BluetoothDevice> =
        try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    fun connect(device: BluetoothDevice) {
        val h = hid
        if (h == null || !ready) {
            status = "El control Bluetooth aún no está listo. Espera un momento."
            return
        }
        if (host?.address == device.address) {
            status = "Conectado a ${device.name ?: device.address}"
            return
        }
        try {
            host?.let { h.disconnect(it) }
            status = "Conectando a ${device.name ?: device.address}…"
            if (!h.connect(device)) {
                status = "No se pudo conectar. Revisa que esté emparejado y con Bluetooth activo."
            } else {
                main.removeCallbacks(timeout)
                main.postDelayed(timeout, 12000)
            }
        } catch (e: Exception) {
            status = "No se pudo conectar."
        }
    }

    fun disconnect() {
        try {
            host?.let { hid?.disconnect(it) }
        } catch (e: Exception) {
        }
    }

    fun retry() {
        stop()
        status = "Reintentando…"
        main.postDelayed({ start() }, 700)
    }

    fun stop() {
        stopping = true
        main.removeCallbacks(timeout)
        try {
            hid?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {
        }
        hid = null
        host = null
        started = false
        ready = false
    }

    // ---------- Envío de teclas ----------

    private fun report(id: Int, data: ByteArray, quiet: Boolean = false) {
        val h = hid
        val d = host
        if (h == null || d == null) {
            if (!quiet) status = "Sin conexión. Elige un dispositivo en la lista."
            return
        }
        try {
            h.sendReport(d, id, data)
        } catch (e: Exception) {
        }
    }

    private fun tapConsumer(usage: Int) {
        report(3, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        main.postDelayed({ report(3, byteArrayOf(0, 0), true) }, 40)
    }

    private fun tapKeyboard(mod: Int, usage: Int) {
        report(1, byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        main.postDelayed({ report(1, ByteArray(8), true) }, 40)
    }

    override fun send(key: Key) {
        if (key.consumer) tapConsumer(key.usage) else tapKeyboard(0, key.usage)
    }

    override fun sendText(text: String) {
        var i = 0
        for (c in text) {
            val hidKey = charToHid(c) ?: continue
            val t = i * 70L
            main.postDelayed({ report(1, byteArrayOf(hidKey.first.toByte(), 0, hidKey.second.toByte(), 0, 0, 0, 0, 0)) }, t)
            main.postDelayed({ report(1, ByteArray(8), true) }, t + 35)
            i++
        }
    }

    override fun moveMouse(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            report(2, byteArrayOf(0, sx.toByte(), sy.toByte(), 0), true)
            x -= sx
            y -= sy
        }
    }

    override fun click(right: Boolean) {
        val button = if (right) 2 else 1
        report(2, byteArrayOf(button.toByte(), 0, 0, 0))
        main.postDelayed({ report(2, byteArrayOf(0, 0, 0, 0), true) }, 40)
    }

    override fun scroll(amount: Int) {
        report(2, byteArrayOf(0, 0, 0, amount.coerceIn(-127, 127).toByte()), true)
    }

    private fun charToHid(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
        in 'A'..'Z' -> 0x02 to (0x04 + (c - 'A'))
        in '1'..'9' -> 0 to (0x1E + (c - '1'))
        '0' -> 0 to 0x27
        ' ' -> 0 to 0x2C
        '\n' -> 0 to 0x28
        '.' -> 0 to 0x37
        ',' -> 0 to 0x36
        '-' -> 0 to 0x2D
        '_' -> 0x02 to 0x2D
        '@' -> 0x02 to 0x1F
        '/' -> 0 to 0x38
        ':' -> 0x02 to 0x33
        ';' -> 0 to 0x33
        '?' -> 0x02 to 0x38
        '!' -> 0x02 to 0x1E
        '\'' -> 0 to 0x34
        else -> null
    }

    companion object {
        /** Descriptor HID: teclado (id 1), mouse (id 2) y control multimedia (id 3). */
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // Teclado
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Mouse
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // Control multimedia
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0
        ).map { it.toByte() }.toByteArray()
    }
}
