package com.nonosky.s2000dash.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Habla HCI directamente con un dongle Bluetooth por USB, sin el kernel.
 *
 * Existe porque la pila Bluetooth de este radio no sirve —no ve adaptadores
 * que cualquier telefono ve, se apaga sola, y un barrido BLE de 25 segundos
 * no encuentra nada— y porque el kernel de esta ROM **no trae `btusb`**:
 * `/sys/class/bluetooth/` esta vacio y el dongle quedo enganchado al driver
 * USB generico. Android nunca lo va a adoptar como su radio.
 *
 * Pero Android SI nos concede permiso sobre el aparato USB. Y un dongle
 * Bluetooth es, por especificacion, un transporte HCI muy simple:
 *
 *   - comandos HCI  -> transferencia de CONTROL (bmRequestType 0x20)
 *   - eventos HCI   -> endpoint de INTERRUPCION de entrada
 *   - datos ACL     -> endpoints BULK
 *
 * O sea que se le puede hablar desde el espacio de usuario, saltandose la
 * pila rota por completo. Eso es lo que hace esta clase.
 *
 * NO es una pila Bluetooth: es lo justo para preguntarle quien es y para
 * barrer por BLE, que es lo que hace falta para encontrar la bateria.
 */
class HciUsb(
    private val manager: UsbManager,
    private val device: UsbDevice,
) {

    private var conexion: UsbDeviceConnection? = null
    private var itf: UsbInterface? = null
    private var eventos: UsbEndpoint? = null

    /**
     * Reclama la interfaz HCI del dongle.
     *
     * La interfaz 0 es la del transporte HCI: trae un endpoint de
     * interrupcion (eventos) y dos BULK (datos ACL). Las demas interfaces de
     * un dongle son isocronas, para audio SCO, y no nos sirven.
     */
    fun abrir(): List<String> {
        val traza = mutableListOf<String>()

        if (!manager.hasPermission(device)) return listOf("ERROR: sin permiso USB")

        val candidata = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { i ->
                (0 until i.endpointCount).any { j ->
                    val e = i.getEndpoint(j)
                    e.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        e.direction == UsbConstants.USB_DIR_IN
                }
            } ?: return listOf("ERROR: no hay interfaz con endpoint de interrupcion (¿no es un dongle HCI?)")

        val con = manager.openDevice(device) ?: return listOf("ERROR: openDevice devolvio null")
        if (!con.claimInterface(candidata, true)) {
            con.close()
            return listOf("ERROR: no se pudo reclamar la interfaz HCI")
        }

        for (j in 0 until candidata.endpointCount) {
            val e = candidata.getEndpoint(j)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN) {
                eventos = e
            }
        }
        if (eventos == null) {
            con.releaseInterface(candidata)
            con.close()
            return listOf("ERROR: la interfaz no expone endpoint de eventos")
        }

        conexion = con
        itf = candidata
        traza += "interfaz HCI reclamada (eventos en endpoint ${eventos?.address})"
        return traza
    }

    /**
     * Manda un comando HCI por el endpoint de control.
     *
     * En el transporte USB de Bluetooth los comandos NO llevan el byte de
     * tipo de paquete: el tipo lo da el propio endpoint. Por eso aqui va
     * solo opcode y parametros.
     */
    fun mandarComando(opcode: Int, parametros: ByteArray = ByteArray(0)): Int {
        val con = conexion ?: return -1
        val paquete = ByteArray(3 + parametros.size)
        paquete[0] = (opcode and 0xFF).toByte()
        paquete[1] = ((opcode shr 8) and 0xFF).toByte()
        paquete[2] = parametros.size.toByte()
        parametros.copyInto(paquete, 3)
        return con.controlTransfer(
            TIPO_CONTROL_HCI, 0x00, 0, 0, paquete, paquete.size, TIMEOUT_MS
        )
    }

    /**
     * Lee un evento HCI **completo**, o `null` si no llego nada.
     *
     * Reensambla a proposito, y no es un detalle: el endpoint de eventos de
     * este dongle tiene `maxPacketSize` de **16 bytes**, asi que un informe
     * de anuncio BLE —que ronda los 40— llega partido en tres trozos. La
     * primera version leia un solo trozo y lo trataba como evento entero:
     * el resultado eran MAC inventadas a partir de bytes que en realidad
     * eran datos de anuncio, y ni un aparato reconocido aunque el aire
     * estuviera lleno.
     *
     * La cabecera HCI dice cuanto falta: byte 0 el codigo, byte 1 la
     * longitud de los parametros. Se lee hasta completar `2 + longitud`.
     */
    fun leerEvento(timeoutMs: Int = TIMEOUT_MS): ByteArray? {
        val con = conexion ?: return null
        val ep = eventos ?: return null
        val paquete = ep.maxPacketSize.coerceAtLeast(16)
        val buf = ByteArray(paquete)

        val primero = con.bulkTransfer(ep, buf, paquete, timeoutMs)
        if (primero <= 0) return null

        val acumulado = java.io.ByteArrayOutputStream()
        acumulado.write(buf, 0, primero)

        // Sin los dos bytes de cabecera no se sabe cuanto falta.
        if (acumulado.size() < 2) return acumulado.toByteArray()
        val total = 2 + (acumulado.toByteArray()[1].toInt() and 0xFF)

        // Los trozos que faltan ya estan en el pipe: un plazo corto basta y
        // evita quedarse esperando por un evento que ya llego entero.
        var vacios = 0
        while (acumulado.size() < total && vacios < 4) {
            val n = con.bulkTransfer(ep, buf, paquete, CONTINUACION_MS)
            if (n > 0) acumulado.write(buf, 0, n) else vacios++
        }
        return acumulado.toByteArray()
    }

    fun cerrar() {
        runCatching {
            itf?.let { conexion?.releaseInterface(it) }
            conexion?.close()
        }.onFailure { Log.w(TAG, "cerrar fallo: ${it.message}") }
        conexion = null
        itf = null
        eventos = null
    }

    companion object {
        private const val TAG = "HciUsb"

        /**
         * Clase + salida + destinatario aparato. Es lo que fija el
         * transporte USB de Bluetooth para los comandos HCI.
         */
        private const val TIPO_CONTROL_HCI = 0x20

        private const val TIMEOUT_MS = 1_500

        /** Plazo para los trozos que continuan un evento ya empezado. */
        private const val CONTINUACION_MS = 250

        // --- Opcodes. opcode = (OGF shl 10) or OCF ---
        /** Reinicia el controlador. El "¿estas ahi?" de HCI. */
        const val CMD_RESET = 0x0C03
        /** Version del controlador: delata fabricante y revision. */
        const val CMD_READ_LOCAL_VERSION = 0x1001
        /** Direccion Bluetooth propia del dongle. */
        const val CMD_READ_BD_ADDR = 0x1009

        /** Bit 6 del byte 4 de estas features dice si el chip soporta LE. */
        const val CMD_READ_LOCAL_FEATURES = 0x1003
        const val CMD_LE_READ_BUFFER_SIZE = 0x2002
        const val CMD_LE_READ_LOCAL_FEATURES = 0x2003

        const val CMD_LE_SET_SCAN_PARAMS = 0x200B
        const val CMD_LE_SET_SCAN_ENABLE = 0x200C

        // --- Codigos de evento ---
        const val EVT_COMMAND_COMPLETE = 0x0E
        const val EVT_COMMAND_STATUS = 0x0F
        const val EVT_LE_META = 0x3E
        const val SUBEVT_LE_ADVERTISING_REPORT = 0x02

        /** Un dongle Bluetooth se declara con esta clase en su descriptor. */
        const val CLASE_INALAMBRICA = 0xE0

        fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02X", it) }

        /** VID de los USB-serial baratos. NO son dongles Bluetooth. */
        private val VID_SERIAL = setOf(0x1A86, 0x10C4, 0x0403, 0x067B)

        /**
         * Localiza el dongle Bluetooth entre lo que haya en el USB.
         *
         * El primer intento buscaba solo por forma —interrupcion de entrada
         * mas dos BULK— y eligio el receptor TPMS: un CH340 tiene exactamente
         * esos tres endpoints. Buscar por forma no basta cuando dos aparatos
         * distintos tienen la misma silueta.
         *
         * Ahora manda el descriptor: la especificacion USB reserva la clase
         * 0xE0 subclase 0x01 protocolo 0x01 para el transporte Bluetooth, y
         * eso no lo tiene un conversor serial. Los VID de los serial conocidos
         * se descartan de entrada por si algun clon miente en su clase.
         */
        fun buscarDongle(um: UsbManager, vid: Int? = null, pid: Int? = null): UsbDevice? {
            val todos = runCatching { um.deviceList.values.toList() }.getOrNull() ?: return null

            // Si nos dicen cual, no adivinamos.
            if (vid != null) {
                return todos.firstOrNull { it.vendorId == vid && (pid == null || it.productId == pid) }
            }

            // 1. Por descriptor: es la respuesta correcta cuando existe.
            todos.firstOrNull { d -> (0 until d.interfaceCount).any { esInterfazBluetooth(d.getInterface(it)) } }
                ?.let { return it }

            // 2. Por forma, pero descartando los serial conocidos.
            return todos.firstOrNull { d ->
                d.vendorId !in VID_SERIAL && (0 until d.interfaceCount).any { i ->
                    tieneFormaHci(d.getInterface(i))
                }
            }
        }

        private fun esInterfazBluetooth(itf: UsbInterface): Boolean =
            itf.interfaceClass == CLASE_INALAMBRICA &&
                itf.interfaceSubclass == 0x01 &&
                itf.interfaceProtocol == 0x01

        private fun tieneFormaHci(itf: UsbInterface): Boolean {
            var interrupcion = false
            var bulkEntrada = false
            var bulkSalida = false
            for (j in 0 until itf.endpointCount) {
                val e = itf.getEndpoint(j)
                when {
                    e.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        e.direction == UsbConstants.USB_DIR_IN -> interrupcion = true
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        e.direction == UsbConstants.USB_DIR_IN -> bulkEntrada = true
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        e.direction == UsbConstants.USB_DIR_OUT -> bulkSalida = true
                }
            }
            return interrupcion && bulkEntrada && bulkSalida
        }

        /** Todos los candidatos con su motivo, para poder diagnosticar la eleccion. */
        fun listarCandidatos(um: UsbManager): List<String> =
            runCatching { um.deviceList.values.toList() }.getOrNull().orEmpty().map { d ->
                val porClase = (0 until d.interfaceCount).any { esInterfazBluetooth(d.getInterface(it)) }
                val porForma = (0 until d.interfaceCount).any { tieneFormaHci(d.getInterface(it)) }
                "VID=0x${String.format("%04X", d.vendorId)} " +
                    "PID=0x${String.format("%04X", d.productId)} " +
                    (runCatching { d.productName }.getOrNull() ?: "") +
                    " | claseBluetooth=$porClase formaHci=$porForma " +
                    "esSerial=${d.vendorId in VID_SERIAL}"
            }
    }
}
