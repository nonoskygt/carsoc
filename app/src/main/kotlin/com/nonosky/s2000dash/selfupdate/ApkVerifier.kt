package com.nonosky.s2000dash.selfupdate

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Comprueba que un APK descargado es de verdad una version nueva de ESTA
 * app antes de instalarlo.
 *
 * Es la defensa principal del auto-instalador. El descubrimiento del
 * servidor va por difusion UDP sin autenticar y la descarga por HTTP en
 * claro: cualquiera en la Wi-Fi puede anunciar un servidor falso. Lo que
 * impide que eso acabe en ejecucion de codigo arbitrario en el carro es
 * esto — un APK ajeno no esta firmado con nuestro certificado y no pasa.
 *
 * Se comprueban tres cosas, y las tres tienen que cuadrar:
 *  1. el paquete es exactamente el nuestro,
 *  2. el certificado de firma es identico al de la app instalada,
 *  3. el versionCode es el que el manifiesto prometio, y es mayor que el
 *     instalado (asi no se puede reinstalar en bucle ni degradar).
 */
object ApkVerifier {

    private const val TAG = "ApkVerifier"

    sealed class Result {
        object Ok : Result()
        data class Rechazado(val motivo: String) : Result()
    }

    fun verify(context: Context, apk: File, versionCodeEsperado: Int): Result {
        val pm = context.packageManager

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val info = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return Result.Rechazado("el archivo no es un APK legible")

        if (info.packageName != context.packageName) {
            return Result.Rechazado("es otro paquete: ${info.packageName}")
        }

        val code = versionCodeOf(info)
        if (code != versionCodeEsperado) {
            return Result.Rechazado("versionCode $code no es el prometido $versionCodeEsperado")
        }

        val propio = try {
            versionCodeOf(pm.getPackageInfo(context.packageName, 0))
        } catch (e: Exception) {
            -1
        }
        if (code <= propio) {
            return Result.Rechazado("no es mas nuevo que el instalado ($code <= $propio)")
        }

        val huellasApk = huellas(info)
        if (huellasApk.isEmpty()) return Result.Rechazado("el APK no trae firma")

        val huellasPropias = try {
            huellas(pm.getPackageInfo(context.packageName, flags))
        } catch (e: Exception) {
            emptySet<String>()
        }
        if (huellasPropias.isEmpty()) return Result.Rechazado("no se pudo leer la firma propia")

        if (huellasApk.intersect(huellasPropias).isEmpty()) {
            // El caso que de verdad importa: alguien nos colo otro APK.
            Log.w(TAG, "Firma distinta. APK=$huellasApk propia=$huellasPropias")
            return Result.Rechazado("la firma no es la nuestra")
        }

        return Result.Ok
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }

    @Suppress("DEPRECATION")
    private fun huellas(info: PackageInfo): Set<String> {
        val firmas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            info.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return firmas.mapNotNull { f ->
            runCatching { md.digest(f.toByteArray()).joinToString("") { "%02x".format(it) } }
                .getOrNull()
        }.toSet()
    }
}
