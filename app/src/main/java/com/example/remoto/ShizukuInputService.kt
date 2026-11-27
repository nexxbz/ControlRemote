package com.example.remoto

import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Display
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
 * - Crea un mouse HID real del kernel en /dev/uhid para la pantalla principal (y secundarias si el sistema lo permite).
 * - Concede SYSTEM_ALERT_WINDOW automáticamente para poder dibujar el puntero sobre scrcpy --new-display.
 * - Inyecta toques, deslizamientos y teclas reales directamente con IInputManager en cualquier displayId.
 */
@Keep
class ShizukuInputService : IShizukuInput.Stub() {

    private var uhidFd: FileDescriptor? = null
    @Volatile
    private var uhidActive = false
    @Volatile
    private var uhidBoundDisplayId = Display.DEFAULT_DISPLAY
    private var uhidError: String? = null
    private var buttons = 0
    private var dragDownTime = 0L

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

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
            setDisplayIdMethod = try {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
        }
    }

    private var lastBindInfo = ""

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

    private fun destroyUhidLocked() {
        val fd = uhidFd
        uhidFd = null
        uhidActive = false
        buttons = 0
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
    }

    private fun recreateUhid() {
        synchronized(lock) {
            destroyUhidLocked()
        }
        SystemClock.sleep(60)
        initUhid()
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

    private fun obtainTouchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        displayId: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            setDisplayIdMethod?.invoke(ev, displayId)
        } catch (_: Exception) {
        }
        return ev
    }

    override fun isUhidActive(): Boolean = uhidActive

    override fun getStatusDetail(): String = when {
        uhidActive -> "UHID_OK${if (lastBindInfo.isNotEmpty()) "($lastBindInfo)" else ""}"
        inputManager != null -> "INJECT_OK:${uhidError ?: ""}"
        else -> "ERR:${uhidError ?: "sin acceso"}"
    }

    override fun getUhidBoundDisplayId(): Int = uhidBoundDisplayId

    private fun lookupDisplayUniqueId(displayId: Int): String? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "display") as? IBinder ?: return null
            val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val dm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return null
            val info = dm.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dm, displayId) ?: return null
            info.javaClass.getField("uniqueId").get(info) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun removeAssociation(im: Any) {
        for (name in listOf("removeUniqueIdAssociationByPort", "removeUniqueIdAssociation")) {
            try {
                im.javaClass.getMethod(name, String::class.java).invoke(im, UHID_PORT)
                return
            } catch (_: Throwable) {
            }
        }
    }

    override fun bindUhidDisplay(displayId: Int, displayUniqueId: String?): Boolean {
        val im = inputManager
        val prevBound = uhidBoundDisplayId
        if (displayId == Display.DEFAULT_DISPLAY || im == null) {
            if (im != null) removeAssociation(im)
            uhidBoundDisplayId = Display.DEFAULT_DISPLAY
            lastBindInfo = "D0"
            if (prevBound != Display.DEFAULT_DISPLAY && uhidActive) recreateUhid()
            return displayId == Display.DEFAULT_DISPLAY
        }
        val uid = displayUniqueId?.takeIf { it.isNotBlank() } ?: lookupDisplayUniqueId(displayId)
        if (uid.isNullOrBlank()) {
            uhidBoundDisplayId = Display.DEFAULT_DISPLAY
            lastBindInfo = "sin_uniqueId"
            return false
        }
        var lastErr: String? = null
        removeAssociation(im)
        for (name in listOf("addUniqueIdAssociationByPort", "addUniqueIdAssociation")) {
            try {
                im.javaClass.getMethod(name, String::class.java, String::class.java).invoke(im, UHID_PORT, uid)
                uhidBoundDisplayId = displayId
                lastBindInfo = "D$displayId"
                if (prevBound != displayId && uhidActive) recreateUhid()
                return true
            } catch (t: Throwable) {
                val root = t.cause ?: t
                lastErr = root.javaClass.simpleName + ":" + (root.message ?: "")
            }
        }
        uhidBoundDisplayId = Display.DEFAULT_DISPLAY
        lastBindInfo = lastErr ?: "no_assoc"
        return false
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
        if (uhidActive && displayId == uhidBoundDisplayId) {
            val bit = if (right) 2 else 1
            buttons = buttons or bit
            val downOk = sendUhidReport(0, 0, 0)
            SystemClock.sleep(35)
            buttons = buttons and bit.inv()
            val upOk = sendUhidReport(0, 0, 0)
            return downOk && upOk
        }
        val now = SystemClock.uptimeMillis()
        val holdMs = if (right) 650L else 45L
        val d = obtainTouchEvent(now, now, MotionEvent.ACTION_DOWN, displayId, x, y)
        val ok = injectEvent(d)
        d.recycle()
        SystemClock.sleep(holdMs)
        val u = obtainTouchEvent(now, now + holdMs, MotionEvent.ACTION_UP, displayId, x, y)
        injectEvent(u)
        u.recycle()
        return ok
    }

    override fun scrollMouse(displayId: Int, x: Float, y: Float, vScroll: Float): Boolean {
        if (uhidActive && displayId == uhidBoundDisplayId) {
            val w = vScroll.toInt().let { if (it == 0) (if (vScroll > 0f) 1 else -1) else it }
            return sendUhidReport(0, 0, w)
        }
        val now = SystemClock.uptimeMillis()
        val dist = 150f * vScroll
        val steps = 8
        val d = obtainTouchEvent(now, now, MotionEvent.ACTION_DOWN, displayId, x, y)
        val ok = injectEvent(d)
        d.recycle()
        for (i in 1..steps) {
            val t = now + i * 22L
            val yy = (y + dist * (i.toFloat() / steps)).coerceAtLeast(0f)
            val m = obtainTouchEvent(now, t, MotionEvent.ACTION_MOVE, displayId, x, yy)
            injectEvent(m)
            m.recycle()
            SystemClock.sleep(20)
        }
        val endT = now + (steps + 1) * 22L
        val u = obtainTouchEvent(now, endT, MotionEvent.ACTION_UP, displayId, x, (y + dist).coerceAtLeast(0f))
        injectEvent(u)
        u.recycle()
        return ok
    }

    override fun dragMouse(displayId: Int, x: Float, y: Float, action: Int): Boolean {
        if (uhidActive && displayId == uhidBoundDisplayId) {
            buttons = if (action == 2) (buttons and 1.inv()) else (buttons or 1)
            return sendUhidReport(0, 0, 0)
        }
        val now = SystemClock.uptimeMillis()
        if (action == 0 || dragDownTime == 0L) {
            dragDownTime = now
        }
        val motAction = when (action) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_UP
        }
        val ev = obtainTouchEvent(dragDownTime, now, motAction, displayId, x, y)
        if (action == 2) {
            dragDownTime = 0L
        }
        val ok = injectEvent(ev)
        ev.recycle()
        return ok
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
        try {
            inputManager?.let { im ->
                im.javaClass.getMethod("removeUniqueIdAssociation", String::class.java).invoke(im, UHID_PORT)
            }
        } catch (_: Throwable) {
        }
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
