package com.nonosky.s2000dash.confirmador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Recibe del tablero el aviso de "voy a instalar, prepara el clic".
 *
 * El receptor exige un permiso de nivel `signature`, asi que solo puede
 * armarlo una app firmada con el mismo certificado. Sin eso, cualquier app
 * del radio podria abrir la ventana de confirmacion a voluntad.
 */
class ArmarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCION_ARMAR -> {
                val version = intent.getIntExtra(EXTRA_VERSION, -1)
                val duracion = intent.getLongExtra(EXTRA_DURACION, 120_000L)
                Armado.armar(version, duracion)
                Log.i(TAG, "Armado para v$version durante $duracion ms")
            }
            ACCION_DESARMAR -> {
                Armado.desarmar()
                Log.i(TAG, "Desarmado")
            }
        }
    }

    companion object {
        private const val TAG = "ArmarReceiver"
        const val ACCION_ARMAR = "com.nonosky.s2000dash.ARMAR_INSTALACION"
        const val ACCION_DESARMAR = "com.nonosky.s2000dash.DESARMAR_INSTALACION"
        const val EXTRA_VERSION = "versionCode"
        const val EXTRA_DURACION = "duracionMs"
    }
}
