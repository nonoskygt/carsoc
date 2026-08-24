package com.nonosky.s2000dash.bateria

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.nonosky.s2000dash.hci.HciUsb
import kotlin.concurrent.thread

/**
 * Vigila la bateria de litio barriendo BLE por el dongle USB.
 *
 * Existe porque el Bluetooth del radio no sirve: no ve aparatos que cualquier
 * telefono ve, se apaga solo, y un barrido BLE de 25 segundos por la via
 * normal de Android no encontro absolutamente nada. El kernel de esta ROM
 * tampoco trae `btusb`, asi que el dongle nunca va a ser la radio del
 * sistema. Pero Android SI da permiso sobre el aparato USB, y un dongle
 * Bluetooth es un transporte HCI simple — asi que se le habla directo.
 *
 * Por ahora esto solo DETECTA: da MAC, nombre y señal. El voltaje y el SoC
 * viven detras de una conexion GATT (L2CAP + ATT sobre HCI) que todavia no
 * esta escrita. Detectar no es leer, y el tablero lo dice asi en vez de
 * inventar numeros.
 */
class VigilanteBateria(private val context: Context) {

    @Volatile
    var estado: BateriaState = BateriaState()
        private set

    var alCambiar: (() -> Unit)? = null

    @Volatile
    private var vivo = false
    private var hilo: Thread? = null

    fun arrancar() {
        if (vivo) return
        vivo = true
        // Hilo propio y envuelto entero: una excepcion que escapa de un hilo
        // en Android MATA el proceso, y eso tumbaria de golpe el tablero, el
        // puente y el actualizador. Ya paso una vez con el DebugServer.
        hilo = thread(name = "vigilante-bateria", isDaemon = true) {
            while (vivo) {
                runCatching { unaRonda() }
                    .onFailure { Log.w(TAG, "ronda fallida: ${it.message}") }
                dormir(if (estado.detectada()) ESPERA_DETECTADA_MS else ESPERA_BUSCANDO_MS)
            }
        }
    }

    fun detener() {
        vivo = false
        runCatching { hilo?.interrupt() }
        hilo = null
    }

    private fun dormir(ms: Long) {
        val hasta = System.currentTimeMillis() + ms
        while (vivo && System.currentTimeMillis() < hasta) {
            runCatching { Thread.sleep(500) }.onFailure { return }
        }
    }

    /** Un barrido corto. Se abre el dongle, se barre, se cierra. */
    private fun unaRonda() {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return publicar(estado.copy(enlace = EnlaceBateria.SinDongle, detalle = "sin UsbManager"))

        val dongle = HciUsb.buscarDongle(um)
            ?: return publicar(estado.copy(enlace = EnlaceBateria.SinDongle, detalle = "no hay dongle en el USB"))

        val hci = HciUsb(um, dongle)
        try {
            val traza = hci.abrir()
            if (traza.any { it.startsWith("ERROR") }) {
                publicar(estado.copy(enlace = EnlaceBateria.DongleMudo, detalle = traza.last()))
                return
            }

            while (hci.leerEvento(100) != null) { /* vaciar lo viejo */ }

            hci.mandarComando(HciUsb.CMD_RESET)
            hci.leerEvento(1_500)

            // Barrido PASIVO: para oir a un BMS que se anuncia basta escuchar,
            // y asi no se ensucia el aire con peticiones de barrido activo.
            hci.mandarComando(
                HciUsb.CMD_LE_SET_SCAN_PARAMS,
                byteArrayOf(0x00, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00),
            )
            hci.leerEvento(1_500)
            hci.mandarComando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x01, 0x00))
            hci.leerEvento(1_500)

            if (!estado.detectada()) {
                publicar(estado.copy(enlace = EnlaceBateria.Buscando, detalle = null))
            }

