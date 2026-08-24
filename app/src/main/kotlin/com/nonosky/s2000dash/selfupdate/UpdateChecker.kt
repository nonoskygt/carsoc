package com.nonosky.s2000dash.selfupdate

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca, descarga e instala actualizaciones sin que nadie toque el radio.
 *
 * El servidor publica un `version.json`:
 *
 *     { "versionCode": 5, "versionName": "1.4", "url": "http://.../dash-v14.apk" }
 *
 * Si el `versionCode` publicado es mayor que el instalado, se baja el APK a
 * la carpeta privada de la app y se instala con [AutoInstaller].
 */
class UpdateChecker(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Ultima base que funciono, para no volver a difundir cada vez. */
    var baseUrl: String?
        get() = prefs.getString(KEY_BASE, null)
        set(v) { prefs.edit().putString(KEY_BASE, v).apply() }

    /**
     * Resuelve donde vive el servidor: primero lo ya conocido, y si no
     * contesta, se pregunta a la red. El orden importa — la difusion cuesta
     * un segundo y medio y casi nunca hace falta.
     */
    private fun resolveBase(): String? {
        baseUrl?.let { if (reachable(it)) return it }
        val descubierto = ServerDiscovery.discover(context)
        if (descubierto != null && reachable(descubierto)) {
            baseUrl = descubierto
            UpdateState.note("Servidor descubierto: $descubierto")
            return descubierto
        }
        UpdateState.note("No se encontro servidor de actualizaciones")
        return null
    }

    private fun reachable(base: String): Boolean =
        fetchText("$base/version.json") != null

    /** @return true si arranco una instalacion. */
    fun checkAndInstall(): Boolean {
        return try {
            val base = resolveBase() ?: return false
            val manifest = fetchText("$base/version.json") ?: return false
            val json = JSONObject(manifest)
            val remote = json.getInt("versionCode")
            // El nombre del archivo sale del manifiesto, pero el host lo
            // pone quien contesto: asi el manifiesto sigue sirviendo aunque
            // la laptop cambie de IP entre una publicacion y la siguiente.
            val archivo = json.optString("file").ifBlank {
                json.optString("url").substringAfterLast('/')
            }
            if (archivo.isBlank()) {
                UpdateState.note("El manifiesto no dice que archivo bajar")
                return false
            }
            val url = "$base/$archivo"
            UpdateState.recordCheck(remote)

            val local = installedVersionCode()
            if (remote <= local) {
                UpdateState.note("Al dia (local=$local remoto=$remote)")
                return false
            }

            UpdateState.note("Actualizando de $local a $remote")
            val apk = File(context.filesDir, "update-$remote.apk")
            if (!download(url, apk)) {
                UpdateState.note("Fallo la descarga de $url")
                return false
            }
            UpdateState.note("Descargado ${apk.length()} bytes; instalando")
            AutoInstaller.install(context, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo la revision: ${e.message}")
            UpdateState.note("Fallo la revision: ${e.message}")
            false
        }
    }

    fun installedVersionCode(): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    } catch (e: Exception) {
        -1
    }

    private fun fetchText(url: String): String? = openConn(url)?.use { conn ->
        if (conn.responseCode != 200) return null
        conn.inputStream.bufferedReader().readText()
    }

    private fun download(url: String, into: File): Boolean {
        val tmp = File(into.absolutePath + ".part")
        return try {
            openConn(url)?.use { conn ->
                if (conn.responseCode != 200) return false
                tmp.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            } ?: return false
            if (tmp.length() == 0L) return false
            // Renombrar al final: asi nunca se intenta instalar un APK a medio
            // bajar si se corta el wifi del carro a la mitad.
            if (into.exists()) into.delete()
            tmp.renameTo(into)
        } catch (e: Exception) {
            Log.w(TAG, "Descarga fallida: ${e.message}")
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun openConn(url: String): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            useCaches = false
            requestMethod = "GET"
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo abrir $url: ${e.message}")
        null
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS = "s2000dash-update"
        private const val KEY_BASE = "base_url"
    }
}
