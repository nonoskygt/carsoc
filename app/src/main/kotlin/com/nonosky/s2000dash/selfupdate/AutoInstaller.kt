package com.nonosky.s2000dash.selfupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Instala un APK usando la sesion de [PackageInstaller].
 *
 * Se usa la API de sesion y no un intent con `file://`/FileProvider porque
 * es la unica que devuelve el resultado de la instalacion por callback, y
 * porque el sistema nos deja escribir el APK directo sin exponerlo a nadie.
 *
 * Android sigue exigiendo una confirmacion del usuario para instalar (salvo
 * que la app sea device owner, que no lo es). Esa confirmacion la contesta
 * [com.nonosky.s2000dash.a11y.InstallAutoClickService].
 */
object AutoInstaller {

    private const val TAG = "AutoInstaller"
    const val ACTION_INSTALL_STATUS = "com.nonosky.s2000dash.INSTALL_STATUS"

    /** El APK aparte que pulsa "Instalar" por nosotros. */
    private const val CONFIRMADOR = "com.nonosky.s2000dash.confirmador"
    private const val ACCION_ARMAR = "com.nonosky.s2000dash.ARMAR_INSTALACION"

    /**
     * Avisa al confirmador externo de que va a salir un dialogo nuestro.
     *
     * El confirmador vive en otro APK porque Android desactiva el servicio
     * de accesibilidad de una app en cuanto esa app se actualiza — con el
     * confirmador dentro del tablero, la cadena servia una sola vez.
     */
    fun armarConfirmador(context: Context, versionCode: Int) {
        val intent = Intent(ACCION_ARMAR).apply {
            setPackage(CONFIRMADOR)
            putExtra("versionCode", versionCode)
            putExtra("duracionMs", 120_000L)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "No se pudo armar el confirmador: ${it.message}") }
    }

    /** Si es false hay que mandar al usuario a "instalar apps desconocidas". */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Arranca la instalacion de [apk]. Devuelve false si ni siquiera se pudo
     * abrir la sesion; el resultado real llega por [ACTION_INSTALL_STATUS].
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) {
            Log.w(TAG, "APK inexistente o vacio: ${apk.absolutePath}")
            return false
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }

                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                // FLAG_MUTABLE: el sistema mete el estado y el intent de
                // confirmacion dentro de este PendingIntent. Inmutable lo
                // dejaria vacio y nunca sabriamos si hizo falta confirmar.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
            Log.i(TAG, "Sesion de instalacion $sessionId enviada")
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo instalar: ${e.message}")
            false
        }
    }
}
