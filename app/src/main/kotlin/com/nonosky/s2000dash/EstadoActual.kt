package com.nonosky.s2000dash

import android.view.View
import java.lang.ref.WeakReference

/**
 * Ultimo estado conocido del vehiculo, accesible desde todo el proceso.
 *
 * Hace falta porque el puente de diagnostico vive en un servicio y el
 * sondeo vive en la pantalla: sin un punto comun, el servicio no tendria
 * nada que contar cuando la pantalla no esta.
 */
object EstadoActual {

    @Volatile
    var ultimo: VehicleState = VehicleState()

    /**
     * Referencia debil a la vista del tablero, para poder fotografiarla.
     *
     * Debil a proposito: si la pantalla se destruye, esto no debe impedir
     * que se libere. Cuando no hay vista, el puente contesta que no hay
     * nada que dibujar en vez de mentir con una imagen vieja.
     */
    @Volatile
    private var vistaRef: WeakReference<View>? = null

    var vista: View?
        get() = vistaRef?.get()
        set(v) { vistaRef = if (v == null) null else WeakReference(v) }
}
