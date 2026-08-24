package com.nonosky.s2000dash.l2cap

import java.io.Closeable

/**
 * La costura con la capa ACL/L2CAP, que la escribe otro.
 *
 * Esto NO es una implementacion: es el contrato exacto que RFCOMM necesita,
 * escrito aparte para que las dos mitades se puedan desarrollar y probar sin
 * esperarse. Si los nombres del otro lado terminan siendo otros, lo unico
 * que cambia es un adaptador de veinte lineas.
 *
 * ---------------------------------------------------------------------------
 * LO QUE RFCOMM DA POR CIERTO, y por que importa
 *
 * 1. **[enviar] entrega una SDU entera.** La fragmentacion en paquetes ACL
 *    (banderas PB 0b10 para el primero y 0b01 para los que siguen) es asunto
 *    de la capa de abajo. RFCOMM manda tramas de 132 bytes como maximo, o sea
 *    que en la practica nunca habra que fragmentar; el contrato lo dice de
 *    todos modos porque depender de "nunca" es como no depender de nada.
 *
 * 2. **[recibir] devuelve UNA SDU completa.** L2CAP en modo basico conserva
 *    la frontera del mensaje, y RFCOMM cuenta con eso: cada SDU lleva
 *    exactamente una trama RFCOMM. Aun asi el decodificador de arriba
 *    reensambla por el campo de longitud, porque el endpoint de eventos de
 *    este mismo dongle ya ensena la leccion: `maxPacketSize` de 16 bytes
 *    partia los eventos en tres y la primera version inventaba MAC a partir
 *    de datos de anuncio. El endpoint BULK partira los paquetes ACL igual.
 *
 * 3. **Control de flujo de ACL.** Quien implemente esto tiene que llevar la
 *    cuenta de `Total_Num_ACL_Data_Packets` que devuelve `READ_BUFFER_SIZE`
 *    (0x1005) y devolver creditos con el evento `Number Of Completed
 *    Packets` (0x13). Y **no vale** el resultado de `LE_READ_BUFFER_SIZE`:
 *    en este dongle dio 27 bytes / 15 buffers, y siendo distinto de cero
 *    significa que LE tiene su propia reserva, separada de la de BR/EDR.
 *
 * 4. **[mtuSalida] >= 132.** Una trama RFCOMM son como maximo
 *    `N1 + 6` bytes: direccion, control, dos de longitud, un credito, la
 *    informacion y el FCS. Con N1 = 127 —que es lo que se negocia aqui a
 *    proposito, ver `CanalRfcomm`— eso son 132. Si el MTU acordado sale
 *    menor, hay que bajar N1 o la trama no cabe.
 * ---------------------------------------------------------------------------
 */
interface CanalL2cap : Closeable {

    /** Maximo que acepta el OTRO extremo en una sola SDU. */
    val mtuSalida: Int

    /** Maximo que nosotros anunciamos aceptar. */
    val mtuEntrada: Int

    val abierto: Boolean

    /** Manda una SDU. Lanza [java.io.IOException] si el canal murio. */
    fun enviar(sdu: ByteArray)

    /**
     * Una SDU entera, o `null` si se agoto [timeoutMs] sin que llegara nada.
     *
     * `null` significa silencio, **no** canal cerrado. Para eso esta
     * [abierto]: un lector que confunda las dos cosas se queda girando en
     * vacio sobre un enlace muerto, que es como se cuelga un tablero.
     */
    fun recibir(timeoutMs: Long): ByteArray?

    override fun close()
}

/**
 * Abre canales L2CAP sobre un enlace ACL ya establecido.
 *
 * El `handle` viene de `EnlaceBrEdr.conectar()`. Los PSM que hacen falta
 * aqui son dos:
 *   - 0x0001 SDP    — solo si se decide preguntar el canal del SPP
 *   - 0x0003 RFCOMM — el que lleva los datos
 */
interface PilaL2cap {

    /**
     * Conecta y configura un canal.
     *
     * Un rechazo por seguridad —resultado 0x0003 en la Connection Response,
     * "Connection refused - security block"— es la senal de que hay que
     * emparejar y reintentar. Que llegue distinguible importa: es la
     * diferencia entre "empareja" y "el PSM no existe".
     */
    fun conectar(handle: Int, psm: Int, timeoutMs: Long = 10_000): CanalL2cap

    companion object {
        const val PSM_SDP = 0x0001
        const val PSM_RFCOMM = 0x0003

        /** Resultado de Connection Response que exige emparejar antes. */
        const val RESULTADO_BLOQUEO_SEGURIDAD = 0x0003
    }
}

/** Se rechazo el canal por seguridad: hay que emparejar y volver a intentar. */
class BloqueoSeguridadL2cap(mensaje: String) : java.io.IOException(mensaje)
