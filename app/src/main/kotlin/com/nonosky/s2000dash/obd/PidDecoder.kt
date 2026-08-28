package com.nonosky.s2000dash.obd

/**
 * Convierte respuestas crudas del ELM327 en magnitudes fisicas.
 *
 * Funciones puras: sin I/O, sin estado, sin excepciones. Toda entrada
 * invalida produce `null`. Esta es la unidad mas facil de probar y ahi vive
 * el grueso de las pruebas — un clon de ELM327 escupe basura constantemente
 * (§10 del diseño) y el parser jamas puede fallar con ninguna de ellas.
 */
object PidDecoder {

    /** PIDs que usa el tablero. */
    const val PID_RPM = "010C"
    const val PID_SPEED = "010D"
    const val PID_LOAD = "0104"
    const val PID_COOLANT = "0105"
    const val PID_IAT = "010F"

    /**
     * Sonda de oxigeno de banda ANCHA, sensor 1 del banco 1.
     *
     * Este es el que sirve para calcular la mezcla de verdad. El S2000 monta
     * una sonda lineal (LAF), no una de banda estrecha, y una banda estrecha
     * solo sabe decir "rica" o "pobre" — su voltaje salta entre extremos y no
     * se puede convertir en un numero.
     *
     * El PID 0134 devuelve la **relacion de equivalencia** (lambda) en los dos
     * primeros bytes, escalada a 2/65536. Con eso la mezcla sale directa:
     * AFR = lambda * 14.7 para gasolina.
     */
    /**
     * Presion absoluta del colector, en kPa. Un byte, sin escala.
     *
     * En un atmosferico esto es un vacuometro: al ralenti ronda los 30 kPa y
     * a acelerador abierto se acerca a la presion atmosferica (~100 kPa al
     * nivel del mar, menos en altura). Dice cuanto esta pidiendo el motor
     * mucho mejor que el porcentaje de carga calculado.
     */
    /** Ajuste de combustible a CORTO plazo, banco 1. */
    const val PID_TRIM_CORTO = "0106"

    /** Ajuste de combustible a LARGO plazo, banco 1. */
    const val PID_TRIM_LARGO = "0107"

    /** Estado de monitores: luz de averia y cuantos codigos hay guardados. */
    const val PID_ESTADO = "0101"

    const val PID_MAP = "010B"

    /** Posicion del acelerador, 0-100%. Un byte escalado 100/255. */
    const val PID_ACELERADOR = "0111"

    /** Avance de encendido en grados. Un byte: A/2 - 64. */
    const val PID_AVANCE = "010E"

    /**
     * Voltaje de la sonda de oxigeno 1, banco 1.
     *
     * Dos bytes: el voltaje en A (escala 0.005 V) y el trim en B. Es de banda
     * ESTRECHA en lo que expone este carro, asi que no da un AFR: solo dice de
     * que lado de la estequiometrica esta. Se usa tal cual, como rica/pobre,
     * porque convertirlo en un numero de mezcla seria inventarselo.
     */
    const val PID_O2_V = "0114"

    // Aqui vivian PID_O2_ANCHA ("0134") y PID_O2_ESTRECHA, que era un
    // duplicado exacto de PID_O2_V. Se van con decodeLambda: preguntarle a
    // esta ECU por el 0134 devolvia vacio SIEMPRE, y la consulta del 0100
    // que se le hizo al carro lo explico — su mapa de PIDs se corta en el
    // 0x20, asi que ese sensor no existe aqui y nunca existio.

    /**
     * Tokens que en `ATRV` significan que no hubo lectura.
     *
     * Ojo: NO se usan para las tramas de PID. Ver [payloadOf] — ahi buscar
     * tokens de error sobre la respuesta entera era justamente el error.
     */
    private val ERROR_TOKENS = listOf(
        "NODATA", "STOPPED", "UNABLETOCONNECT", "BUSERROR",
        "CANERROR", "DATAERROR", "ERROR", "?"
    )

    /**
     * Extrae los bytes de datos de una respuesta a [pid].
     *
     * Se parsea **linea por linea**, y una linea que no sea una trama valida
     * simplemente se ignora. Esto importa mas de lo que parece: un ELM327
     * antepone banners de progreso a la trama buena, dentro de la MISMA
     * respuesta —
     *
     *     BUS INIT: ...OK\r41 0C 1A F8\r\r>
     *     SEARCHING...\r41 0C 1A F8\r\r>
     *
     * — y en ISO 9141-2 la primera peticion de cada conexion SIEMPRE trae
     * el `BUS INIT`. Rechazar la respuesta entera por contener ese texto
     * tiraba la lectura con la que se comprueba que el bus responde, asi que
     * el enlace bueno se declaraba muerto y no se leia un solo dato.
     *
     * No hace falta buscar tokens de error: si no hay trama, no hay muestra.
     * `BUS INIT: ERROR` y `UNABLE TO CONNECT` caen solos por no traer trama.
     *
     * Absorbe ademas el prompt `>`, los espacios (por si `ATS0` no tomo
     * efecto) y el eco del comando (por si `ATE0` no tomo efecto): el eco
     * lleva el modo de peticion `01`, no el de respuesta `41`.
     */
    fun payloadOf(raw: String?, pid: String): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val prefix = responsePrefix(pid) ?: return null

