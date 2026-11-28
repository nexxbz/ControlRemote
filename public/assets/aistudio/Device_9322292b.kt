package com.example.remoto

import android.content.Context

data class Device(val name: String, val host: String, val port: Int = 5555)

/** Guarda la lista de dispositivos en SharedPreferences (una línea por dispositivo). */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("dispositivos", Context.MODE_PRIVATE)

    fun load(): List<Device> =
        (prefs.getString("lista", "") ?: "").lines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size == 3) p[2].toIntOrNull()?.let { Device(p[0], p[1], it) } else null
        }

    fun save(devices: List<Device>) {
        prefs.edit().putString("lista", devices.joinToString("\n") { "${it.name}\t${it.host}\t${it.port}" }).apply()
    }
}
