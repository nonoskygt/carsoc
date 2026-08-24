package com.nonosky.s2000dash.bateria

import android.content.Context
import android.hardware.usb.UsbManager
import com.nonosky.s2000dash.hci.BombaHci
import com.nonosky.s2000dash.hci.DuenoDongle
import com.nonosky.s2000dash.hci.ConexionLe
import com.nonosky.s2000dash.hci.GestorL2cap
import com.nonosky.s2000dash.hci.HciUsb
import com.nonosky.s2000dash.hci.L2cap

/**
 * La union que faltaba: de un dongle USB a una sesion GATT utilizable.
 *
 * Las piezas ya existian por separado —[HciUsb] habla con el dongle,
 * [BombaHci] reparte eventos y ACL, [GestorL2cap] da canales, `Att` arma las
 * PDU y [LectorBmsGatt] entiende el BMS— pero nadie las ataba. Esta clase es
 * ese nudo, y nada mas: abre el dongle, conecta con la bateria por LE, toma
 * el canal fijo de ATT y expone el par enviar/recibir que el lector espera.
 *
 * El canal de ATT es **fijo (CID 0x0004)**, y esa es la razon por la que la
 * bateria se puede leer y el adaptador OBD es mucho mas trabajo: aqui no hay
 * que negociar canal, ni consultar SDP, ni emparejarse. Se conecta y se
 * habla.
 */
