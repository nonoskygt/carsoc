package com.nonosky.s2000dash.hci

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * El bucle de bombeo del HCI: un solo dueno del USB.
 *
 * ### Por que hace falta
 *
 * Hasta ahora el trato con el dongle era "mando un comando y leo la
 * respuesta", y con eso bastaba para interrogarlo y barrer BLE. En cuanto hay
 * enlaces ese modelo se cae: llegan cosas que **nadie pidio** —notificaciones
 * ATT, datos RFCOMM, desconexiones, creditos de flujo— y llegan mezcladas con
 * las respuestas a lo que si se pidio. Quien lea "la siguiente respuesta" se
 * quedara con una notificacion y creera que su comando fallo.
 *
 * ### Por que UN solo hilo toca el USB
 *
 * Dos hilos leyendo el mismo endpoint **se roban paquetes entre si**: cada
 * `bulkTransfer` se lleva un trozo, y el que lo recibe no tiene forma de
 * devolverlo. Media respuesta en un hilo y media en el otro, y ninguno de los
 * dos puede reensamblar nada. Aqui el hilo `bomba-hci` es el unico que
 * escribe y lee del aparato; todo lo demas se le pide por colas.
 *
 * ### Por que hay un SEGUNDO hilo para repartir
 *
 * Porque si la bomba entregara los datos llamando directamente a quien
 * escucha, y ese alguien contestara algo (una respuesta de configuracion
 * L2CAP, por ejemplo), se bloquearia esperando credito de flujo... y el
 * credito llega en un evento que solo la bomba puede leer. **Bloqueo mutuo.**
 * Por eso la bomba solo toca el USB y encola; el hilo `reparto-hci` es el que
 * llama a los oyentes, y ese si se puede bloquear sin parar el mundo.
 *
 * Los creditos (evento 0x13) y las desconexiones (0x05) se procesan **en la
 * bomba**, antes de encolar: son contabilidad, son baratos, y no pueden
 * quedarse esperando detras de un oyente lento.
 *
 * ### Aislamiento de fallos
 *
 * Los dos hilos van envueltos enteros. Una excepcion que escape de un hilo en
 * Android mata el proceso, y eso tumbaria de golpe el tablero, el TPMS, el
 * puente y el actualizador. Ya paso una vez con el DebugServer.
 */
class BombaHci(private val hci: HciUsb) {

    /**
     * Quien quiera enterarse de lo que llega.
     *
     * Los metodos se llaman desde el hilo de reparto, NUNCA desde la bomba.
     * Pueden tardar y pueden enviar. Lo que no pueden es llamar a los metodos
     * bloqueantes de esta clase ([comando], [esperarEvento]) desde dentro:
     * eso si se bloquearia a si mismo.
     */
    interface Oyente {
        fun alEvento(evento: ByteArray) {}
        fun alPdu(handle: Int, pdu: ByteArray) {}
        fun alCaerEnlace(handle: Int, razon: Int) {}
    }

    val flujo = ControlFlujoAcl()

    private val oyentes = CopyOnWriteArrayList<Oyente>()
    private val ensamblador = EnsambladorAcl()

    /** Trozos ACL ya con credito reservado, listos para salir por el BULK. */
    private val salida = LinkedBlockingQueue<ByteArray>()

    /** Lo que hay que repartir. Acotada: la bomba no puede bloquearse aqui. */
    private val reparto = ArrayBlockingQueue<Recibido>(REPARTO_MAX)

    private val porMandar = LinkedBlockingQueue<Peticion>()

    /** Un comando a la vez: es lo unico seguro sin mirar Num_HCI_Command_Packets. */
    private val cerrojoComando = ReentrantLock()

    @Volatile private var enCurso: Peticion? = null

    /** Un cerrojo por enlace para que dos PDU no intercalen sus trozos. */
    private val cerrojosEnvio = ConcurrentHashMap<Int, ReentrantLock>()

