package com.nonosky.s2000dash.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.nonosky.s2000dash.EstadoActual
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

    /**
     * Atendedores acotados. Cuatro bastan: las rutas son de una en una y
     * nadie mas que la laptop del taller habla con esto.
     */
    private val piscina = java.util.concurrent.ThreadPoolExecutor(
        1, MAX_ATENDEDORES, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(COLA_PETICIONES),
        { r -> Thread(r, "puente-atiende").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    /** Peticiones rechazadas por estar lleno. Sirve para saber si pasa. */
    @Volatile
    var rechazadas = 0L
        private set

    fun start() {
        if (running) return
        running = true
        thread(name = "debug-server", isDaemon = true) {
            // Reintentar el bind: si el puerto sigue ocupado por la instancia
            // anterior —al recrearse el servicio, por ejemplo— rendirse a la
            // primera dejaba el radio incomunicado y en silencio, sin ninguna
            // señal de que el puente no estaba.
            var s: ServerSocket? = null
            for (intento in 1..REINTENTOS_BIND) {
                if (!running) return@thread
                s = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                }.getOrNull()
                if (s != null) break
                Log.w(TAG, "Puerto $port ocupado; reintento $intento")
                Thread.sleep(2_000)
            }
            if (s == null) {
                Log.w(TAG, "No se pudo abrir el puerto $port tras $REINTENTOS_BIND intentos")
                return@thread
            }
            try {
                server = s
                Log.i(TAG, "Puente de diagnostico en el puerto $port")
                while (running) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept fallo: ${e.message}")
                        break
                    }
                    // Un hilo por peticion, y SIEMPRE envuelto: en Android
                    // una excepcion que escapa de un hilo mata el proceso
                    // entero. Un simple escaneo de puertos que abriera la
                    // conexion sin escribir nada tumbaba el tablero a mitad
                    // de camino, igual que cortar un curl a la mitad.
                    // Pool ACOTADO, no un hilo por conexion.
                    //
                    // Un hilo por peticion parecia inofensivo hasta que se
                    // sumo el plazo de 40 s: una conexion que se abre y NO
                    // MANDA NADA ocupa un hilo cuarenta segundos. Un escaneo
                    // de puertos, un navegador con varias pestañas, o un
                    // cliente que se corta a la mitad bastan para acumular
                    // decenas de hilos parados en el mismo aparato que ya va
                    // justo. Con pool, lo que sobra se rechaza y se cuenta.
                    val aceptada = runCatching {
                        piscina.execute {
                            runCatching { handle(client) }
                                .onFailure { Log.w(TAG, "peticion fallida: ${it.message}") }
                        }
                        true
                    }.getOrDefault(false)
                    if (!aceptada) {
                        rechazadas++
                        runCatching { client.close() }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Puente caido: ${e.message}")
            } finally {
                runCatching { s.close() }
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
            // DOS plazos, no uno.
            //
            // Antes se ponia 40 s desde el principio "porque el barrido de
            // Bluetooth tarda". Eso hacia que una conexion que no manda NADA
            // se quedara cuarenta segundos ocupando un atendedor. El plazo
            // largo hace falta para ESPERAR LA RESPUESTA de una ruta lenta, no
            // para esperar a que el cliente se digne a hablar.
            sock.soTimeout = PLAZO_LEER_PETICION_MS
            val reader = sock.getInputStream().bufferedReader()
            val request = readLineAcotada(reader) ?: return
            val ruta = request.split(" ").getOrNull(1) ?: "/"
            val path = ruta.substringBefore('?')
            val consulta = parametros(ruta)
            // Consumir el resto de cabeceras para que el cliente no vea RST.
            var cabeceras = 0
            while (cabeceras++ < MAX_CABECERAS) {
                val l = readLineAcotada(reader) ?: break
                if (l.isBlank()) break
            }

            // Ya sabemos que ruta es: si es de las que tardan (barridos,
            // GATT, emparejamientos), ahora si se amplia el plazo.
            if (path in RUTAS_LENTAS) sock.soTimeout = PLAZO_RUTA_LENTA_MS

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
                "/adaptadores" -> {
                    val lista = runCatching { EstadoActual.listarAdaptadores?.invoke() }
                        .getOrNull() ?: emptyList()
                    sendText(out, 200, "application/json",
                        org.json.JSONArray(lista).toString(2))
                }
                "/elegir" -> {
                    val mac = consulta["mac"]
                    val ok = if (mac.isNullOrBlank()) false
                    else runCatching { EstadoActual.elegirAdaptador?.invoke(mac) }
                        .getOrNull() ?: false
                    sendText(out, if (ok) 200 else 400, "application/json",
                        """{"elegido":$ok,"mac":${org.json.JSONObject.quote(mac ?: "")}}""")
                }
                "/buscar" -> {
                    // Bloquea unos segundos mientras barre; por eso el
                    // timeout del socket es generoso en esta ruta.
                    val lista = runCatching { EstadoActual.buscarAdaptadores?.invoke() }
                        .getOrNull() ?: emptyList()
                    sendText(out, 200, "application/json",
                        org.json.JSONArray(lista).toString(2))
                }
                "/emparejar" -> {
                    val mac = consulta["mac"]
                    val res = if (mac.isNullOrBlank()) "falta mac"
                    else runCatching { EstadoActual.emparejarAdaptador?.invoke(mac) }
                        .getOrNull() ?: "sin pantalla"
                    sendText(out, 200, "application/json",
                        """{"resultado":${org.json.JSONObject.quote(res)}}""")
                }
                "/desvincular" -> {
                    // Saca un aparato del Bluetooth del carro sin apagar la
                    // radio. Por omision, el Steren: se habla por el dongle y
                    // no debe quedar vinculado aqui compitiendo.
                    val mac = consulta["mac"] ?: com.nonosky.s2000dash.DashService.MAC_OBD
                    val res = runCatching {
                        EstadoActual.desvincularAdaptador?.invoke(mac)
                    }.getOrNull() ?: "sin pantalla"
                    sendText(out, 200, "application/json",
                        """{"resultado":${org.json.JSONObject.quote(res)}}""")
                }
                "/olvidar" -> {
                    runCatching { EstadoActual.olvidarAdaptador?.invoke() }
                    sendText(out, 200, "application/json", """{"olvidado":true}""")
                }
                "/instalar-companero" -> {
                    val res = runCatching {
                        EstadoActual.instalarCompanero?.invoke(
                            consulta["url"] ?: "", consulta["paquete"] ?: ""
                        )
                    }.getOrNull() ?: "sin pantalla"
                    sendText(out, 200, "application/json",
                        """{"resultado":${org.json.JSONObject.quote(res)}}""")
                }
                "/armar-pin" -> {
                    val pin = consulta["pin"] ?: "1234"
                    runCatching { EstadoActual.armarPin?.invoke(pin) }
                    sendText(out, 200, "application/json",
                        """{"pinArmado":${org.json.JSONObject.quote(pin)}}""")
                }
                "/confirmador" -> sendText(out, 200, "text/plain",
                    EstadoActual.loQueDiceElConfirmador().joinToString(SALTO))
                "/ble" -> {
                    // Barrido LE, que es OTRA radio que la del barrido clasico
                    // de /buscar. Un aparato BLE no aparece en /buscar por mucho
                    // que este encendido a un palmo de distancia.
                    val seg = consulta["segundos"]?.toIntOrNull() ?: 10
                    val lista = runCatching { EstadoActual.barrerBle?.invoke(seg) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el barrido BLE")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/gatt" -> {
                    val mac = consulta["mac"]
                    val seg = consulta["segundos"]?.toIntOrNull() ?: 12
                    val lista = if (mac.isNullOrBlank()) listOf("falta mac")
                    else runCatching { EstadoActual.volcarGatt?.invoke(mac, seg) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el volcado GATT")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/usb" -> {
                    val lista = runCatching { EstadoActual.listarUsb?.invoke() }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el listado USB")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/pantalla" -> sendText(out, 200, "text/plain", mando("volcar", null, null, null, null, 2_500))
                "/tocar" -> sendText(out, 200, "text/plain",
                    mando("tocar", consulta["x"], consulta["y"], null, null, 1_200))
                "/arrastrar" -> sendText(out, 200, "text/plain",
                    mando("arrastrar", consulta["x1"], consulta["y1"],
                        consulta["x2"], consulta["y2"], 1_500))
                "/pulsar" -> sendText(out, 200, "text/plain",
                    mando("pulsar", consulta["texto"], null, null, null, 1_500))
                "/escribir" -> sendText(out, 200, "text/plain",
                    mando("escribir", consulta["texto"], null, null, null, 1_500))
                "/accion" -> sendText(out, 200, "text/plain",
                    mando("accion", consulta["a"], null, null, null, 1_500))
                "/abrir" -> sendText(out, 200, "text/plain",
                    mando("abrir", consulta["paquete"], null, null, null, 2_000))
                "/apps" -> sendText(out, 200, "text/plain",
                    mando("apps", consulta["filtro"], null, null, null, 3_000))
                "/bateria-gatt" -> {
                    // Conecta AHORA con el BMS y devuelve la traza entera. Es
                    // la unica forma de depurar una pila Bluetooth escrita a
                    // mano en un radio sin shell: hay que ver en que escalon
                    // se cayo, no solo que se cayo.
                    val mac = consulta["mac"]
                        ?: EstadoActual.vigilanteBateria?.estado?.mac
                    val lista = if (mac.isNullOrBlank()) {
                        listOf("falta mac y el vigilante no ha detectado ninguna bateria todavia")
                    } else {
                        runCatching { EstadoActual.leerBmsAhora?.invoke(mac) }
                            .getOrNull() ?: listOf("ERROR: el servicio no registro la lectura del BMS")
                    }
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/bateria-fijar" -> {
                    // Zanja la ambiguedad cuando hay mas de un BMS en el aire.
                    val mac = consulta["mac"]
                    val v = EstadoActual.vigilanteBateria
                    v?.macFijada = mac
                    // Se relee de disco para confirmar que quedo guardada de
                    // verdad, en vez de repetir lo que se acaba de mandar: la
                    // pregunta que importa es "¿sobrevive al reinicio?", y eso
                    // solo lo contesta lo que hay en el archivo.
                    val guardada = v?.macFijada
                    sendText(out, 200, "application/json",
                        """{"fijada":${org.json.JSONObject.quote(guardada ?: "")},""" +
                            ""","persistida":${guardada != null}}""")
                }
                "/obd-traza" -> {
                    // Solo mira. A diferencia de /obd-hci, no abre nada ni
                    // pausa la bateria: devuelve la traza del lector que ya
                    // esta corriendo. Preguntar no puede costar el enlace.
                    val l = EstadoActual.lectorObd
                    val lineas = if (l == null) listOf("el motor esta apagado (/fuente?cual=motor&on=1)")
                    else listOf("estado: ${EstadoActual.ultimo.connection}") + l.ultimaTraza
                    sendText(out, 200, "text/plain", lineas.joinToString(SALTO))
                }
                "/obd-hci" -> {
                    val mac = consulta["mac"] ?: "00:1D:A5:68:98:8B"
                    val lista = runCatching { EstadoActual.probarObdHci?.invoke(mac) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el OBD por HCI")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/dongle" -> sendText(out, 200, "text/plain",
                    (com.nonosky.s2000dash.hci.RadioBt.diagnostico() +
                        com.nonosky.s2000dash.hci.RadioBt.traza).joinToString(SALTO))
                "/at" -> {
                    // Comandos separados por coma, sobre el enlace ya vivo.
                    val cmds = (consulta["cmd"] ?: "").split(",")
                        .map { it.trim().uppercase() }
                        .filter { it.isNotEmpty() }
                        .take(12)
                    val lista = if (cmds.isEmpty()) listOf("falta cmd=")
                    else runCatching { EstadoActual.comandoObd?.invoke(cmds) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el canal AT")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/fuente" -> {
                    // /fuente?cual=motor&on=1  — se suben de a una, midiendo.
                    val cual = consulta["cual"] ?: ""
                    val on = consulta["on"] != "0"
                    val r = runCatching { EstadoActual.encenderFuente?.invoke(cual, on) }
                        .getOrNull() ?: "el servicio no registro los interruptores"
                    sendText(out, 200, "application/json",
                        """{"resultado":${org.json.JSONObject.quote(r)}}""")
                }
                "/pin" -> {
                    // Fija a quien emparejar y con que PIN. El emparejamiento
                    // no dice cual es el bueno, solo si acerto: por eso se
                    // pueden probar los candidatos sin desplegar nada.
                    consulta["mac"]?.let { EstadoActual.macAEmparejar = it }
                    consulta["pin"]?.let { EstadoActual.pinDeEmparejamiento = it }
                    sendText(out, 200, "application/json", JSONObject().apply {
                        put("mac", EstadoActual.macAEmparejar ?: JSONObject.NULL)
                        put("pin", EstadoActual.pinDeEmparejamiento)
                    }.toString(2))
                }
                "/termica" -> sendText(out, 200, "text/plain",
                    com.nonosky.s2000dash.Termometro.diagnostico().joinToString(SALTO))
                // Zona por zona, con lo crudo. Es lo unico que distingue
                // "no existe" de "SELinux lo niega" de "contesta basura",
                // y cada caso se arregla distinto.
                "/zonas" -> sendText(out, 200, "text/plain",
                    com.nonosky.s2000dash.Termometro.zonas().joinToString(SALTO))
                // Frecuencia y gobernador por nucleo: distingue "la app
                // calienta el radio" de "la ROM tiene el reloj clavado".
                "/cpu" -> sendText(out, 200, "text/plain",
                    com.nonosky.s2000dash.Termometro.cpu().joinToString(SALTO))
                "/obd-spp" -> {
                    // El mismo dialogo AT que /obd-hci, pero por la radio
                    // interna del head unit en vez del dongle USB.
                    val mac = consulta["mac"] ?: com.nonosky.s2000dash.DashService.MAC_OBD
                    val lista = runCatching { EstadoActual.probarSpp?.invoke(mac) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro la prueba SPP")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/soltar-bt" -> {
                    val r = runCatching { EstadoActual.soltarBluetooth?.invoke() }
                        .getOrNull() ?: "el servicio no registro soltarBluetooth"
                    sendText(out, 200, "text/plain", r)
                }
                "/interruptores" -> {
                    val lista = runCatching { EstadoActual.interruptores?.invoke() }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro los interruptores")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/ajustes" -> {
                    val lista = runCatching {
                        EstadoActual.abrirAjustes?.invoke(consulta["que"], consulta["paquete"])
                    }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro la apertura de ajustes")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/overlays" -> {
                    val lista = runCatching { EstadoActual.listarOverlays?.invoke() }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el listado de overlays")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/tpms" -> sendText(out, 200, "text/plain", tpmsTexto())
                "/bateria" -> {
                    val v = EstadoActual.vigilanteBateria
                    if (v == null) sendText(out, 200, "text/plain", "el servicio no arranco el vigilante")
                    else {
                        val b = v.estado
                        sendText(out, 200, "application/json", JSONObject().apply {
                            put("enlace", b.enlace.name)
                            put("detalle", b.detalle ?: JSONObject.NULL)
                            put("mac", b.mac ?: JSONObject.NULL)
                            put("nombre", b.nombre ?: JSONObject.NULL)
                            put("rssi", b.rssi ?: JSONObject.NULL)
                            put("vistaHaceMs", if (b.vistaMs == 0L) JSONObject.NULL
                                else System.currentTimeMillis() - b.vistaMs)
                            put("voltaje", b.voltaje?.toDouble() ?: JSONObject.NULL)
                            put("soc", b.soc ?: JSONObject.NULL)
                            put("corrienteA", b.corrienteA?.toDouble() ?: JSONObject.NULL)
                            put("potenciaW", b.potenciaW?.toDouble() ?: JSONObject.NULL)
                            put("temperaturaC", b.temperaturaC ?: JSONObject.NULL)
                            put("celdas", org.json.JSONArray(b.celdas.map { it.toDouble() }))
                            put("candidatas", org.json.JSONArray(b.candidatas))
                        }.toString(2))
                    }
                }
                "/serial" -> {
                    // Vuelca lo que escupa el USB-serial, sin decodificar. La
                    // velocidad VERIFICADA en vivo del receptor TPMS es 19200;
                    // por eso es el valor por defecto y no 9600, que devolvia
                    // bytes con bits sueltos y parecia un formato raro.
                    val baudios = consulta["baudios"]?.toIntOrNull() ?: 19200
                    val seg = consulta["segundos"]?.toIntOrNull() ?: 8
                    val lista = runCatching { EstadoActual.volcarUsbSerial?.invoke(baudios, seg) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro el volcado serial")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/hci" -> {
                    // Le habla HCI al dongle USB directamente: la pila del
                    // radio no sirve y el kernel no trae btusb, pero Android
                    // si nos da permiso sobre el aparato USB.
                    val vid = consulta["vid"]?.removePrefix("0x")?.toIntOrNull(16)
                    val pid = consulta["pid"]?.removePrefix("0x")?.toIntOrNull(16)
                    val lista = runCatching { EstadoActual.interrogarHci?.invoke(vid, pid) }
                        .getOrNull() ?: listOf("ERROR: el servicio no registro la sonda HCI")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/hci-ble" -> {
                    val seg = consulta["segundos"]?.toIntOrNull() ?: 12
                    val vid = consulta["vid"]?.removePrefix("0x")?.toIntOrNull(16)
                    val pid = consulta["pid"]?.removePrefix("0x")?.toIntOrNull(16)
                    val activo = consulta["activo"] == "1"
                    val crudo = consulta["crudo"] == "1"
                    val lista = runCatching {
                        EstadoActual.barrerBleHci?.invoke(seg, vid, pid, activo, crudo)
                    }.getOrNull() ?: listOf("ERROR: el servicio no registro el barrido HCI")
                    sendText(out, 200, "text/plain", lista.joinToString(SALTO))
                }
                "/bluetooth" -> {
                    // Se ha visto la pila de este radio apagarse sola tras
                    // varios emparejamientos fallidos. Sin esta ruta, cada vez
                    // que pasa hay que ir al carro a encenderlo a mano.
                    val encender = consulta["on"] != "0"
                    val res = runCatching { EstadoActual.encenderBluetooth?.invoke(encender) }
                        .getOrNull() ?: "ERROR: el servicio no registro el control de Bluetooth"
                    sendText(out, 200, "application/json",
                        """{"resultado":${org.json.JSONObject.quote(res)}}""")
                }
                "/" -> sendText(out, 200, "text/plain", HELP)
                else -> sendText(out, 404, "text/plain", "no existe: $path")
            }
            out.flush()
        }
    }

    /**
     * Manda algo al confirmador y espera su respuesta.
     *
     * El confirmador contesta por difusion, o sea de forma asincrona, asi que
     * hay que vaciar antes y esperar despues. El plazo se pasa por parametro
     * porque un volcado de arbol tarda bastante mas que un toque, y esperar
     * lo mismo para todo o corta respuestas o hace lento lo rapido.
     */
    private fun mando(
        comando: String,
        a: String?, b: String?, c: String?, d: String?,
        esperaMs: Long,
    ): String {
        val enviar = EstadoActual.mandarAlConfirmador
            ?: return "el servicio no registro el canal de mando"
        EstadoActual.olvidarLoDelConfirmador()
        runCatching { enviar(comando, a, b, c, d) }
            .onFailure { return "no se pudo difundir el mando: ${it.message}" }

        // Se sondea en vez de esperar el plazo entero: un toque contesta en
        // 50 ms y no tiene sentido tener el socket parado dos segundos.
        val hasta = System.currentTimeMillis() + esperaMs
        var ultimo = 0
        var quieto = 0
        while (System.currentTimeMillis() < hasta) {
            Thread.sleep(120)
            val n = EstadoActual.loQueDiceElConfirmador().size
            if (n > 0 && n == ultimo) {
                // Dos vueltas sin lineas nuevas: ya termino de contestar.
                if (++quieto >= 3) break
            } else {
                quieto = 0
            }
            ultimo = n
        }

        val dicho = EstadoActual.loQueDiceElConfirmador()
        return if (dicho.isEmpty()) {
            "SIN RESPUESTA del confirmador.\n" +
                "Lo mas probable es que su servicio de accesibilidad este apagado: " +
                "Android lo desactiva cada vez que se actualiza ese APK."
        } else {
            dicho.joinToString(SALTO)
        }
    }

    /**
     * Lo que el TPMS sabe, con sus contadores de calidad.
     *
     * Los contadores importan tanto como las presiones: un XOR que falla
     * seguido significa velocidad mal puesta o cable con ruido, y sin verlos
     * un tablero en blanco parece un receptor muerto cuando en realidad se
     * esta descartando todo por una razon corregible.
     */
    private fun tpmsTexto(): String {
        val lector = EstadoActual.lectorTpms
            ?: return "el servicio no arranco el lector TPMS"
        val ahora = System.currentTimeMillis()
        val st = lector.estado()
        val d = lector.diagnostico()
        val sb = StringBuilder()
        sb.append("enlace: ").append(lector.enlace)
        lector.enlaceDetalle?.let { sb.append(" (").append(it).append(")") }
        sb.append(SALTO).append("reaperturas: ").append(lector.reaperturas)
        sb.append(SALTO).append(SALTO)
        for (r in com.nonosky.s2000dash.tpms.Rueda.values()) {
            val l = st.de(r)
            sb.append(r.corta).append(": ")
            if (l == null) {
                sb.append("sin sensor")
            } else {
                sb.append(l.presionPsi?.let { String.format("%.1f psi", it) } ?: "-- psi")
                sb.append("  ").append(l.temperaturaC?.let { "$it C" } ?: "-- C")
                sb.append("  edad=").append((ahora - l.medidaMs) / 1000).append("s")
                if (l.rancia(ahora)) sb.append("  RANCIA")
                sb.append("  crudo=").append(
                    String.format("%02X %02X %02X", l.trama.crudoA, l.trama.crudoB, l.trama.crudoC)
                )
            }
            sb.append(SALTO)
        }
        if (st.otras.isNotEmpty()) {
            sb.append(SALTO).append("ids que no son de rueda:").append(SALTO)
            st.otras.forEach { (id, t) ->
                sb.append(String.format("  id=%02X  %02X %02X %02X", id, t.crudoA, t.crudoB, t.crudoC))
                sb.append(SALTO)
            }
        }
        sb.append(SALTO).append("tramas buenas=").append(d.tramasBuenas)
        sb.append("  xor malo=").append(d.tramasXorMalo)
        sb.append("  largo raro=").append(d.tramasLargoRaro)
        sb.append("  bytes tirados=").append(d.bytesDescartados)
        return sb.toString()
    }

    /**
     * Lee una linea con tope de longitud.
     *
     * `readLine()` a secas crece sin limite: un cliente que mande bytes sin
     * salto de linea puede agotar la memoria del proceso.
     */
    private fun readLineAcotada(reader: java.io.BufferedReader): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_LINEA) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            // 10 = salto de linea, 13 = retorno de carro.
            if (c == 10) return sb.toString().trimEnd(13.toChar())
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Parametros de la URL, sin dependencias externas. */
    private fun parametros(ruta: String): Map<String, String> {
        val q = ruta.substringAfter('?', "")
        if (q.isBlank()) return emptyMap()
        return q.split('&').mapNotNull { par ->
            val i = par.indexOf('=')
            if (i <= 0) null
            else runCatching {
                java.net.URLDecoder.decode(par.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(par.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    private fun stateJson(): String {
        val s = stateProvider()
        val u = updaterProvider()
        return JSONObject().apply {
            put("connection", s.connection.name)
            // Si la pantalla no esta, no hay sondeo: decirlo en vez de dejar
            // que el ultimo estado bueno pase por actual.
            put("pantallaViva", viewProvider() != null)
            put("adaptador", EstadoActual.adaptadorElegido ?: JSONObject.NULL)
            put("ultimoErrorEnlace", EstadoActual.ultimoErrorEnlace ?: JSONObject.NULL)
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
        val done = CountDownLatch(1)
        // draw() tiene que correr en el hilo de UI, y la peticion HTTP viene
        // de un hilo de red.
        // Dibujar SI tiene que ser en el hilo de UI, pero comprimir a PNG
        // no: son decenas de milisegundos que, en la CPU de este radio, se
        // notan como tirones en la aguja. Se dibuja en Main y se comprime
        // aqui, en el hilo de red.
        var bmp: Bitmap? = null
        Handler(Looper.getMainLooper()).post {
            try {
                if (view.width > 0 && view.height > 0) {
                    val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(b))
                    bmp = b
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo dibujar: ${e.message}")
            } finally {
                done.countDown()
            }
        }
        if (!done.await(5, TimeUnit.SECONDS)) return null

        val imagen = bmp ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            imagen.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprimir: ${e.message}")
            null
        } finally {
            imagen.recycle()
        }
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
        /** Salto de linea, aparte para no romper el literal. */
        val SALTO = System.lineSeparator()
        const val REINTENTOS_BIND = 10
        const val MAX_LINEA = 4_096
        const val MAX_CABECERAS = 64

        /** Atendedores a la vez. Mas seria malgastar en este aparato. */
        const val MAX_ATENDEDORES = 4

        /** Peticiones en espera antes de empezar a rechazar. */
        const val COLA_PETICIONES = 8

        /** Lo que se le concede a un cliente para mandar su peticion. */
        const val PLAZO_LEER_PETICION_MS = 3_000

        /** Lo que se concede DESPUES, y solo a las rutas que de verdad tardan. */
        const val PLAZO_RUTA_LENTA_MS = 120_000

        /** Las que hacen radio o USB y pueden tardar de verdad. */
        val RUTAS_LENTAS = setOf(
            "/buscar", "/emparejar", "/ble", "/gatt", "/hci", "/hci-ble",
            "/serial", "/bateria-gatt", "/obd-hci", "/obd-spp", "/update",
            "/instalar-companero", "/pantalla", "/apps",
        )
        val HELP = """
            S2000 Dash - puente de diagnostico
              /state     estado del vehiculo y del enlace (JSON)
              /shot.png  el tablero tal como se ve ahora
              /log       bitacora de actualizaciones
              /update    busca e instala version nueva
              /adaptadores  adaptadores Bluetooth emparejados
              /elegir?mac=  elige adaptador OBD ya emparejado
              /buscar       barre el aire en busca de adaptadores
              /emparejar?mac=  empareja y elige
              /olvidar      olvida el adaptador guardado
              /bluetooth?on=1  enciende (o apaga con on=0) la radio Bluetooth
              /ble?segundos=10 barrido Bluetooth LE con el anuncio crudo
              /gatt?mac=       servicios y caracteristicas de un aparato BLE
              /usb             lo que hay colgado del USB (VID, PID, endpoints)
              /pantalla        vuelca el arbol de la ventana activa, con coordenadas
              /tocar?x=&y=     toca un punto de la pantalla
              /arrastrar?x1=&y1=&x2=&y2=   arrastra
              /pulsar?texto=   pulsa el nodo que diga ese texto
              /escribir?texto= escribe en el campo editable que haya
              /accion?a=atras|inicio|recientes|notificaciones
              /abrir?paquete=  abre una app
              /apps?filtro=    lista lo instalado
              /at?cmd=0100,0120  manda comandos crudos al ELM327
              /tpms            presiones y temperaturas de las cuatro llantas
              /pin?mac=&pin=   a quien emparejar y con que PIN
              /bateria         estado del BMS de litio por BLE
              /bateria-gatt?mac=  conecta por GATT y lee el BMS, con traza
              /obd-hci?mac=    OBD por RFCOMM sobre HCI crudo, con traza
              /dongle          quien tiene tomado el dongle Bluetooth
              /serial?baudios=19200&segundos=8  vuelca el USB-serial en crudo
              /hci?vid=&pid=   interroga por HCI al dongle Bluetooth USB
              /hci-ble?segundos=12&activo=1&crudo=1  barrido BLE por HCI crudo
        """.trimIndent()
    }
}