class CanalGattHci private constructor(
    private val hci: HciUsb,
    private val bomba: BombaHci,
    private val gestor: GestorL2cap,
    private val canal: GestorL2cap.Canal,
    private val handle: Int,
    val traza: List<String>,
) : CanalGatt, BombaHci.Oyente {

    @Volatile
    private var vivo = true

    override val abierto: Boolean get() = vivo

    /**
     * Cola propia, con suscripcion PERMANENTE mientras el canal viva.
     *
     * No se usa `BombaHci.esperarPdu` y esto no es un capricho: ese metodo se
     * suscribe, espera UN PDU y se da de baja en su `finally`. Entre que el
     * lector procesa una notificacion y vuelve a llamar, nadie escucha — y las
     * dos notificaciones en que llega la respuesta del registro 0x03 del BMS
     * salen con microsegundos de diferencia, asi que la segunda cae justo en
     * ese hueco y se descarta.
     *
     * Se midio: el 0x03 llegaba siempre "a medias, 20 bytes sin completar tras
     * 1 notificacion". No era el MTU ni el CCCD —los dos sospechosos obvios—
     * era la ventana ciega entre dos esperas. Con una cola no hay ventana.
     */
    private val recibidas = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    /**
     * Encola lo que llega. **Envuelto de arriba a abajo, sin excepciones.**
     *
     * Esto corre en el hilo de la bomba, y en Android una excepcion que escapa
     * de un hilo MATA el proceso entero. La primera version dejaba fuera del
     * runCatching la llamada a `L2cap.cidDe(pdu)` —solo envolvia el `offer`—
     * y con eso basto: un PDU mas corto de lo que espera el parseo tumbo el
     * tablero, el puente y el actualizador de golpe, y hubo que arrancar el
     * carro para recuperarlo.
     *
     * La regla de este proyecto no es "envuelve lo que pueda fallar", es
     * **envuelve el metodo entero** cuando corre en un hilo ajeno. Adivinar
     * cual linea lanza es como se llega a un radio incomunicado.
     */
    override fun alPdu(handle: Int, pdu: ByteArray) {
        runCatching {
            if (handle != this.handle) return
            if (L2cap.cidDe(pdu) != L2cap.CID_ATT) return
            recibidas.offer(L2cap.cargaDe(pdu))
        }
    }

    override fun alCaerEnlace(handle: Int, razon: Int) {
        runCatching { if (handle == this.handle) vivo = false }
    }

    override fun enviar(pdu: ByteArray): Boolean =
        runCatching { bomba.enviarAcl(handle, L2cap.CID_ATT, pdu) }.getOrDefault(false)

    override fun recibir(timeoutMs: Int): ByteArray? = runCatching {
        recibidas.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrNull()

    /**
     * Cierra en orden inverso al de apertura.
     *
     * Importa: dejar la conexion LE viva sin cerrarla ocupa una de las pocas
     * ranuras del controlador, y a la tercera vez el dongle deja de aceptar
     * conexiones nuevas sin decir por que.
     */
    override fun cerrar() {
        if (!vivo) return
        vivo = false
        runCatching { bomba.quitar(this) }
        runCatching { gestor.cerrar(canal) }
        runCatching {
            bomba.comando(
                ConexionLe.CMD_DISCONNECT,
                ConexionLe.parametrosDesconectar(handle),
            )
        }
        runCatching { gestor.detener() }
        runCatching { bomba.detener() }
        runCatching { hci.cerrar() }
    }

    companion object {

        /**
         * Abre todo el camino hasta la bateria.
         *
         * Devuelve null si algo falla, y la traza cuenta hasta donde se llego:
         * en un radio sin shell, "no se pudo" a secas no permite arreglar nada.
         */
        fun abrir(context: Context, mac: String): Pair<CanalGattHci?, List<String>> =
            DuenoDongle.usar("gatt-bateria", esperaMs = 5_000) { abrirConDongle(context, mac) }
                ?: (null to listOf(
                    "el dongle lo tiene " + (DuenoDongle.ocupadoPor() ?: "otro") +
                        "; se reintenta luego"
                ))

        private fun abrirConDongle(context: Context, mac: String): Pair<CanalGattHci?, List<String>> {
            val traza = mutableListOf<String>()

            val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return null to listOf("ERROR: este radio no expone UsbManager")

            val dongle = HciUsb.buscarDongle(um)
                ?: return null to listOf("ERROR: no hay dongle Bluetooth en el USB")

            val hci = HciUsb(um, dongle)
            val abierto = hci.abrir()
            traza += abierto
            if (abierto.any { it.startsWith("ERROR") }) {
                runCatching { hci.cerrar() }
                return null to traza
            }

            val bomba = BombaHci(hci)
            if (!bomba.arrancar()) {
                traza += "ERROR: la bomba de HCI no arranco"
                runCatching { hci.cerrar() }
                return null to traza
            }

            // Sin esto no se sabe cuantos paquetes ACL admite el controlador, y
            // pasarse desborda su cola: deja de contestar y parece colgado.
            traza += bomba.configurarDesdeLe()

            val gestor = GestorL2cap(bomba)
            gestor.arrancar()

            traza += "conectando por LE con $mac"
            val estado = bomba.comando(
                ConexionLe.CMD_LE_CREATE_CONNECTION,
                ConexionLe.paraMac(mac).codificar(),
            )
            val st = ConexionLe.interpretarCommandStatus(estado, ConexionLe.CMD_LE_CREATE_CONNECTION)
            traza += "Command Status de la conexion: ${st ?: "sin respuesta"}"
            if (st != 0) {
                traza += "ERROR: el controlador rechazo la conexion"
                cerrarTodo(hci, bomba, gestor)
                return null to traza
            }

            // El evento de conexion tarda: el anuncio de la bateria puede ir
            // cada 1-2 segundos y hay que esperar a que toque.
            val evento = bomba.esperarEvento(12_000) {
                ConexionLe.interpretarConexionCompleta(it) != null
            }
            val completa = ConexionLe.interpretarConexionCompleta(evento)
            if (completa == null || completa.estado != 0) {
                traza += "ERROR: no se completo la conexion LE " +
                    "(${completa?.let { ConexionLe.nombreEstado(it.estado) } ?: "sin evento"})"
                // Cancelar, o el intento se queda colgado y bloquea el siguiente.
                runCatching { bomba.comando(ConexionLe.CMD_LE_CREATE_CONNECTION_CANCEL) }
                cerrarTodo(hci, bomba, gestor)
                return null to traza
            }

            traza += "conectado, handle=${completa.handle}"
            val canal = gestor.canalFijo(completa.handle, L2cap.CID_ATT)
            traza += "canal ATT (CID 0x0004) listo"

            val ch = CanalGattHci(hci, bomba, gestor, canal, completa.handle, traza)
            // Suscribirse ANTES de devolver: si el BMS empieza a notificar solo
            // en cuanto se activa el CCCD, esas primeras tramas no se pierden.
            bomba.suscribir(ch)
            return ch to traza
        }

        private fun cerrarTodo(hci: HciUsb, bomba: BombaHci, gestor: GestorL2cap) {
            runCatching { gestor.detener() }
            runCatching { bomba.detener() }
            runCatching { hci.cerrar() }
        }
    }
}
