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

    /**
     * @return true si arranco una instalacion.
     *
     * Trae freno propio a proposito. La primera version revisaba en cada
     * `onStart`, y como al cerrarse el dialogo del instalador el tablero
     * vuelve al primer plano, eso disparaba OTRA instalacion: un bucle que
     * dejaba el radio inservible, pidiendo confirmacion sin parar. Ahora un
     * intento fallido con la misma version no se repite hasta pasado
     * [ESPERA_TRAS_FALLO_MS], y tras [MAX_INTENTOS] se abandona esa version
     * hasta que salga otra.
     */
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
                olvidarIntentos()
                return false
            }

            if (!sePuedeIntentar(remote)) return false

            UpdateState.note("Actualizando de $local a $remote")
            val apk = File(context.filesDir, "update-$remote.apk")
            if (!download(url, apk)) {
                UpdateState.note("Fallo la descarga de $url")
                return false
            }
            UpdateState.note("Descargado ${apk.length()} bytes; verificando")

            // Antes de instalar NADA: comprobar que el APK es de verdad una
            // version nueva de esta app, firmada por nosotros. El anuncio
            // que nos trajo hasta aqui no esta autenticado y la descarga va
            // en claro, asi que esta es la barrera que impide que alguien de
            // la red meta codigo arbitrario en el carro.
            when (val v = ApkVerifier.verify(context, apk, remote)) {
                is ApkVerifier.Result.Rechazado -> {
                    UpdateState.note("APK RECHAZADO: ${v.motivo}")
                    apk.delete()
                    return false
                }
                ApkVerifier.Result.Ok -> UpdateState.note("APK verificado")
            }

            limpiarDescargasViejas(apk)
            anotarIntento(remote)
            UpdateState.armar(remote)
            // Armar tambien el APK confirmador, que es el que de verdad
            // sobrevive a que el tablero se actualice a si mismo.
            AutoInstaller.armarConfirmador(context, remote)
            val arrancada = AutoInstaller.install(context, apk)
            if (!arrancada) UpdateState.desarmar()
            arrancada
        } catch (e: Exception) {
            Log.w(TAG, "Fallo la revision: ${e.message}")
            UpdateState.note("Fallo la revision: ${e.message}")
            false
        }
    }

    /**
     * Freno contra el bucle de reinstalacion.
     *
     * Si la instalacion no cuaja —porque alguien cancela el dialogo, o
     * porque el confirmador no esta activo— no sirve de nada volver a
     * pedirla al instante: lo unico que se consigue es tapar la pantalla
     * una y otra vez.
     */
    private fun sePuedeIntentar(version: Int): Boolean {
        val intentos = prefs.getInt(KEY_INTENTOS + version, 0)
        if (intentos >= MAX_INTENTOS) {
            UpdateState.note("v$version abandonada tras $intentos intentos")
            return false
        }
        val ultimo = prefs.getLong(KEY_ULTIMO + version, 0)
        val espera = System.currentTimeMillis() - ultimo
        if (ultimo != 0L && espera < ESPERA_TRAS_FALLO_MS) {
            UpdateState.note("v$version en espera (${(ESPERA_TRAS_FALLO_MS - espera) / 1000}s)")
            return false
        }
        return true
    }

    private fun anotarIntento(version: Int) {
        prefs.edit()
            .putInt(KEY_INTENTOS + version, prefs.getInt(KEY_INTENTOS + version, 0) + 1)
            .putLong(KEY_ULTIMO + version, System.currentTimeMillis())
            .apply()
    }

    /** Al quedar al dia se borra el historial: la cuenta era de ese salto. */
    private fun olvidarIntentos() {
        val viejas = prefs.all.keys.filter {
            it.startsWith(KEY_INTENTOS) || it.startsWith(KEY_ULTIMO)
        }
        if (viejas.isEmpty()) return
        prefs.edit().apply { viejas.forEach { remove(it) } }.apply()
    }

    /** El almacenamiento de un head unit es pequeno: no acumular APKs. */
    private fun limpiarDescargasViejas(conservar: File) {
        runCatching {
            context.filesDir.listFiles { f ->
                f.name.startsWith("update-") && f.name.endsWith(".apk") &&
                    f.absolutePath != conservar.absolutePath
            }?.forEach { it.delete() }
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
        private const val KEY_INTENTOS = "intentos_v"
        private const val KEY_ULTIMO = "ultimo_v"

        /** Tras un intento fallido, no volver a molestar en 10 minutos. */
        const val ESPERA_TRAS_FALLO_MS = 10 * 60 * 1000L

        /** Tres intentos y esa version se abandona hasta que salga otra. */
        const val MAX_INTENTOS = 3
    }
}
