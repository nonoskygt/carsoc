package com.nonosky.s2000dash.rfcomm

import android.util.Log
import com.nonosky.s2000dash.l2cap.CanalL2cap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Un canal de datos RFCOMM sobre un canal L2CAP ya abierto en el PSM 0x0003.
 *
 * Lo minimo que hace falta para hablar texto con un ELM327, y ni una linea
 * mas: multiplexor, un solo DLC, control de flujo por credito si el otro
 * extremo lo quiere, y un buffer de entrada. No hay servidor, ni varios
 * DLC simultaneos, ni RPN, ni control de linea — un adaptador OBD no usa
 * nada de eso.
 *
 * Secuencia de apertura, y **cada paso tiene su senal de que funciono**:
 *
 * ```
 *   1. SABM  DLCI 0        -> UA DLCI 0        el multiplexor esta vivo
 *   2. PN    (DLCI destino)-> PN respuesta     tamano de trama y creditos
 *   3. SABM  DLCI destino  -> UA               el canal existe
 *                          -> DM               ese canal NO existe: probar otro
 *   4. MSC   (DLCI destino)-> MSC respuesta    la linea esta lista
 *   5. UIH con "ATZ\r"     -> UIH con "ELM327" ya hay adaptador al otro lado
 * ```
 *
 * El paso 3 es el que decide si hay que buscar el canal: un DM llega
 * inmediato y es un "no" limpio, mientras que un canal correcto contesta UA.
 * Por eso se puede tantear 1, 2 y 3 sin SDP.
 */
class CanalRfcomm(private val l2cap: CanalL2cap) {

    val traza = CopyOnWriteArrayList<String>()

    @Volatile
    var abierto = false
        private set

    /** El canal de servidor que quedo abierto. Diagnostico puro. */
    @Volatile
    var canalAbierto: Int = -1
        private set

    @Volatile
    var creditoNegociado = false
        private set

    @Volatile
    private var dlci = -1

    /** Creditos que ELLOS nos dieron: cuantas tramas podemos mandar. */
    @Volatile
    private var creditosEnvio = 0

    /** Creditos que NOSOTROS les dimos: cuantas tramas pueden mandarnos. */
    @Volatile
    private var creditosRecepcion = 0

    private var maxTrama = 127

    private val entrada = ByteArrayOutputStream()
    private val respuestas = LinkedBlockingQueue<TramaRfcomm.Trama>(64)
    private val mccs = LinkedBlockingQueue<Pair<Int, ByteArray>>(32)

    @Volatile
    private var vivo = false
    private var lector: Thread? = null

    /** Restos de una SDU partida, para reensamblar por longitud. */
    private var pendiente = ByteArray(0)

    // ------------------------------------------------------------------ apertura

    /**
     * Etapa 4a: arrancar el multiplexor.
     *
     * `03 3F 01 1C` sale, `03 73 01 D7` vuelve. Esos dos vectores son los
     * mas publicados de RFCOMM y sirven de prueba de que el FCS y el bit C/R
     * estan bien: si el UA no vuelve, el problema esta aqui y no mas arriba.
     */
    fun arrancarMultiplexor(timeoutMs: Long = 5_000): Boolean {
        arrancarLector()
        anotar("-> SABM DLCI 0  ${TramaRfcomm.hex(TramaRfcomm.sabm(0))}")
        l2cap.enviar(TramaRfcomm.sabm(0))
        val r = esperarPara(0, timeoutMs)
        if (r == null) {
            anotar("<- nada. El multiplexor no arranco (¿FCS? ¿bit C/R? ¿L2CAP mudo?)")
            return false
        }
        anotar("<- ${TramaRfcomm.nombre(r.tipo)} DLCI 0")
        return r.tipo == TramaRfcomm.UA
    }

