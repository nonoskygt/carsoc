package com.nonosky.s2000dash.rfcomm

/**
 * El codec de RFCOMM: bytes a tramas y al reves. Nada de I/O.
 *
 * RFCOMM es TS 07.10 (el multiplexor de los modem GSM) adaptado a Bluetooth.
 * Una trama es:
 *
 * ```
 *   direccion(1) | control(1) | longitud(1 o 2) | [credito(1)] | info(0..N) | FCS(1)
 * ```
 *
 * Tres detalles de esta capa son donde se pierde la tarde, y estan los tres
 * verificados con vectores reales en `TramaRfcommTest`:
 *
 * **1. El FCS cubre tramos distintos segun el tipo.** En SABM, UA, DM y DISC
 * se calcula sobre los TRES primeros bytes; en UIH, solo sobre los DOS
 * primeros. Calcularlo siempre igual da tramas que el otro extremo tira sin
 * decir nada, y el sintoma es "manda y no contesta".
 *
 * **2. El bit C/R de la direccion no es "comando o respuesta" a secas.** Vale
 * 1 para los comandos del INICIADOR del multiplexor y para las respuestas del
 * RESPONDEDOR; vale 0 para los comandos del respondedor y las respuestas del
 * iniciador. Nosotros siempre iniciamos, asi que mandamos 1 en todo menos en
 * las respuestas. Al recibir se ignora: se compara solo el DLCI.
 *
 * **3. El DLCI no es el canal.** `DLCI = (canal shl 1) or direccion`, y la
 * direccion es 0 para el iniciador. O sea que el canal 1 del servidor —donde
 * viven casi todos los ELM327— es el **DLCI 2**, y su byte de direccion es
 * 0x0B. Mandar un SABM al DLCI 1 es preguntar por un canal que no existe.
 *
 * Ancla de correccion: las dos tramas mas publicadas de RFCOMM son el SABM
 * de arranque del multiplexor, `03 3F 01 1C`, y su UA, `03 73 01 D7`. La
 * tabla CRC generada aqui reproduce las dos.
 */
object TramaRfcomm {

    // --------------------------------------------------------------- controles
    // El bit 0x10 es P/F (poll/final) y va aparte del tipo.

    const val SABM = 0x2F
    const val UA = 0x63
    const val DM = 0x0F
    const val DISC = 0x43
    const val UIH = 0xEF

    /** Quita el bit P/F para quedarse con el tipo. */
    fun tipoDe(control: Int): Int = control and 0xEF

    /** P/F puesto. En un UIH de canal de datos significa "trae credito". */
    fun tienePf(control: Int): Boolean = (control and 0x10) != 0

    // ------------------------------------------------------------------- CRC-8

