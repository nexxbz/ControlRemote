package com.example.remoto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Conecta la app con Shizuku (cuando el receptor tiene Shizuku iniciado por ADB) para crear un
 * mouse HID real (/dev/uhid) en la pantalla principal, conceder superposición y enviar teclas reales.
 */
object ShizukuBridge {
    private const val REQ_CODE = 4701

    var available by mutableStateOf(false)
        private set
    var permissionGranted by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var uhidActive by mutableStateOf(false)
        private set
    var statusDetail by mutableStateOf("")
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var initialized = false
    private var appContext: Context? = null
    private var service: IShizukuInput? = null
    private var binding = false
    private var args: Shizuku.UserServiceArgs? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refresh()
        appContext?.let { tryBindIfPermitted(it) }
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            available = false
            connected = false
            uhidActive = false
            statusDetail = ""
        }
        service = null
        binding = false
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE) {
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            main.post { permissionGranted = ok }
            if (ok) appContext?.let { bind(it) }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder == null || !binder.pingBinder()) return
            val s = IShizukuInput.Stub.asInterface(binder)
            service = s
            val pkg = appContext?.packageName
            io.execute {
                if (pkg != null) {
                    try { s.grantOverlayPermission(pkg) } catch (_: Throwable) {}
                }
                val uhid = try { s.isUhidActive } catch (_: Throwable) { false }
                val detail = try { s.statusDetail ?: "" } catch (_: Throwable) { "" }
                main.post {
                    connected = true
                    uhidActive = uhid
                    statusDetail = detail
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            main.post {
                connected = false
                uhidActive = false
                statusDetail = ""
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!initialized) {
            initialized = true
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceived)
                Shizuku.addBinderDeadListener(binderDead)
                Shizuku.addRequestPermissionResultListener(permissionListener)
            } catch (_: Throwable) {
            }
        }
        refresh()
    }

    fun refresh() {
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        val granted = if (alive) {
            try {
                !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        main.post {
            available = alive
            permissionGranted = granted
            if (!alive) {
                connected = false
                uhidActive = false
                statusDetail = ""
            }
        }
    }

    fun tryBindIfPermitted(context: Context) {
        init(context)
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) return
        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (granted) bind(context)
    }

    fun requestOrConnect(context: Context): Boolean {
        init(context)
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) {
            val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
            return false
        }
        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!granted) {
            try {
                Shizuku.requestPermission(REQ_CODE)
            } catch (_: Throwable) {
            }
            return true
        }
        bind(context)
        return true
    }

    private fun bind(context: Context) {
        if (service != null || binding) return
        binding = true
        val ctx = context.applicationContext
        val a = args ?: Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuInputService::class.java))
            .daemon(false)
            .processNameSuffix("shizuku_input")
            .version(5)
            .debuggable(BuildConfig.DEBUG)
            .also { args = it }
        try {
            Shizuku.bindUserService(a, conn)
        } catch (_: Throwable) {
            binding = false
        }
    }

    fun unbind() {
        val a = args ?: return
        try {
            Shizuku.unbindUserService(a, conn, true)
        } catch (_: Throwable) {
        }
        service = null
        binding = false
        main.post {
            connected = false
            uhidActive = false
            statusDetail = ""
        }
    }

    fun isReady(): Boolean = service != null && connected
    fun isUhidReady(): Boolean = service != null && connected && uhidActive

    fun moveUhid(dx: Int, dy: Int) {
        val s = service ?: return
        io.execute {
            try {
                s.moveMouse(dx, dy)
            } catch (_: Throwable) {
            }
        }
    }

    fun click(displayId: Int, x: Float, y: Float, right: Boolean): Boolean {
        val s = service ?: return false
        io.execute {
            try {
                s.clickMouse(displayId, x, y, right)
            } catch (_: Throwable) {
            }
        }
        return true
    }

    fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float): Boolean {
        val s = service ?: return false
        io.execute {
            try {
                s.scrollMouse(displayId, x, y, vScroll)
            } catch (_: Throwable) {
            }
        }
        return true
    }

    fun drag(displayId: Int, x: Float, y: Float, action: Int): Boolean {
        val s = service ?: return false
        io.execute {
            try {
                s.dragMouse(displayId, x, y, action)
            } catch (_: Throwable) {
            }
        }
        return true
    }

    fun sendKey(keyCode: Int): Boolean {
        val s = service ?: return false
        io.execute {
            try {
                s.sendKey(keyCode)
            } catch (_: Throwable) {
            }
        }
        return true
    }
}
