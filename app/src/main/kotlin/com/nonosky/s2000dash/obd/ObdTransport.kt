package com.nonosky.s2000dash.obd

import java.io.Closeable

/**
 * Transporta bytes hacia y desde el adaptador. No sabe que es un PID.
 *
 * La interfaz existe con una sola implementacion real a proposito: si el
 * adaptador Steren resulta ser BLE en vez de SPP clasico (riesgo R1 del
 * diseño), se añade un `BleTransport` sin tocar ninguna otra unidad.
 */
interface ObdTransport : Closeable {

    /** Abre el enlace. Lanza [java.io.IOException] si no se puede. */
    fun connect()

    /** Escribe bytes crudos al adaptador. */
    fun write(bytes: ByteArray)

    /**
     * Lee hasta el prompt `>` que marca fin de respuesta, o hasta agotar
     * [timeoutMs]. Devuelve lo acumulado — posiblemente vacio o truncado,
     * que es asunto del parser, no del transporte.
     */
    fun readUntilPrompt(timeoutMs: Long): String

    val isConnected: Boolean
}
