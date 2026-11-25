package com.example.remoto

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

const val CONTROL_PORT = 47321
const val SERVICE_TYPE = "_controlremoto._tcp."

/** IP wifi de este teléfono (por ejemplo 192.168.1.20) o null si no hay red. */
fun localIpv4(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}

/**
 * Cliente: se conecta por wifi (TCP) a otro teléfono que tenga esta app con el servicio activado
 * y le envía las órdenes como líneas de texto.
 */
class WifiController : RemoteController {

    var status by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var out: OutputStream? = null

    fun connect(host: String, port: Int, pin: String, onDone: (ok: Boolean, pinWrong: Boolean) -> Unit) {
        status = "Conectando a $host…"
        io.execute {
            closeQuietly()
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                val o = s.getOutputStream()
                o.write("HELLO $pin\n".toByteArray(Charsets.UTF_8))
                o.flush()
                val reply = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                if (reply != null && reply.startsWith("OK")) {
                    s.soTimeout = 0
                    socket = s
                    out = o
                    main.post {
                        connected = true
                        status = "Conectado"
                        onDone(true, false)
                    }
                } else {
                    s.close()
                    val wrong = reply == "NO"
                    main.post {
                        connected = false
                        status = if (wrong) "PIN incorrecto." else "El otro teléfono rechazó la conexión."
                        onDone(false, wrong)
                    }
                }
            } catch (e: Exception) {
                main.post {
                    connected = false
                    status = "No se pudo conectar. Revisa que el servicio esté activado en el otro teléfono y que ambos estén en la misma wifi."
                    onDone(false, false)
                }
            }
        }
    }

    fun disconnect() {
        io.execute { closeQuietly() }
        connected = false
        status = "Desconectado"
    }

    fun stop() {
        io.execute { closeQuietly() }
        io.shutdown()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        out = null
    }

    private fun sendLine(line: String) {
        io.execute {
            val o = out
            if (o == null) {
                main.post { status = "Sin conexión. Elige un dispositivo." }
                return@execute
            }
            try {
                o.write((line + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (e: Exception) {
                closeQuietly()
                main.post {
                    connected = false
                    status = "Conexión perdida."
                }
            }
        }
    }

    override fun send(key: Key) = sendLine("K:${key.name}")
    override fun sendText(text: String) = sendLine("T:" + text.replace("\n", " "))
    override fun moveMouse(dx: Int, dy: Int) = sendLine("M:$dx,$dy")
    override fun click(right: Boolean) = sendLine(if (right) "C:R" else "C:L")
    override fun scroll(amount: Int) = sendLine("S:$amount")
}
