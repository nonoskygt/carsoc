package com.nonosky.s2000dash.nevera

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * El enlace con la refrigeradora Alpicool.
 *
 * Conecta, pregunta, y **suelta**. No se queda con el enlace abierto, y eso
 * es deliberado: mientras alguien esta conectado, la nevera **deja de
 * anunciarse y rechaza a los demas**, o sea que el tablero le quitaria la
 * nevera a la app del movil para siempre. Preguntando cada medio minuto y
 * soltando, los dos conviven. Una nevera cambia de temperatura despacio; no
 * hay nada que ganar sondeandola mas seguido.
 *
 * No hace falta emparejar: la fuente original lo dice explicitamente —"there
 * is no authentication", "pairing to the device is not required"—. El
 * comando de vinculo (0x00) es una cortesia para que el dueño confirme
 * pulsando un boton, y la nevera obedece igual sin el.
 */
class LectorNevera(private val context: Context) {

    @Volatile var estado: Alpicool.Estado? = null
        private set

    /** Cuando se leyo lo que hay en [estado]. 0 = nunca. */
    @Volatile var leidoMs: Long = 0L
        private set

    @Volatile var detalle: String? = null
        private set

    var alCambiar: (() -> Unit)? = null

    private val traza = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var vivo = false
    private var hilo: Thread? = null

    fun vivoAhora(ahoraMs: Long): Boolean =
        leidoMs > 0L && (ahoraMs - leidoMs) < SIN_VERSE_MS && estado != null

    fun arrancar() {
        if (vivo) return
        vivo = true
        hilo = thread(name = "nevera", isDaemon = true) {
            while (vivo) {
                if (com.nonosky.s2000dash.Termometro.permiteBateria()) {
                    runCatching { unaLectura() }
                        .onFailure { anotar("fallo: ${it.javaClass.simpleName} ${it.message}") }
                }
                dormir(PERIODO_MS)
            }
        }
    }

    fun detener() {
        vivo = false
        hilo?.interrupt()
        hilo = null
    }

    fun diagnostico(): List<String> {
        val e = estado
        val edad = if (leidoMs > 0) "${(System.currentTimeMillis() - leidoMs) / 1000}s" else "nunca"
        return listOf(
            "mac=$MAC  leido=$edad  ${detalle ?: ""}",
            if (e == null) "sin estado" else
                "encendida=${e.encendida}  actual=${e.actual}  consigna=${e.consigna}  " +
                    "histeresis=${e.histeresis}  unidad=${if (e.unidadCelsius) "C" else "F"}  " +
                    "voltaje=${e.voltaje ?: "--"}  compresor(deducido)=${e.compresorEnMarcha()}",
        ) + traza.toList().takeLast(12)
    }

    private fun dormir(ms: Long) { runCatching { Thread.sleep(ms) } }

    private fun anotar(t: String) {
        traza += t
        while (traza.size > 40) traza.poll()
        Log.i(TAG, t)
    }

