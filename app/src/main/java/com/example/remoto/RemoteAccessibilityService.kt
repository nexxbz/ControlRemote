package com.example.remoto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** PIN de 6 dígitos que protege este teléfono; el otro teléfono debe escribirlo para controlarlo. */
object ReceiverPin {
    private fun prefs(context: Context) = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun get(context: Context): String {
        var pin = prefs(context).getString("pin", null)
        if (pin == null) {
            pin = newPin()
            prefs(context).edit().putString("pin", pin).apply()
        }
        return pin
    }

    fun renew(context: Context): String {
        val pin = newPin()
        prefs(context).edit().putString("pin", pin).apply()
        return pin
    }

    private fun newPin(): String = (100000 + SecureRandom().nextInt(900000)).toString()
}

/**
 * Servicio de accesibilidad: abre un puerto por wifi, se anuncia en la red y ejecuta las órdenes
 * (atrás, inicio, volumen, toques, deslizamientos, texto) de quien conozca el PIN.
 */
class RemoteAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var cursor: CursorView? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    private var cx = 0f
    private var cy = 0f
    private var cursorActive = false
    private val hideCursor = Runnable {
        cursor?.visibility = View.GONE
        cursorActive = false
    }
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val releaseAwake = Runnable { releaseAwakeLocks() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val m = resources.displayMetrics
        cx = m.widthPixels / 2f
        cy = m.heightPixels / 2f
        startServer()
        registerNsd()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        releaseAwakeLocks()
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        try {
            regListener?.let { nsd?.unregisterService(it) }
        } catch (e: Exception) {
        }
        regListener = null
        main.post { removeCursor() }
    }

    // ---------- Red ----------

    private fun startServer() {
        if (server != null) return
        Thread {
            try {
                val ss = ServerSocket(CONTROL_PORT)
                server = ss
                while (!ss.isClosed) {
                    val client = ss.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                server = null
            }
        }.start()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val out = client.getOutputStream()
            val hello = reader.readLine() ?: return
            if (hello != "HELLO ${ReceiverPin.get(this)}") {
                Thread.sleep(1000)
                out.write("NO\n".toByteArray())
                out.flush()
                return
            }
            out.write("OK ${Build.MODEL}|${Build.VERSION.SDK_INT}\n".toByteArray())
            out.flush()
            client.soTimeout = 0
            while (true) {
                val line = reader.readLine() ?: break
                main.post { execute(line) }
            }
        } catch (e: Exception) {
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun registerNsd() {
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val info = NsdServiceInfo()
        info.serviceName = Build.MODEL
        info.serviceType = SERVICE_TYPE
        info.port = CONTROL_PORT
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        regListener = l
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            regListener = null
        }
    }

    // ---------- Órdenes ----------

    private fun execute(line: String) {
        val idx = line.indexOf(':')
        if (idx < 0) return
        val cmd = line.substring(0, idx)
        val arg = line.substring(idx + 1)
        when (cmd) {
            "K" -> key(arg)
            "T" -> typeText(arg)
            "M" -> {
                val p = arg.split(",")
                moveCursor(p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            "C" -> clickAtCursor(arg == "R")
            "S" -> scrollAtCursor(arg.toIntOrNull() ?: 0)
        }
    }

    private fun key(name: String) {
        when (name) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "MENU" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "SETTINGS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "POWER" -> power()
            "VOL_UP" -> volume(AudioManager.ADJUST_RAISE)
            "VOL_DOWN" -> volume(AudioManager.ADJUST_LOWER)
            "MUTE" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "PLAY" -> media(KeyEvent.KEYCODE_MEDIA_PLAY)
            "PAUSE" -> media(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "REWIND" -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            "FORWARD" -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "BRIGHT_UP" -> brightness(25)
            "BRIGHT_DOWN" -> brightness(-25)
            "OK" -> dpad(4)
            "UP" -> dpad(0)
            "DOWN" -> dpad(1)
            "LEFT" -> dpad(2)
            "RIGHT" -> dpad(3)
            "STOP" -> media(KeyEvent.KEYCODE_MEDIA_STOP)
            "PREV" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "NEXT" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            "SCREENSHOT" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "SPACE" -> typeText(" ")
            "BACKSPACE" -> backspace()
            "ENTER" -> enter()
            else -> if (name.length == 2 && name[0] == 'N' && name[1].isDigit()) typeText(name.substring(1))
        }
    }

    private fun volume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun media(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun brightness(delta: Int) {
        if (!Settings.System.canWrite(this)) return
        val cr = contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (current + delta).coerceIn(1, 255))
    }

    /**
     * Cruceta real. En Android 13+ el sistema ejecuta las teclas de dirección y OK como un control de TV
     * (mueve el foco en cualquier app). En versiones anteriores se mueve el foco desde accesibilidad.
     * dir: 0 arriba, 1 abajo, 2 izquierda, 3 derecha, 4 centro (OK).
     */
    private fun dpad(dir: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            val action = when (dir) {
                0 -> GLOBAL_ACTION_DPAD_UP
                1 -> GLOBAL_ACTION_DPAD_DOWN
                2 -> GLOBAL_ACTION_DPAD_LEFT
                3 -> GLOBAL_ACTION_DPAD_RIGHT
                else -> GLOBAL_ACTION_DPAD_CENTER
            }
            if (performGlobalAction(action)) return
        }
        dpadFallback(dir)
    }

    /** Cruceta por accesibilidad (Android 12 o menor): mueve el foco, nunca toca la pantalla. */
    private fun dpadFallback(dir: Int) {
        val root = rootInActiveWindow ?: return
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (dir == 4) {
            // OK: pulsar el elemento enfocado (o el cursor si se acaba de usar el panel táctil)
            var n = current
            while (n != null) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                n = n.parent
            }
            if (cursorActive) clickAtCursor(false)
            return
        }

        // Sin foco todavía: la primera flecha enfoca el primer elemento, como en un control de TV
        if (current == null) {
            firstFocusable(root)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }

        val direction = when (dir) {
            0 -> View.FOCUS_UP
            1 -> View.FOCUS_DOWN
            2 -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val next = current.focusSearch(direction)
        if (next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return

        // No hay más elementos en esa dirección: desplazar la lista si se puede
        val scroll = when (dir) {
            0 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            1 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            2 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        var n: AccessibilityNodeInfo? = current
        while (n != null) {
            if (n.isScrollable && n.performAction(scroll)) return
            n = n.parent
        }
    }

    private fun firstFocusable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocusable && node.isVisibleToUser && node.isEnabled && node.childCount == 0) return node
        for (i in 0 until node.childCount) {
            val found = firstFocusable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ---------- Encender / apagar ----------

    /** Un solo botón: si la pantalla está encendida la apaga; si está apagada la enciende. */
    private fun power() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            holdAwakeForWake()
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            wakeScreen()
        }
    }

    /** Con la pantalla apagada se mantiene la conexión viva un rato para poder volver a encenderla. */
    @Suppress("DEPRECATION")
    private fun holdAwakeForWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (cpuLock == null) cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "controlremoto:cpu")
            cpuLock?.acquire(30 * 60 * 1000L)
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "controlremoto:wifi")
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
            main.removeCallbacks(releaseAwake)
            main.postDelayed(releaseAwake, 30 * 60 * 1000L)
        } catch (e: Exception) {
        }
    }

    private fun releaseAwakeLocks() {
        try {
            if (cpuLock?.isHeld == true) cpuLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "controlremoto:wake"
            )
            wl.acquire(5000)
        } catch (e: Exception) {
        }
        main.removeCallbacks(releaseAwake)
        releaseAwakeLocks()
    }

    private fun backspace() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        if (current.isEmpty()) return
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.dropLast(1))
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun enter() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val ok = node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
        if (!ok) dpad(4)
    }

    private fun typeText(text: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------- Gestos y cursor ----------

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun swipe(fx1: Float, fy1: Float, fx2: Float, fy2: Float) {
        val m = resources.displayMetrics
        val path = Path()
        path.moveTo(fx1 * m.widthPixels, fy1 * m.heightPixels)
        path.lineTo(fx2 * m.widthPixels, fy2 * m.heightPixels)
        gesture(path, 250)
    }

    private fun moveCursor(dx: Int, dy: Int) {
        val m = resources.displayMetrics
        cx = (cx + dx).coerceIn(0f, m.widthPixels.toFloat())
        cy = (cy + dy).coerceIn(0f, m.heightPixels.toFloat())
        showCursor()
    }

    private fun clickAtCursor(hold: Boolean) {
        val path = Path()
        path.moveTo(cx, cy)
        gesture(path, if (hold) 700L else 60L)
        showCursor()
    }

    private fun scrollAtCursor(amount: Int) {
        if (amount == 0) return
        val m = resources.displayMetrics
        val dist = if (amount > 0) 450f else -450f
        val y2 = (cy + dist).coerceIn(0f, m.heightPixels.toFloat())
        val path = Path()
        path.moveTo(cx, cy)
        path.lineTo(cx, y2)
        gesture(path, 250)
        showCursor()
    }

    private fun showCursor() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (28 * resources.displayMetrics.density).toInt()
        val x = (cx - size / 2f).toInt()
        val y = (cy - size / 2f).toInt()
        val existing = cursor
        val lp = cursorLp
        if (existing == null || lp == null) {
            val view = CursorView(this)
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = x
            params.y = y
            try {
                wm.addView(view, params)
                cursor = view
                cursorLp = params
            } catch (e: Exception) {
                return
            }
        } else {
            lp.x = x
            lp.y = y
            try {
                wm.updateViewLayout(existing, lp)
            } catch (e: Exception) {
            }
        }
        cursor?.visibility = View.VISIBLE
        cursorActive = true
        main.removeCallbacks(hideCursor)
        main.postDelayed(hideCursor, 4000)
    }

    private fun removeCursor() {
        val view = cursor ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
        }
        cursor = null
        cursorLp = null
    }
}

/** Círculo que marca dónde está el cursor en el teléfono controlado. */
class CursorView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f - 4f
        canvas.drawCircle(width / 2f, height / 2f, r, fill)
        canvas.drawCircle(width / 2f, height / 2f, r, stroke)
    }
}
