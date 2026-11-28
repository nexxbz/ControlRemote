package com.example.remoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.Keep
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val cursorThread = HandlerThread("shizuku_cursor").apply { start() }
    private val cursorHandler = Handler(cursorThread.looper)
    private var shellCtx: Context? = null
    private var shellOverlayGranted = false
    private var shellCursorView: View? = null
    private var shellCursorLp: WindowManager.LayoutParams? = null
    private var shellCursorWm: WindowManager? = null
    private var shellCursorDisplayId: Int = -1
    private var shellOverlayErr: String = ""
    private val hideShellCursor = Runnable {
        shellCursorView?.visibility = View.GONE
    }

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
        if (!uhidActive) return false
        val w = vScroll.toInt().let { if (it == 0) (if (vScroll > 0f) 1 else -1) else it }
        return sendUhidReport(0, 0, w)
    }

    override fun dragMouse(displayId: Int, x: Float, y: Float, action: Int): Boolean {
        if (!uhidActive) return false
        buttons = if (action == 2) (buttons and 1.inv()) else (buttons or 1)
        return sendUhidReport(0, 0, 0)
    }

    private fun resolveShellContext(): Context? {
        shellCtx?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = try {
                atClass.getMethod("currentActivityThread").invoke(null)
            } catch (_: Throwable) {
                null
            } ?: atClass.getMethod("systemMain").invoke(null)
            val sysCtx = atClass.getMethod("getSystemContext").invoke(at) as? Context ?: return null
            val ctx = sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
            shellCtx = ctx
            ctx
        } catch (t: Throwable) {
            shellOverlayErr = "ctx:${t.javaClass.simpleName}"
            null
        }
    }

    private fun removeShellCursorOnThread() {
        cursorHandler.removeCallbacks(hideShellCursor)
        val v = shellCursorView
        val wm = shellCursorWm
        shellCursorView = null
        shellCursorLp = null
        shellCursorWm = null
        shellCursorDisplayId = -1
        if (v != null && wm != null) {
            try {
                wm.removeView(v)
            } catch (_: Throwable) {
            }
        }
    }

    private fun showOverlayCursorOnThread(displayId: Int, x: Int, y: Int): Boolean {
        if (shellCursorView != null && shellCursorDisplayId == displayId) {
            val v = shellCursorView
            val lp = shellCursorLp
            val wm = shellCursorWm
            if (v != null && lp != null && wm != null) {
                lp.x = x
                lp.y = y
                v.visibility = View.VISIBLE
                try {
                    wm.updateViewLayout(v, lp)
                    v.invalidate()
                    cursorHandler.removeCallbacks(hideShellCursor)
                    cursorHandler.postDelayed(hideShellCursor, 15000L)
                    return true
                } catch (_: Throwable) {
                    removeShellCursorOnThread()
                }
            }
        } else {
            removeShellCursorOnThread()
        }

        if (!shellOverlayGranted) {
            shellOverlayGranted = grantOverlayPermission("com.android.shell")
        }
        val ctx = resolveShellContext() ?: return false
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return false
        val display = dm.getDisplay(displayId) ?: return false
        val sz = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(sz)
        val minDim = minOf(sz.x, sz.y).coerceAtLeast(0)

        val candidates = mutableListOf<Pair<WindowManager, Context>>()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val wCtx = ctx.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                wCtx.getSystemService(WindowManager::class.java)?.let { candidates.add(it to wCtx) }
            } catch (_: Throwable) {
            }
        }
        try {
            val dCtx = ctx.createDisplayContext(display)
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val wCtx = dCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    wCtx.getSystemService(WindowManager::class.java)?.let { candidates.add(it to wCtx) }
                } catch (_: Throwable) {
                }
            }
            dCtx.getSystemService(WindowManager::class.java)?.let { candidates.add(it to dCtx) }
        } catch (_: Throwable) {
        }

        for ((wm, vCtx) in candidates) {
            val view = ShellArrowView(vCtx, minDim)
            val lp = WindowManager.LayoutParams(
                view.prefW, view.prefH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            try {
                wm.addView(view, lp)
                shellCursorView = view
                shellCursorLp = lp
                shellCursorWm = wm
                shellCursorDisplayId = displayId
                shellOverlayErr = ""
                cursorHandler.removeCallbacks(hideShellCursor)
                cursorHandler.postDelayed(hideShellCursor, 15000L)
                return true
            } catch (t: Throwable) {
                shellOverlayErr = "${t.javaClass.simpleName}:${t.message ?: ""}"
            }
        }
        return false
    }

    override fun showOverlayCursor(displayId: Int, x: Int, y: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val posted = cursorHandler.post {
            try {
                ok = showOverlayCursorOnThread(displayId, x, y)
            } catch (t: Throwable) {
                shellOverlayErr = t.javaClass.simpleName
                ok = false
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return try {
            latch.await(1500, TimeUnit.MILLISECONDS) && ok
        } catch (_: InterruptedException) {
            false
        }
    }

    override fun hideOverlayCursor() {
        cursorHandler.post {
            cursorHandler.removeCallbacks(hideShellCursor)
            shellCursorView?.visibility = View.GONE
        }
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
        cursorHandler.post {
            removeShellCursorOnThread()
            cursorThread.quitSafely()
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

    private class ShellArrowView(context: Context, minDim: Int) : View(context) {
        val prefW: Int = maxOf(
            (24 * context.resources.displayMetrics.density).toInt(),
            (minDim * 0.025f).toInt(),
            28
        )
        val prefH: Int = (prefW * 1.5f).toInt()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = (2f * context.resources.displayMetrics.density).coerceAtLeast(3f)
            strokeJoin = Paint.Join.ROUND
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x77000000
            style = Paint.Style.FILL
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(resolveSize(prefW, widthMeasureSpec), resolveSize(prefH, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
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
