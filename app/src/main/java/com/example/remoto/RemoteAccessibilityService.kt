package com.example.remoto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

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
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val pushExec = Executors.newSingleThreadExecutor()
    private var displayListener: DisplayManager.DisplayListener? = null
    private var secondaryOverlayBlocked = false
    private var lastOverlayError = ""
    private var manualDisplaySelected = false
    private var lastKnownDisplayCount = 1
    private var cursor: CursorView? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    private var cursorWm: WindowManager? = null
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private var cursorDisplayId: Int = Display.DEFAULT_DISPLAY
    private var cx = 0f
    private var cy = 0f
    private var cursorActive = false
    private var lastPointerMove = 0L
    private var ring: RingView? = null
    private var ringLp: WindowManager.LayoutParams? = null
    private var ringBounds: Rect? = null
    private val hideRing = Runnable { ring?.visibility = View.GONE }
    private class Cand(val node: AccessibilityNodeInfo, val rect: Rect)
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
        ShizukuBridge.tryBindIfPermitted(this)
        registerDisplayListener()
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
            displayListener?.let {
                (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(it)
            }
        } catch (_: Exception) {
        }
        displayListener = null
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
        clients.clear()
        ShizukuBridge.unbind()
        main.removeCallbacksAndMessages(mediaFallbackToken)
        main.removeCallbacksAndMessages(focusRecoverToken)
        main.post {
            removeCursor()
            removeRing()
        }
    }

    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY && !manualDisplaySelected) {
                    val target = allDisplays().firstOrNull { it.displayId == displayId }
                    if (target != null) {
                        switchActiveDisplay(target, recenter = true)
                        showCursor()
                    }
                }
                notifyDisplayState()
            }
            override fun onDisplayRemoved(displayId: Int) {
                if (shizukuTouchDragging && dragDisplayId == displayId) {
                    shizukuTouchDragging = false
                }
                if (activeDisplayId == displayId) {
                    activeDisplayId = Display.DEFAULT_DISPLAY
                    manualDisplaySelected = false
                    secondaryOverlayBlocked = false
                    lastOverlayError = ""
                    removeCursor()
                    ShizukuBridge.tryBindDisplay(Display.DEFAULT_DISPLAY, null) {
                        notifyDisplayState()
                    }
                }
                notifyDisplayState()
            }
            override fun onDisplayChanged(displayId: Int) { notifyDisplayState() }
        }
        displayListener = listener
        try {
            dm.registerDisplayListener(listener, main)
        } catch (_: Exception) {
        }
    }

    private fun buildDisplayStateLine(): String {
        val displays = allDisplays()
        val count = displays.size.coerceAtLeast(1)
        val idx = displays.indexOfFirst { it.displayId == activeDisplayId }.let { if (it >= 0) it else 0 }
        val blocked = if (secondaryOverlayBlocked) 1 else 0
        val note = when {
            secondaryOverlayBlocked && lastOverlayError.isNotEmpty() -> lastOverlayError.replace("|", " ").replace("\n", " ").take(80)
            count > 1 && activeDisplayId != Display.DEFAULT_DISPLAY -> {
                if (ShizukuBridge.isReady()) "Puntero + Shizuku en Pantalla ${idx + 1}"
                else "Puntero (Accesibilidad) en Pantalla ${idx + 1}"
            }
            count > 1 -> if (useKernelUhid()) "Mouse real (UHID) en Tel" else "Puntero en Tel"
            else -> ""
        }
        return "DISPLAYS:$count|$idx|$blocked|$note\n"
    }

    private fun notifyDisplayState() {
        if (clients.isEmpty()) return
        val msg = buildDisplayStateLine().toByteArray(Charsets.UTF_8)
        pushExec.execute {
            for (out in clients) {
                try {
                    out.write(msg)
                    out.flush()
                } catch (_: Exception) {
                    clients.remove(out)
                }
            }
        }
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
        var outRef: OutputStream? = null
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
            outRef = out
            clients.add(out)
            main.post { notifyDisplayState() }
            client.soTimeout = 0
            while (true) {
                val line = reader.readLine() ?: break
                main.post { execute(line) }
            }
        } catch (e: Exception) {
        } finally {
            outRef?.let { clients.remove(it) }
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
            "D" -> if (arg == "1") dragStart() else dragEnd()
            "DISP" -> selectDisplayByIndex(arg.toIntOrNull() ?: 0)
            "M_HIDE" -> hideCursorNow()
        }
    }

    private fun key(name: String) {
        when (name) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "MENU" -> if (!sendPcKey(KeyEvent.KEYCODE_MENU)) performGlobalAction(GLOBAL_ACTION_RECENTS)
            "SETTINGS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "POWER" -> power()
            "VOL_UP" -> volume(AudioManager.ADJUST_RAISE)
            "VOL_DOWN" -> volume(AudioManager.ADJUST_LOWER)
            "MUTE" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "PLAY" -> playPause(true)
            "PAUSE" -> playPause(false)
            "PLAY_PAUSE" -> togglePlayPause()
            "REWIND" -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            "FORWARD" -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "BRIGHT_UP" -> brightness(1)
            "BRIGHT_DOWN" -> brightness(-1)
            "OK" -> {
                if (ring?.visibility == View.VISIBLE) okPress()
                else if (!sendPcKey(KeyEvent.KEYCODE_DPAD_CENTER)) dpad(4)
            }
            "UP" -> arrow(0, KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> arrow(1, KeyEvent.KEYCODE_DPAD_DOWN)
            "LEFT" -> arrow(2, KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> arrow(3, KeyEvent.KEYCODE_DPAD_RIGHT)
            "STOP" -> media(KeyEvent.KEYCODE_MEDIA_STOP)
            "PREV" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "NEXT" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            "SCREENSHOT" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "SPACE" -> typeText(" ")
            "BACKSPACE" -> backspace()
            "ENTER" -> if (!sendPcKey(KeyEvent.KEYCODE_ENTER)) enter()
            else -> if (name.length == 2 && name[0] == 'N' && name[1].isDigit()) typeText(name.substring(1))
        }
    }

    private val focusRecoverToken = Any()

    private fun arrow(dir: Int, keyCode: Int) {
        hideCursorNow()
        main.removeCallbacksAndMessages(focusRecoverToken)
        if (ring?.visibility == View.VISIBLE) {
            moveVirtual(dir, true)
            return
        }
        val beforeNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val beforeRect = beforeNode?.let { Rect().apply { it.getBoundsInScreen(this) } }
        if (!sendPcKey(keyCode)) {
            dpad(dir)
            return
        }
        main.postAtTime({
            recoverFocusIfStuck(dir, beforeNode, beforeRect)
        }, focusRecoverToken, SystemClock.uptimeMillis() + 160L)
    }

    private fun recoverFocusIfStuck(dir: Int, beforeNode: AccessibilityNodeInfo?, beforeRect: Rect?) {
        val root = rootInActiveWindow ?: return
        val afterNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val direction = when (dir) {
            0 -> View.FOCUS_UP
            1 -> View.FOCUS_DOWN
            2 -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }

        if (afterNode == null) {
            val all = ArrayList<Cand>()
            collect(root, all, 0)
            val focusables = all.filter { it.node.isFocusable && it.node.isVisibleToUser }
            if (focusables.isEmpty()) return
            val selected = focusables.firstOrNull { it.node.isSelected }
            if (selected != null) {
                val next = selected.node.focusSearch(direction)
                if (next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
                val geom = nearestInDirection(focusables, selected.rect, dir)
                if (geom != null && geom.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
                if (selected.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
            }
            val fallback = focusables.minWithOrNull(compareBy<Cand>({ it.rect.top / 40 }, { it.rect.left }))?.node
            fallback?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }

        if (beforeNode == null || beforeRect == null || afterNode.isEditable || afterNode.isScrollable || afterNode.rangeInfo != null) return
        if (afterNode != beforeNode) return
        val afterRect = Rect()
        afterNode.getBoundsInScreen(afterRect)
        if (afterRect != beforeRect) return

        // El evento de teclado no movió el foco desde afterNode: intentamos moverlo al elemento vecino en dir
        val next = afterNode.focusSearch(direction)
        if (next != null && next != afterNode && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            removeRing()
            return
        }

        val all = ArrayList<Cand>()
        collect(root, all, 0)
        val m = resources.displayMetrics
        val screenArea = m.widthPixels.toFloat() * m.heightPixels
        val small = all.filter { it.rect.width().toFloat() * it.rect.height() < screenArea * 0.7f }
        val focusables = small.filter { it.node.isFocusable && it.node.isVisibleToUser }
        val geom = nearestInDirection(focusables, afterRect, dir)
        if (geom != null && geom.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            removeRing()
            return
        }

        // Si el vecino en dir solo es clicable (no focusable), permitimos seleccionarlo con el recuadro
        val clickables = small.filter { it.node.isClickable && it.node.isVisibleToUser }
        val clickableNeighbor = nearestInDirection(clickables, afterRect, dir)
        if (clickableNeighbor != null) {
            showRing(clickableNeighbor.rect)
        }
    }

    private fun nearestInDirection(list: List<Cand>, from: Rect, dir: Int): Cand? {
        val sx = from.exactCenterX()
        val sy = from.exactCenterY()
        return list
            .filter { it.rect != from }
            .mapNotNull { c ->
                val dx = c.rect.exactCenterX() - sx
                val dy = c.rect.exactCenterY() - sy
                val primary = when (dir) {
                    0 -> -dy
                    1 -> dy
                    2 -> -dx
                    else -> dx
                }
                if (primary <= 4f) null
                else {
                    val perp = if (dir < 2) abs(dx) else abs(dy)
                    c to (primary + 2f * perp)
                }
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun sendPcKey(keyCode: Int): Boolean {
        if (RemoteKeyboardService.isSelected(this)) {
            val ime = RemoteKeyboardService.instance
            if (ime != null && ime.sendPcKey(keyCode)) {
                removeRing()
                return true
            }
        }
        if (ShizukuBridge.sendKey(keyCode)) {
            removeRing()
            return true
        }
        return false
    }

    private fun volume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private val mediaFallbackToken = Any()

    private fun playPause(play: Boolean) {
        main.removeCallbacksAndMessages(mediaFallbackToken)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val directCode = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE

        if (!sendPcKey(directCode)) {
            amDispatchMedia(directCode)
        }

        fun scheduleIfNeeded(delayMs: Long, action: () -> Unit) {
            main.postAtTime({
                val playing = am.isMusicActive
                if ((play && !playing) || (!play && playing)) {
                    action()
                }
            }, mediaFallbackToken, SystemClock.uptimeMillis() + delayMs)
        }

        scheduleIfNeeded(250L) { amDispatchMedia(directCode) }
        scheduleIfNeeded(550L) {
            if (!sendPcKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }
        scheduleIfNeeded(900L) { amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        scheduleIfNeeded(1250L) {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused?.isEditable != true) {
                sendPcKey(KeyEvent.KEYCODE_SPACE)
            }
        }
    }

    private fun togglePlayPause() {
        main.removeCallbacksAndMessages(mediaFallbackToken)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wasPlaying = am.isMusicActive

        if (!sendPcKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        fun scheduleIfUnchanged(delayMs: Long, action: () -> Unit) {
            main.postAtTime({
                if (am.isMusicActive == wasPlaying) {
                    action()
                }
            }, mediaFallbackToken, SystemClock.uptimeMillis() + delayMs)
        }

        if (wasPlaying) {
            scheduleIfUnchanged(250L) { amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            scheduleIfUnchanged(550L) {
                if (!sendPcKey(KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                    amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
            scheduleIfUnchanged(850L) { amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            scheduleIfUnchanged(1150L) {
                val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused?.isEditable != true) {
                    sendPcKey(KeyEvent.KEYCODE_SPACE)
                }
            }
        } else {
            scheduleIfUnchanged(250L) {
                if (!sendPcKey(KeyEvent.KEYCODE_MEDIA_PLAY)) {
                    amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            scheduleIfUnchanged(550L) { amDispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY) }
        }
    }

    private fun media(code: Int) {
        if (sendPcKey(code)) return
        amDispatchMedia(code)
    }

    private fun amDispatchMedia(code: Int) {
        val now = SystemClock.uptimeMillis()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(
            KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
        )
        am.dispatchMediaKeyEvent(
            KeyEvent(
                now, now, KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
        )
    }

    /** Máximo de la escala de brillo de este teléfono (muchos usan 1023, 2047 o 4095 en vez de 255). */
    private fun brightnessMax(): Int {
        return try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            if (id != 0) res.getInteger(id).coerceAtLeast(255) else 255
        } catch (e: Exception) {
            255
        }
    }

    /** direction: +1 sube, -1 baja. Cada paso es el 10 % de la escala, así se llega desde 0 hasta el 100 %. */
    private fun brightness(direction: Int) {
        if (!Settings.System.canWrite(this)) return
        val cr = contentResolver
        val max = brightnessMax()
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, max / 2)
        val step = (max * 0.1f).toInt().coerceAtLeast(1)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (current + direction * step).coerceIn(1, max))
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
        if (dir == 4) okPress() else if (!moveNative(dir)) moveVirtual(dir, true)
    }

    // ---------- Cruceta por pantalla (Android 12 o menor, o si el sistema no la ejecuta) ----------
    // Se dibuja un recuadro sobre el elemento seleccionado; las flechas lo mueven al elemento más cercano
    // en esa dirección y OK lo pulsa. Funciona aunque la app no soporte navegación con teclado.

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Cand>, depth: Int) {
        if (node == null || depth > 40 || out.size > 500) return
        if (!node.isVisibleToUser) return
        val r = Rect()
        node.getBoundsInScreen(r)
        if ((node.isClickable || node.isFocusable) && node.isEnabled && r.width() > 8 && r.height() > 8) {
            out.add(Cand(node, r))
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    /**
     * Si la app ya tiene un elemento con foco (como las apps de TV), se usa la navegación nativa del sistema:
     * el foco se mueve con el mismo buscador de foco que usan las flechas de un teclado y la app dibuja su
     * propio resaltado. Devuelve false si no hay foco y entonces se usa el recuadro.
     */
    private fun moveNative(dir: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val direction = when (dir) {
            0 -> View.FOCUS_UP
            1 -> View.FOCUS_DOWN
            2 -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val next = current.focusSearch(direction)
        if (next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            removeRing()
            return true
        }
        // Borde de la lista: se desplaza como lo haría la app
        val r = Rect()
        current.getBoundsInScreen(r)
        return scrollForDirection(root, r, dir)
    }

    private fun moveVirtual(dir: Int, retry: Boolean) {
        val root = rootInActiveWindow ?: return
        val all = ArrayList<Cand>()
        collect(root, all, 0)
        val m = resources.displayMetrics
        val screenArea = m.widthPixels.toFloat() * m.heightPixels
        var list: List<Cand> = all.filter { it.rect.width().toFloat() * it.rect.height() < screenArea * 0.7f }
        val focusables = list.filter { it.node.isFocusable }
        val ringOnNonFocusable = ringBounds != null && list.any { it.rect == ringBounds } && focusables.none { it.rect == ringBounds }
        if (focusables.size >= 2 && !ringOnNonFocusable) list = focusables
        if (list.isEmpty()) return

        var cur: Rect? = ringBounds?.let { r -> list.firstOrNull { it.rect == r }?.rect }
        if (cur == null) {
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                val fr = Rect()
                f.getBoundsInScreen(fr)
                if (list.any { it.rect == fr }) cur = fr
            }
        }
        if (cur == null) {
            // Primera pulsación: se marca el elemento más arriba y a la izquierda
            val first = list.minWithOrNull(compareBy<Cand>({ it.rect.top / 40 }, { it.rect.left }))
            if (first != null) showRing(first.rect)
            return
        }

        val cx = cur.exactCenterX()
        val cy = cur.exactCenterY()
        var best: Cand? = null
        var bestScore = Float.MAX_VALUE
        for (c in list) {
            if (c.rect == cur) continue
            val dx = c.rect.exactCenterX() - cx
            val dy = c.rect.exactCenterY() - cy
            val primary = when (dir) {
                0 -> -dy
                1 -> dy
                2 -> -dx
                else -> dx
            }
            if (primary <= 4f) continue
            val perp = if (dir < 2) abs(dx) else abs(dy)
            val score = primary + 2f * perp
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        val target = best
        if (target != null) {
            if (RemoteKeyboardService.isSelected(this) && target.node.isFocusable &&
                target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            ) {
                removeRing()
                return
            }
            showRing(target.rect)
            return
        }
        // No hay más elementos en esa dirección: se desplaza la lista y se vuelve a intentar una vez
        if (retry && scrollForDirection(root, cur, dir)) {
            main.postDelayed({ moveVirtual(dir, false) }, 450)
        }
    }

    private fun scrollForDirection(root: AccessibilityNodeInfo, cur: Rect, dir: Int): Boolean {
        val action = when (dir) {
            0 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            1 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            2 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isVisibleToUser && n.isScrollable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.contains(cur.centerX(), cur.centerY())) {
                    val area = r.width().toLong() * r.height()
                    if (area < bestArea) {
                        bestArea = area
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best?.performAction(action) ?: false
    }

    /** OK: pulsa el elemento marcado; si no se puede, toca en su centro. */
    private fun okPress() {
        val r = ringBounds
        if (r != null && ring?.visibility == View.VISIBLE) {
            val root = rootInActiveWindow
            if (root != null) {
                val all = ArrayList<Cand>()
                collect(root, all, 0)
                val match = all.firstOrNull { it.rect == r }
                var n: AccessibilityNodeInfo? = match?.node
                while (n != null) {
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    n = n.parent
                }
            }
            val path = Path()
            path.moveTo(r.exactCenterX(), r.exactCenterY())
            gesture(path, 60)
            return
        }
        // Sin recuadro: se usa el elemento con foco
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused != null) {
            if (focused.isEditable) {
                enter()
                return
            }
            var n: AccessibilityNodeInfo? = focused
            while (n != null) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                n = n.parent
            }
            // No es "clicable" (apps de TV con su propia tecla OK): se toca en su centro
            val bounds = Rect()
            focused.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val path = Path()
                path.moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                gesture(path, 60)
                return
            }
        }
        // Sin foco: solo si el puntero sigue activo se hace clic donde está
        if (cursorActive && System.currentTimeMillis() - lastPointerMove < 60000L) clickAtCursor(false)
    }

    private fun showRing(r: Rect) {
        ringBounds = r
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val existing = ring
        val lp = ringLp
        if (existing == null || lp == null) {
            val view = RingView(this)
            val params = WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = r.left
            params.y = r.top
            try {
                wm.addView(view, params)
                ring = view
                ringLp = params
            } catch (e: Exception) {
                return
            }
        } else {
            lp.width = r.width()
            lp.height = r.height()
            lp.x = r.left
            lp.y = r.top
            try {
                wm.updateViewLayout(existing, lp)
            } catch (e: Exception) {
            }
        }
        ring?.visibility = View.VISIBLE
        ring?.invalidate()
        main.removeCallbacks(hideRing)
        main.postDelayed(hideRing, 10000)
    }

    private fun removeRing() {
        val view = ring ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
        }
        ring = null
        ringLp = null
        ringBounds = null
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

    // ---------- Gestos y cursor (multipantalla para scrcpy --new-display) ----------

    private fun allDisplays(): List<Display> {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        val byId = LinkedHashMap<Int, Display>()
        for (d in dm.displays) {
            if (d.isValid) byId[d.displayId] = d
        }
        for (d in dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.isValid) byId[d.displayId] = d
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val map = windowsOnAllDisplays
                for (i in 0 until map.size()) {
                    val d = dm.getDisplay(map.keyAt(i))
                    if (d != null && d.isValid) byId[d.displayId] = d
                }
            } catch (_: Exception) {
            }
        }
        return byId.values.sortedBy { it.displayId }
    }

    private fun displaySize(display: Display): Point {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = createDisplayContext(display).getSystemService(WindowManager::class.java)
                if (wm != null) {
                    val b = wm.currentWindowMetrics.bounds
                    if (b.width() > 0 && b.height() > 0) return Point(b.width(), b.height())
                }
            } catch (_: Exception) {
            }
        }
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        if (p.x <= 0 || p.y <= 0) {
            val m = resources.displayMetrics
            return Point(m.widthPixels.coerceAtLeast(1), m.heightPixels.coerceAtLeast(1))
        }
        return p
    }

    private fun activeDisplay(): Display? {
        val displays = allDisplays()
        return displays.firstOrNull { it.displayId == activeDisplayId }
            ?: displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?: displays.firstOrNull()
    }

    private fun activeDisplaySize(): Point {
        val d = activeDisplay() ?: return resources.displayMetrics.let { Point(it.widthPixels, it.heightPixels) }
        if (d.displayId != activeDisplayId) activeDisplayId = d.displayId
        return displaySize(d)
    }

    private fun focusedDisplayId(): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val map = windowsOnAllDisplays
            for (i in 0 until map.size()) {
                val dId = map.keyAt(i)
                if (map.valueAt(i).any { it.isFocused }) return dId
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun displayUniqueId(display: Display): String? {
        return try {
            Display::class.java.getMethod("getUniqueId").invoke(display) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun useKernelUhid(displayId: Int = activeDisplayId): Boolean =
        displayId == Display.DEFAULT_DISPLAY && ShizukuBridge.isUhidReady()

    private fun hideOverlayKeepActive() {
        main.removeCallbacks(hideCursor)
        cursor?.visibility = View.GONE
        cursorActive = true
        if (secondaryOverlayBlocked) {
            secondaryOverlayBlocked = false
            notifyDisplayState()
        }
    }

    private fun switchActiveDisplay(target: Display, recenter: Boolean) {
        activeDisplayId = target.displayId
        if (recenter) {
            val p = displaySize(target)
            cx = p.x / 2f
            cy = p.y / 2f
        }
    }

    private fun selectDisplayByIndex(index: Int) {
        val displays = allDisplays()
        if (displays.isEmpty()) return
        val target = displays[index.coerceIn(0, displays.lastIndex)]
        manualDisplaySelected = true
        switchActiveDisplay(target, recenter = target.displayId != activeDisplayId)
        lastPointerMove = System.currentTimeMillis()
        if (useKernelUhid(target.displayId)) {
            hideOverlayKeepActive()
        } else {
            showCursor()
        }
        notifyDisplayState()
    }

    private fun tryAddCursorView(display: Display, x: Int, y: Int): Boolean {
        val attempts = mutableListOf<Triple<WindowManager, Context, Int>>()
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            attempts.add(Triple(wm, this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
        } else {
            val displayCtx = try {
                createDisplayContext(display)
            } catch (_: Exception) {
                null
            }
            if (displayCtx != null) {
                // En pantallas virtuales (scrcpy --new-display), TYPE_APPLICATION_OVERLAY es el más fiable en Android 10-14
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        val winCtx = displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        winCtx.getSystemService(WindowManager::class.java)?.let { wm ->
                            attempts.add(Triple(wm, winCtx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
                        }
                    } catch (_: Exception) {
                    }
                }
                displayCtx.getSystemService(WindowManager::class.java)?.let { wm ->
                    attempts.add(Triple(wm, displayCtx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        val winCtx = displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                        winCtx.getSystemService(WindowManager::class.java)?.let { wm ->
                            attempts.add(Triple(wm, winCtx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                        }
                    } catch (_: Exception) {
                    }
                }
                displayCtx.getSystemService(WindowManager::class.java)?.let { wm ->
                    attempts.add(Triple(wm, displayCtx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                }
            }
        }

        var lastErr = ""
        for ((wm, ctx, type) in attempts) {
            val view = CursorView(ctx)
            val params = WindowManager.LayoutParams(
                view.prefW, view.prefH,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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
                cursorWm = wm
                cursorDisplayId = display.displayId
                lastOverlayError = ""
                return true
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            }
        }
        lastOverlayError = lastErr
        return false
    }

    private fun gesture(path: Path, duration: Long, displayId: Int = activeDisplayId) {
        val b = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        if (Build.VERSION.SDK_INT >= 30) {
            b.setDisplayId(displayId)
        }
        dispatchGesture(b.build(), null, null)
    }

    private fun swipe(fx1: Float, fy1: Float, fx2: Float, fy2: Float) {
        val s = activeDisplaySize()
        val path = Path()
        path.moveTo(fx1 * s.x, fy1 * s.y)
        path.lineTo(fx2 * s.x, fy2 * s.y)
        gesture(path, 250)
    }

    private fun moveCursor(dx: Int, dy: Int) {
        val displays = allDisplays()
        var displayChanged = displays.size != lastKnownDisplayCount
        lastKnownDisplayCount = displays.size
        if (!cursorActive && displays.isNotEmpty()) {
            val activeList = displays.filter { it.state != Display.STATE_OFF }.ifEmpty { displays }
            val targetId = focusedDisplayId()?.takeIf { id -> displays.any { it.displayId == id } }
                ?: activeList.firstOrNull { it.displayId == activeDisplayId }?.displayId
                ?: activeList[0].displayId
            if (targetId != activeDisplayId) {
                val target = displays.first { it.displayId == targetId }
                switchActiveDisplay(target, recenter = true)
                displayChanged = true
            }
        } else if (displays.isNotEmpty() && displays.none { it.displayId == activeDisplayId }) {
            switchActiveDisplay(displays[0], recenter = true)
            displayChanged = true
        }

        val size = activeDisplaySize()
        val nx = cx + dx
        val ny = cy + dy
        val anyDragging = uhidDragging || shizukuTouchDragging || dragStroke != null

        if (!anyDragging && displays.size > 1) {
            val idx = displays.indexOfFirst { it.displayId == activeDisplayId }
            if (idx >= 0) {
                if (nx > size.x.toFloat() && dx > 0 && idx + 1 < displays.size) {
                    val next = displays[idx + 1]
                    val nextSize = displaySize(next)
                    val yRatio = (ny / size.y.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    switchActiveDisplay(next, recenter = false)
                    cx = (nx - size.x.toFloat()).coerceIn(0f, nextSize.x.toFloat())
                    cy = (yRatio * nextSize.y.toFloat()).coerceIn(0f, nextSize.y.toFloat())
                    lastPointerMove = System.currentTimeMillis()
                    if (useKernelUhid(next.displayId)) {
                        hideOverlayKeepActive()
                    } else {
                        showCursor()
                    }
                    notifyDisplayState()
                    return
                } else if (nx < 0f && dx < 0 && idx - 1 >= 0) {
                    val prev = displays[idx - 1]
                    val prevSize = displaySize(prev)
                    val yRatio = (ny / size.y.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    switchActiveDisplay(prev, recenter = false)
                    cx = (prevSize.x.toFloat() + nx).coerceIn(0f, prevSize.x.toFloat())
                    cy = (yRatio * prevSize.y.toFloat()).coerceIn(0f, prevSize.y.toFloat())
                    lastPointerMove = System.currentTimeMillis()
                    if (useKernelUhid(prev.displayId)) {
                        hideOverlayKeepActive()
                    } else {
                        showCursor()
                    }
                    notifyDisplayState()
                    return
                }
            }
        }

        cx = nx.coerceIn(0f, size.x.toFloat())
        cy = ny.coerceIn(0f, size.y.toFloat())
        lastPointerMove = System.currentTimeMillis()

        if (useKernelUhid(activeDisplayId)) {
            hideOverlayKeepActive()
            ShizukuBridge.moveUhid(dx, dy)
            if (displayChanged) notifyDisplayState()
            return
        }

        showCursor()
        if (displayChanged) notifyDisplayState()
        if (shizukuTouchDragging) {
            ShizukuBridge.drag(dragDisplayId, cx, cy, 1)
        } else if (dragStroke != null) {
            dragPump()
        }
    }

    // ---------- Apretar y deslizar (arrastrar) ----------
    // Se mantiene el dedo "apretado" en el cursor con un gesto continuo y se va moviendo con el panel táctil.

    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragBusy = false
    private var dragEndRequested = false
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var dragDisplayId = Display.DEFAULT_DISPLAY
    private var uhidDragging = false
    private var shizukuTouchDragging = false

    private fun dragStart() {
        if (useKernelUhid(activeDisplayId)) {
            uhidDragging = true
            dragDisplayId = activeDisplayId
            ShizukuBridge.drag(activeDisplayId, cx, cy, 0)
            return
        }
        if (activeDisplayId != Display.DEFAULT_DISPLAY && ShizukuBridge.isReady()) {
            shizukuTouchDragging = true
            dragDisplayId = activeDisplayId
            ShizukuBridge.drag(dragDisplayId, cx, cy, 0)
            showCursor()
            return
        }
        if (dragStroke != null) return
        dragLastX = cx
        dragLastY = cy
        dragDisplayId = activeDisplayId
        dragEndRequested = false
        val path = Path()
        path.moveTo(cx, cy)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L, true)
        dragStroke = stroke
        dragBusy = true
        val b = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= 30) b.setDisplayId(dragDisplayId)
        val ok = dispatchGesture(
            b.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    main.post { dragBusy = false; dragPump() }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    main.post { dragBusy = false; dragStroke = null }
                }
            },
            null
        )
        if (!ok) {
            dragBusy = false
            dragStroke = null
        }
        showCursor()
    }

    private fun dragEnd() {
        if (uhidDragging) {
            uhidDragging = false
            ShizukuBridge.drag(dragDisplayId, cx, cy, 2)
            return
        }
        if (shizukuTouchDragging) {
            shizukuTouchDragging = false
            ShizukuBridge.drag(dragDisplayId, cx, cy, 2)
            return
        }
        if (dragStroke == null) return
        dragEndRequested = true
        dragPump()
    }

    private fun dragPump() {
        val stroke = dragStroke ?: return
        if (dragBusy) return
        val end = dragEndRequested
        if (!end && cx == dragLastX && cy == dragLastY) return
        val path = Path()
        path.moveTo(dragLastX, dragLastY)
        path.lineTo(cx, cy)
        val next = stroke.continueStroke(path, 0L, if (end) 50L else 20L, !end)
        dragLastX = cx
        dragLastY = cy
        if (end) {
            dragStroke = null
            dragEndRequested = false
        } else {
            dragStroke = next
        }
        dragBusy = true
        val b = GestureDescription.Builder().addStroke(next)
        if (Build.VERSION.SDK_INT >= 30) b.setDisplayId(dragDisplayId)
        val ok = dispatchGesture(
            b.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    main.post { dragBusy = false; dragPump() }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    main.post { dragBusy = false; dragStroke = null; dragEndRequested = false }
                }
            },
            null
        )
        if (!ok) {
            dragBusy = false
            dragStroke = null
            dragEndRequested = false
        }
    }

    private fun clickAtCursor(hold: Boolean) {
        if (useKernelUhid(activeDisplayId)) {
            ShizukuBridge.click(activeDisplayId, cx, cy, hold)
            return
        }
        if (activeDisplayId != Display.DEFAULT_DISPLAY && ShizukuBridge.click(activeDisplayId, cx, cy, hold)) {
            showCursor()
            return
        }
        val path = Path()
        path.moveTo(cx, cy)
        gesture(path, if (hold) 700L else 60L)
        showCursor()
    }

    private fun scrollAtCursor(amount: Int) {
        if (amount == 0) return
        if (useKernelUhid(activeDisplayId)) {
            ShizukuBridge.scroll(activeDisplayId, cx, cy, amount.toFloat())
            return
        }
        if (activeDisplayId != Display.DEFAULT_DISPLAY && ShizukuBridge.scroll(activeDisplayId, cx, cy, amount.toFloat())) {
            showCursor()
            return
        }
        val s = activeDisplaySize()
        val dist = 150f * amount
        val y2 = (cy + dist).coerceIn(0f, s.y.toFloat())
        val path = Path()
        path.moveTo(cx, cy)
        path.lineTo(cx, y2)
        gesture(path, 250)
        showCursor()
    }

    private fun showCursor() {
        val display = activeDisplay()
        val targetDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (targetDisplayId != cursorDisplayId) {
            removeCursor()
        }
        val x = cx.toInt()
        val y = cy.toInt()
        val existing = cursor
        val lp = cursorLp
        if (existing == null || lp == null) {
            val added = if (display != null) {
                tryAddCursorView(display, x, y)
            } else {
                false
            }
            val wasBlocked = secondaryOverlayBlocked
            secondaryOverlayBlocked = targetDisplayId != Display.DEFAULT_DISPLAY && !added
            if (wasBlocked != secondaryOverlayBlocked) {
                notifyDisplayState()
            }
        } else {
            lp.x = x
            lp.y = y
            try {
                cursorWm?.updateViewLayout(existing, lp)
            } catch (_: Exception) {
                removeCursor()
            }
            if (secondaryOverlayBlocked && targetDisplayId == Display.DEFAULT_DISPLAY) {
                secondaryOverlayBlocked = false
                notifyDisplayState()
            }
        }
        cursor?.visibility = View.VISIBLE
        cursorActive = true
        main.removeCallbacks(hideCursor)
        main.postDelayed(hideCursor, 8000)
    }

    private fun hideCursorNow() {
        if (uhidDragging) {
            uhidDragging = false
            ShizukuBridge.drag(dragDisplayId, cx, cy, 2)
        }
        if (shizukuTouchDragging) {
            shizukuTouchDragging = false
            ShizukuBridge.drag(dragDisplayId, cx, cy, 2)
        }
        main.removeCallbacks(hideCursor)
        cursor?.visibility = View.GONE
        cursorActive = false
        lastPointerMove = 0L
    }

    private fun removeCursor() {
        val view = cursor
        cursor = null
        cursorLp = null
        val wm = cursorWm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        cursorWm = null
        if (view != null) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }
    }
}

/** Carga la imagen del puntero de ratón del propio sistema (si el teléfono la deja leer). */
private fun loadSystemPointer(): android.graphics.drawable.Drawable? {
    val res = android.content.res.Resources.getSystem()
    for (name in listOf("pointer_arrow", "pointer_arrow_icon")) {
        try {
            val id = res.getIdentifier(name, "drawable", "android")
            if (id != 0) {
                val d = res.getDrawable(id, null)
                if (d != null) return d
            }
        } catch (e: Exception) {
        }
    }
    return null
}

/** Puntero de ratón: usa la imagen original del sistema; si no está disponible dibuja una flecha igual. */
class CursorView(context: Context) : View(context) {
    private val system: android.graphics.drawable.Drawable? = loadSystemPointer()
    private val minW = (22 * context.resources.displayMetrics.density).toInt().coerceAtLeast(22)
    private val minH = (34 * context.resources.displayMetrics.density).toInt().coerceAtLeast(34)
    val prefW: Int = maxOf(system?.intrinsicWidth ?: 0, minW)
    val prefH: Int = maxOf(system?.intrinsicHeight ?: 0, minH)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val sys = system
        if (sys != null) {
            sys.setBounds(0, 0, width, height)
            sys.draw(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Path()
        p.moveTo(2f, 2f)
        p.lineTo(2f, h * 0.82f)
        p.lineTo(w * 0.27f, h * 0.64f)
        p.lineTo(w * 0.45f, h * 0.97f)
        p.lineTo(w * 0.62f, h * 0.89f)
        p.lineTo(w * 0.45f, h * 0.57f)
        p.lineTo(w * 0.82f, h * 0.57f)
        p.close()
        canvas.save()
        canvas.translate(2f, 3f)
        canvas.drawPath(p, shadow)
        canvas.restore()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, edge)
    }
}

/** Recuadro que marca el elemento seleccionado con la cruceta. */
class RingView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E88E5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    override fun onDraw(canvas: Canvas) {
        val rect = RectF(6f, 6f, width - 6f, height - 6f)
        canvas.drawRoundRect(rect, 16f, 16f, fill)
        canvas.drawRoundRect(rect, 16f, 16f, outline)
        canvas.drawRoundRect(rect, 16f, 16f, stroke)
    }
}
