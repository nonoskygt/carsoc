package com.nonosky.s2000dash.hci

import android.content.Context
import android.hardware.usb.UsbManager

/**
 * Prueba el camino de datos completo contra un aparato real y lo cuenta paso
 * a paso.
 *
 * Es la unica forma honesta de saber si el cimiento aguanta: ACL, L2CAP y el
 * control de flujo no se pueden validar con pruebas de escritorio: se validan
 * hablando con algo que conteste. Aqui el algo es la bateria, que ya se sabe
 * que esta ahi (MAC A4:C1:38:CD:FA:C8, nombre "S2000", servicio 0xFF00) y que
 * hasta hoy solo se podia ver anunciarse, nunca leer.
 *
 * La secuencia, que es tambien el orden en que hay que arreglar las cosas si
 * falla:
 *
 * ```
 *   1. abrir el dongle y comprobar que tiene los dos BULK
 *   2. Reset y leer el pool de buffers  -> sin esto no hay control de flujo
 *   3. barrer para saber el TIPO de direccion del aparato
 *   4. LE_Create_Connection            -> Command Status, y LUEGO el handle
 *   5. canal ATT fijo (CID 0x0004)     -> sin negociar nada
 *   6. ATT Exchange MTU                -> primera ida y vuelta de datos reales
 *   7. descubrir servicios primarios   -> confirma que el 0xFF00 esta ahi
 *   8. desconectar y devolver creditos
 * ```
 *
 * Todo lo que llega se vuelca en crudo. Si un byte no se entiende se dice que
 * no se entiende: no se inventa un significado, igual que en el TPMS.
 */
object SondaAcl {

    // --- ATT: lo minimo para preguntar quien eres ---
    private const val ATT_ERROR_RSP = 0x01
    private const val ATT_EXCHANGE_MTU_PET = 0x02
    private const val ATT_EXCHANGE_MTU_RSP = 0x03
    private const val ATT_READ_BY_GROUP_TYPE_PET = 0x10
    private const val ATT_READ_BY_GROUP_TYPE_RSP = 0x11
    private const val ATT_NOTIFICACION = 0x1B

    /** UUID del descriptor "servicio primario". */
    private const val UUID_SERVICIO_PRIMARIO = 0x2800

    /** "No hay mas atributos": es como TERMINA un descubrimiento, no un fallo. */
    private const val ATT_ERR_NO_HAY_MAS = 0x0A