    /**
     * Etapa 4b: abrir un canal de datos, tanteando [canales] en orden.
     *
     * Devuelve el canal que abrio, o -1. Un DM significa "ese canal no
     * existe aqui" y se pasa al siguiente sin drama. Un silencio, en cambio,
     * es sospechoso: puede ser que el modulo se haya atragantado, y entonces
     * lo que hay que rehacer es el L2CAP, no insistir.
     */
    fun abrirCanal(canales: List<Int> = listOf(1, 2, 3), timeoutMs: Long = 4_000): Int {
        for (canal in canales) {
            val d = TramaRfcomm.dlciDeCanal(canal)
            anotar("--- probando canal $canal (DLCI $d) ---")

            negociarParametros(d)

            anotar("-> SABM DLCI $d  ${TramaRfcomm.hex(TramaRfcomm.sabm(d))}")
            l2cap.enviar(TramaRfcomm.sabm(d))
            val r = esperarPara(d, timeoutMs)
            when {
                r == null -> {
                    anotar("<- silencio en el canal $canal. Si pasa en todos, rehacer el L2CAP")
                }
                r.tipo == TramaRfcomm.UA -> {
                    anotar("<- UA: canal $canal ABIERTO")
                    dlci = d
                    canalAbierto = canal
                    abierto = true
                    mandarMsc(d)
                    return canal
                }
                r.tipo == TramaRfcomm.DM -> {
                    anotar("<- DM: el canal $canal no existe en este aparato")
                }
                else -> {
                    anotar("<- ${TramaRfcomm.nombre(r.tipo)} inesperado en el canal $canal")
                }
            }
        }
        return -1
    }

    /**
     * PN: tamano de trama y control de flujo por credito.
     *
     * Es opcional en la especificacion. Se manda igual porque fija N1 en 127
     * —lo que mantiene el campo de longitud en un solo byte— y porque es la
     * unica forma de encender el credito. Si el otro extremo contesta NSC
     * ("no soporto ese comando") o no contesta, se sigue adelante con los
     * valores por defecto: no vale la pena morir por una negociacion
     * opcional.
     */
    private fun negociarParametros(d: Int) {
        val v = TramaRfcomm.valorPn(d, pedirCredito = true, maxTrama = 127, creditosIniciales = 7)
        val trama = TramaRfcomm.uih(0, TramaRfcomm.mcc(TramaRfcomm.MCC_PN_CMD, v))
        anotar("-> PN DLCI $d pidiendo credito, N1=127  ${TramaRfcomm.hex(trama)}")
        l2cap.enviar(trama)

        val r = esperarMcc(TramaRfcomm.MCC_PN_RSP, 2_000)
        if (r == null) {
            anotar("<- sin respuesta a PN: seguimos con los valores por defecto, sin credito")
            return
        }
        // valor: dlci, CL|I, prioridad, T1, N1 lo, N1 hi, retransmisiones, creditos
        if (r.size < 8) {
            anotar("<- PN respuesta truncada (${r.size} bytes): se ignora")
            return
        }
        val cl = (r[1].toInt() and 0xF0)
        val n1 = ((r[5].toInt() and 0xFF) shl 8) or (r[4].toInt() and 0xFF)
        val cr = r[7].toInt() and 0x07
        creditoNegociado = cl == 0xE0
        // Nunca por encima de lo que cabe en una SDU de L2CAP: la trama
        // completa son N1 mas seis bytes de sobrecarga. Si el L2CAP quedo
        // con un MTU corto, mandar N1=127 produce tramas que se parten y
        // el otro extremo las tira sin decir nada.
        val tope = (l2cap.mtuSalida - 6).coerceAtLeast(23)
        maxTrama = (if (n1 in 23..127) n1 else 127).coerceAtMost(tope)
        if (creditoNegociado) {
            creditosEnvio = cr
            creditosRecepcion = 7
            anotar("<- PN respuesta: CREDITO ACEPTADO, N1=$n1, nos dan $cr creditos")
        } else {
            anotar("<- PN respuesta: sin credito (CL=0x${String.format("%02X", cl)}), N1=$n1")
        }
    }