    @Volatile private var vivo = false
    private var hiloBomba: Thread? = null
    private var hiloReparto: Thread? = null

    private var reensambla = ReensamblaUsb.paraAcl(27)

    // --- contadores, para poder diagnosticar en remoto ---
    @Volatile
    var eventosLeidos = 0L
        private set

    @Volatile
    var pdusRecibidas = 0L
        private set

    @Volatile
    var trozosEnviados = 0L
        private set

    @Volatile
    var trozosFallidos = 0L
        private set

    @Volatile
    var repartosPerdidos = 0L
        private set

    @Volatile
    var vueltasConFallo = 0L
        private set

    @Volatile
    var ultimoFallo: String? = null
        private set

    private class Peticion(val opcode: Int, val parametros: ByteArray) {
        val listo = CountDownLatch(1)
        @Volatile var respuesta: ByteArray? = null
        @Volatile var escrito = Int.MIN_VALUE
    }

    private class Recibido(val evento: ByteArray?, val handle: Int, val pdu: ByteArray?)

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Arranca los dos hilos. El dongle ya tiene que estar abierto.
     *
     * Ojo: **no** hace Reset. Quien llame decide si reinicia el controlador,
     * porque un Reset vacia los buffers y obliga a reconfigurar el control de
     * flujo (y a olvidar los enlaces que hubiera).
     */
    fun arrancar(): Boolean {
        if (vivo) return true
        if (!hci.abierto) return false
        vivo = true

        hiloBomba = thread(name = "bomba-hci", isDaemon = true) {
            val buffer = ByteArray(hci.tamBloqueEntrada.coerceAtLeast(64))
            while (vivo) {
                val ok = runCatching { unaVuelta(buffer) }
                if (ok.isFailure) {
                    vueltasConFallo++
                    ultimoFallo = ok.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    Log.w(TAG, "vuelta de la bomba fallida: $ultimoFallo")
                    // Un fallo seguido a toda velocidad quemaria la CPU del
                    // radio mientras el usuario conduce. Se respira.
                    runCatching { Thread.sleep(50) }
                }
            }
        }

        hiloReparto = thread(name = "reparto-hci", isDaemon = true) {
            while (vivo) {
                runCatching {
                    val r = reparto.poll(200, TimeUnit.MILLISECONDS) ?: return@runCatching
                    repartir(r)
                }.onFailure { Log.w(TAG, "reparto fallido: ${it.message}") }
            }
        }
        return true
    }

    fun detener() {
        vivo = false
        runCatching { hiloBomba?.interrupt() }
        runCatching { hiloReparto?.interrupt() }
        hiloBomba = null
        hiloReparto = null
        // Nadie va a contestar ya: se sueltan los que esperaban una respuesta
        // en vez de dejarlos colgados hasta su plazo.
        enCurso?.listo?.countDown()
        enCurso = null
        salida.clear()
        reparto.clear()
    }

    fun suscribir(o: Oyente) {
        oyentes.addIfAbsent(o)
    }

    fun quitar(o: Oyente) {
        oyentes.remove(o)
    }

    // ------------------------------------------------------------------
    // El bucle
    // ------------------------------------------------------------------