    fun probar(
        context: Context,
        mac: String,
        vid: Int? = null,
        pid: Int? = null,
        segundosBarrido: Int = 5,
    ): List<String> {
        val salida = mutableListOf<String>()
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return listOf("ERROR: este radio no expone UsbManager")

        val objetivo = macABytes(mac)
            ?: return listOf("ERROR: MAC ilegible: $mac (se espera AA:BB:CC:DD:EE:FF)")

        val dongle = HciUsb.buscarDongle(um, vid, pid)
            ?: return listOf("ERROR: no encuentro ningun dongle HCI en el USB")

        val hci = HciUsb(um, dongle)
        val bomba = BombaHci(hci)
        val gestor = GestorL2cap(bomba)
        var handle = -1

        try {
            // --- 1. Abrir ---
            val traza = hci.abrir()
            salida += traza
            if (traza.any { it.startsWith("ERROR") }) return salida
            if (!hci.tieneAcl) {
                salida += "ERROR: este dongle no expone los dos BULK. Sin ellos NO hay datos."
                return salida
            }

            // Vaciar lo viejo ANTES de arrancar la bomba: un evento de una
            // sesion anterior casaria con el primer comando de esta.
            while (hci.leerEvento(150) != null) { /* descartar */ }

            if (!bomba.arrancar()) {
                salida += "ERROR: no arranco la bomba"
                return salida
            }
            gestor.arrancar()

            // --- 2. Reset y pool de buffers ---
            val ev = bomba.comando(HciUsb.CMD_RESET, timeoutMs = 3_000)
            salida += "RESET -> ${ev?.let { HciUsb.hex(it) } ?: "SIN RESPUESTA"}"
            if (ev == null) return salida

            var cuenta = bomba.configurarDesdeLe()
            salida += "control de flujo: $cuenta"
            if (cuenta.startsWith("el controlador no tiene pool LE")) {
                cuenta = bomba.configurarDesdeClasico()
                salida += "control de flujo (BR/EDR): $cuenta"
            }
            bomba.ajustarEntrada()

            // --- 3. Barrer para saber el tipo de direccion ---
            // Conectar con el tipo equivocado falla en silencio: el
            // controlador acepta el comando y el enlace nunca se establece.
            val tipoDir = tipoDeDireccion(bomba, objetivo, segundosBarrido, salida)
            if (tipoDir < 0) {
                salida += "ERROR: no se vio anunciarse a $mac en ${segundosBarrido}s; " +
                    "sin su tipo de direccion no se puede conectar sin adivinar"
                return salida
            }
            salida += "tipo de direccion: $tipoDir (${if (tipoDir == 0) "publica" else "aleatoria"})"

            // --- 4. Conectar ---
            handle = conectarLe(bomba, objetivo, tipoDir, salida)
            if (handle < 0) return salida
            salida += "ENLACE ESTABLECIDO, handle=0x${"%03X".format(handle)}"

            // --- 5. Canal ATT, sin negociar nada ---
            val canal = gestor.canalFijo(handle, L2cap.CID_ATT)
            val recibidas = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
            canal.alRecibir = { recibidas.offer(it) }
            salida += "canal ATT listo (CID 0x0004, fijo, sin configuracion): $canal"

            // --- 6. Exchange MTU: la primera ida y vuelta de datos ---
            val mtuPet = byteArrayOf(
                ATT_EXCHANGE_MTU_PET.toByte(),
                (MTU_CLIENTE and 0xFF).toByte(),
                ((MTU_CLIENTE shr 8) and 0xFF).toByte(),
            )
            salida += "-> ATT Exchange MTU (cliente $MTU_CLIENTE)"
            if (!canal.enviar(mtuPet)) {
                salida += "ERROR: no se pudo enviar por el canal ATT (${bomba.ultimoFallo})"
                return salida
            }
            val rspMtu = esperarRespuesta(recibidas, salida)
            if (rspMtu == null) {
                salida += "SIN RESPUESTA al Exchange MTU"
                salida += "   (llego hasta aqui: el enlace existe pero no vuelven datos ACL)"
            } else {
                salida += "<- ${HciUsb.hex(rspMtu)}"
                if (rspMtu.size >= 3 && (rspMtu[0].toInt() and 0xFF) == ATT_EXCHANGE_MTU_RSP) {
                    val suMtu = (rspMtu[1].toInt() and 0xFF) or ((rspMtu[2].toInt() and 0xFF) shl 8)
                    // La MTU efectiva es la MENOR de las dos. Usar la propia
                    // seria mandar PDU que el otro tiene derecho a tirar.
                    canal.mtuRemoto = minOf(MTU_CLIENTE, suMtu)
                    salida += "   servidor $suMtu -> MTU efectiva ${canal.mtuRemoto}"
                } else {
                    salida += "   " + interpretarAtt(rspMtu)
                }
            }

            // --- 7. Servicios primarios ---
            salida += "--- servicios primarios ---"
            salida += descubrirServicios(canal, recibidas)

            // --- 8. Cerrar ---
            val cierre = bomba.comando(
                HciUsb.CMD_DISCONNECT,
                byteArrayOf(
                    (handle and 0xFF).toByte(),
                    ((handle shr 8) and 0x0F).toByte(),
                    HciUsb.RAZON_TERMINADO_LOCAL.toByte(),
                ),
                2_000,
            )
            salida += "DISCONNECT -> ${cierre?.let { HciUsb.hex(it) } ?: "sin respuesta"}"
            // El credito de los paquetes en vuelo lo devuelve el evento de
            // desconexion, que la bomba procesa sola. Se le da un momento.
            bomba.esperarEvento(2_000) {
                (it[0].toInt() and 0xFF) == HciUsb.EVT_DISCONNECTION_COMPLETE
            }

            salida += "--- diagnostico ---"
            salida += bomba.diagnostico()
            salida += gestor.diagnostico()
            return salida
        } catch (e: Exception) {
            salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            salida += bomba.diagnostico()
            return salida
        } finally {
            runCatching { gestor.detener() }
            runCatching { bomba.detener() }
            runCatching { hci.cerrar() }
        }
    }

