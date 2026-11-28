package com.example.remoto

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
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
    private var shellPkgName: String = "com.android.shell"
    private var shellOverlayGranted = false
    private var shellCursorView: ShellArrowView? = null
    private var shellCursorLp: WindowManager.LayoutParams? = null
    private var shellCursorWm: WindowManager? = null
    private var shellCursorPres: Presentation? = null
    private var shellCursorDisplayId: Int = -1
    @Volatile
    private var shellOverlayErr: String = ""
    @Volatile
    private var shellOverlayMethod: String = ""
    private val hideShellCursor = Runnable {
        shellCursorView?.visibility = View.GONE
        shellCursorPres?.window?.decorView?.visibility = View.GONE
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
            arrayOf("/system/bin/cmd", "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
            arrayOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
            arrayOf("/system/bin/appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")
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

    private fun patchOpPackageName(ctx: Context, pkg: String) {
        try {
            val f = ctx.javaClass.getDeclaredField("mOpPackageName")
            f.isAccessible = true
            f.set(ctx, pkg)
        } catch (_: Throwable) {
        }
    }

    private fun resolveShellContext(): Context? {
        shellCtx?.let { return it }
        return try {
            val uid = try { Os.getuid() } catch (_: Throwable) { 2000 }
            val targetPkg = if (uid == 0) "android" else "com.android.shell"
            shellPkgName = targetPkg
            val atClass = Class.forName("android.app.ActivityThread")
            val at = try {
                atClass.getMethod("currentActivityThread").invoke(null)
            } catch (_: Throwable) {
                null
            } ?: atClass.getMethod("systemMain").invoke(null)
            val sysCtx = atClass.getMethod("getSystemContext").invoke(at) as? Context ?: return null
            val ctx = if (targetPkg == "android") {
                sysCtx
            } else {
                try {
                    val pkgCtx = sysCtx.createPackageContext(targetPkg, Context.CONTEXT_IGNORE_SECURITY)
                    patchOpPackageName(pkgCtx, targetPkg)
                    pkgCtx
                } catch (_: Throwable) {
                    sysCtx
                }
            }
            shellCtx = ctx
            ctx
        } catch (t: Throwable) {
            shellOverlayErr = "ctx:${t.javaClass.simpleName}:${t.message ?: ""}"
            null
        }
    }

    private fun removeShellCursorOnThread() {
        cursorHandler.removeCallbacks(hideShellCursor)
        val pres = shellCursorPres
        shellCursorPres = null
        val v = shellCursorView
        val wm = shellCursorWm
        shellCursorView = null
        shellCursorLp = null
        shellCursorWm = null
        shellCursorDisplayId = -1
        if (pres != null) {
            try {
                pres.dismiss()
            } catch (_: Throwable) {
            }
        } else if (v != null && wm != null) {
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
            val pres = shellCursorPres
            if (v != null && lp != null && (wm != null || pres != null)) {
                lp.x = x
                lp.y = y
                v.visibility = View.VISIBLE
                pres?.window?.decorView?.visibility = View.VISIBLE
                try {
                    if (pres != null) {
                        pres.window?.attributes = lp
                    } else {
                        wm?.updateViewLayout(v, lp)
                    }
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

        val ctx = resolveShellContext() ?: return false
        if (shellPkgName == "com.android.shell" && !shellOverlayGranted) {
            shellOverlayGranted = grantOverlayPermission("com.android.shell")
        }
        val dm = ctx.getSystemService(DisplayManager::class.java)
        if (dm == null) {
            shellOverlayErr = "no DisplayManager"
            return false
        }
        val display = dm.getDisplay(displayId)
        if (display == null) {
            shellOverlayErr = "no display $displayId"
            return false
        }
        val sz = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(sz)
        val minDim = minOf(sz.x, sz.y).coerceAtLeast(0)

        val candidates = mutableListOf<Triple<WindowManager, Context, String>>()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val wCtx = ctx.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                patchOpPackageName(wCtx, shellPkgName)
                wCtx.getSystemService(WindowManager::class.java)?.let { candidates.add(Triple(it, wCtx, "ShWin")) }
            } catch (_: Throwable) {
            }
        }
        try {
            val dCtx = ctx.createDisplayContext(display)
            patchOpPackageName(dCtx, shellPkgName)
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val wCtx = dCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    patchOpPackageName(wCtx, shellPkgName)
                    wCtx.getSystemService(WindowManager::class.java)?.let { candidates.add(Triple(it, wCtx, "ShDispWin")) }
                } catch (_: Throwable) {
                }
            }
            dCtx.getSystemService(WindowManager::class.java)?.let { candidates.add(Triple(it, dCtx, "ShDisp")) }
        } catch (_: Throwable) {
        }

        var lastErr = ""
        for ((wm, vCtx, tag) in candidates) {
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
                packageName = shellPkgName
            }
            try {
                wm.addView(view, lp)
                shellCursorView = view
                shellCursorLp = lp
                shellCursorWm = wm
                shellCursorPres = null
                shellCursorDisplayId = displayId
                shellOverlayMethod = tag
                shellOverlayErr = ""
                cursorHandler.removeCallbacks(hideShellCursor)
                cursorHandler.postDelayed(hideShellCursor, 15000L)
                return true
            } catch (t: Throwable) {
                lastErr = "${t.javaClass.simpleName}:${t.message ?: ""}"
            }
        }

        try {
            val pres = object : Presentation(ctx, display, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {}
            val view = ShellArrowView(pres.context, minDim)
            val win = pres.window
            if (win != null) {
                win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                win.setGravity(Gravity.TOP or Gravity.START)
            }
            pres.setContentView(view)
            pres.show()
            if (win != null) {
                win.setLayout(view.prefW, view.prefH)
                win.attributes = win.attributes.apply {
                    this.x = x
                    this.y = y
                    packageName = shellPkgName
                }
            }
            shellCursorPres = pres
            shellCursorLp = win?.attributes
            shellCursorView = view
            shellCursorWm = null
            shellCursorDisplayId = displayId
            shellOverlayMethod = "ShPres"
            shellOverlayErr = ""
            cursorHandler.removeCallbacks(hideShellCursor)
            cursorHandler.postDelayed(hideShellCursor, 15000L)
            return true
        } catch (t: Throwable) {
            lastErr = "${t.javaClass.simpleName}:${t.message ?: ""}"
        }

        shellOverlayErr = lastErr.ifEmpty { "sin candidatos" }
        return false
    }

    override fun showOverlayCursor(displayId: Int, x: Int, y: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val posted = cursorHandler.post {
            try {
                ok = showOverlayCursorOnThread(displayId, x, y)
            } catch (t: Throwable) {
                shellOverlayErr = "${t.javaClass.simpleName}:${t.message ?: ""}"
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
            shellCursorPres?.window?.decorView?.visibility = View.GONE
        }
    }

    override fun getOverlayStatus(): String {
        val v = shellCursorView
        return if (v != null) {
            "OK:$shellOverlayMethod:d=${v.attachedDisplayId ?: -1}:dr=${v.drawCount}"
        } else {
            "ERR:$shellOverlayErr"
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
        @Volatile
        var drawCount = 0
            private set
        @Volatile
        var attachedDisplayId: Int? = null
            private set

        val prefW: Int = maxOf(
            (28 * context.resources.displayMetrics.density).toInt(),
            (minDim * 0.032f).toInt(),
            36
        )
        val prefH: Int = (prefW * 1.5f).toInt()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = (2.5f * context.resources.displayMetrics.density).coerceAtLeast(3f)
            strokeJoin = Paint.Join.ROUND
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x77000000
            style = Paint.Style.FILL
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attachedDisplayId = display?.displayId
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(resolveSize(prefW, widthMeasureSpec), resolveSize(prefH, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            drawCount++
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