    private fun unaVuelta(buffer: ByteArray) {
        // 1. Un comando, si alguien lo dejo pedido.
        porMandar.poll()?.let { p ->
            p.escrito = hci.mandarComando(p.opcode, p.parametros)
            if (p.escrito < 0) {
                // No salio del USB: no va a llegar respuesta nunca. Soltar ya
                // al que espera es mejor que dejarlo agotar su plazo sin saber
                // que el problema fue la escritura y no el controlador.
                enCurso = null
                p.listo.countDown()
            }
        }

        // 2. Los trozos ACL que ya tienen credito.
        var escritos = 0
        while (escritos < MAX_POR_VUELTA) {
            val t = salida.poll() ?: break
            val n = hci.escribirAclCrudo(t)
            if (n < 0) {
                trozosFallidos++
                ultimoFallo = "escritura ACL fallida (${t.size} bytes)"
                // El credito ya estaba reservado y el controlador nunca va a
                // devolverlo con un 0x13, porque nunca recibio el paquete.
                flujo.devolver(PaqueteAcl.handleDe(t), 1)
            } else {
                trozosEnviados++
            }
            escritos++
        }

        // 3. Eventos.
        var leidos = 0
        while (leidos < MAX_POR_VUELTA) {
            val e = hci.leerEvento(ESPERA_EVENTO_MS) ?: break
            eventosLeidos++
            contabilizar(e)
            encolar(Recibido(e, -1, null))
            leidos++
        }

        // 4. Datos ACL.
        leidos = 0
        while (leidos < MAX_POR_VUELTA) {
            val n = hci.leerAclCrudo(buffer, ESPERA_ACL_MS)
            if (n <= 0) break
            for (paquete in reensambla.alimentar(buffer, n)) {
                for (pdu in ensamblador.alimentar(paquete)) {
                    pdusRecibidas++
                    encolar(Recibido(null, pdu.handle, pdu.datos))
                }
            }
            leidos++
        }
    }

    /**
     * Contabilidad que NO puede esperar detras de un oyente lento.
     *
     * Los creditos y las caidas de enlace se procesan aqui, en la bomba, por
     * dos razones: son cuatro operaciones de aritmetica, y si se retrasaran
     * el envio se pararia esperando creditos que ya estaban concedidos.
     */
    private fun contabilizar(e: ByteArray) {
        if (e.isEmpty()) return
        when (e[0].toInt() and 0xFF) {
            HciUsb.EVT_NUM_COMPLETED_PACKETS -> flujo.procesarEvento(e)

            HciUsb.EVT_DISCONNECTION_COMPLETE -> {
                // 05 | largo | estado | handle(2) | razon
                if (e.size >= 6) {
                    val handle = (e[3].toInt() and 0xFF) or ((e[4].toInt() and 0x0F) shl 8)
                    val devueltos = flujo.olvidar(handle)
                    ensamblador.olvidar(handle)
                    cerrojosEnvio.remove(handle)
                    if (devueltos > 0) {
                        Log.i(TAG, "enlace 0x${"%03X".format(handle)} caido: " +
                            "$devueltos creditos recuperados")
                    }
                }
            }
        }
        casarConComando(e)
    }

    /**
     * Casa un evento con el comando que espera respuesta.
     *
     * Command Complete y Command Status llevan el opcode del comando al que
     * contestan, en sitios distintos:
     *
     * ```
     *  0E | largo | numPaquetes | opcode(2, LE) | estado | ...   Command Complete
     *  0F | largo | estado | numPaquetes | opcode(2, LE)         Command Status
     * ```
     *
     * Sin comparar el opcode, un evento espontaneo se tomaria por la
     * respuesta del comando en curso.
     */
    private fun casarConComando(e: ByteArray) {
        val p = enCurso ?: return
        val codigo = e[0].toInt() and 0xFF
        val opcode = when {
            codigo == HciUsb.EVT_COMMAND_COMPLETE && e.size >= 5 ->
                (e[3].toInt() and 0xFF) or ((e[4].toInt() and 0xFF) shl 8)
            codigo == HciUsb.EVT_COMMAND_STATUS && e.size >= 6 ->
                (e[4].toInt() and 0xFF) or ((e[5].toInt() and 0xFF) shl 8)
            else -> return
        }
        if (opcode != p.opcode) return
        p.respuesta = e
        enCurso = null
        p.listo.countDown()
    }

    private fun encolar(r: Recibido) {
        if (!reparto.offer(r)) {
            // Preferible perder una notificacion y contarla que bloquear la
            // bomba: si la bomba para, para tambien el control de flujo y el
            // enlace entero se cae en cadena.
            repartosPerdidos++
        }
    }

