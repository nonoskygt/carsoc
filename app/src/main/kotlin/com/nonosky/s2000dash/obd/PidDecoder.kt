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

    const val PID_O2_ANCHA = "0134"

    /** Sonda de banda estrecha. Solo como respaldo: no da un AFR real. */
    const val PID_O2_ESTRECHA = "0114"

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

        for (line in raw.split('\r', '\n')) {
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

    /** Carga calculada = A * 100 / 255 (%). */

    /**
     * Lambda (relacion de equivalencia) de la sonda de banda ancha.
     *
     * Bytes A y B, escala 2/65536. Lambda 1.0 es la mezcla estequiometrica.
     *
     * Se devuelve lambda y no directamente el AFR porque lambda es lo que
     * mide el sensor; el AFR depende del combustible, y multiplicar por 14.7
     * es una conversion para gasolina que conviene tener a la vista y no
     * escondida dentro del decodificador.
     */
    fun decodeLambda(raw: String?): Float? {
        val p = payloadOf(raw, PID_O2_ANCHA) ?: return null
        if (p.size < 2) return null
        val bruto = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val lambda = bruto * 2f / 65536f
        // Fuera de este rango el sensor no esta midiendo: arranque en frio,
        // corte en deceleracion, o sonda sin calentar. Devolver un numero
        // ahi seria pintar una mezcla que nadie esta quemando.
        return lambda.takeIf { it in 0.5f..1.6f }
    }

    /** AFR para gasolina. La estequiometrica son 14.7:1. */
    fun afrDesdeLambda(lambda: Float?): Float? = lambda?.let { it * 14.7f }


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

    /**
     * Voltaje de la sonda, en voltios. Escala 0.005 V por cuenta.
     *
     * Una sonda de banda estrecha oscila alrededor de 0.45 V cuando el motor
     * esta en lazo cerrado: por encima va rica, por debajo pobre. Los
     * extremos (0.1 y 0.9) son los topes del sensor, no medidas finas.
     */
    fun decodeO2Voltaje(raw: String?): Float? =
        payloadOf(raw, PID_O2_V)?.takeIf { it.isNotEmpty() }
            ?.let { (it[0].toInt() and 0xFF) * 0.005f }
            ?.takeIf { it in 0f..1.275f }

    fun decodeLoad(raw: String?): Int? {
        val d = payloadOf(raw, PID_LOAD) ?: return null
        if (d.isEmpty()) return null
        return d.u(0) * 100 / 255
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