    /**
     * Tabla CRC-8 de TS 07.10: polinomio x^8+x^2+x+1 reflejado (0xE0).
     *
     * Se genera en vez de copiarse: 256 constantes tecleadas a mano son 256
     * oportunidades de una errata que solo se manifiesta con una trama de
     * cierto largo. La prueba verifica los primeros valores y las dos tramas
     * canonicas.
     */
    val TABLA: IntArray = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c shr 1) xor 0xE0 else c shr 1 }
            t[i] = c
        }
    }

    private fun crc2(a: Int, b: Int): Int = TABLA[TABLA[0xFF xor a] xor b]

    /** FCS de una trama UIH: solo direccion y control. */
    fun fcsUih(direccion: Int, control: Int): Int = 0xFF - crc2(direccion, control)

    /** FCS de SABM, UA, DM y DISC: direccion, control y longitud. */
    fun fcsControl(direccion: Int, control: Int, longitud: Int): Int =
        0xFF - TABLA[crc2(direccion, control) xor longitud]

    /**
     * Comprueba el FCS recibido.
     *
     * El truco de TS 07.10: reintroducir el FCS en el CRC tiene que dar
     * 0xCF. Es mas barato y menos propenso a errores que recalcular.
     */
    fun fcsValido(cabecera: ByteArray, esUih: Boolean, fcs: Int): Boolean {
        if (cabecera.size < 2) return false
        var f = crc2(cabecera[0].toInt() and 0xFF, cabecera[1].toInt() and 0xFF)
        if (!esUih) {
            if (cabecera.size < 3) return false
            f = TABLA[f xor (cabecera[2].toInt() and 0xFF)]
        }
        return TABLA[f xor (fcs and 0xFF)] == 0xCF
    }

    // -------------------------------------------------------- direccion y DLCI

    /** `EA(1) | C/R | DLCI shl 2`. */
    fun direccion(dlci: Int, cr: Int): Int = ((dlci and 0x3F) shl 2) or ((cr and 1) shl 1) or 0x01

    fun dlciDe(direccion: Int): Int = (direccion and 0xFF) shr 2

    /**
     * El DLCI de un canal de servidor, visto desde el iniciador.
     *
     * El bit de direccion vale 0 para quien arranco el multiplexor. Canal 1
     * -> DLCI 2, canal 2 -> DLCI 4, canal 3 -> DLCI 6.
     */
    fun dlciDeCanal(canal: Int): Int = (canal and 0x1F) shl 1

    // ------------------------------------------------------------- construccion

    /** SABM: "abre este DLCI". Con P/F puesto, como manda la especificacion. */
    fun sabm(dlci: Int): ByteArray = tramaControl(dlci, SABM or 0x10, cr = 1)

    /** DISC: "cierra este DLCI". DLCI 0 cierra el multiplexor entero. */
    fun disc(dlci: Int): ByteArray = tramaControl(dlci, DISC or 0x10, cr = 1)

    /** UA de respuesta. Somos iniciador, asi que nuestras respuestas van con C/R=0. */
    fun ua(dlci: Int): ByteArray = tramaControl(dlci, UA or 0x10, cr = 0)

    /** DM de respuesta: "ese DLCI no esta abierto". */
    fun dm(dlci: Int): ByteArray = tramaControl(dlci, DM or 0x10, cr = 0)

    private fun tramaControl(dlci: Int, control: Int, cr: Int): ByteArray {
        val a = direccion(dlci, cr)
        val l = 0x01 // longitud 0 con el bit EA puesto: (0 shl 1) or 1
        return byteArrayOf(a.toByte(), control.toByte(), l.toByte(), fcsControl(a, control, l).toByte())
    }

    /**
     * UIH con informacion, y opcionalmente un credito por delante.
     *
     * Dos cosas que la especificacion dice de pasada y muerden:
     *
     * - El byte de credito **no** cuenta en el campo de longitud. La longitud
     *   describe solo la informacion.
     * - La presencia del credito se senala con el bit P/F del control
     *   (0xEF -> 0xFF), no con nada del campo de longitud.
     *
     * Se usa longitud de un byte siempre que se pueda (info < 128), que es
     * siempre: la trama maxima negociada es 127. Ver `CanalRfcomm`.
     */
    fun uih(dlci: Int, info: ByteArray, credito: Int? = null, cr: Int = 1): ByteArray {
        val a = direccion(dlci, cr)
        val control = if (credito != null) (UIH or 0x10) else UIH
        val fcs = fcsUih(a, control)

        val largo = info.size
        val dosBytes = largo >= 0x80
        val n = 2 + (if (dosBytes) 2 else 1) + (if (credito != null) 1 else 0) + largo + 1
        val t = ByteArray(n)
        var i = 0
        t[i++] = a.toByte()
        t[i++] = control.toByte()
        if (dosBytes) {
            // EA=0 en el primero: la longitud sigue en el siguiente byte.
            t[i++] = ((largo shl 1) and 0xFE).toByte()
            t[i++] = ((largo shr 7) and 0xFF).toByte()
        } else {
            t[i++] = (((largo shl 1) or 1) and 0xFF).toByte()
        }
        if (credito != null) t[i++] = (credito and 0xFF).toByte()
        info.copyInto(t, i); i += largo
        t[i] = fcs.toByte()
        return t
    }

    // ------------------------------------------------------ control multiplexor

    /** Tipos MCC ya con EA y C/R: comando y respuesta. */
    const val MCC_PN_CMD = 0x83
    const val MCC_PN_RSP = 0x81
    const val MCC_MSC_CMD = 0xE3
    const val MCC_MSC_RSP = 0xE1
    const val MCC_NSC_RSP = 0x11
    const val MCC_CLD_CMD = 0xC3

    /**
     * Parameter Negotiation, los 8 bytes del valor.
     *
     * Aqui se pide el control de flujo por credito: `CL = 0xF` en el comando
     * (0xF0 en el byte, porque CL son los 4 bits altos). Un respondedor que
     * lo soporte contesta 0xE0; uno que no, 0x00 — y hay que aceptar las dos
     * respuestas, porque un modulo barato puede no traerlo.
     *
     * `maxTrama` se fija en **127** a proposito. No es timidez: con 127 el
     * campo de longitud de toda trama cabe en un byte, y desaparece de un
     * golpe toda la codificacion de longitud de dos bytes con su bit EA. Un
     * ELM327 contesta lineas de 20 o 30 caracteres; 127 es de sobra. La
     * complejidad que no se escribe es la que no falla.
     */
    fun valorPn(
        dlci: Int,
        pedirCredito: Boolean,
        maxTrama: Int = 127,
        creditosIniciales: Int = 7,
        esRespuesta: Boolean = false,
        prioridad: Int = 0x00,
    ): ByteArray = byteArrayOf(
        (dlci and 0x3F).toByte(),
        when {
            !pedirCredito -> 0x00
            esRespuesta -> 0xE0.toByte()
            else -> 0xF0.toByte()
        },
        prioridad.toByte(),
        0x00, // temporizador de reconocimiento: no se usa en RFCOMM
        (maxTrama and 0xFF).toByte(),
        ((maxTrama shr 8) and 0xFF).toByte(),
        0x00, // maximo de retransmisiones: RFCOMM no retransmite
        (if (pedirCredito) creditosIniciales and 0x07 else 0).toByte(),
    )

    /**
     * Modem Status Command, los 2 bytes del valor.
     *
     * **No es opcional en la practica.** Varios modulos —los clones de
     * ELM327 entre ellos— no mandan un solo byte de datos hasta que se ha
     * intercambiado el MSC, porque hasta entonces consideran que la linea
     * virtual no esta lista. El sintoma de olvidarlo es el peor de todos: el
     * DLCI abre con su UA y despues silencio absoluto, que se parece
     * demasiado a un problema del cable.
     *
     * El primer byte lleva el DLCI en formato de direccion (EA y C/R a 1).
     * El segundo son las senales V.24: EA(0x01) + RTC(0x04) "listo para
     * comunicar" + RTR(0x08) "listo para recibir" + DV(0x80) "datos
     * validos" = 0x8D. FC(0x02) se deja en 0: no estamos frenando nada.
     */
    fun valorMsc(dlci: Int, senales: Int = 0x8D): ByteArray =
        byteArrayOf((((dlci and 0x3F) shl 2) or 0x03).toByte(), senales.toByte())

    /** Envuelve un mensaje MCC en el campo de informacion de un UIH del DLCI 0. */
    fun mcc(tipo: Int, valor: ByteArray): ByteArray {
        require(valor.size < 0x80) { "MCC de mas de 127 bytes no hace falta aqui" }
        val t = ByteArray(2 + valor.size)
        t[0] = tipo.toByte()
        t[1] = (((valor.size shl 1) or 1) and 0xFF).toByte()
        valor.copyInto(t, 2)
        return t
    }

    // ------------------------------------------------------------ decodificacion

    /**
     * Una trama ya desarmada.
     *
     * @param credito el credito que venia por delante, o `null` si no habia.
     * @param fcsOk   si el FCS cuadra. Una trama con FCS malo **no se
     *                procesa**: se cuenta y se tira. Actuar sobre una trama
     *                corrupta es peor que perderla.
     */
    data class Trama(
        val dlci: Int,
        val control: Int,
        val tipo: Int,
        val info: ByteArray,
        val credito: Int?,
        val fcsOk: Boolean,
        val bytesConsumidos: Int,
    )

    /**
     * Saca UNA trama de [buf] a partir de [desde], o `null` si aun no esta
     * completa.
     *
     * Se decodifica por longitud y no se confia en que cada SDU de L2CAP
     * traiga exactamente una trama, aunque en modo basico deberia. El motivo
     * esta escrito con sangre en este proyecto: el endpoint de eventos del
     * dongle tiene `maxPacketSize` de 16 bytes y partia los eventos HCI en
     * tres; el BULK de ACL va a partir los paquetes igual. Reensamblar por
     * longitud cuesta veinte lineas y evita inventar datos.
     *
     * @param creditoActivo si el control de flujo por credito esta negociado.
     *   Sin esto no se puede saber si el bit P/F de un UIH significa
     *   "credito" o solo esta puesto; y leer un byte de credito que no
     *   existe desplaza toda la informacion una posicion.
     */
    fun decodificar(buf: ByteArray, desde: Int, hasta: Int, creditoActivo: Boolean): Trama? {
        var i = desde
        if (hasta - i < 4) return null // direccion, control, longitud y FCS como minimo

        val a = buf[i].toInt() and 0xFF
        val control = buf[i + 1].toInt() and 0xFF
        i += 2

        val l0 = buf[i].toInt() and 0xFF
        var largo: Int
        val bytesLargo: Int
        if (l0 and 1 == 1) {
            largo = l0 shr 1
            bytesLargo = 1
        } else {
            if (hasta - i < 2) return null
            largo = (l0 shr 1) or ((buf[i + 1].toInt() and 0xFF) shl 7)
            bytesLargo = 2
        }
        i += bytesLargo

        val tipo = tipoDe(control)
        val dlci = dlciDe(a)
        val traeCredito = creditoActivo && tipo == UIH && dlci != 0 && tienePf(control)

        val necesario = (if (traeCredito) 1 else 0) + largo + 1
        if (hasta - i < necesario) return null

        val credito = if (traeCredito) (buf[i++].toInt() and 0xFF) else null
        val info = if (largo > 0) buf.copyOfRange(i, i + largo) else ByteArray(0)
        i += largo
        val fcs = buf[i].toInt() and 0xFF
        i++

        val cabecera = byteArrayOf(a.toByte(), control.toByte(), buf[desde + 2])
        val ok = fcsValido(cabecera, tipo == UIH, fcs)

        return Trama(dlci, control, tipo, info, credito, ok, i - desde)
    }

    /** Nombre legible del tipo, para las trazas. */
    fun nombre(tipo: Int): String = when (tipo) {
        SABM -> "SABM"
        UA -> "UA"
        DM -> "DM"
        DISC -> "DISC"
        UIH -> "UIH"
        else -> "tipo 0x${String.format("%02X", tipo)}"
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { String.format("%02X", it) }
}