    private fun repartir(r: Recibido) {
        for (o in oyentes) {
            runCatching {
                if (r.evento != null) {
                    o.alEvento(r.evento)
                    if ((r.evento[0].toInt() and 0xFF) == HciUsb.EVT_DISCONNECTION_COMPLETE &&
                        r.evento.size >= 6
                    ) {
                        val handle = (r.evento[3].toInt() and 0xFF) or ((r.evento[4].toInt() and 0x0F) shl 8)
                        o.alCaerEnlace(handle, r.evento[5].toInt() and 0xFF)
                    }
                } else if (r.pdu != null) {
                    o.alPdu(r.handle, r.pdu)
                }
            }.onFailure { Log.w(TAG, "un oyente lanzo: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    /**
     * Manda un comando HCI y espera SU respuesta.
     *
     * Devuelve el evento Command Complete o Command Status, o null si no
     * llego a tiempo. Ojo con la diferencia, que se paga cara:
     *
     *  - **Command Complete** significa que el comando termino.
     *  - **Command Status** significa que el comando fue ACEPTADO y que el
     *    resultado de verdad llegara despues en otro evento. Es lo que
     *    contestan `LE_Create_Connection` y `Create_Connection`: el enlace no
     *    existe hasta que llega el Connection Complete, y quien tome el
     *    Command Status por exito intentara hablar con un handle que aun no
     *    existe.
     *
     * Se serializa: un comando a la vez. La especificacion permite mas si el
     * controlador lo dice en `Num_HCI_Command_Packets`, pero mandar de mas es
     * exactamente como se pierde la sincronia entre comando y respuesta.
     */
    fun comando(opcode: Int, parametros: ByteArray = ByteArray(0), timeoutMs: Long = 3_000): ByteArray? {
        if (!vivo) return null
        cerrojoComando.lock()
        try {
            val p = Peticion(opcode, parametros)
            enCurso = p
            porMandar.offer(p)
            val llego = p.listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!llego) {
                // Se abandona la espera, pero si la respuesta llega tarde se
                // repartira como un evento mas. Mejor eso que dejar el hueco
                // ocupado y que el siguiente comando case con la respuesta del
                // anterior — el desfase de un turno no se corrige solo.
                enCurso = null
            }
            return p.respuesta
        } finally {
            cerrojoComando.unlock()
        }
    }

    /**
     * Espera un evento que cumpla un filtro. Para lo que llega DESPUES del
     * Command Status: Connection Complete, Encryption Change, etc.
     */
    fun esperarEvento(timeoutMs: Long, filtro: (ByteArray) -> Boolean): ByteArray? {
        val encontrado = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val listo = CountDownLatch(1)
        val o = object : Oyente {
            override fun alEvento(evento: ByteArray) {
                if (encontrado.get() == null && runCatching { filtro(evento) }.getOrDefault(false)) {
                    encontrado.set(evento)
                    listo.countDown()
                }
            }
        }
        suscribir(o)
        try {
            listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            return encontrado.get()
        } finally {
            quitar(o)
        }
    }

    // ------------------------------------------------------------------
    // Envio de datos
    // ------------------------------------------------------------------

    /**
     * Manda una PDU L2CAP por un enlace: la arma, la trocea y la encola.
     *
     * `canal` es el CID: 0x0004 para ATT, 0x0001 para la senalizacion
     * clasica, o el dinamico que haya repartido la senalizacion.
     *
     * Tres cosas pasan aqui y las tres importan:
     *
     *  1. **Cerrojo por enlace.** Los trozos de una PDU tienen que salir
     *     seguidos. Si dos hilos enviaran a la vez por el mismo handle, sus
     *     trozos se intercalarian y el otro lado reensamblaria una PDU con
     *     mitades de las dos. No da error: da datos falsos.
     *  2. **Un credito por trozo, no todos de golpe.** Reservar los N de una
     *     PDU larga se bloquearia para siempre si N fuera mayor que el pool
     *     entero (15 buffers en LE); pidiendo de uno en uno siempre se avanza.
     *  3. **Si falta credito a mitad, el enlace queda roto.** Los trozos ya
     *     mandados no se pueden recoger, asi que el otro lado se queda con una
     *     PDU incompleta. Se devuelve false y se anota: quien llame debe tirar
     *     el enlace, no reintentar el trozo.
     */
    fun enviarAcl(handle: Int, canal: Int, datos: ByteArray, timeoutMs: Long = 5_000): Boolean {
        if (!vivo) return false
        if (!hci.tieneAcl) {
            ultimoFallo = "este dongle no expone endpoints BULK: no hay camino de datos"
            return false
        }

        val pdu = L2cap.armar(canal, datos)
        val max = PaqueteAcl.maxDatosSeguro(flujo.tamPaquete, hci.tamBloqueSalida)
        val trozos = PaqueteAcl.trocear(handle, pdu, max)

        val cerrojo = cerrojosEnvio.getOrPut(handle) { ReentrantLock() }
        cerrojo.lock()
        try {
            for ((i, t) in trozos.withIndex()) {
                if (!flujo.reservar(handle, timeoutMs)) {
                    ultimoFallo = "sin creditos ACL tras ${timeoutMs}ms en el trozo $i de " +
                        "${trozos.size} (enlace 0x${"%03X".format(handle)} desincronizado)"
                    Log.w(TAG, ultimoFallo!!)
                    return false
                }
                salida.offer(t)
            }
            return true
        } finally {
            cerrojo.unlock()
        }
    }

    /**
     * Espera una PDU de un enlace y un CID concretos, y devuelve su carga.
     *
     * Existe para los dialogos de pregunta-respuesta (una peticion ATT, una
     * respuesta de senalizacion). Lo asincrono —notificaciones, datos que
     * llegan solos— se atiende registrando un [Oyente], que es para lo que
     * esta la bomba.
     */
    fun esperarPdu(handle: Int, cid: Int, timeoutMs: Long, filtro: ((ByteArray) -> Boolean)? = null): ByteArray? {
        val encontrado = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val listo = CountDownLatch(1)
        val o = object : Oyente {
            override fun alPdu(h: Int, pdu: ByteArray) {
                if (h != handle || L2cap.cidDe(pdu) != cid) return
                val carga = L2cap.cargaDe(pdu)
                if (filtro != null && !runCatching { filtro(carga) }.getOrDefault(false)) return
                if (encontrado.compareAndSet(null, carga)) listo.countDown()
            }
        }
        suscribir(o)
        try {
            listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            return encontrado.get()
        } finally {
            quitar(o)
        }
    }

    // ------------------------------------------------------------------
    // Control de flujo: preguntarle al controlador cuanto aguanta
    // ------------------------------------------------------------------

    /**
     * Lee el pool LE y deja el control de flujo listo para enlaces LE.
     *
     * Respuesta medida en este dongle: `0E07010220001B000F`
     * -> paquete de 0x001B = **27 bytes**, 0x0F = **15 buffers**.
     *
     * Si el tamano viene 0, el controlador NO tiene pool LE propio y usa el de
     * BR/EDR: hay que preguntar por [configurarDesdeClasico]. Es un caso real
     * de la especificacion, no una rareza.
     */
    fun configurarDesdeLe(): String {
        val e = comando(HciUsb.CMD_LE_READ_BUFFER_SIZE)
            ?: return "LE_READ_BUFFER_SIZE: sin respuesta"
        // 0E | largo | num | opcode(2) | estado | tam(2) | numPaquetes(1)
        //  1     1       1      2          1        2         1        = 9 bytes
        //
        // Nueve, no diez. Con el tope en 10 este dongle —que contesta
        // exactamente 0E07010220001B000F— se rechazaba como "respuesta
        // corta", el control de flujo se quedaba en cero buffers, y entonces
        // NINGUN paquete ACL podia salir: la conexion LE se establecia bien y
        // luego no se podia mandar ni el Exchange MTU. Un byte de mas en una
        // comparacion tumbaba toda la capa de datos.
        if (e.size < 9) return "LE_READ_BUFFER_SIZE: respuesta corta ${HciUsb.hex(e)}"
        if ((e[5].toInt() and 0xFF) != 0) return "LE_READ_BUFFER_SIZE: estado ${e[5].toInt() and 0xFF}"
        val tam = (e[6].toInt() and 0xFF) or ((e[7].toInt() and 0xFF) shl 8)
        val num = e[8].toInt() and 0xFF
        if (tam == 0 || num == 0) {
            return "el controlador no tiene pool LE propio (tam=$tam num=$num): usar el de BR/EDR"
        }
        flujo.configurar(num, tam)
        return "pool LE: $num buffers de $tam bytes"
    }

    /**
     * Lee el pool BR/EDR. Es el que hace falta para el enlace clasico del
     * adaptador OBD, y tambien el de reserva si el LE no tiene pool propio.
     */
    fun configurarDesdeClasico(): String {
        val e = comando(HciUsb.CMD_READ_BUFFER_SIZE)
            ?: return "READ_BUFFER_SIZE: sin respuesta"
        // 0E | largo | num | opcode(2) | estado | acl(2) | sco(1) | numAcl(2) | numSco(2)
        if (e.size < 13) return "READ_BUFFER_SIZE: respuesta corta ${HciUsb.hex(e)}"
        if ((e[5].toInt() and 0xFF) != 0) return "READ_BUFFER_SIZE: estado ${e[5].toInt() and 0xFF}"
        val tam = (e[6].toInt() and 0xFF) or ((e[7].toInt() and 0xFF) shl 8)
        val num = (e[9].toInt() and 0xFF) or ((e[10].toInt() and 0xFF) shl 8)
        if (tam == 0 || num == 0) return "READ_BUFFER_SIZE devolvio pool vacio (tam=$tam num=$num)"
        flujo.configurar(num, tam)
        return "pool BR/EDR: $num buffers de $tam bytes"
    }

    /**
     * Ajusta el reensamblador de entrada al tamano que dijo el controlador.
     *
     * Se llama despues de configurar el pool: un paquete mas grande que eso no
     * puede venir del controlador, y aceptarlo seria tragarse un flujo
     * desalineado como si fuera un dato bueno.
     */
    fun ajustarEntrada() {
        reensambla = ReensamblaUsb.paraAcl(flujo.tamPaquete)
    }

    fun diagnostico(): List<String> = listOf(
        "bomba: ${if (vivo) "viva" else "parada"}",
        "eventos leidos: $eventosLeidos, PDU recibidas: $pdusRecibidas",
        "trozos enviados: $trozosEnviados, fallidos: $trozosFallidos",
        "cola de salida: ${salida.size}, cola de reparto: ${reparto.size}, " +
            "perdidos por cola llena: $repartosPerdidos",
        "vueltas con fallo: $vueltasConFallo, ultimo: ${ultimoFallo ?: "ninguno"}",
        "reensamblado USB: ${reensambla.completos} paquetes, " +
            "${reensambla.longitudesImposibles} longitudes imposibles, " +
            "${reensambla.pendientes()} bytes a medias",
    ) + ensamblador.diagnostico() + flujo.diagnostico()

    private companion object {
        const val TAG = "BombaHci"

        /**
         * Plazos cortos: la vuelta entera cuesta como mucho la suma de los
         * dos, y de eso depende cuanto tarda en salir un trozo que espera en
         * la cola. Demasiado corto quema CPU; demasiado largo hace lento el
         * dialogo con el ELM327, que son cientos de idas y vueltas por minuto.
         */
        const val ESPERA_EVENTO_MS = 15
        const val ESPERA_ACL_MS = 15

        /** Tope de operaciones por vuelta, para que ninguna ahogue a las otras. */
        const val MAX_POR_VUELTA = 8

        const val REPARTO_MAX = 256
    }
}
