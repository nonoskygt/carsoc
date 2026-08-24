package com.nonosky.s2000dash.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.nonosky.s2000dash.VehicleState
import com.nonosky.s2000dash.selfupdate.UpdateChecker
import com.nonosky.s2000dash.selfupdate.UpdateState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Servidor HTTP diminuto para diagnosticar el tablero desde la laptop.
 *
 * Nace de una limitacion concreta del radio: sin root no hay `screencap`,
 * `dumpsys` esta vetado y `logcat` solo muestra el UID que lo invoca. Sin
 * esto, la unica forma de saber que hace la app es que alguien le tome una
 * foto a la pantalla — que es justo lo que queremos dejar de necesitar.
 *
 * Rutas:
 *  - `GET /state`   estado del vehiculo y del enlace, en JSON
 *  - `GET /shot.png` la vista del tablero dibujada tal cual se ve
 *  - `GET /log`     bitacora de actualizaciones
 *  - `GET /update`  fuerza revision e instalacion de una version nueva
 *
 * Escucha solo en la red local del taller y no expone nada que escriba en
 * el carro: se puede mirar y se puede pedir una actualizacion, nada mas.
 */
class DebugServer(
    private val port: Int = PORT,
    private val stateProvider: () -> VehicleState,
    private val viewProvider: () -> View?,
    private val updaterProvider: () -> UpdateChecker,
) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "debug-server", isDaemon = true) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                server = s
                Log.i(TAG, "Puente de diagnostico en el puerto $port")
                while (running) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept fallo: ${e.message}")
                        break
                    }
                    // Un hilo por peticion: son de una en una y esporadicas.
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir el puerto $port: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5_000
            val reader = sock.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            val path = request.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            // Consumir el resto de cabeceras para que el cliente no vea RST.
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isBlank()) break
            }

            val out = sock.getOutputStream()
            when (path) {
                "/state" -> sendText(out, 200, "application/json", stateJson())
                "/log" -> sendText(out, 200, "text/plain", UpdateState.snapshot().joinToString("\n"))
                "/update" -> {
                    val started = runCatching { updaterProvider().checkAndInstall() }.getOrDefault(false)
                    sendText(out, 200, "application/json", """{"started":$started}""")
                }
                "/shot.png" -> {
                    val png = screenshot()
                    if (png == null) sendText(out, 503, "text/plain", "sin vista que dibujar")
                    else sendBytes(out, 200, "image/png", png)
                }
                "/" -> sendText(out, 200, "text/plain", HELP)
                else -> sendText(out, 404, "text/plain", "no existe: $path")
            }
            out.flush()
        }
    }

    private fun stateJson(): String {
        val s = stateProvider()
        val u = updaterProvider()
        return JSONObject().apply {
            put("connection", s.connection.name)
            put("protocol", s.protocol ?: JSONObject.NULL)
            put("rpm", s.rpm ?: JSONObject.NULL)
            put("speedKmh", s.speedKmh ?: JSONObject.NULL)
            put("coolantC", s.coolantC ?: JSONObject.NULL)
            put("iatC", s.iatC ?: JSONObject.NULL)
            put("loadPct", s.loadPct ?: JSONObject.NULL)
            put("batteryV", s.batteryV?.toDouble() ?: JSONObject.NULL)
            put("vtecActive", s.vtecActive)
            put("sessionMaxRpm", s.sessionMaxRpm)
            put("nowMs", System.currentTimeMillis())
            put("rpmAtMs", s.rpmAtMs)
            put("installedVersionCode", u.installedVersionCode())
            put("remoteVersionCode", UpdateState.remoteVersionCode)
            put("lastCheckMs", UpdateState.lastCheckMs)
        }.toString(2)
    }

    /**
     * La app se dibuja a si misma en un bitmap.
     *
     * No es una captura del sistema —eso necesitaria root— sino la propia
     * vista renderizada de nuevo. Para el tablero es equivalente: es todo
     * lo que hay en pantalla.
     */
    private fun screenshot(): ByteArray? {
        val view = viewProvider() ?: return null
        var png: ByteArray? = null
        val done = CountDownLatch(1)
        // draw() tiene que correr en el hilo de UI, y la peticion HTTP viene
        // de un hilo de red.
        Handler(Looper.getMainLooper()).post {
            try {
                if (view.width > 0 && view.height > 0) {
                    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bmp))
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    bmp.recycle()
                    png = bos.toByteArray()
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo dibujar: ${e.message}")
            } finally {
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return png
    }

    private fun sendText(out: OutputStream, code: Int, type: String, body: String) =
        sendBytes(out, code, "$type; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun sendBytes(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
    }

    private companion object {
        const val TAG = "DebugServer"
        const val PORT = 8099
        val HELP = """
            S2000 Dash - puente de diagnostico
              /state     estado del vehiculo y del enlace (JSON)
              /shot.png  el tablero tal como se ve ahora
              /log       bitacora de actualizaciones
              /update    busca e instala version nueva
        """.trimIndent()
    }
}