        for (line in raw.lines()) {
            val hex = line.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.isEmpty()) continue

            val at = hex.indexOf(prefix)
            if (at < 0) continue

            var body = hex.substring(at + prefix.length)
            // Longitud impar = respuesta truncada a la mitad de un byte.
            if (body.length % 2 != 0) body = body.dropLast(1)
            if (body.isEmpty()) continue

            return ByteArray(body.length / 2) { i ->
                body.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return null
    }

    /** `010C` -> `410C`. El modo de respuesta es el de peticion mas 0x40. */
    private fun responsePrefix(pid: String): String? {
        if (pid.length < 4) return null
        val mode = pid.substring(0, 2).toIntOrNull(16) ?: return null
        return "%02X".format(mode + 0x40) + pid.substring(2)
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    /** RPM = ((A * 256) + B) / 4 */
    fun decodeRpm(raw: String?): Int? {
        val d = payloadOf(raw, PID_RPM) ?: return null
        if (d.size < 2) return null
        val rpm = ((d.u(0) * 256) + d.u(1)) / 4
        return if (rpm in 0..16_383) rpm else null
    }

    /** Velocidad = A, ya en km/h. */
    fun decodeSpeed(raw: String?): Int? {
        val d = payloadOf(raw, PID_SPEED) ?: return null
        if (d.isEmpty()) return null
        return d.u(0)
    }

    /** Refrigerante = A - 40 (°C). */
    fun decodeCoolant(raw: String?): Int? {
        val d = payloadOf(raw, PID_COOLANT) ?: return null
        if (d.isEmpty()) return null
        return d.u(0) - 40
    }

    /** Aire de admision = A - 40 (°C). */
    fun decodeIat(raw: String?): Int? {
        val d = payloadOf(raw, PID_IAT) ?: return null
        if (d.isEmpty()) return null
        return d.u(0) - 40
    }


    /**
     * Que PIDs soporta esta ECU, preguntandoselo a ella.
     *
     * El modo 01 tiene un PID de indice cada 32: `0100` contesta con cuatro
     * bytes cuyos bits dicen si soporta del 0x01 al 0x20, el bit mas alto
     * del primer byte para el 0x01. Si el ultimo bit esta puesto, es que hay
     * otro bloque y se puede preguntar `0120`, y asi.
     *
     * Existe porque el proyecto llevaba tiempo suponiendo lo que soporta el
     * AP1 —y suponiendo mal: se gastaron turnos pidiendo el AFR de banda
     * ancha 0134 durante quien sabe cuanto para recibir vacio—. Preguntar
     * cuesta una lectura y zanja la discusion.
     *
     * @param base 0x00 para el bloque 01-20, 0x20 para el 21-40, etc.
     */
    fun soportados(raw: String?, base: Int): List<Int> {
        val d = payloadOf(raw, "01%02X".format(base)) ?: return emptyList()
        if (d.size < 4) return emptyList()
        val bits = ((d[0].toInt() and 0xFF) shl 24) or
            ((d[1].toInt() and 0xFF) shl 16) or
            ((d[2].toInt() and 0xFF) shl 8) or
            (d[3].toInt() and 0xFF)
        return (0 until 32).filter { i ->
            (bits shr (31 - i)) and 1 == 1
        }.map { base + it + 1 }
    }

    /** Hay otro bloque de 32 detras de este. */
    fun hayMasBloques(raw: String?, base: Int): Boolean =
        soportados(raw, base).contains(base + 0x20)

    /** Nombre legible de los PIDs del modo 01 que valen la pena. */
    val NOMBRES: Map<Int, String> = mapOf(
        0x01 to "estado de monitores y numero de averias",
        0x03 to "estado del sistema de combustible (lazo abierto/cerrado)",
        0x04 to "carga calculada",
        0x05 to "temperatura del refrigerante",
        0x06 to "ajuste de combustible CORTO plazo, banco 1",
        0x07 to "ajuste de combustible LARGO plazo, banco 1",
        0x08 to "ajuste corto, banco 2",
        0x09 to "ajuste largo, banco 2",
        0x0A to "presion de combustible",
        0x0B to "presion del colector (MAP)",
        0x0C to "revoluciones",
        0x0D to "velocidad",
        0x0E to "avance de encendido",
        0x0F to "temperatura del aire de admision",
        0x10 to "caudal de aire (MAF)",
        0x11 to "posicion del acelerador",
        0x13 to "sondas lambda presentes",
        0x14 to "sonda 1 banco 1: voltaje y ajuste corto",
        0x15 to "sonda 2 banco 1: voltaje y ajuste corto",
        0x1C to "norma OBD a la que responde",
        0x1F to "tiempo funcionando desde el arranque",
        0x21 to "distancia con la luz de averia encendida",
        0x2E to "purga del canister",
        0x2F to "nivel de combustible",
        0x33 to "presion barometrica",
        0x42 to "voltaje del modulo de control",
        0x43 to "carga absoluta",
        0x44 to "relacion aire/combustible mandada",
        0x45 to "posicion relativa del acelerador",
        0x46 to "temperatura ambiente",
        0x5C to "temperatura del aceite del motor",
    )

    /**
     * Ajuste de combustible, en por ciento. `(A - 128) * 100 / 128`.
     *
     * Es lo que la centralita esta corrigiendo sobre la inyeccion base para
     * mantener la mezcla donde quiere. **Cero es perfecto.** Positivo =
     * mete mas gasolina porque lee pobre; negativo = quita porque lee rica.
     *
     * Vale mas que casi cualquier otro dato de esta ECU para un motor viejo,
     * porque delata la averia ANTES de que encienda la luz: una fuga de
     * vacio empuja el ajuste arriba, un inyector sucio tambien, y una sonda
     * muriendose lo vuelve erratico. Por encima de +-10% ya hay algo que
     * mirar; por encima de +-25% la centralita esta al limite de lo que
     * puede corregir y la luz esta a punto de encenderse.
     */
    fun decodeTrim(raw: String?, pid: String): Int? =
        payloadOf(raw, pid)?.takeIf { it.isNotEmpty() }
            ?.let { (((it[0].toInt() and 0xFF) - 128) * 100) / 128 }
            ?.takeIf { it in -100..99 }

    /**
     * Luz de averia encendida, y cuantos codigos hay guardados.
     *
     * Byte A del `0101`: el bit alto es la lampara, los siete de abajo son
     * el numero de codigos. Se lee entero de una vez porque van en el mismo
     * byte y separarlos costaria dos peticiones para el mismo dato.
     */
    fun decodeMil(raw: String?): Pair<Boolean, Int>? =
        payloadOf(raw, PID_ESTADO)?.takeIf { it.isNotEmpty() }
            ?.let { d ->
                val a = d[0].toInt() and 0xFF
                Pair((a and 0x80) != 0, a and 0x7F)
            }

    /** Presion del colector en kPa. Un byte directo. */
    fun decodeMap(raw: String?): Int? =
        payloadOf(raw, PID_MAP)?.takeIf { it.isNotEmpty() }
            ?.let { it[0].toInt() and 0xFF }
            ?.takeIf { it in 0..255 }

    /** Acelerador en por ciento. */
    fun decodeAcelerador(raw: String?): Int? =
        payloadOf(raw, PID_ACELERADOR)?.takeIf { it.isNotEmpty() }
            ?.let { ((it[0].toInt() and 0xFF) * 100 / 255) }

    /** Avance de encendido en grados. A/2 - 64. */
    fun decodeAvance(raw: String?): Int? =
        payloadOf(raw, PID_AVANCE)?.takeIf { it.isNotEmpty() }
            ?.let { ((it[0].toInt() and 0xFF) / 2) - 64 }
            ?.takeIf { it in -64..64 }

    fun decodeLoad(raw: String?): Int? {
        val d = payloadOf(raw, PID_LOAD)
        if (d != null && d.isNotEmpty()) return d.u(0) * 100 / 255

        // ESTA ECU CONTESTA EL 0104 SIN EL BYTE DEL PID.
        //
        // Medido en el carro: `0104` devuelve `414B` en vez de `41044B`. El
        // `0100` la declara soportada y todos los demas PIDs contestan con
        // su encabezado completo —`41067A`, `410E88`, `411112`— asi que no es
        // el adaptador comiendose bytes en general: es este PID.
        //
        // La consecuencia era que la carga salia vacia PARA SIEMPRE, y con
        // ella se caia la deteccion del VTEC, que necesita rpm y carga.
        //
        // Se acepta el formato corto solo aqui y con dos guardias: tiene que
        // ser exactamente `41` mas un byte —ni mas ni menos, para no tragarse
        // la respuesta de otro PID que pase cerca— y el resultado tiene que
        // caer en 0..100. Un motor al ralenti da ~29%, que es justo lo que
        // sale de ese `4B`.
        return cargaEnFormatoCorto(raw)
    }

    private fun cargaEnFormatoCorto(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        for (line in raw.lines()) {
            val hex = line.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.length != 4 || !hex.startsWith("41")) continue
            val a = hex.substring(2, 4).toIntOrNull(16) ?: continue
            val pct = a * 100 / 255
            if (pct in 0..100) return pct
        }
        return null
    }

    /**
     * Voltaje de `ATRV`, que responde algo como `12.6V`.
     *
     * Lo da el adaptador, no la ECU, asi que no gasta presupuesto de K-line
     * y no lleva encabezado de respuesta que validar.
     */
    fun decodeVoltage(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.uppercase().filter { !it.isWhitespace() && it != '>' }
        if (ERROR_TOKENS.any { compact.contains(it) }) return null
        val number = compact.takeWhile { it.isDigit() || it == '.' }
        val v = number.toFloatOrNull() ?: return null
        // Un carro sano vive entre 11 y 15 V. Fuera de rango es basura.
        return if (v in 6f..20f) v else null
    }
}