    private fun adaptador(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    /** Una vuelta completa: conectar, suscribir, preguntar, cerrar. */
    private fun unaLectura() {
        val adapter = adaptador() ?: run { detalle = "sin BluetoothAdapter"; return }
        val dev: BluetoothDevice = runCatching { adapter.getRemoteDevice(MAC) }.getOrNull()
            ?: run { detalle = "MAC invalida"; return }

        val conectado = CountDownLatch(1)
        val descubierto = CountDownLatch(1)
        val suscrito = CountDownLatch(1)
        val recibidas = LinkedBlockingQueue<ByteArray>()
        val muerto = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, nuevo: Int) {
                runCatching {
                    if (nuevo == BluetoothGatt.STATE_CONNECTED) {
                        // LIMPIAR LA CACHE ANTES DE DESCUBRIR. Android guarda
                        // la tabla de handles por aparato entre reinicios; si
                        // esta rancia, el CCCD se escribe contra un handle que
                        // ya no es, contesta status=0 tan contento, y las
                        // notificaciones no llegan NUNCA. Es la leccion que
                        // costo la mitad de una sesion con el BMS.
                        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
                        if (!g.discoverServices()) descubierto.countDown()
                        conectado.countDown()
                    } else {
                        muerto.set(true)
                        conectado.countDown(); descubierto.countDown(); suscrito.countDown()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                runCatching { descubierto.countDown() }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
            ) {
                runCatching { if (d.uuid == CCCD) suscrito.countDown() }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic,
            ) {
                runCatching { c.value?.let { recibidas.offer(it.copyOf()) } }
            }
        }

        try {
            gatt = dev.connectGatt(context, false, cb)
            if (!conectado.await(MS_CONECTAR, TimeUnit.MILLISECONDS) || muerto.get()) {
                detalle = "no conecto"; anotar("no conecto con $MAC"); return
            }
            if (!descubierto.await(MS_DESCUBRIR, TimeUnit.MILLISECONDS)) {
                detalle = "no descubrio servicios"; return
            }

            // El servicio puede anunciarse como 1234 o como fff0 segun la
            // unidad; las caracteristicas son las mismas en los dos casos.
            val svc = gatt.getService(SERVICIO) ?: gatt.getService(SERVICIO_ALT)
                ?: run { detalle = "sin el servicio 1234 ni fff0"; return }
            val escribir = svc.getCharacteristic(CAR_ESCRIBIR)
                ?: run { detalle = "sin la caracteristica 1235"; return }
            val notificar = svc.getCharacteristic(CAR_NOTIFICAR)
                ?: run { detalle = "sin la caracteristica 1236"; return }

            gatt.setCharacteristicNotification(notificar, true)
            val cccd = notificar.getDescriptor(CCCD)
                ?: run { detalle = "sin CCCD en 1236"; return }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            if (!suscrito.await(MS_SUSCRIBIR, TimeUnit.MILLISECONDS)) {
                detalle = "no acepto las notificaciones"; return
            }

            // Escritura sin acuse si se puede: es lo que hacen las
            // implementaciones que funcionan.
            escribir.writeType =
                if (escribir.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            escribir.value = Alpicool.consulta()
            gatt.writeCharacteristic(escribir)

            // Acumular hasta tener una trama de estado entera. Llegan
            // partidas y a veces pegadas; el troceo lo hace Alpicool.partir.
            val acc = ByteArray(512)
            var usado = 0
            val hasta = System.currentTimeMillis() + MS_RESPUESTA
            while (System.currentTimeMillis() < hasta) {
                val trozo = recibidas.poll(400, TimeUnit.MILLISECONDS) ?: continue
                if (usado + trozo.size > acc.size) usado = 0   // reinicio defensivo
                trozo.copyInto(acc, usado); usado += trozo.size
                val (tramas, consumidos) = Alpicool.partir(acc, usado)
                if (consumidos > 0) {
                    acc.copyInto(acc, 0, consumidos, usado); usado -= consumidos
                }
                val e = tramas.firstNotNullOfOrNull { Alpicool.decodificar(it) }
                if (e != null) {
                    estado = e
                    leidoMs = System.currentTimeMillis()
                    detalle = null
                    anotar("estado: actual=${e.actual} consigna=${e.consigna} " +
                        "encendida=${e.encendida} v=${e.voltaje}")
                    runCatching { alCambiar?.invoke() }
                    return
                }
            }
            detalle = "conecto pero no contesto una trama valida"
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    companion object {
        private const val TAG = "LectorNevera"

        /** Medida en el carro: `ED:67:39:96:50:9B  A1-4XXXXXXXXXXX  uuids=00001234`. */
        const val MAC = "ED:67:39:96:50:9B"

        val SERVICIO: UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        /** Algunas unidades anuncian fff0; las caracteristicas no cambian. */
        val SERVICIO_ALT: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CAR_ESCRIBIR: UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
        val CAR_NOTIFICAR: UUID = UUID.fromString("00001236-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Cada 30 s, como la integracion de Home Assistant. La app de fabrica
         * pregunta cada 2 s, que para un tablero es gastar radio sin ganar
         * nada: una nevera no cambia de temperatura en dos segundos.
         */
        const val PERIODO_MS = 30_000L
        const val SIN_VERSE_MS = 150_000L

        const val MS_CONECTAR = 12_000L
        const val MS_DESCUBRIR = 12_000L
        const val MS_SUSCRIBIR = 5_000L
        const val MS_RESPUESTA = 6_000L
    }
}
