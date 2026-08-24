package com.nonosky.s2000dash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Transporte sobre Bluetooth Clasico (RFCOMM / SPP).
 *
 * El permiso `BLUETOOTH_CONNECT` lo pide y verifica [com.nonosky.s2000dash.DashActivity]
 * antes de construir esta clase; por eso los `SuppressLint`.
 */
@SuppressLint("MissingPermission")
class SppTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override fun connect() {
        // El descubrimiento activo mata el throughput de RFCOMM.
        runCatching { adapter?.cancelDiscovery() }

        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: IOException) {
            throw IOException("No se pudo crear el socket RFCOMM", e)
        }

        try {
            s.connect()
        } catch (first: IOException) {
            // Muchos clones fallan el connect() normal pero aceptan el canal 1
            // por reflexion. Es un truco conocido y vale intentarlo antes de
            // rendirse: sin el, varios adaptadores baratos nunca conectan.
            runCatching { s.close() }
            val fallback = try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (second: Exception) {
                throw IOException("Fallo RFCOMM (normal y canal 1): ${first.message}", second)
            }
            attach(fallback)
            return
        }
        attach(s)
    }

    private fun attach(s: BluetoothSocket) {
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("Transporte no conectado")
        out.write(bytes)
        out.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val ins = input ?: throw IOException("Transporte no conectado")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(64)

        while (System.currentTimeMillis() < deadline) {
            val available = ins.available()
            if (available <= 0) {
                // Sondeo corto: el K-line tarda, pero no queremos quemar CPU
                // en un head unit debil.
                Thread.sleep(4)
                continue
            }
            val n = ins.read(buf, 0, minOf(available, buf.size))
            if (n <= 0) continue
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == PROMPT) return sb.toString()
                sb.append(c)
            }
        }
        // Timeout: devolvemos lo que haya. Truncado es asunto del parser.
        return sb.toString()
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        Log.d(TAG, "Transporte cerrado")
    }

    companion object {
        private const val TAG = "SppTransport"
        private const val PROMPT = '>'
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