    /**
     * MSC: decirle que la linea virtual esta lista.
     *
     * Sin esto muchos modulos abren el DLCI y no mandan nada nunca. Se
     * espera la respuesta pero no se exige: alguno la manda tarde y no vale
     * la pena abortar por eso.
     */
    private fun mandarMsc(d: Int) {
        val trama = TramaRfcomm.uih(0, TramaRfcomm.mcc(TramaRfcomm.MCC_MSC_CMD, TramaRfcomm.valorMsc(d)))
        anotar("-> MSC DLCI $d senales=0x8D (RTC+RTR+DV)  ${TramaRfcomm.hex(trama)}")
        l2cap.enviar(trama)
        val r = esperarMcc(TramaRfcomm.MCC_MSC_RSP, 2_000)
        anotar(if (r != null) "<- MSC respuesta: la linea esta lista" else "<- sin respuesta a MSC (seguimos igual)")
    }

    // -------------------------------------------------------------------- datos

    /**
     * Manda datos por el canal abierto, troceando a [maxTrama].
     *
     * Con credito activo, cada trama gasta uno. Si se agotan hay que esperar
     * a que devuelvan: quedarse sin creditos y mandar igual es lo que hace
     * que un modulo cierre el enlace sin explicacion.
     */
    fun escribir(datos: ByteArray) {
        if (!abierto) throw IOException("RFCOMM no esta abierto")
        var off = 0
        while (off < datos.size) {
            val n = minOf(maxTrama, datos.size - off)
            val trozo = datos.copyOfRange(off, off + n)
            off += n

            if (creditoNegociado) {
                if (!esperarCredito(CREDITO_TIMEOUT_MS)) {
                    throw IOException("RFCOMM sin creditos de envio tras ${CREDITO_TIMEOUT_MS} ms")
                }
                creditosEnvio--
            }
            // Al mandar datos se aprovecha para regalar creditos de vuelta:
            // ahorra una trama de credito suelta por cada intercambio.
            val regalo = if (creditoNegociado) reponerRecepcion() else null
            l2cap.enviar(TramaRfcomm.uih(dlci, trozo, regalo))
        }
    }

    /** Cuantos bytes hay ya leidos y sin consumir. */
    fun disponibles(): Int = synchronized(entrada) { entrada.size() }

    /**
     * Saca hasta [max] bytes de lo recibido. Devuelve 0 si no hay nada.
     *
     * No bloquea: el hilo lector es quien llena. Quien quiera esperar, que
     * sondee — asi es como lo hace `SppTransport` y el codigo de arriba no
     * nota la diferencia.
     */
    fun leer(max: Int): ByteArray = synchronized(entrada) {
        if (entrada.size() == 0) return ByteArray(0)
        val todo = entrada.toByteArray()
        entrada.reset()
        if (todo.size <= max) return todo
        entrada.write(todo, max, todo.size - max)
        return todo.copyOfRange(0, max)
    }

    /** Tira lo que haya sin leer. */
    fun vaciar() = synchronized(entrada) { entrada.reset() }

    // ------------------------------------------------------------------- cierre

    fun cerrar() {
        runCatching {
            if (abierto && dlci >= 0) {
                anotar("-> DISC DLCI $dlci")
                l2cap.enviar(TramaRfcomm.disc(dlci))
            }
            if (vivo) {
                anotar("-> DISC DLCI 0 (cierra el multiplexor)")
                l2cap.enviar(TramaRfcomm.disc(0))
            }
        }
        abierto = false
        vivo = false
        runCatching { lector?.join(500) }
        lector = null
        runCatching { l2cap.close() }
    }

    // ------------------------------------------------------------- hilo lector