            var encontrada = false
            val hasta = System.currentTimeMillis() + BARRIDO_MS
            while (vivo && System.currentTimeMillis() < hasta) {
                val e = hci.leerEvento(400) ?: continue
                val hallazgo = interpretarAnuncio(e) ?: continue
                encontrada = true
                publicar(
                    estado.copy(
                        mac = hallazgo.mac,
                        nombre = hallazgo.nombre ?: estado.nombre,
                        rssi = hallazgo.rssi,
                        vistaMs = System.currentTimeMillis(),
                        enlace = EnlaceBateria.Detectada,
                        detalle = null,
                    )
                )
                // Con verla una vez por ronda basta: seguir escuchando gasta
                // el dongle sin añadir informacion.
                break
            }

            hci.mandarComando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))

            if (!encontrada && estado.detectada() && estado.rancia(System.currentTimeMillis())) {
                publicar(estado.copy(enlace = EnlaceBateria.Buscando, detalle = "dejo de anunciarse"))
            }
        } catch (e: Exception) {
            publicar(estado.copy(enlace = EnlaceBateria.Fallo, detalle = "${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            runCatching { hci.cerrar() }
        }
    }

    private fun publicar(nuevo: BateriaState) {
        estado = nuevo
        runCatching { alCambiar?.invoke() }
    }

    private data class Hallazgo(val mac: String, val nombre: String?, val rssi: Int)

    /**
     * Saca la bateria de un LE Advertising Report, si es ella.
     *
     * Se reconoce por el UUID de servicio 0xFF00 del anuncio, que es lo que
     * delata a un BMS JBD/Xiaoxiang — no por el nombre. El nombre de este
     * aparato resulta ser "S2000", que es una coincidencia comoda pero un
     * criterio fragil: cualquiera puede renombrar un BMS, y el servicio no.
     */
    private fun interpretarAnuncio(e: ByteArray): Hallazgo? {
        if (e.size < 12) return null
        if ((e[0].toInt() and 0xFF) != HciUsb.EVT_LE_META) return null
        if ((e[2].toInt() and 0xFF) != HciUsb.SUBEVT_LE_ADVERTISING_REPORT) return null

        var i = 4
        i++ // tipo de evento
        i++ // tipo de direccion
        if (i + 7 > e.size) return null
        val mac = (0 until 6).map { e[i + it] }.reversed()
            .joinToString(":") { String.format("%02X", it) }
        i += 6
        val largo = e[i].toInt() and 0xFF
        i++
        if (i + largo > e.size) return null
        val datos = e.copyOfRange(i, i + largo)
        val rssi = if (i + largo < e.size) e[i + largo].toInt() else 0

        var esBms = false
        var nombre: String? = null
        var j = 0
        while (j + 1 < datos.size) {
            val len = datos[j].toInt() and 0xFF
            if (len == 0) break
            val tipo = datos[j + 1].toInt() and 0xFF
            val fin = (j + 1 + len).coerceAtMost(datos.size)
            when (tipo) {
                // 0x02 lista incompleta de UUID de 16 bits, 0x03 completa.
                0x02, 0x03 -> {
                    var k = j + 2
                    while (k + 1 < fin) {
                        val uuid = ((datos[k + 1].toInt() and 0xFF) shl 8) or (datos[k].toInt() and 0xFF)
                        if (uuid == BateriaState.SERVICIO_JBD) esBms = true
                        k += 2
                    }
                }
                // 0x08 nombre corto, 0x09 nombre completo.
                0x08, 0x09 -> if (j + 2 < fin) {
                    nombre = String(datos, j + 2, fin - j - 2, Charsets.UTF_8)
                }
            }
            j += len + 1
        }

        return if (esBms) Hallazgo(mac, nombre, rssi) else null
    }

    private companion object {
        const val TAG = "VigilanteBateria"

        /** Cuanto se escucha en cada ronda. */
        const val BARRIDO_MS = 6_000L

        /**
         * Ya detectada, se comprueba de tarde en tarde: el dongle es un
         * recurso compartido y abrirlo cada segundo para reconfirmar lo que ya
         * se sabe solo quita tiempo a lo que venga despues (el GATT).
         */
        const val ESPERA_DETECTADA_MS = 30_000L

        /** Sin detectar, se insiste mas seguido. */
        const val ESPERA_BUSCANDO_MS = 10_000L
    }
}
