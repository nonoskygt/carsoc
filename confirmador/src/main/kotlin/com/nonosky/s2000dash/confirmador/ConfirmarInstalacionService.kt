package com.nonosky.s2000dash.confirmador

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulsa "Instalar" en el dialogo del sistema cuando el tablero se actualiza.
 *
 * Vive en un APK aparte porque Android desactiva el servicio de
 * accesibilidad de una app en cuanto esa app se actualiza. Estando aqui,
 * el tablero puede actualizarse cuantas veces quiera sin perder el permiso.
 */
class ConfirmarInstalacionService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Confirmador conectado")
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!Reglas.esInstalador(event.packageName?.toString())) return
        // Sin ventana armada por el tablero no se toca nada, diga lo que
        // diga la pantalla.
        if (!Armado.activo()) return

        val root = rootInActiveWindow ?: return
        try {
            val textos = ArrayList<String>()
            recogerTextos(root, textos)
            if (!Reglas.puedeConfirmar(textos, armado = true)) return

            val pulsado = pulsarPrimerBoton(root)
            if (pulsado != null) {
                Log.i(TAG, "Confirmado con '$pulsado'")
                // Una sola confirmacion por ventana: si no, seguiria
                // pulsando en cualquier dialogo posterior.
                Armado.desarmar()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo revisando la ventana: ${e.message}")
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun recogerTextos(nodo: AccessibilityNodeInfo?, destino: MutableList<String>) {
        nodo ?: return
        if (destino.size > MAX_NODOS) return
        nodo.text?.toString()?.takeIf { it.isNotBlank() }?.let { destino += it }
        nodo.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { destino += it }
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            try {
                recogerTextos(hijo, destino)
            } finally {
                runCatching { hijo.recycle() }
            }
        }
    }

    private fun pulsarPrimerBoton(nodo: AccessibilityNodeInfo?): String? {
        nodo ?: return null
        val txt = (nodo.text?.toString() ?: "").trim()
        val desc = (nodo.contentDescription?.toString() ?: "").trim()
        val etiqueta = if (txt.isNotEmpty()) txt else desc

        if (etiqueta.isNotEmpty() && nodo.isClickable && nodo.isEnabled &&
            Reglas.esBotonDeConfirmar(etiqueta)
        ) {
            if (nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return etiqueta
        }
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            try {
                pulsarPrimerBoton(hijo)?.let { return it }
            } finally {
                runCatching { hijo.recycle() }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "Confirmador"
        /** Tope de nodos: una ventana rara no nos cuelga. */
        const val MAX_NODOS = 400
    }
}
