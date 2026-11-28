package com.example.remoto

import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Servicio privilegiado vía Shizuku (UID shell / root):
 * - Crea un mouse HID real del kernel en /dev/uhid para la pantalla principal (sin reiniciar ni reasociar pantallas).
 * - Concede SYSTEM_ALERT_WINDOW para dibujar el cursor sobre pantallas secundarias como scrcpy --new-display.
 * - Envía teclas reales de PC / D-Pad mediante IInputManager.
 */
@Keep
class ShizukuInputService : IShizukuInput.Stub() {

    private var uhidFd: FileDescriptor? = null
    @Volatile
    private var uhidActive = false
    private var uhidError: String? = null
    private var buttons = 0

    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    private val lock = Any()

    init {
        initInputManager()
        initUhid()
    }

    private fun initInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder ?: return
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val im = asInterface.invoke(null, binder) ?: return
            val inject = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            inputManager = im
            injectMethod = inject
        } catch (_: Exception) {
        }
    }

    private fun initUhid() {
        synchronized(lock) {
            try {
                val fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
                val ev = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                ev.putInt(UHID_CREATE2)
                putCString(ev, 4, "ControlRemoto Mouse", 128)
                putCString(ev, 132, UHID_PORT, 64)
                putCString(ev, 196, "", 64)
                ev.putShort(260, MOUSE_DESCRIPTOR.size.toShort())
                ev.putShort(262, BUS_USB.toShort())
                ev.putInt(264, 0x18D1)
                ev.putInt(268, 0x5001)
                ev.putInt(272, 1)
                ev.putInt(276, 0)
                ev.position(280)
                ev.put(MOUSE_DESCRIPTOR)

                writeFully(fd, ev.array())
                uhidFd = fd
                uhidActive = true
                uhidError = null

                Thread {
                    val buf = ByteArray(UHID_EVENT_SIZE)
                    try {
                        while (uhidActive && uhidFd === fd) {
                            val n = Os.read(fd, buf, 0, buf.size)
                            if (n <= 0) break
                        }
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true; start() }
            } catch (t: Throwable) {
                uhidActive = false
                uhidError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    private fun putCString(buf: ByteBuffer, offset: Int, text: String, maxLen: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val count = minOf(bytes.size, maxLen - 1)
        for (i in 0 until count) {
            buf.put(offset + i, bytes[i])
        }
    }

    private fun writeFully(fd: FileDescriptor, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val written = Os.write(fd, data, off, data.size - off)
            if (written <= 0) break
            off += written
        }
    }

    private fun sendUhidReport(dx: Int, dy: Int, wheel: Int): Boolean {
        val fd = uhidFd ?: return false
        return synchronized(lock) {
            try {
                val ev = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                ev.putInt(UHID_INPUT2)
                ev.putShort(4, 4.toShort())
                ev.put(6, buttons.toByte())
                ev.put(7, dx.coerceIn(-127, 127).toByte())
                ev.put(8, dy.coerceIn(-127, 127).toByte())
                ev.put(9, wheel.coerceIn(-127, 127).toByte())
                writeFully(fd, ev.array())
                true
            } catch (_: Throwable) {
                uhidActive = false
                false
            }
        }
    }

    private fun injectEvent(event: InputEvent): Boolean {
        val im = inputManager ?: return false
        val method = injectMethod ?: return false
        return try {
            method.invoke(im, event, 0) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }


    // ---- Ratón por inyección de MotionEvent para pantallas secundarias (scrcpy --new-display) ----
    // UHID solo mueve el puntero de la pantalla principal; aquí se inyecta con displayId explícito.
    private var injDownTime = 0L
    private var injButtons = 0

    private fun mouseEvent(
        action: Int, x: Float, y: Float, displayId: Int, buttonState: Int, vScroll: Float = 0f
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (buttonState != 0) 1f else 0f
            size = 1f
            if (vScroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        }
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(
            if (injDownTime == 0L) now else injDownTime, now, action, 1,
            arrayOf(props), arrayOf(coords), 0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(e, displayId)
        } catch (_: Throwable) {
        }
        return e
    }

    private fun injMouse(
        action: Int, x: Float, y: Float, displayId: Int, buttons: Int, vScroll: Float = 0f
    ): Boolean {
        val e = mouseEvent(action, x, y, displayId, buttons, vScroll)
        return try { injectEvent(e) } finally { e.recycle() }
    }

    private fun injClick(displayId: Int, x: Float, y: Float, right: Boolean): Boolean {
        val b = if (right) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        injDownTime = SystemClock.uptimeMillis()
        var ok = injMouse(MotionEvent.ACTION_HOVER_MOVE, x, y, displayId, 0)
        ok = injMouse(MotionEvent.ACTION_DOWN, x, y, displayId, b) && ok
        ok = injMouse(MotionEvent.ACTION_BUTTON_PRESS, x, y, displayId, b) && ok
        SystemClock.sleep(35)
        ok = injMouse(MotionEvent.ACTION_BUTTON_RELEASE, x, y, displayId, 0) && ok
        ok = injMouse(MotionEvent.ACTION_UP, x, y, displayId, 0) && ok
        injDownTime = 0L
        return ok
    }

    private fun injScroll(displayId: Int, x: Float, y: Float, v: Float): Boolean {
        // Android: vScroll positivo = hacia arriba; el panel manda positivo = abajo.
        val ok1 = injMouse(MotionEvent.ACTION_HOVER_MOVE, x, y, displayId, 0)
        val ok2 = injMouse(MotionEvent.ACTION_SCROLL, x, y, displayId, 0, -v)
        return ok1 && ok2
    }

    /** action: 0 = apretar, 1 = mover apretado, 2 = soltar, 3 = solo mover (hover). */
    private fun injDrag(displayId: Int, x: Float, y: Float, action: Int): Boolean = when (action) {
        0 -> {
            injDownTime = SystemClock.uptimeMillis()
            injButtons = MotionEvent.BUTTON_PRIMARY
            injMouse(MotionEvent.ACTION_DOWN, x, y, displayId, injButtons) &&
                injMouse(MotionEvent.ACTION_BUTTON_PRESS, x, y, displayId, injButtons)
        }
        1 -> injMouse(MotionEvent.ACTION_MOVE, x, y, displayId, injButtons)
        2 -> {
            val ok = injMouse(MotionEvent.ACTION_BUTTON_RELEASE, x, y, displayId, 0) &&
                injMouse(MotionEvent.ACTION_UP, x, y, displayId, 0)
            injButtons = 0
            injDownTime = 0L
            ok
        }
        else -> injMouse(MotionEvent.ACTION_HOVER_MOVE, x, y, displayId, 0)
    }

    override fun isUhidActive(): Boolean = uhidActive

    override fun getStatusDetail(): String = when {
        uhidActive -> "UHID_OK"
        inputManager != null -> "KEYS_OK:${uhidError ?: ""}"
        else -> "ERR:${uhidError ?: "sin acceso"}"
    }

    override fun grantOverlayPermission(packageName: String): Boolean {
        val cmds = listOf(
            arrayOf("cmd", "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
            arrayOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")
        )
        for (cmd in cmds) {
            try {
                if (Runtime.getRuntime().exec(cmd).waitFor() == 0) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    override fun moveMouse(dx: Int, dy: Int): Boolean {
        if (!uhidActive) return false
        var x = dx
        var y = dy
        var ok = true
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            ok = sendUhidReport(sx, sy, 0) && ok
            x -= sx
            y -= sy
        }
        return ok
    }

    override fun clickMouse(displayId: Int, x: Float, y: Float, right: Boolean): Boolean {
        if (displayId != 0) return injClick(displayId, x, y, right)
        if (!uhidActive) return false
        val bit = if (right) 2 else 1
        buttons = buttons or bit
        val downOk = sendUhidReport(0, 0, 0)
        SystemClock.sleep(35)
        buttons = buttons and bit.inv()
        val upOk = sendUhidReport(0, 0, 0)
        return downOk && upOk
    }

    override fun scrollMouse(displayId: Int, x: Float, y: Float, vScroll: Float): Boolean {
        if (displayId != 0) return injScroll(displayId, x, y, vScroll)
        if (!uhidActive) return false
        val w = vScroll.toInt().let { if (it == 0) (if (vScroll > 0f) 1 else -1) else it }
        return sendUhidReport(0, 0, w)
    }

    override fun dragMouse(displayId: Int, x: Float, y: Float, action: Int): Boolean {
        if (displayId != 0) return injDrag(displayId, x, y, action)
        if (!uhidActive) return false
        buttons = if (action == 2) (buttons and 1.inv()) else (buttons or 1)
        return sendUhidReport(0, 0, 0)
    }

    override fun sendKey(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        val up = KeyEvent(
            now, now + 20, KeyEvent.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        val ok1 = injectEvent(down)
        val ok2 = injectEvent(up)
        return ok1 && ok2
    }

    override fun destroy() {
        uhidActive = false
        val fd = uhidFd
        uhidFd = null
        if (fd != null) {
            try {
                val ev = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                ev.putInt(UHID_DESTROY)
                writeFully(fd, ev.array())
            } catch (_: Throwable) {
            }
            try {
                Os.close(fd)
            } catch (_: Throwable) {
            }
        }
        System.exit(0)
    }

    companion object {
        private const val UHID_PORT = "controlremoto/0"
        private const val UHID_EVENT_SIZE = 4376
        private const val UHID_DESTROY = 1
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val BUS_USB = 3

        private val MOUSE_DESCRIPTOR = byteArrayOf(
            0x05, 0x01,
            0x09, 0x02,
            0xA1.toByte(), 0x01,
            0x09, 0x01,
            0xA1.toByte(), 0x00,
            0x05, 0x09,
            0x19, 0x01,
            0x29, 0x03,
            0x15, 0x00,
            0x25, 0x01,
            0x95.toByte(), 0x03,
            0x75, 0x01,
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x01,
            0x75, 0x05,
            0x81.toByte(), 0x03,
            0x05, 0x01,
            0x09, 0x30,
            0x09, 0x31,
            0x09, 0x38,
            0x15, 0x81.toByte(),
            0x25, 0x7F,
            0x75, 0x08,
            0x95.toByte(), 0x03,
            0x81.toByte(), 0x06,
            0xC0.toByte(),
            0xC0.toByte()
        )
    }
}
