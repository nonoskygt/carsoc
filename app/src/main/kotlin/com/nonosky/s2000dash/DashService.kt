package com.nonosky.s2000dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nonosky.s2000dash.bateria.CanalGattDisponible
import com.nonosky.s2000dash.bateria.LectorBmsAndroid
import com.nonosky.s2000dash.bateria.LectorBmsDirecto
import com.nonosky.s2000dash.bateria.LectorBmsGatt
import com.nonosky.s2000dash.bateria.CanalGattHci
import com.nonosky.s2000dash.bateria.VigilanteBateria
import com.nonosky.s2000dash.debug.DebugServer
import com.nonosky.s2000dash.descubrimiento.Descubridor
import com.nonosky.s2000dash.hci.SondaHci
import com.nonosky.s2000dash.tpms.TpmsReader
import com.nonosky.s2000dash.selfupdate.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Servicio en primer plano que sostiene el puente de diagnostico y la
 * revision periodica de actualizaciones.
 *
 * Nace de un fallo concreto: el puente y el buscador vivian dentro de la
 * pantalla del tablero, asi que en cuanto el usuario abria otra app y la
 * actividad se destruia, el radio dejaba de ser alcanzable y de revisar
 * actualizaciones. Justo lo contrario de lo que se buscaba — un tablero
 * que se mantiene solo no puede depender de que alguien lo tenga abierto.
 */
class DashService : Service() {

    private var puente: DebugServer? = null
    private var lectorTpms: TpmsReader? = null
    private var vigilante: VigilanteBateria? = null
    private var lectorObd: com.nonosky.s2000dash.obd.LectorObdHci? = null

    /** El sondeo por la radio INTERNA del head unit (RFCOMM/SPP). */
    private var sondeoInterno: com.nonosky.s2000dash.obd.PollScheduler? = null
    private var alcanceInterno: CoroutineScope? = null
    private var enlaceInterno: Job? = null

