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
     * El lector del TPMS, vivo mientras viva el servicio.
     *
     * Lo expone el servicio y lo consultan la pantalla y el puente. Va aqui y
     * no en la Activity porque las llantas deben seguir midiendose con el
     * tablero cerrado: si colgara de la pantalla, cambiar de app dejaria de
     * vigilar las presiones — justo cuando el carro esta andando.
     */
    @Volatile
    var lectorTpms: com.nonosky.s2000dash.tpms.TpmsReader? = null

    /**
     * El vigilante de la bateria de litio, que barre BLE por el dongle USB.
     *
     * No usa la pila Bluetooth del radio porque esa no sirve: le habla HCI
     * directo al dongle. Vive en el servicio por lo mismo que el TPMS — hay
     * que seguir vigilando con el tablero cerrado.
     */
    @Volatile
    var vigilanteBateria: com.nonosky.s2000dash.bateria.VigilanteBateria? = null

    /**
     * Conecta con el BMS y lo lee AHORA, devolviendo la traza paso a paso.
     *
     * Es para diagnosticar: una pila Bluetooth escrita a mano falla en algun
     * escalon concreto —conexion, MTU, descubrimiento, CCCD, checksum— y sin
     * ver cual, "no lee la bateria" no se puede arreglar.
     */
    @Volatile
    var leerBmsAhora: ((String) -> List<String>)? = null

    /**
     * Intenta el OBD por HCI crudo contra el dongle USB, y cuenta cada paso.
     *
     * Es la via que esquiva la pila Bluetooth rota del radio. Devuelve la
     * traza completa porque cada escalon —emparejar, L2CAP, SABM, MSC— falla
     * distinto, y sin ver cual, "no conecta" no se puede arreglar.
     */
    @Volatile
    var probarObdHci: ((String) -> List<String>)? = null

    /** La pantalla se registra aqui para repintar cuando cambia la bateria. */
    @Volatile
    var alCambiarBateria: (() -> Unit)? = null

    /** La pantalla se registra aqui para repintar cuando llega una trama. */
    @Volatile
    var alCambiarTpms: (() -> Unit)? = null

    /**
     * Interroga por HCI crudo a un dongle Bluetooth USB, sin el kernel.
     *
     * Hace falta porque el kernel de esta ROM no trae `btusb`
     * (`/sys/class/bluetooth/` esta vacio) y el dongle quedo enganchado al
     * driver USB generico: Android jamas lo va a usar como su radio. Pero si
     * nos concede permiso sobre el aparato USB, y un dongle Bluetooth es un
     * transporte HCI simple: comandos por control, eventos por interrupcion.
     */
    @Volatile
    var interrogarHci: ((Int?, Int?) -> List<String>)? = null

    /** Barrido BLE hablandole al dongle por HCI, saltandose la pila rota. */
    @Volatile
    var barrerBleHci: ((Int, Int?, Int?, Boolean, Boolean) -> List<String>)? = null

    /**
     * Prueba el camino de datos ACL/L2CAP contra un aparato real.
     *
     * Es lo que distingue "encuentro la bateria" de "leo la bateria": el
     * barrido solo escucha anuncios, esto abre un enlace y habla ATT por el.
     * Devuelve la traza paso a paso porque cuando falle habra que saber en
     * cual de los ocho pasos fue, desde la laptop y sin tocar el radio.
     */
    @Volatile
    var probarAcl: ((String, Int?, Int?, Int) -> List<String>)? = null

    /**
     * Abre el USB-serial y vuelca lo que llegue, en crudo.
     *
     * El formato de trama del receptor TPMS es propietario: hay que ver bytes
     * reales antes de escribir un decodificador, no al reves.
     */
    @Volatile
    var volcarUsbSerial: ((Int, Int) -> List<String>)? = null

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
     * Vacia lo que dijo el confirmador.
     *
     * Hace falta antes de cada mando: sin esto la respuesta de un volcado
     * viene mezclada con lo que quedaba de mandos anteriores, y no hay forma
     * de saber que linea contesta a que pregunta.
     */
    fun olvidarLoDelConfirmador() = dichos.clear()

    /**
     * Manda al confirmador tocar, volcar o abrir algo.
     *
     * Es la unica via de controlar la pantalla de este radio: sin root, el
     * shell no puede `input tap` ni `am start`, y un AccessibilityService es
     * lo unico que Android deja hacerlo. Lo registra la Activity, que es
     * quien puede difundir con el permiso de firma.
     */
    @Volatile
    var mandarAlConfirmador: ((String, String?, String?, String?, String?) -> Unit)? = null

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