    private fun arrancarLector() {
        if (vivo) return
        vivo = true
        // Envuelto entero: una excepcion que escape de un hilo en Android
        // mata el proceso, y con el se irian el TPMS y el tablero.
        lector = thread(name = "rfcomm-lector", isDaemon = true) {
            while (vivo) {
                val sdu = runCatching { l2cap.recibir(300) }
                    .onFailure { Log.w(TAG, "L2CAP fallo al recibir: ${it.message}") }
                    .getOrNull()
                if (sdu == null) {
                    if (!l2cap.abierto) {
                        anotar("el canal L2CAP se cerro por debajo")
                        abierto = false
                        vivo = false
                    }
                    continue
                }
                runCatching { procesar(sdu) }
                    .onFailure { Log.w(TAG, "procesar fallo: ${it.message}") }
            }
        }
    }

    /**
     * Reensambla y desarma. Una SDU deberia traer una trama entera, pero se
     * reensambla por longitud igual: el endpoint BULK del dongle va a partir
     * los paquetes ACL igual que el de interrupcion partia los eventos, y esa
     * leccion ya se pago una vez en este proyecto.
     */
    private fun procesar(sdu: ByteArray) {
        val buf = if (pendiente.isEmpty()) sdu else pendiente + sdu
        var i = 0
        while (i < buf.size) {
            val t = TramaRfcomm.decodificar(buf, i, buf.size, creditoNegociado) ?: break
            i += t.bytesConsumidos
            if (!t.fcsOk) {
                // Una trama con FCS malo NO se procesa. Actuar sobre bytes
                // corruptos es peor que perderlos.
                anotar("trama con FCS malo, descartada (DLCI ${t.dlci}, ${TramaRfcomm.nombre(t.tipo)})")
                continue
            }
            manejar(t)
        }
        pendiente = if (i < buf.size) buf.copyOfRange(i, buf.size) else ByteArray(0)
    }

    private fun manejar(t: TramaRfcomm.Trama) {
        // El credito llega pegado a cualquier UIH del canal de datos,
        // incluso a uno de longitud cero que solo existe para dar credito.
        t.credito?.let { if (it > 0) creditosEnvio += it }

        when (t.tipo) {
            TramaRfcomm.UIH -> {
                if (t.dlci == 0) {
                    manejarMcc(t.info)
                } else if (t.dlci == dlci && t.info.isNotEmpty()) {
                    synchronized(entrada) { entrada.write(t.info) }
                    if (creditoNegociado) {
                        creditosRecepcion--
                        if (creditosRecepcion <= UMBRAL_CREDITO) darCreditos()
                    }
                }
            }
            TramaRfcomm.UA, TramaRfcomm.DM -> respuestas.offer(t)
            TramaRfcomm.DISC -> {
                // Nos cierran. Se contesta UA porque callarse deja al otro
                // extremo reintentando y ensuciando el enlace.
                anotar("<- DISC DLCI ${t.dlci}: nos cierran")
                runCatching { l2cap.enviar(TramaRfcomm.ua(t.dlci)) }
                if (t.dlci == dlci || t.dlci == 0) abierto = false
            }
            TramaRfcomm.SABM -> {
                // No somos servidor: cualquier DLCI entrante se rechaza.
                runCatching { l2cap.enviar(TramaRfcomm.dm(t.dlci)) }
            }
        }
    }

