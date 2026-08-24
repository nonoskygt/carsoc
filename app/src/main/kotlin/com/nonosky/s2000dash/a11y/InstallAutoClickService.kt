package com.nonosky.s2000dash.a11y

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nonosky.s2000dash.selfupdate.UpdateState

/**
 * Contesta por nosotros el dialogo de instalacion del sistema.
 *
 * Android no deja instalar en silencio a una app que no sea device owner:
 * siempre sale un dialogo de "¿Quieres instalar esta aplicacion?". Un
 * servicio de accesibilidad es el unico mecanismo legitimo sin root para
 * leer esa ventana ajena y tocar el boton. Con esto, publicar una version
 * nueva en el servidor basta para que el radio se actualice solo.
 *
 * **Esta acotado a proposito.** Solo actua si la ventana pertenece a un
 * instalador del sistema *y* el contenido menciona esta app. Un servicio de
 * accesibilidad que aceptara cualquier instalacion seria una puerta abierta
 * a que cualquier APK que llegue al radio se instale sin que nadie lo vea.
 */
class InstallAutoClickService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!InstallDialogRules.isInstallerPackage(pkg)) return

        val root = rootInActiveWindow ?: return
        try {
            if (!mentionsThisApp(root)) return
            val clicked = clickFirstMatching(root)
            if (clicked != null) {
                Log.i(TAG, "Confirmado con el boton '$clicked'")
                UpdateState.note("Dialogo confirmado: '$clicked'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo revisando la ventana: ${e.message}")
        } finally {
            runCatching { root.recycle() }
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Servicio de auto-confirmacion conectado")
        UpdateState.note("Auto-confirmacion activa")
    }

    /** Salvaguarda: no confirmamos instalaciones que no sean de esta app. */
    private fun mentionsThisApp(root: AccessibilityNodeInfo): Boolean {
        val textos = mutableListOf<String>()
        collectTexts(root, textos)
        return InstallDialogRules.mentionsOurApp(textos, packageName, APP_LABEL)
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, into: MutableList<String>) {
        node ?: return
        if (into.size > MAX_NODOS) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { into += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { into += it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectTexts(child, into)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    /** @return el texto del boton que se toco, o null si no habia ninguno. */
    private fun clickFirstMatching(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val txt = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val etiqueta = if (txt.isNotEmpty()) txt else desc

        if (etiqueta.isNotEmpty() && node.isClickable && node.isEnabled &&
            InstallDialogRules.isConfirmButton(etiqueta)
        ) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return etiqueta
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                clickFirstMatching(child)?.let { return it }
            } finally {
                runCatching { child.recycle() }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "InstallAutoClick"
        const val APP_LABEL = "s2000 dash"
        /** Tope de nodos a recorrer: una ventana rara no nos cuelga. */
        const val MAX_NODOS = 400
    }
}
