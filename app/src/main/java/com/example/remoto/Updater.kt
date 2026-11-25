package com.example.remoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionName: String, val apkUrl: String)

/** update != null: hay versión nueva. Si es null, message explica por qué (ya estás al día, repositorio privado, sin internet...). */
data class CheckResult(val update: UpdateInfo?, val message: String)

/** Busca el último Release del repositorio de GitHub y, si es más nuevo, lo descarga e instala. */
object Updater {

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ControlRemoto")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            if (code == 404) {
                return@withContext CheckResult(null, "No hay Releases publicados o el repositorio es privado. Hazlo público.")
            }
            if (code != 200) {
                return@withContext CheckResult(null, "GitHub respondió con el código $code.")
            }

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            // Los releases se llaman "build-<número>"; el número es el versionCode de la app.
            val tag = json.getString("tag_name")
            val number = tag.substringAfterLast("-").toIntOrNull()
                ?: return@withContext CheckResult(null, "El Release \"$tag\" no tiene un número de versión válido.")
            if (number <= BuildConfig.VERSION_CODE) {
                return@withContext CheckResult(
                    null,
                    "Ya tienes la última versión (instalada 1.0.${BuildConfig.VERSION_CODE}, último Release 1.0.$number)."
                )
            }

            val assets = json.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apk = a.getString("browser_download_url")
                    break
                }
            }
            if (apk == null) CheckResult(null, "El Release 1.0.$number no tiene un archivo APK.")
            else CheckResult(UpdateInfo("1.0.$number", apk), "")
        } catch (e: Exception) {
            CheckResult(null, "No se pudo consultar GitHub. ¿Hay internet?")
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            out
        }

    /** Devuelve false si primero hay que permitir "instalar apps desconocidas" para esta app. */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
