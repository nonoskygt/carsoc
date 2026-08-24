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
     * Nombre y MAC del adaptador elegido, para poder diagnosticarlo en
     * remoto. Sin esto no habia forma de saber si el tablero estaba
     * intentando hablar con el adaptador correcto o con otra cosa.
     */
    @Volatile
    var adaptadorElegido: String? = null

    /**
     * Lista los adaptadores Bluetooth que el radio ya tiene emparejados.
     *
     * Lo pone la pantalla, que es quien tiene los permisos. Sirve para
     * poder configurar el adaptador EN REMOTO: sin esto habia que ir al
     * carro a tocar el selector, que es justo lo que se quiere evitar.
     */
    @Volatile
    var listarAdaptadores: (() -> List<String>)? = null

    /** Elige un adaptador YA emparejado por MAC y arranca el sondeo. */
    @Volatile
    var elegirAdaptador: ((String) -> Boolean)? = null

    /**
     * Barre el aire en busca de adaptadores. Bloquea hasta terminar.
     *
     * En API 30 el barrido exige permiso de ubicacion; el resultado lo dice
     * en vez de devolver una lista vacia sin explicacion, que es la forma
     * mas facil de perder una tarde.
     */
    @Volatile
    var buscarAdaptadores: (() -> List<String>)? = null

    /** Empareja por MAC (contestando el PIN) y luego lo elige. */
    @Volatile
    var emparejarAdaptador: ((String) -> String)? = null

    /** Olvida el adaptador guardado y detiene el sondeo. */
    @Volatile
    var olvidarAdaptador: (() -> Unit)? = null

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
