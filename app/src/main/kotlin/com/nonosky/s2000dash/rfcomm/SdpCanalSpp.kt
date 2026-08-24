package com.nonosky.s2000dash.rfcomm

import com.nonosky.s2000dash.l2cap.CanalL2cap

/**
 * Pregunta por SDP en que canal RFCOMM vive el puerto serie. Plan B.
 *
 * ---------------------------------------------------------------------------
 * POR QUE ESTO ES EL PLAN B Y NO EL PLAN A
 *
 * SDP hecho como manda la especificacion es un parser de elementos de datos
 * anidados —secuencias dentro de secuencias, descriptores de tamano
 * variable, estado de continuacion para respuestas partidas— y son entre 150
 * y 200 lineas mas sus pruebas.
 *
 * La alternativa cuesta diez: mandar SABM al canal 1, y si vuelve un DM,
 * probar el 2 y el 3. Un DM es un "no" inmediato y sin ambiguedad. Casi
 * todos los clones de ELM327 son firmware tipo HC-05 sobre un CSR BC417, y
 * ese anuncia el puerto serie en el canal 1.
 *
 * Asi que el orden recomendado es: **tantear 1, 2 y 3 primero**. Si los tres
 * dan DM —o sea, el aparato publica el puerto serie en un canal raro— se
 * recurre a esto.
 *
 * Y esto tampoco es un parser completo: es un buscador de patron. En la
 * respuesta de SDP, la lista de protocolos de un puerto serie contiene
 * siempre esta secuencia de bytes:
 *
 * ```
 *   35 05      secuencia de 5 bytes
 *     19 00 03   UUID de 16 bits 0x0003 = RFCOMM
 *     08 XX      entero sin signo de 8 bits = el numero de canal
 * ```
 *
 * Buscar `19 00 03 08` y quedarse con el byte siguiente funciona en todas
 * las respuestas reales, cuesta veinte lineas, y no puede equivocarse de
 * forma silenciosa: si el patron no esta, devuelve `null` y se dice.
 * ---------------------------------------------------------------------------
 */
object SdpCanalSpp {

    /**
     * Peticion `ServiceSearchAttributeRequest` ya armada, byte a byte.
     *
     * ```
     *   06            PDU ID: ServiceSearchAttributeRequest
     *   00 01         identificador de transaccion
     *   00 0D         longitud de los parametros: 13
     *   35 03 19 11 01   secuencia: UUID 0x1101 = SerialPort
     *   02 00         maximo de bytes de atributo que aceptamos: 512
     *   35 03 09 00 04   secuencia: atributo 0x0004 ProtocolDescriptorList
     *   00            sin estado de continuacion
     * ```
     *
     * Los 512 bytes de tope se eligen para que la respuesta quepa en una
     * sola SDU con el MTU de 672 por defecto de L2CAP: pidiendo mas, el
     * servidor la parte y hay que implementar el estado de continuacion,
     * que es justo la complejidad que este atajo evita.
     */
    val PETICION: ByteArray = byteArrayOf(
        0x06,
        0x00, 0x01,
        0x00, 0x0D,
        0x35, 0x03, 0x19, 0x11, 0x01,
        0x02, 0x00,
        0x35, 0x03, 0x09, 0x00, 0x04,
        0x00,
    )

    /**
     * Pregunta y devuelve el canal, o `null`.
     *
     * @param canal un L2CAP ya abierto contra el PSM 0x0001.
     */
    fun preguntar(canal: CanalL2cap, timeoutMs: Long = 5_000): Int? {
        canal.enviar(PETICION)
        val hasta = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < hasta) {
            val r = canal.recibir(500) ?: continue
            // 0x07 = ServiceSearchAttributeResponse. Cualquier otra cosa
            // (0x01 = ErrorResponse) se ignora en vez de interpretarse.
            if (r.isEmpty() || (r[0].toInt() and 0xFF) != 0x07) continue
            return buscarCanal(r)
        }
        return null
    }

    /**
     * Busca `19 00 03 08 XX` y devuelve XX si es un canal plausible.
     *
     * Los canales de servidor validos van del 1 al 30. Filtrar por eso evita
     * que una coincidencia casual dentro de otro atributo se cuele como
     * canal: preferimos no encontrar nada a devolver un numero inventado.
     */
    fun buscarCanal(respuesta: ByteArray): Int? {
        var i = 0
        while (i + 4 < respuesta.size) {
            if ((respuesta[i].toInt() and 0xFF) == 0x19 &&
                (respuesta[i + 1].toInt() and 0xFF) == 0x00 &&
                (respuesta[i + 2].toInt() and 0xFF) == 0x03 &&
                (respuesta[i + 3].toInt() and 0xFF) == 0x08
            ) {
                val c = respuesta[i + 4].toInt() and 0xFF
                if (c in 1..30) return c
            }
            i++
        }
        return null
    }
}