    /**
     * Barre hasta ver al objetivo y devuelve su tipo de direccion.
     *
     * -1 si no aparecio. No se supone "publica" por defecto: media hora
     * perdida es el precio de suponerlo mal, y el dato esta en el aire.
     */
    private fun tipoDeDireccion(
        bomba: BombaHci,
        objetivo: ByteArray,
        segundos: Int,
        salida: MutableList<String>,
    ): Int {
        bomba.comando(HciUsb.CMD_LE_SET_SCAN_PARAMS,
            byteArrayOf(0x00, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00), 2_000)
        bomba.comando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x01, 0x00), 2_000)

        var tipo = -1
        val e = bomba.esperarEvento(segundos * 1_000L) { ev ->
            if ((ev[0].toInt() and 0xFF) != HciUsb.EVT_LE_META) return@esperarEvento false
            if (ev.size < 13 || (ev[2].toInt() and 0xFF) != HciUsb.SUBEVT_LE_ADVERTISING_REPORT) {
                return@esperarEvento false
            }
            // 3E | largo | 02 | numReportes | tipoEvento | tipoDir | dir(6) ...
            val t = ev[5].toInt() and 0xFF
            val coincide = (0 until 6).all { i -> ev[6 + i] == objetivo[i] }
            if (coincide) tipo = t
            coincide
        }
        if (e != null) salida += "visto anunciarse: ${HciUsb.hex(e)}"

        bomba.comando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00), 2_000)
        return tipo
    }

    /**
     * LE_Create_Connection y espera del handle.
     *
     * OJO con lo que contesta cada cosa: este comando devuelve **Command
     * Status** ("lo intento"), no Command Complete. El enlace existe cuando
     * llega el subevento LE Connection Complete, y no antes. Tomar el Command
     * Status por exito lleva a hablarle a un handle que no existe.
     */
    private fun conectarLe(
        bomba: BombaHci,
        objetivo: ByteArray,
        tipoDir: Int,
        salida: MutableList<String>,
    ): Int {
        // Parametros, en orden y en little endian:
        //  intervaloBarrido(2) ventanaBarrido(2) filtro(1) tipoDirPar(1)
        //  dirPar(6) tipoDirPropia(1) intervaloMin(2) intervaloMax(2)
        //  latencia(2) supervision(2) ceMin(2) ceMax(2)
        val p = ByteArray(25)
        var i = 0
        fun le16(v: Int) {
            p[i++] = (v and 0xFF).toByte(); p[i++] = ((v shr 8) and 0xFF).toByte()
        }
        le16(0x0060)              // intervalo de barrido, 60 ms
        le16(0x0030)              // ventana, 30 ms
        p[i++] = 0x00             // sin lista blanca: se usa la direccion de abajo
        p[i++] = (tipoDir and 0xFF).toByte()
        for (b in objetivo) p[i++] = b
        p[i++] = 0x00             // direccion propia publica
        le16(0x0018)              // intervalo minimo, 30 ms
        le16(0x0028)              // intervalo maximo, 50 ms
        le16(0x0000)              // latencia
        le16(0x01F4)              // supervision, 5 s
        le16(0x0000)              // longitud minima de evento
        le16(0x0000)              // longitud maxima

        val st = bomba.comando(HciUsb.CMD_LE_CREATE_CONNECTION, p, 4_000)
        salida += "LE_CREATE_CONNECTION -> ${st?.let { HciUsb.hex(it) } ?: "SIN RESPUESTA"}"
        if (st == null) return -1
        val estado = when (st[0].toInt() and 0xFF) {
            HciUsb.EVT_COMMAND_STATUS -> st.getOrElse(2) { 0xFF.toByte() }.toInt() and 0xFF
            HciUsb.EVT_COMMAND_COMPLETE -> st.getOrElse(5) { 0xFF.toByte() }.toInt() and 0xFF
            else -> 0xFF
        }
        if (estado != 0) {
            salida += "   el controlador RECHAZO el intento (estado 0x${"%02X".format(estado)})"
            return -1
        }
        salida += "   aceptado; esperando el enlace (esto NO es todavia un enlace)"

        val ev = bomba.esperarEvento(ESPERA_ENLACE_MS) { e ->
            (e[0].toInt() and 0xFF) == HciUsb.EVT_LE_META && e.size >= 7 &&
                ((e[2].toInt() and 0xFF) == HciUsb.SUBEVT_LE_CONNECTION_COMPLETE ||
                    (e[2].toInt() and 0xFF) == HciUsb.SUBEVT_LE_ENHANCED_CONNECTION_COMPLETE)
        }
        if (ev == null) {
            salida += "   SIN LE Connection Complete en ${ESPERA_ENLACE_MS}ms"
            // Dejar un intento de conexion abierto deja el controlador
            // ocupado y el siguiente intento fallara sin decir por que.
            bomba.comando(HciUsb.CMD_LE_CREATE_CONNECTION_CANCEL, timeoutMs = 2_000)
            return -1
        }
        salida += "<- ${HciUsb.hex(ev)}"
        // 3E | largo | subevento | estado | handle(2) | rol | ...
        val estadoEnlace = ev[3].toInt() and 0xFF
        if (estadoEnlace != 0) {
            salida += "   el enlace FALLO (estado 0x${"%02X".format(estadoEnlace)})"
            return -1
        }
        return (ev[4].toInt() and 0xFF) or ((ev[5].toInt() and 0x0F) shl 8)
    }

    /**
     * Recorre los servicios primarios con Read By Group Type.
     *
     * El descubrimiento TERMINA con un Error Response 0x0A ("no hay mas"), que
     * no es un fallo sino la forma normal de acabar. Confundirlo con un error
     * hace que parezca que el aparato no tiene servicios.
     */
    private fun descubrirServicios(
        canal: GestorL2cap.Canal,
        recibidas: java.util.concurrent.BlockingQueue<ByteArray>,
    ): List<String> {
        val salida = mutableListOf<String>()
        var inicio = 0x0001
        var vueltas = 0

        while (inicio <= 0xFFFF && vueltas < MAX_VUELTAS_DESCUBRIMIENTO) {
            vueltas++
            val pet = byteArrayOf(
                ATT_READ_BY_GROUP_TYPE_PET.toByte(),
                (inicio and 0xFF).toByte(), ((inicio shr 8) and 0xFF).toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                (UUID_SERVICIO_PRIMARIO and 0xFF).toByte(),
                ((UUID_SERVICIO_PRIMARIO shr 8) and 0xFF).toByte(),
            )
            if (!canal.enviar(pet)) {
                salida += "ERROR: no se pudo enviar la peticion de descubrimiento"
                break
            }
            val r = esperarRespuesta(recibidas, salida)
            if (r == null) {
                salida += "SIN RESPUESTA al Read By Group Type desde 0x${"%04X".format(inicio)}"
                break
            }
            salida += "<- ${HciUsb.hex(r)}"

            val codigo = r[0].toInt() and 0xFF
            if (codigo == ATT_ERROR_RSP) {
                salida += "   " + interpretarAtt(r)
                break
            }
            if (codigo != ATT_READ_BY_GROUP_TYPE_RSP || r.size < 2) {
                salida += "   respuesta inesperada; se para para no inventar nada"
                break
            }

            val largo = r[1].toInt() and 0xFF
            if (largo < 4) {
                salida += "   largo de registro absurdo ($largo)"
                break
            }
            var i = 2
            var ultimo = inicio
            while (i + largo <= r.size) {
                val h1 = (r[i].toInt() and 0xFF) or ((r[i + 1].toInt() and 0xFF) shl 8)
                val h2 = (r[i + 2].toInt() and 0xFF) or ((r[i + 3].toInt() and 0xFF) shl 8)
                val valor = r.copyOfRange(i + 4, i + largo)
                val uuid = when (valor.size) {
                    2 -> "0x" + "%04X".format(
                        (valor[0].toInt() and 0xFF) or ((valor[1].toInt() and 0xFF) shl 8))
                    16 -> uuid128(valor)
                    else -> "crudo=" + HciUsb.hex(valor)
                }
                val nota = if (valor.size == 2 &&
                    ((valor[0].toInt() and 0xFF) or ((valor[1].toInt() and 0xFF) shl 8)) == SERVICIO_BMS
                ) "   <<< el servicio del BMS JBD/Xiaoxiang >>>" else ""
                salida += "  servicio $uuid  handles 0x${"%04X".format(h1)}..0x${"%04X".format(h2)}$nota"
                ultimo = h2
                i += largo
            }
            if (ultimo >= 0xFFFF || ultimo < inicio) break
            inicio = ultimo + 1
        }
        return salida
    }

    /**
     * Espera una RESPUESTA ATT, saltandose las notificaciones.
     *
     * Hace falta de verdad: un BMS que ya tenga notificaciones encendidas
     * empieza a mandarlas por su cuenta, y tomar la primera PDU que llegue
     * como respuesta desincroniza el dialogo entero — cada peticion recibiria
     * la respuesta de la anterior, que es el mismo fallo que ya se corrigio en
     * el ELM327 con el `drain()`.
     */
    private fun esperarRespuesta(
        recibidas: java.util.concurrent.BlockingQueue<ByteArray>,
        salida: MutableList<String>,
    ): ByteArray? {
        val hasta = System.currentTimeMillis() + ESPERA_ATT_MS
        while (System.currentTimeMillis() < hasta) {
            val r = recibidas.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (r.isNotEmpty() && (r[0].toInt() and 0xFF) == ATT_NOTIFICACION) {
                salida += "  (notificacion espontanea: ${HciUsb.hex(r)})"
                continue
            }
            return r
        }
        return null
    }

    private fun interpretarAtt(pdu: ByteArray): String {
        if (pdu.isEmpty()) return "PDU ATT vacia"
        return when (pdu[0].toInt() and 0xFF) {
            ATT_ERROR_RSP -> {
                if (pdu.size < 5) return "Error Response truncado"
                val cod = pdu[4].toInt() and 0xFF
                val texto = when (cod) {
                    0x01 -> "peticion no soportada"
                    0x02 -> "handle invalido"
                    0x05 -> "hace falta autenticacion"
                    0x08 -> "hace falta autorizacion"
                    ATT_ERR_NO_HAY_MAS -> "no hay mas atributos (FIN NORMAL del recorrido)"
                    0x0E -> "no se pudo procesar"
                    else -> "codigo 0x${"%02X".format(cod)} (no documentado aqui)"
                }
                "Error Response a 0x${"%02X".format(pdu[1].toInt() and 0xFF)}: $texto"
            }
            ATT_NOTIFICACION -> "Notificacion de handle " +
                "0x${"%04X".format((pdu.getOrElse(1) { 0 }.toInt() and 0xFF) or ((pdu.getOrElse(2) { 0 }.toInt() and 0xFF) shl 8))}"
            else -> "PDU ATT con opcode 0x${"%02X".format(pdu[0].toInt() and 0xFF)}"
        }
    }

    /** UUID de 128 bits: viene al reves en el aire. */
    private fun uuid128(b: ByteArray): String {
        val r = b.reversed().joinToString("") { "%02X".format(it) }
        return "${r.substring(0, 8)}-${r.substring(8, 12)}-${r.substring(12, 16)}-" +
            "${r.substring(16, 20)}-${r.substring(20)}"
    }

    /** MAC de texto a los 6 bytes en el orden del aire (al reves). */
    private fun macABytes(mac: String): ByteArray? {
        val partes = mac.trim().split(":", "-").filter { it.isNotBlank() }
        if (partes.size != 6) return null
        val b = ByteArray(6)
        for (i in 0 until 6) {
            val v = partes[i].toIntOrNull(16) ?: return null
            if (v !in 0..255) return null
            b[5 - i] = v.toByte()
        }
        return b
    }

    private const val MTU_CLIENTE = 247
    private const val ESPERA_ENLACE_MS = 12_000L
    private const val ESPERA_ATT_MS = 4_000L
    private const val MAX_VUELTAS_DESCUBRIMIENTO = 16
    private const val SERVICIO_BMS = 0xFF00
}
