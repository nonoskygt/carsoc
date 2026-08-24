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
     * Ultimo fallo del enlace, con su causa.
     *
     * Un tablero que solo dice "conectando" no permite diagnosticar
     * nada en remoto: hay que saber SI fallo y POR QUE.
     */
    @Volatile
    var ultimoErrorEnlace: String? = null

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

    /** Descarga, verifica la firma e instala un APK acompanante. */
    @Volatile
    var instalarCompanero: ((String, String) -> String)? = null

    /** Arma el confirmador para que teclee el PIN del emparejamiento. */
    @Volatile
    var armarPin: ((String) -> Unit)? = null

    /**
     * Barrido Bluetooth LE, con el anuncio crudo de cada hallazgo.
     *
     * Aparte del barrido clasico a proposito: son dos radios distintas. Un
     * `startDiscovery()` no ve un aparato BLE por mucho que este ahi, que es
     * exactamente por que la bateria de litio nunca aparecio en la lista.
     */
    @Volatile
    var barrerBle: ((Int) -> List<String>)? = null

    /** Vuelca servicios y caracteristicas de un aparato BLE por GATT. */
    @Volatile
    var volcarGatt: ((String, Int) -> List<String>)? = null

    /** Lo que hay colgado del USB, con VID, PID, interfaces y endpoints. */
    @Volatile
    var listarUsb: (() -> List<String>)? = null

    /**
     * Enciende o apaga la radio Bluetooth del head unit.
     *
     * No es un lujo: tras reiniciar el carro queda apagada, y se ha visto
     * apagarse sola tras varios emparejamientos fallidos. Sin esto hay que
     * ir fisicamente al carro.
     */
    @Volatile
    var encenderBluetooth: ((Boolean) -> String)? = null

    /** Lo ultimo que conto el confirmador sobre lo que ve en pantalla. */
    private val dichos = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun anotarConfirmador(t: String) {
        if (dichos.size > 40) dichos.removeAt(0)
        dichos.add(t)
    }

    fun loQueDiceElConfirmador(): List<String> = dichos.toList()

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