    /** La radio del propio head unit. Null si este aparato no trae. */
    private val radioInterna: android.bluetooth.BluetoothAdapter? by lazy {
        runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
        }.getOrNull()
    }
    private val actualizador by lazy { UpdateChecker(applicationContext) }

    @Volatile
    private var vivo = false

    /** Para que el termometro arranque aunque `vivo` aun no sea true. */
    @Volatile
    private var arranco = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        arrancarEnPrimerPlano()

        // Antes que nada: sin contexto, el termometro se queda solo con
        // sysfs, que es justo lo que este radio no deja leer.
        Termometro.iniciar(applicationContext)

        puente = DebugServer(
            stateProvider = { EstadoActual.ultimo },
            viewProvider = { EstadoActual.vista },
            updaterProvider = { actualizador },
        ).also { it.start() }

        registrarDescubrimiento()

        // ARRANQUE MINIMO, a proposito.
        //
        // Solo el TPMS, que lee un puerto serie y cuesta casi nada. El motor y
        // la bateria quedan APAGADOS hasta que alguien los encienda por HTTP.
        //
        // No es prudencia teorica: este radio se apago TRES veces por calor
        // con todo corriendo, y la ultima el dueño tuvo que cortarle la
        // corriente al vehiculo. Pedirle que instale otra version que arranque
        // sola con todo encendido seria repetir el experimento con su carro.
        //
        // Ahora se sube de a una fuente, midiendo /termica entre cada paso.
        arrancarTpms()
        arrancarTermometro()
        registrarInterruptores()

        vivo = true
        arranco = true
        arrancarRevisionPeriodica()
        Log.i(TAG, "Servicio arriba")
    }

    /**
     * El descubrimiento vive AQUI, no en la pantalla.
     *
     * El emparejamiento OBD se registra desde la Activity porque necesita
     * mostrar la lista al usuario. Estas fuentes no: la bateria y el TPMS
     * hay que poder buscarlos con el tablero cerrado, desde la laptop, sin
     * que nadie toque el radio. Colgarlas de la Activity las mataria en
     * cuanto el usuario abriera otra app — el mismo error que ya dejo al
     * radio incomunicado una vez.
     */
    private fun registrarDescubrimiento() {
        val ctx = applicationContext
        val adapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        }.getOrNull()

        EstadoActual.barrerBle = { segundos ->
            Descubridor.barrerBle(ctx, adapter, segundos)
        }
        EstadoActual.volcarGatt = { mac, segundos ->
            Descubridor.volcarGatt(ctx, adapter, mac, segundos)
        }
        EstadoActual.listarUsb = {
            Descubridor.listarUsb(ctx)
        }
        // Estos dos son la salida de la pescadilla del overlay: dicen quien
        // tapa la pantalla y abren donde se le quita el permiso, sin
        // depender del confirmador — que es justo lo que no se puede
        // encender mientras el overlay siga puesto.
        EstadoActual.abrirAjustes = { que, paquete -> Ajustes.abrir(ctx, que, paquete) }
        EstadoActual.interruptores = { Ajustes.interruptores(ctx) }
        EstadoActual.soltarBluetooth = { soltarBluetooth() }

        // El diagnostico del BMS se registra SIEMPRE, no solo con la bateria
        // encendida. Hacia falta apagar el vigilante para poder mirar por que
        // fallaba, y apagarlo quitaba justo la ruta con la que se mira. Una
        // herramienta de diagnostico no puede depender de lo que diagnostica.
        EstadoActual.leerBmsAhora = { mac ->
            // Con sondas: esta ruta es para depurar, y ahi si compensa pagar
            // el atasco de cola a cambio de saber si el aparato contesta algo.
            val lectura = LectorBmsAndroid.leer(ctx, radioInterna, mac)
                // Sin sondas: se midio que una lectura de 2a00 que no
                // contesta deja la operacion en vuelo y la cola de GATT
                // rechaza las peticiones que vienen detras. La sonda impedia
                // ver si el resto funcionaba.
            lectura.traza + lectura.problemas +
                listOfNotNull(
                    lectura.basico?.let { "BASICO: $it" },
                    lectura.celdas?.let { "CELDAS: $it" },
                )
        }
        EstadoActual.listarOverlays = { Ajustes.overlays(ctx) }
        EstadoActual.volcarUsbSerial = { baudios, segundos ->
            Descubridor.volcarUsbSerial(ctx, baudios, segundos)
        }
        EstadoActual.interrogarHci = { vid, pid ->
            SondaHci.interrogar(ctx, vid, pid)
        }
        EstadoActual.barrerBleHci = { segundos, vid, pid, activo, crudo ->
            SondaHci.barrerBle(ctx, segundos, vid, pid, activo, crudo)
        }
        EstadoActual.mandarAlConfirmador = { comando, a, b, c, d ->
            runCatching {
                sendBroadcast(
                    Intent("com.nonosky.s2000dash.MANDO")
                        .setPackage("com.nonosky.s2000dash.confirmador")
                        .putExtra("comando", comando)
                        .putExtra("a", a)
                        .putExtra("b", b)
                        .putExtra("c", c)
                        .putExtra("d", d)
                )
            }.onFailure { Log.w(TAG, "no se pudo mandar '$comando': ${it.message}") }
        }
        EstadoActual.pidsSoportados = {
            val salida = mutableListOf<String>()
            val adapter = radioInterna
            val dev = runCatching { adapter?.getRemoteDevice(MAC_OBD) }.getOrNull()
            if (dev == null) salida += "ERROR: no se pudo resolver el adaptador"
            else {
                val t = com.nonosky.s2000dash.obd.SppTransport(dev, adapter)
                try {
                    t.connect()
                    val sesion = com.nonosky.s2000dash.obd.Elm327Session(t)
                    sesion.initialize()
                    var base = 0x00
                    var vueltas = 0
                    while (vueltas < 4) {
                        val cmd = "01%02X".format(base)
                        val raw = sesion.queryRaw(cmd)
                        salida += "--- $cmd -> ${raw ?: "sin respuesta"}"
                        val lista = com.nonosky.s2000dash.obd.PidDecoder.soportados(raw, base)
                        if (lista.isEmpty()) break
                        for (pid in lista) {
                            if (pid % 0x20 == 0) continue  // el indice del bloque siguiente
                            val n = com.nonosky.s2000dash.obd.PidDecoder.NOMBRES[pid]
                            salida += "  01%02X  %s".format(pid, n ?: "(sin nombre conocido)")
                        }
                        if (!com.nonosky.s2000dash.obd.PidDecoder.hayMasBloques(raw, base)) break
                        base += 0x20
                        vueltas++
                    }
                } catch (e: Exception) {
                    salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    runCatching { t.close() }
                }
            }
            salida
        }
        EstadoActual.probarSpp = { mac ->
            val salida = mutableListOf<String>()
            val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            if (adapter == null) salida += "ERROR: este radio no expone BluetoothAdapter"
            else if (dev == null) salida += "ERROR: no se pudo resolver $mac"
            else {
                salida += "vinculo actual: ${runCatching { dev.bondState }.getOrNull()} (12=vinculado)"
                // El descubrimiento activo mata el throughput de RFCOMM, y
                // SppTransport ya lo cancela, pero si el dongle esta dentro
                // del ELM327 no hay nada que hacer: solo atiende a uno.
                val t = com.nonosky.s2000dash.obd.SppTransport(dev, adapter)
                try {
                    t.connect()
                    salida += "socket RFCOMM abierto: ${t.isConnected}"
                    val sesion = com.nonosky.s2000dash.obd.Elm327Session(t)
                    val info = sesion.initialize()
                    salida += "ATDP dijo: ${info.describedAs} (fallback=${info.usedFallback})"
                    salida += "voltaje del adaptador: ${sesion.readVoltage() ?: "n/d"}"
                    salida += "RPM crudo: ${sesion.queryRaw("010C") ?: "sin respuesta"}"
                    salida += "agua crudo: ${sesion.queryRaw("0105") ?: "sin respuesta"}"
                    salida += "admision crudo: ${sesion.queryRaw("010B") ?: "sin respuesta"}"
                    // Los que se resisten, en crudo: si la ECU los declara
                    // soportados en el 0100 y aun asi no llegan, la respuesta
                    // literal es lo unico que distingue "no contesta" de
                    // "contesta algo que el decodificador rechaza".
                    for (p in listOf("0104", "0106", "0107", "0101", "0111", "010E")) {
                        salida += "$p crudo: ${sesion.queryRaw(p) ?: "sin respuesta"}"
                    }
                } catch (e: Exception) {
                    salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    runCatching { t.close() }
                }
            }
            salida
        }
        EstadoActual.probarObdHci = { mac ->
            val salida = mutableListOf<String>()
            // El OBD necesita el dongle sin interrupciones: se pausa el
            // vigilante de la bateria y se le da tiempo a soltarlo. Sin esto,
            // el OBD pierde todas las carreras contra un vigilante que entra
            // cada 30 segundos y se queda dentro veinte.
            val v = EstadoActual.vigilanteBateria
            v?.pausar()
            salida += "vigilante de bateria en pausa; esperando que suelte el dongle"
            var esperas = 0
            while (com.nonosky.s2000dash.hci.DuenoDongle.ocupadoPor() != null && esperas < 30) {
                Thread.sleep(1_000); esperas++
            }
            salida += "dongle libre tras ${esperas}s"
            val t = com.nonosky.s2000dash.obd.HciObdTransport(ctx, mac)
            try {
                t.connect()
                salida += t.traza
                salida += "--- dialogo AT ---"
                val sesion = com.nonosky.s2000dash.obd.Elm327Session(t)
                val info = sesion.initialize()
                salida += "ATDP dijo: ${info.describedAs} (fallback=${info.usedFallback})"
                salida += "voltaje del adaptador: ${sesion.readVoltage() ?: "n/d"}"
                salida += "RPM crudo: ${sesion.queryRaw("010C") ?: "sin respuesta"}"
                salida += "agua crudo: ${sesion.queryRaw("0105") ?: "sin respuesta"}"
            } catch (e: Exception) {
                salida += t.traza
                salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { t.close() }
                v?.reanudar()
                salida += "vigilante de bateria reanudado"
            }
            salida
        }
        EstadoActual.encenderBluetooth = { encender ->
            Descubridor.encenderBluetooth(adapter, encender)
        }
    }

    /**
     * Arranca la lectura del TPMS en su propio hilo.
     *
     * Envuelto entero: si el receptor no esta, o el USB falla, el tablero
     * tiene que seguir en pie. Antes TODO colgaba del enlace OBD y sin
     * adaptador no habia nada en pantalla; repetir ese error con el TPMS
     * seria no haber aprendido nada.
     */
    private fun arrancarTpms() {
        runCatching {
            val lector = TpmsReader(applicationContext)
            lectorTpms = lector
            EstadoActual.lectorTpms = lector
            lector.alCambiar = { runCatching { EstadoActual.alCambiarTpms?.invoke() } }
            lector.arrancar()
        }.onFailure { Log.w(TAG, "TPMS no arranco: ${it.message}") }
    }

    /**
     * Arranca la vigilancia de la bateria. Envuelto, como todo lo demas: si
     * el dongle no esta, el resto del tablero sigue en pie.
     */
    /**
     * La bateria por la radio INTERNA del radio, sin dongle.
     *
     * Se comprobo antes de escribirlo: el volcado GATT por la radio interna
     * lista el servicio `ff00` del BMS con `ff01` notificando y `ff02`
     * escribiendo. O sea que la pila de Android llega al BMS igual que
     * llegaba el dongle, y ademas hace el descubrimiento y el MTU por dentro.
     */
    private fun arrancarBateriaInterna() {
        runCatching {
            val ctx = applicationContext
            // Cablear el camino corto ANTES de arrancar el vigilante: si
            // arranca primero, su primera ronda se va por el dongle que no
            // esta y publica "sin dongle" sin motivo.
            LectorBmsDirecto.leer = { mac ->
                LectorBmsAndroid.leer(ctx, radioInterna, mac)
            }
            LectorBmsDirecto.barrer = { segundos ->
                LectorBmsAndroid.barrer(radioInterna, segundos)
            }

            val v = VigilanteBateria(ctx)
            vigilante = v
            EstadoActual.vigilanteBateria = v
            v.alCambiar = { runCatching { EstadoActual.alCambiarBateria?.invoke() } }
            v.arrancar()

            EstadoActual.leerBmsAhora = { mac ->
                val lectura = LectorBmsAndroid.leer(ctx, radioInterna, mac, sondas = true)
                lectura.traza + lectura.problemas +
                    listOfNotNull(
                        lectura.basico?.let { "BASICO: $it" },
                        lectura.celdas?.let { "CELDAS: $it" },
                    )
            }
        }.onFailure { Log.w(TAG, "la bateria por radio interna no arranco: ${it.message}") }
    }

    private fun arrancarBateria() {
        runCatching {
            // Enchufa la capa ACL/L2CAP al lector del BMS. Mientras esto fuera
            // null, el vigilante sabia que el GATT no estaba cableado y lo
            // decia en el tablero en vez de fingir que buscaba.
            CanalGattDisponible.fabrica = { mac ->
                CanalGattHci.abrir(applicationContext, mac).first
            }
            val v = VigilanteBateria(applicationContext)
            vigilante = v
            EstadoActual.vigilanteBateria = v
            v.alCambiar = { runCatching { EstadoActual.alCambiarBateria?.invoke() } }
            v.arrancar()

            EstadoActual.leerBmsAhora = { mac ->
                val salida = mutableListOf<String>()
                val (canal, traza) = CanalGattHci.abrir(applicationContext, mac)
                salida += traza
                if (canal == null) {
                    salida += "no se pudo abrir el canal GATT"
                } else {
                    try {
                        val lector = LectorBmsGatt(canal)
                        val lectura = lector.leerTodo()
                        salida += lectura.traza
                        salida += lectura.problemas
                        lectura.basico?.let { salida += "BASICO: $it" }
                        lectura.celdas?.let { salida += "CELDAS: $it" }
                    } finally {
                        runCatching { canal.cerrar() }
                    }
                }
                salida
            }
        }.onFailure { Log.w(TAG, "vigilante de bateria no arranco: ${it.message}") }
    }

    /**
     * Arranca el motor por el dongle, no por la pila de Android.
     *
     * La pila interna esta apagada a proposito: era ella la que le robaba el
     * adaptador Steren al dongle y provocaba el PAGE TIMEOUT. Todo el
     * Bluetooth del tablero pasa ahora por el dongle USB.
     */
    /**
     * Sondea el motor por el Bluetooth INTERNO del radio.
     *
     * El head unit viejo no podia: emparejaba y moria en `BOND_NONE`, y las
     * cuatro vias de RFCOMM fallaban igual. Por eso se escribio toda la pila
     * HCI sobre el dongle USB. Este radio SI puede —empareja a `BOND_BONDED`,
     * abre el socket, y el ELM327 contesta `ISO 9141-2`— asi que el dongle
     * deja de ser obligatorio.
     *
     * Solo puede correr UNO de los dos lectores. [com.nonosky.s2000dash.obd.PollScheduler]
     * y [com.nonosky.s2000dash.obd.LectorObdHci] escriben los dos en
     * `EstadoActual.ultimo`, y cuando convivieron el interno pisaba al del
     * dongle con `Disconnected` porque el Steren solo le contestaba a uno.
     * Por eso arrancar este apaga aquel, y no al reves.
     */
    private fun arrancarObdInterno() {
        runCatching {
            val adapter = radioInterna ?: run {
                Log.w(TAG, "este radio no expone BluetoothAdapter")
                return
            }
            runCatching { lectorObd?.detener() }
            lectorObd = null
            EstadoActual.lectorObd = null

            val dev = adapter.getRemoteDevice(MAC_OBD)
            val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val sched = com.nonosky.s2000dash.obd.PollScheduler(
                transportFactory = {
                    com.nonosky.s2000dash.obd.SppTransport(dev, adapter)
                },
                scope = alcance,
            )
            alcanceInterno = alcance
            sondeoInterno = sched
            sched.start()

            // Publicar su estado donde lo ven la vista y el puente. Sin esto
            // el sondeo corre y nadie se entera: el tablero se queda en
            // guiones y parece que no hay enlace.
            enlaceInterno = alcance.launch {
                sched.state.collect { st ->
                    EstadoActual.ultimo = st
                    runCatching { EstadoActual.alCambiarObd?.invoke() }
                }
            }
        }.onFailure { Log.w(TAG, "el sondeo interno no arranco: ${it.message}") }
    }

    /**
     * Suelta la radio Bluetooth entera y la deja libre para Android Auto.
     *
     * Es lo que pidio el dueño al elegir "solo interno": el tablero toma la
     * radio mientras esta abierto y la devuelve al cerrarse, en vez de
     * pelearsela al telefono todo el tiempo. Un controlador puede con las dos
     * cosas a la vez, pero el sondeo OBD es charlatan y degradaria el audio.
     * Asi que no se comparte: se turna.
     *
     * NO apaga el TPMS —va por USB, no por radio— ni el puente HTTP, que no
     * usa Bluetooth. El radio sigue siendo alcanzable y sigue avisando de una
     * llanta baja con el tablero cerrado, que es justo cuando importa.
     */
    fun soltarBluetooth(): String {
        val partes = mutableListOf<String>()

        if (sondeoInterno != null) partes += "sondeo interno detenido"
        runCatching { enlaceInterno?.cancel() }
        runCatching { sondeoInterno?.stop() }
        runCatching { alcanceInterno?.cancel() }
        enlaceInterno = null
        sondeoInterno = null
        alcanceInterno = null

        if (lectorObd != null) partes += "lector del dongle detenido"
        runCatching { lectorObd?.detener() }
        lectorObd = null
        EstadoActual.lectorObd = null
        EstadoActual.comandoObd = null

        if (vigilante != null) partes += "vigilante de bateria detenido"
        runCatching { vigilante?.detener() }
        vigilante = null
        EstadoActual.vigilanteBateria = null
        // Desenchufar tambien el camino directo: si quedara puesto, cualquier
        // ronda superviviente volveria a tomar la radio que acabamos de soltar.
        LectorBmsDirecto.leer = null
        LectorBmsDirecto.barrer = null

        // Que el tablero no deje colgados los ultimos valores como si el
        // enlace siguiera vivo: un dato viejo sin avisar enseña a no creerle
        // al tablero, que es el unico pecado que no se puede cometer aqui.
        EstadoActual.ultimo = VehicleState()
        runCatching { EstadoActual.alCambiarObd?.invoke() }

        if (partes.isEmpty()) partes += "no habia nada tomando la radio"
        partes += "Bluetooth libre"
        return partes.joinToString(" | ")
    }

    private fun arrancarObd() {
        runCatching {
            val l = com.nonosky.s2000dash.obd.LectorObdHci(applicationContext, MAC_OBD)
            lectorObd = l
            EstadoActual.lectorObd = l
            EstadoActual.comandoObd = { cmds -> l.preguntar(cmds) }
            l.arrancar()
        }.onFailure { Log.w(TAG, "el lector de OBD no arranco: ${it.message}") }
    }

    /**
     * Si el sistema mata el servicio, que se reprograme solo.
     *
     * START_STICKY ya pide que Android lo reviva, pero en estas ROMs el
     * "gestor de bateria" del fabricante mata servicios y a veces NO los
     * devuelve. Una alarma programada es la red de seguridad: aunque el
     * proceso muera entero, el sistema lo vuelve a levantar a la hora fijada.
     *
     * El dueño reporto tener que abrir la app a mano tras cada reinicio; esto
     * ataca justo eso, sin depender de que el fabricante respete el
     * BOOT_COMPLETED ni de las listas blancas de autoarranque.
     *
     * **DESACTIVADA.** Se escribio para ahorrarle al dueño abrir la app tras
     * cada reinicio, pero si la app es la que cuelga el radio —y lo colgo tres
     * veces— entonces revivirla cada diez minutos convierte un problema en un
     * bucle del que el dueño no puede salir sin desinstalar. Vuelve a
     * activarse cuando haya una sesion larga midiendo temperatura sin cuelgues.
     */
    @Suppress("unused")
    private fun programarResurreccion() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val i = Intent(this, BootReceiver::class.java)
                .setAction(ACCION_RESUCITAR)
            val pi = android.app.PendingIntent.getBroadcast(
                this, 42, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        android.app.PendingIntent.FLAG_IMMUTABLE else 0),
            )
            // Repetitiva y no exacta: no hace falta puntualidad, solo que
            // alguien pregunte de vez en cuando si el tablero sigue vivo.
            am.setInexactRepeating(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + INTERVALO_RESURRECCION_MS,
                INTERVALO_RESURRECCION_MS,
                pi,
            )
            Log.i(TAG, "resurreccion programada cada ${INTERVALO_RESURRECCION_MS / 60000} min")
        }.onFailure { Log.w(TAG, "no se pudo programar la resurreccion: ${it.message}") }
    }

    /**
     * Mide la temperatura cada pocos segundos.
     *
     * Es un hilo mas, si — pero uno que lee un archivo de texto cada cinco
     * segundos y duerme. Su costo es despreciable comparado con lo que evita:
     * el radio se apago DOS veces por calor, y cada vez el dueño se quedo sin
     * tablero y sin radio en el carro.
     */
    private fun arrancarTermometro() {
        thread(name = "termometro", isDaemon = true) {
            while (vivo || !arranco) {
                runCatching { Termometro.medir() }
                runCatching { Thread.sleep(5_000) }.onFailure { return@thread }
            }
        }
    }

    /** Enciende o apaga el motor y la bateria en caliente, por HTTP. */
    private fun registrarInterruptores() {
        EstadoActual.encenderFuente = { cual, encender ->
            runCatching {
                when (cual.lowercase()) {
                    // Por omision, la radio INTERNA. El dongle queda como
                    // "motor-dongle" para poder volver a el sin recompilar.
                    "motor", "obd" -> if (encender) {
                        if (sondeoInterno == null) arrancarObdInterno()
                        "motor encendido por la radio interna"
                    } else {
                        runCatching { enlaceInterno?.cancel() }
                        runCatching { sondeoInterno?.stop() }
                        runCatching { alcanceInterno?.cancel() }
                        enlaceInterno = null
                        sondeoInterno = null
                        alcanceInterno = null
                        EstadoActual.ultimo = VehicleState()
                        runCatching { EstadoActual.alCambiarObd?.invoke() }
                        "motor apagado"
                    }
                    "motor-dongle" -> if (encender) {
                        if (lectorObd == null) arrancarObd() else "el dongle ya estaba encendido"
                        "motor encendido por el dongle"
                    } else {
                        runCatching { lectorObd?.detener() }
                        lectorObd = null
                        EstadoActual.lectorObd = null
                        "dongle apagado"
                    }
                    // Por omision, la radio INTERNA, igual que el motor.
                    "bateria" -> if (encender) {
                        if (vigilante == null) arrancarBateriaInterna()
                        "bateria encendida por la radio interna"
                    } else {
                        LectorBmsDirecto.leer = null
                        LectorBmsDirecto.barrer = null
                        runCatching { vigilante?.detener() }
                        vigilante = null
                        EstadoActual.vigilanteBateria = null
                        "bateria apagada"
                    }
                    "bateria-dongle" -> if (encender) {
                        if (vigilante == null) arrancarBateria() else "la bateria ya estaba encendida"
                        "bateria encendida por el dongle"
                    } else {
                        runCatching { vigilante?.detener() }
                        vigilante = null
                        EstadoActual.vigilanteBateria = null
                        "bateria apagada"
                    }
                    else -> "fuente desconocida: $cual (usa motor, " +
                        "motor-dongle, bateria o bateria-dongle)"
                }
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: si el sistema lo mata por memoria, que vuelva. En un
        // head unit con poca RAM eso pasa mas de lo que uno quisiera.
        return START_STICKY
    }

    override fun onDestroy() {
        vivo = false
        runCatching { enlaceInterno?.cancel() }
        runCatching { sondeoInterno?.stop() }
        runCatching { alcanceInterno?.cancel() }
        runCatching { lectorObd?.detener() }
        lectorObd = null
        EstadoActual.lectorObd = null
        runCatching { vigilante?.detener() }
        vigilante = null
        EstadoActual.vigilanteBateria = null
        runCatching { lectorTpms?.detener() }
        lectorTpms = null
        EstadoActual.lectorTpms = null
        puente?.stop()
        puente = null
        Log.i(TAG, "Servicio abajo")
        super.onDestroy()
    }

    /**
     * Revisa actualizaciones cada cierto rato, no solo al abrir la app.
     *
     * El intervalo es largo a proposito: descargar e instalar interrumpe el
     * tablero, y en un carro eso no puede pasar cada dos por tres.
     */
    private fun arrancarRevisionPeriodica() {
        thread(name = "revisor-actualizaciones", isDaemon = true) {
            // Un respiro al arrancar: dejar que la red del carro se asiente
            // antes de ponerse a difundir y descargar.
            Thread.sleep(20_000)
            while (vivo) {
                runCatching { actualizador.checkAndInstall() }
                    .onFailure { Log.w(TAG, "Revision fallida: ${it.message}") }
                Thread.sleep(INTERVALO_MS)
            }
        }
    }

    private fun arrancarEnPrimerPlano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL, "S2000 Dash", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Mantiene el tablero disponible" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(canal)
        }

        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
        )

        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CANAL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("S2000 Dash")
            .setContentText("Tablero disponible")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()

        startForeground(ID_NOTIFICACION, n)
    }

    companion object {
        private const val TAG = "DashService"
        private const val CANAL = "s2000dash"
        private const val ID_NOTIFICACION = 1
        private const val INTERVALO_MS = 15 * 60 * 1000L

        /**
         * El adaptador OBD, por MAC.
         *
         * Va fija y no elegida por el usuario: el dongle USB pagina esta
         * direccion directamente, sin lista de emparejados de por medio.
         *
         * Publica porque el tablero tambien la necesita — para asegurarse de
         * que este aparato NO quede vinculado en la radio del carro. Esa
         * radio se sigue usando para Android Auto; lo unico que no debe
         * tocar es el Steren.
         */
        const val MAC_OBD = "00:1D:A5:68:98:8B"

        /** Accion de la alarma que comprueba que el tablero sigue en pie. */
        const val ACCION_RESUCITAR = "com.nonosky.s2000dash.RESUCITAR"

        /** Cada cuanto se comprueba. Diez minutos no molesta a nadie. */
        private const val INTERVALO_RESURRECCION_MS = 10 * 60 * 1000L

        /** Arranca el servicio si no lo esta. Idempotente. */
        fun arrancar(context: Context) {
            val i = Intent(context, DashService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure { Log.w(TAG, "No se pudo arrancar: ${it.message}") }
        }
    }
}