    private fun manejarMcc(info: ByteArray) {
        if (info.size < 2) return
        val tipo = info[0].toInt() and 0xFF
        val largo = (info[1].toInt() and 0xFF) shr 1
        val valor = if (info.size >= 2 + largo) info.copyOfRange(2, 2 + largo) else ByteArray(0)

        when (tipo) {
            TramaRfcomm.MCC_PN_CMD -> {
                // Nos negocian a nosotros: se acepta lo que pidan, no somos
                // quisquillosos con un adaptador OBD.
                val eco = if (valor.size >= 8) valor.copyOf() else return
                eco[1] = if ((eco[1].toInt() and 0xF0) == 0xF0) 0xE0.toByte() else 0x00
                // C/R = 1 tambien aqui: en un UIH la direccion lleva SIEMPRE
                // el bit del iniciador, y quien distingue comando de
                // respuesta es el bit C/R del octeto de tipo del MCC, no la
                // direccion. Mandar 0x01 en vez de 0x03 aqui hace que el
                // otro extremo ignore la respuesta en silencio.
                l2cap.enviar(TramaRfcomm.uih(0, TramaRfcomm.mcc(TramaRfcomm.MCC_PN_RSP, eco)))
            }
            TramaRfcomm.MCC_MSC_CMD -> {
                // Hay que hacerle eco: es lo que espera el otro extremo.
                l2cap.enviar(TramaRfcomm.uih(0, TramaRfcomm.mcc(TramaRfcomm.MCC_MSC_RSP, valor)))
                anotar("<- MSC comando, contestado")
            }
            TramaRfcomm.MCC_CLD_CMD -> {
                anotar("<- CLD: nos cierran el multiplexor")
                abierto = false
            }
            TramaRfcomm.MCC_NSC_RSP -> {
                anotar("<- NSC: no soporta un comando del multiplexor. Seguimos con defectos")
            }
        }
        mccs.offer(tipo to valor)
    }

    private fun esperarMcc(tipo: Int, timeoutMs: Long): ByteArray? {
        val hasta = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < hasta) {
            val m = runCatching { mccs.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            if (m.first == tipo) return m.second
        }
        return null
    }

    private fun esperarPara(d: Int, timeoutMs: Long): TramaRfcomm.Trama? {
        val hasta = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < hasta) {
            val t = runCatching { respuestas.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            if (t.dlci == d) return t
        }
        return null
    }

    // ------------------------------------------------------------------ credito

    /**
     * Espera a que devuelvan credito. Sondeo simple a proposito.
     *
     * Un `wait`/`notify` seria mas fino, pero aqui se mandan tres o cuatro
     * tramas por segundo: el sondeo cuesta nada y no puede perder una
     * notificacion, que es el error clasico de la version "elegante".
     */
    private fun esperarCredito(timeoutMs: Long): Boolean {
        val hasta = System.currentTimeMillis() + timeoutMs
        while (creditosEnvio <= 0 && System.currentTimeMillis() < hasta) {
            runCatching { Thread.sleep(5) }.onFailure { return false }
        }
        return creditosEnvio > 0
    }

    /** Cuantos creditos regalar pegados al proximo envio, o `null`. */
    private fun reponerRecepcion(): Int? {
        val faltan = VENTANA_CREDITO - creditosRecepcion
        if (faltan <= 0) return null
        creditosRecepcion += faltan
        return faltan
    }

    /** Trama de solo credito: longitud 0, P/F puesto y el credito delante. */
    private fun darCreditos() {
        val faltan = VENTANA_CREDITO - creditosRecepcion
        if (faltan <= 0) return
        creditosRecepcion += faltan
        runCatching { l2cap.enviar(TramaRfcomm.uih(dlci, ByteArray(0), faltan)) }
    }

    private fun anotar(t: String) {
        Log.i(TAG, t)
        if (traza.size > 200) traza.removeAt(0)
        traza.add(t)
    }

    private companion object {
        const val TAG = "CanalRfcomm"

        /** Cuantas tramas se les deja tener en vuelo hacia nosotros. */
        const val VENTANA_CREDITO = 32

        /** Cuando bajan de aqui, se reponen sin esperar a mandar datos. */
        const val UMBRAL_CREDITO = 8

        /**
         * Si el otro extremo no devuelve creditos en este plazo, algo se
         * rompio. Fallar es mejor que colgarse: un tablero congelado no dice
         * nada, una excepcion con motivo si.
         */
        const val CREDITO_TIMEOUT_MS = 3_000L
    }
}
