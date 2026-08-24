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
