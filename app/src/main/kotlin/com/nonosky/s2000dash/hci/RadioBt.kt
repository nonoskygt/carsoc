package com.nonosky.s2000dash.hci

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * La radio Bluetooth compartida: **un** dongle, varios enlaces a la vez.
 *
 * Sustituye al reparto por turnos, que era una limitacion de mi diseño y no
 * del aparato. Un controlador de doble modo mantiene sin problema un enlace
 * LE (la bateria) y uno clasico (el adaptador OBD) simultaneamente: los
 * paquetes van etiquetados con su handle de conexion y el controlador los
 * separa solo.
 *
 * Lo que estaba mal era que cada parte abria su PROPIO [HciUsb] sobre el
 * mismo aparato USB. Dos duenos del mismo endpoint se roban los paquetes, y
 * el sintoma no es un error limpio sino "el BMS dejo de contestar". Se puso
 * un candado para que no coincidieran, y funciono — pero al precio de que el
 * motor solo estuviera en linea a ratos.
 *
 * Aqui el aparato se abre **una vez**, la bomba reparte por handle, y cada
 * consumidor se suscribe a lo suyo. Sin turnos y sin candado.
 */
object RadioBt {

    private const val TAG = "RadioBt"

    private val cerrojo = Any()

    private var hci: HciUsb? = null
    private var bomba: BombaHci? = null
    private var gestor: GestorL2cap? = null

    /** Cuantos consumidores la tienen tomada. Se cierra al llegar a cero. */
    private var usuarios = 0

    @Volatile
    var ultimoFallo: String? = null
        private set

    /** Traza de la ultima apertura, para diagnosticar en remoto. */
    @Volatile
    var traza: List<String> = emptyList()
        private set

    /**
     * Abre la radio si hace falta y devuelve las piezas.
     *
     * Cada llamada con exito suma un usuario, y cada [soltar] resta uno. El
     * aparato se cierra solo cuando nadie lo usa: cerrarlo mientras el motor
     * sigue sondeando tiraria su enlace sin motivo.
     */
    fun tomar(context: Context, quien: String): Piezas? = synchronized(cerrojo) {
        val yaAbierta = hci != null && bomba != null && gestor != null
        if (yaAbierta) {
            usuarios++
            Log.i(TAG, "'$quien' se engancha a la radio ya abierta (usuarios=$usuarios)")
            return Piezas(hci!!, bomba!!, gestor!!)
        }

        val t = mutableListOf<String>()
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (um == null) {
            ultimoFallo = "este radio no expone UsbManager"
            return null
        }
        val dongle = HciUsb.buscarDongle(um)
        if (dongle == null) {
            ultimoFallo = "no hay dongle Bluetooth en el USB"
            return null
        }

        val h = HciUsb(um, dongle)
        val abierto = h.abrir()
        t += abierto
        if (abierto.any { it.startsWith("ERROR") }) {
            ultimoFallo = abierto.last()
            traza = t
            runCatching { h.cerrar() }
            return null
        }

        val b = BombaHci(h)
        if (!b.arrancar()) {
            ultimoFallo = "la bomba de HCI no arranco"
            traza = t
            runCatching { h.cerrar() }
            return null
        }

        // Los DOS pools: el clasico para el adaptador OBD y el LE para el BMS.
        // Preguntar por los dos importa — un controlador puede no tener pool LE
        // propio y usar el de BR/EDR, y darlo por hecho deja el control de
        // flujo en cero, que es como no poder mandar ni un paquete.
        t += b.configurarDesdeClasico()
        t += b.configurarDesdeLe()

        val g = GestorL2cap(b)
        g.arrancar()

        hci = h
        bomba = b
        gestor = g
        usuarios = 1
        ultimoFallo = null
        traza = t
        Log.i(TAG, "'$quien' abrio la radio")
        return Piezas(h, b, g)
    }

    /** Suelta una referencia. Cierra de verdad solo cuando no queda nadie. */
    fun soltar(quien: String) = synchronized(cerrojo) {
        if (usuarios <= 0) return
        usuarios--
        Log.i(TAG, "'$quien' solto la radio (quedan $usuarios)")
        if (usuarios > 0) return
        runCatching { gestor?.detener() }
        runCatching { bomba?.detener() }
        runCatching { hci?.cerrar() }
        gestor = null
        bomba = null
        hci = null
    }

    /**
     * Fuerza el cierre pase lo que pase.
     *
     * Solo para cuando el servicio muere: dejar el aparato USB reclamado
     * impide reabrirlo hasta que el sistema limpie el proceso.
     */
    fun cerrarTodo() = synchronized(cerrojo) {
        usuarios = 0
        runCatching { gestor?.detener() }
        runCatching { bomba?.detener() }
        runCatching { hci?.cerrar() }
        gestor = null
        bomba = null
        hci = null
    }

    fun abierta(): Boolean = synchronized(cerrojo) { hci != null }

    fun diagnostico(): List<String> = synchronized(cerrojo) {
        listOf(
            "radio: " + (if (hci != null) "abierta, $usuarios usuario(s)" else "cerrada"),
            "ultimo fallo: ${ultimoFallo ?: "ninguno"}",
        ) + (bomba?.diagnostico() ?: emptyList())
    }

    class Piezas(val hci: HciUsb, val bomba: BombaHci, val gestor: GestorL2cap)
}
