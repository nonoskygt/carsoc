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
        Mantenimiento.iniciar(applicationContext)

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
        // La vida del aceite vive AQUI, en el servicio que no muere al cerrar
        // el tablero. Cuenta horas de motor y kilometros por GPS, y las dos
        // cosas tienen que seguir contando con la pantalla apagada — si solo
        // contaran con el tablero abierto, el intervalo mediria cuanto mira
        // el dueño la pantalla y no cuanto anda el carro.
        arrancarKilometraje()
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
        EstadoActual.probarAlertaLlanta = {
            val lect = lectorTpms?.estado()?.ruedas?.values?.firstOrNull()
            if (lect == null) {
                "no hay ninguna rueda leyendo todavia"
            } else {
                // La MISMA funcion y los mismos argumentos que el pinchazo
                // real de revisarPinchazo. Si la prueba usara otra ruta,
                // estaria comprobando una alerta que nadie va a oir nunca.
                avisarPresionBaja(lect, pinchazo = true, caida = 4.5f)
                // "deberia sonar" no vale como respuesta. La forma mas comun
                // de que no suene no es un fallo del codigo sino que Android
                // tenga el canal silenciado o las notificaciones apagadas, y
                // eso NO lanza excepcion: la llamada devuelve bien y el carro
                // se queda callado. Asi que se pregunta y se dice.
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val permitidas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    nm?.areNotificationsEnabled() != false
                } else {
                    true
                }
                val canal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm?.getNotificationChannel(CANAL_ALERTA)
                } else {
                    null
                }
                val id = NOTIF_PRESION_BASE + lect.rueda.ordinal + NOTIF_PINCHAZO_OFFSET
                listOf(
                    "alerta de PRUEBA lanzada sobre ${lect.rueda.corta} (id $id)",
                    "ruta: avisarPresionBaja(pinchazo=true) — la MISMA que un pinchazo real",
                    "gestor de notificaciones: ${if (nm == null) "NULO — no salio nada" else "vivo"}",
                    "notificaciones permitidas: ${if (permitidas) "si" else "NO — no sonara"}",
                    "canal $CANAL_ALERTA: importancia=${canal?.importance ?: -1}" +
                        " (4=alta, 0=silenciado a mano, -1=sin canal o Android viejo)" +
                        "  sonido=${if (canal?.sound != null) "si" else "NO"}",
                    "lo que esto NO prueba: el detector de pinchazo" +
                        " (>= $PSI_CAIDA_PINCHAZO PSI en ${MS_VENTANA_PINCHAZO / 1000}s)",
                ).joinToString("\n")
            }
        }

        // El hermano del boton de prueba: aquel demuestra que la alarma suena,
        // este que hay algo detras dispuesto a tocarla. Se registran juntos
        // porque por separado cada uno engaña.
        EstadoActual.umbralPinchazo = "perder %.1f PSI o mas en %ds seguidos".format(
            PSI_CAIDA_PINCHAZO, MS_VENTANA_PINCHAZO / 1000,
        )
        EstadoActual.detectorPinchazo = { clave, psiAhora -> estadoDetector(clave, psiAhora) }


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
        // EL DIAGNOSTICO EN REMOTO. Y LO PRIMERO QUE HACE ES APAGAR EL SONDEO.
        //
        // LectorDtc abre su PROPIA conexion SPP —no le queda otra: el modo 03
        // exige fijarle la temporizacion al adaptador con ATAT0 + ATST FF, y
        // hacerselo al sondeo en marcha le estropearia el ritmo a mitad de una
        // lectura— y el Steren solo atiende UN enlace RFCOMM a la vez. Con el
        // sondeo corriendo, el segundo socket o muere en las cuatro vias de
        // SppTransport, o peor: si el clon acepta los dos, una respuesta de
        // RPM cae dentro del buffer del modo 03 y se decodifica como averias
        // que el carro no tiene, o corta la respuesta multi-trama y se pierden
        // codigos en silencio, que es justo lo que el ATAT0 venia a evitar.
        //
        // La pantalla de averias NO protege de esto —llama al lector a pelo—
        // y por HTTP hace mas falta todavia, porque se dispara desde la laptop
        // sin nadie mirando el carro.
        val cerrojoDtc = java.util.concurrent.atomic.AtomicBoolean(false)
        EstadoActual.leerDtc = { borrar ->
            // Uno a la vez. El puente atiende hasta cuatro peticiones en
            // paralelo, y dos lecturas de codigos simultaneas son exactamente
            // la colision que todo lo de abajo viene a evitar.
            if (!cerrojoDtc.compareAndSet(false, true)) {
                listOf("ya hay una lectura de codigos en marcha; espera a que termine")
            } else {
                val salida = mutableListOf<String>()
                // El estado se mira ANTES de parar nada: en cuanto se apaga el
                // sondeo, el RPM envejece y ya no se puede saber si el motor
                // estaba girando cuando llego la peticion.
                val antes = EstadoActual.ultimo
                val teniaSondeo = sondeoInterno != null
                val teniaDongle = lectorObd != null
                // La tabla solo se suelta si la cargamos NOSOTROS: si la
                // pantalla de averias esta abierta la tabla es suya, y tirarsela
                // le dejaria los codigos sin explicacion en la mano.
                val tablaEraNuestra = com.nonosky.s2000dash.diag.TablaDtc.cargados == 0
                try {
                    if (teniaSondeo) {
                        // El colector se cancela ANTES de parar el sondeo: al
                        // reves, el Disconnected que publica stop() no llega a
                        // nadie y el tablero se queda pintando el ultimo RPM
                        // como si el enlace siguiera vivo.
                        runCatching { enlaceInterno?.cancel() }
                        runCatching { sondeoInterno?.stop() }
                        runCatching { alcanceInterno?.cancel() }
                        enlaceInterno = null
                        sondeoInterno = null
                        alcanceInterno = null
                        // Se marca desconectado pero NO se borran los valores:
                        // la vista ya los pinta en gris al ponerse rancios, y
                        // vaciarlos haria parpadear el tablero entero por una
                        // pausa de medio minuto.
                        EstadoActual.ultimo = antes.copy(
                            connection = ConnectionState.Disconnected,
                        )
                        runCatching { EstadoActual.alCambiarObd?.invoke() }
                        // El gancho de /at apuntaba a ESTE sondeo, que acaba de
                        // pararse. Si se quedara puesto, un /at durante la
                        // lectura de codigos encolaria comandos contra un
                        // sondeo muerto y esperaria el plazo entero para
                        // decirlo, ocupando de paso uno de los cuatro hilos del
                        // puente mientras /dtc va por su medio minuto. El
                        // `finally` lo repone al llamar a arrancarObdInterno().
                        EstadoActual.comandoObd = null
                        salida += "sondeo interno detenido para dejarle el ELM327 al lector"
                    }
                    if (teniaDongle) {
                        // El dongle habla con el MISMO ELM327 por otra radio:
                        // pausar solo el sondeo interno no libera nada.
                        runCatching { lectorObd?.detener() }
                        lectorObd = null
                        EstadoActual.lectorObd = null
                        EstadoActual.comandoObd = null
                        salida += "lector del dongle detenido (es el mismo adaptador)"
                    }
                    // El vigilante se PAUSA, no se detiene: no hay forma de
                    // saber si el dueño lo arranco por radio interna o por
                    // dongle, y revivirlo por el camino equivocado le cambiaria
                    // la configuracion sin decirselo.
                    vigilante?.pausar()

                    if (teniaSondeo || teniaDongle) {
                        // Cerrar el socket por este lado no significa que el
                        // adaptador se haya enterado. El clon tarda en soltar
                        // el canal, y reconectar de inmediato falla con un error
                        // que parece un adaptador roto cuando es prisa nuestra.
                        //
                        // Dos segundos NO bastaban, medido en el carro: con esa
                        // espera, SppTransport se lanzaba a sus cuatro vias
                        // contra un adaptador todavia ocupado y la peticion
                        // entera tardo CUATRO MINUTOS en vez de medio minuto.
                        // El socket del puente se muere a los 120 s, asi que
                        // quien preguntaba se quedaba sin respuesta de una
                        // lectura que si estaba ocurriendo.
                        runCatching { Thread.sleep(MS_SOLTAR_ADAPTADOR) }
                    }

                    if (borrar && (antes.rpm ?: 0) > 0 &&
                        !antes.isStale(antes.rpmAtMs, System.currentTimeMillis())
                    ) {
                        salida += "OJO: hace un momento el motor giraba a ${antes.rpm} rpm, " +
                            "y muchas ECU rechazan el modo 04 con el motor en marcha"
                    }

                    com.nonosky.s2000dash.diag.TablaDtc.cargar(applicationContext)
                    val lector = com.nonosky.s2000dash.diag.LectorDtc(radioInterna, MAC_OBD)
                    val r = if (borrar) lector.borrar() else lector.leer()

                    salida += if (borrar) "--- BORRADO (modo 04) ---" else "--- LECTURA ---"
                    // El error va ARRIBA y no al final. Quien mira esto por HTTP
                    // lee la primera pantalla, y un fallo de enlace escondido
                    // bajo treinta lineas de traza se acaba leyendo como "el
                    // carro esta sano", que es la peor mentira posible aqui.
                    r.error?.let { salida += "ERROR: $it" }
                    salida += "luz de averia: " + (if (r.luzEncendida) "ENCENDIDA" else "apagada")
                    salida += "la ECU dice tener " +
                        (if (r.cuantosDiceLaEcu < 0) "?" else "${r.cuantosDiceLaEcu}") +
                        " codigos confirmados"
                    for ((rotulo, lista) in listOf(
                        "GUARDADOS" to r.guardados,
                        "PENDIENTES" to r.pendientes,
                        "PERMANENTES" to r.permanentes,
                    )) {
                        salida += "$rotulo (${lista.size}):"
                        if (lista.isEmpty()) salida += "  (ninguno)"
                        for (c in lista) {
                            val e = com.nonosky.s2000dash.diag.TablaDtc.de(c.texto)
                            salida += "  ${c.texto}  " +
                                (e?.titulo ?: "(no catalogado para este carro)")
                        }
                    }
                    // La traza cruda ENTERA, siempre, aunque todo haya ido bien.
                    // Es el motivo de existir de esta ruta: desde la laptop,
                    // "sin averias" y "no hable con la ECU" se leen igual si no
                    // se ve lo que contesto el adaptador palabra por palabra.
                    salida += "--- lo que dijo el ELM327, palabra por palabra ---"
                    salida += r.traza
                } catch (e: Exception) {
                    salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    if (tablaEraNuestra) com.nonosky.s2000dash.diag.TablaDtc.soltar()
                    // Devolver el carro como estaba, pase lo que pase. Dejar el
                    // tablero sin sondeo porque una lectura fallo seria cambiar
                    // un diagnostico por una averia nueva.
                    if (teniaSondeo) {
                        // Con runCatching a proposito: si reanudar lanza, el
                        // `finally` se corta y el dongle y el vigilante se
                        // quedan sin reanudar tambien. Un fallo al volver no
                        // puede arrastrar a los demas.
                        runCatching { arrancarObdInterno() }
                            .onSuccess { salida += "sondeo interno reanudado" }
                            .onFailure { salida += "OJO: el sondeo interno NO volvio: ${it.message}" }
                    }
                    if (teniaDongle) {
                        runCatching { arrancarObd() }
                            .onSuccess { salida += "lector del dongle reanudado" }
                            .onFailure { salida += "OJO: el dongle NO volvio: ${it.message}" }
                    }
                    vigilante?.reanudar()
                    cerrojoDtc.set(false)
                }
                salida
            }
        }

        EstadoActual.pidsSoportados = fun(): List<String> {
            val salida = mutableListOf<String>()
            // Si el sondeo esta vivo, se le pregunta A EL. Abrir un segundo
            // enlace al mismo ELM327 no es una alternativa peor: es la
            // colision que este proyecto ya se comio dos veces. Medido hoy con
            // el sondeo corriendo, esta misma ruta contestaba
            //
            //   RFCOMM fallo por todas las vias: inseguro-SPP=read failed...
            //
            // porque el Steren solo atiende UN canal. Y en el peor caso no
            // falla: acepta los dos y mezcla las respuestas, o sea inventa el
            // mapa de lo que el carro sabe medir.
            val porElSondeo = EstadoActual.comandoObd
            if (porElSondeo != null) {
                salida += "(preguntando por el enlace que ya esta abierto)"
                var base = 0x00
                var vueltas = 0
                while (vueltas < 4) {
                    val cmd = "01%02X".format(base)
                    val raw = porElSondeo(listOf(cmd)).firstOrNull()
                        ?.substringAfter("-> ", "")
                        ?.takeIf { it.isNotBlank() }
                    salida += "--- $cmd -> ${raw ?: "sin respuesta"}"
                    if (!com.nonosky.s2000dash.obd.PidDecoder.huboMascara(raw, base)) {
                        salida += "  *** SIN RESPUESTA AL $cmd: la ECU no dijo su mascara ***"
                        salida += "  *** NO se sabe que soporta este carro. Esto NO es una lista vacia. ***"
                        break
                    }
                    val lista = com.nonosky.s2000dash.obd.PidDecoder.soportados(raw, base)
                    if (lista.isEmpty()) {
                        salida += "  la ECU contesta y dice que no soporta NADA de este bloque"
                    }
                    for (pid in lista) {
                        if (pid % 0x20 == 0) continue
                        val n = com.nonosky.s2000dash.obd.PidDecoder.NOMBRES[pid]
                        salida += "  01%02X  %s".format(pid, n ?: "(sin nombre conocido)")
                    }
                    if (!com.nonosky.s2000dash.obd.PidDecoder.hayMasBloques(raw, base)) break
                    base += 0x20
                    vueltas++
                }
                return salida
            }
            val adapter = radioInterna
            val dev = runCatching { adapter?.getRemoteDevice(MAC_OBD) }.getOrNull()
            if (dev == null) salida += "ERROR: no se pudo resolver el adaptador"
            else {
                // Sin sondeo no hay a quien preguntarle, asi que aqui SI toca
                // abrir enlace propio — y es seguro justamente porque no hay
                // ninguno con quien chocar.
                salida += "(el sondeo esta apagado: se abre un enlace propio)"
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
                        // Antes de imprimir nada, saber si la ECU habló.
                        //
                        // Sin esta guarda el silencio, el `SEARCHING...` y la
                        // máscara a medias salían por la misma puerta que "no
                        // soporta nada": un `break` con la lista vacía, y
                        // /pids pintando un hueco que se lee como "este carro
                        // no mide nada". Es la misma trampa que ya mordió con
                        // los códigos de avería — la ausencia de respuesta no
                        // es una respuesta — y aquí el daño sería mandar a
                        // alguien a buscar un sensor que el carro sí tiene.
                        if (!com.nonosky.s2000dash.obd.PidDecoder.huboMascara(raw, base)) {
                            salida += "  *** SIN RESPUESTA AL $cmd: la ECU no dijo su mascara ***"
                            salida += "  *** NO se sabe que soporta este carro. Esto NO es una lista vacia. ***"
                            salida += "  (silencio, SEARCHING, basura, o mascara de menos de 4 bytes)"
                            break
                        }
                        val lista = com.nonosky.s2000dash.obd.PidDecoder.soportados(raw, base)
                        // Lo contrario del caso de arriba, y por eso se dice
                        // con todas las letras: aquí la ECU sí contestó.
                        if (lista.isEmpty()) {
                            salida += "  la ECU contesta y dice que no soporta NADA de este bloque"
                        }
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
            return salida
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
            lector.alCambiar = {
                runCatching { EstadoActual.alCambiarTpms?.invoke() }
                runCatching { revisarPresiones() }
            }
            lector.arrancar()
        }.onFailure { Log.w(TAG, "TPMS no arranco: ${it.message}") }
    }

    private var oyenteGps: android.location.LocationListener? = null
    private var ultimaPosicion: android.location.Location? = null

    /**
     * Cuenta kilometros por GPS. El odometro de la ECU no existe.
     *
     * Se le pregunto al carro que soporta y su mapa de PIDs se corta en el
     * `0x20`: no hay odometro (`01A6`) ni tiempo de motor (`011F`). Un AP1 no
     * los expone, asi que la distancia hay que medirla por fuera.
     *
     * Se pide una muestra cada 5 s y con 10 m de movimiento minimo. No mas
     * seguido: el receptor ya esta encendido para el resto del sistema —lo
     * usan el launcher y la app del fabricante— asi que esto se cuelga de
     * algo que ya corre, y pedir a 1 Hz solo añadiria calor a un radio que ya
     * se apago tres veces por eso.
     */
    private fun arrancarKilometraje() {
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE)
                as? android.location.LocationManager ?: return
            val oyente = object : android.location.LocationListener {
                override fun onLocationChanged(pos: android.location.Location) {
                    // Envuelto entero: esto llega en un hilo del sistema y una
                    // excepcion suelta ahi se lleva el servicio, el puente y
                    // el aviso de las llantas por delante.
                    runCatching {
                        val velocidad = if (pos.hasSpeed()) pos.speed else 0f
                        val precision = if (pos.hasAccuracy()) pos.accuracy else 999f
                        // Se anota ANTES de mirar si hay posicion previa. La
                        // primera fija de cada arranque no produce distancia
                        // —no hay contra que restar— y si solo se contaran las
                        // que suman, un receptor que engancha de tarde en
                        // tarde se veria exactamente igual que uno muerto.
                        Mantenimiento.anotarFijaGps(velocidad, precision)
                        val previa = ultimaPosicion
                        ultimaPosicion = pos
                        if (previa == null) return@runCatching
                        Mantenimiento.sumarDistancia(
                            previa.distanceTo(pos), velocidad, precision,
                        )
                    }
                }

                @Deprecated("Obligatorio hasta API 29")
                override fun onStatusChanged(p: String?, e: Int, x: android.os.Bundle?) = Unit
                override fun onProviderEnabled(p: String) = Unit
                override fun onProviderDisabled(p: String) = Unit
            }
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER, 5_000L, 10f, oyente,
            )
            oyenteGps = oyente
            Log.i(TAG, "kilometraje por GPS en marcha")
        }.onFailure { Log.w(TAG, "GPS no arranco: ${it.message}") }
    }

    /** Ruedas que ya estan avisadas, para no repetir la alerta. */
    private val ruedasAvisadas = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Presion de cada rueda hace un rato, para ver si se esta yendo DEPRISA.
     *
     * Una llanta que pierde media libra por semana y otra que pierde cinco en
     * dos minutos son dos averias distintas: la primera se infla el sabado, la
     * segunda hay que pararse a mirarla ya. El umbral de presion baja no las
     * distingue —las dos acaban abajo— y para cuando salta, la rapida ya lleva
     * rato rodando desinflada.
     *
     * Guarda: (psi, cuando, ventanas ya comparadas).
     *
     * La cuenta de ventanas no es un historial: es un entero por rueda que
     * dice si el detector llego a comparar alguna vez. Sin ella, un viaje sin
     * alarma no distingue "la presion aguanto" de "nunca hubo dos muestras
     * separadas por la ventana", y lo segundo es una averia del detector. La
     * edad de la referencia sola no vale para eso: al llegar, con los sensores
     * dormidos, las cuatro ruedas tienen la edad por las nubes.
     *
     * Los tres valores van en una TERNA y no en tres campos sueltos porque el
     * puente HTTP lee desde otro hilo. Sueltos, podria coger la presion de una
     * ronda con la hora de la siguiente y anunciar una caida que no ocurrio,
     * justo en la ruta que existe para saber si creerle al detector.
     *
     * Que sea ConcurrentHashMap basta: escribe SOLO el hilo `tpms-lector`
     * —revisarPresiones cuelga de `alCambiar`, que se invoca ahi y en ningun
     * otro sitio— y lee el hilo del puente. Un candado, ademas de sobrar,
     * meteria al servidor de depuracion dentro del lazo del detector: una
     * consulta lenta podria retrasar el aviso de un pinchazo.
     */
    private val presionAnterior =
        java.util.concurrent.ConcurrentHashMap<String, Triple<Float, Long, Int>>()

    /** Ruedas ya avisadas por pinchazo, para no repetir. */
    private val ruedasPinchadas = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Vigila la presion y avisa aunque el tablero este cerrado.
     *
     * Esta es la razon de que el servicio siga vivo al cerrar la pantalla. El
     * TPMS va por USB, no gasta radio, y es lo unico de todo el tablero que
     * avisa de algo que puede reventar en carretera — asi que se queda
     * encendido siempre y habla por su cuenta.
     *
     * Se avisa UNA vez por rueda, al cruzar el umbral hacia abajo, y no se
     * vuelve a avisar hasta que esa rueda se recupere. Una notificacion que
     * se repite cada trama es una que el dueño aprende a ignorar, y entonces
     * deja de servir el dia que importa.
     */
    private fun revisarPresiones() {
        val lector = lectorTpms ?: return
        val estado = runCatching { lector.estado() }.getOrNull() ?: return

        for (lectura in estado.ruedas.values) {
            val clave = lectura.rueda.name
            // presionBaja vive en la TRAMA, no en la lectura. Y se exige
            // ademas que el dato no sea rancio: avisar de una llanta baja con
            // una medida de hace media hora es avisar de algo que quiza ya no
            // pasa, y una alerta falsa gasta la credibilidad de la siguiente.
            val baja = runCatching {
                lectura.trama.presionBaja && !lectura.rancia(System.currentTimeMillis())
            }.getOrDefault(false)
            val yaAvisada = ruedasAvisadas[clave] == true

            // PINCHAZO: caida rapida, aunque todavia no este por debajo del
            // umbral. Se mira antes que la presion baja porque es la urgente.
            runCatching { revisarPinchazo(clave, lectura) }

            if (baja && !yaAvisada) {
                ruedasAvisadas[clave] = true
                runCatching { avisarPresionBaja(lectura) }
                    .onFailure { Log.w(TAG, "no se pudo avisar: ${it.message}") }
            } else if (!baja && yaAvisada) {
                // Se recupero: se rearma para poder volver a avisar.
                ruedasAvisadas.remove(clave)
                runCatching {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(NOTIF_PRESION_BASE + lectura.rueda.ordinal)
                }
            }
        }
    }

    /**
     * Caida rapida de presion: eso es un pinchazo, no un desinflado.
     *
     * Se compara contra una muestra de hace al menos [MS_VENTANA_PINCHAZO] y
     * se exige perder [PSI_CAIDA_PINCHAZO] en esa ventana. Las dos condiciones
     * importan: sin la ventana minima, el ruido de dos tramas seguidas
     * dispararia falsas alarmas; sin la caida minima, el cambio normal por
     * temperatura —una llanta se calienta rodando y sube casi una libra—
     * contaria como fuga.
     */
    private fun revisarPinchazo(clave: String, lectura: com.nonosky.s2000dash.tpms.LecturaRueda) {
        val psi = lectura.presionPsi ?: return
        val ahora = System.currentTimeMillis()
        if (lectura.rancia(ahora)) return

        val previa = presionAnterior[clave]
        if (previa == null) {
            presionAnterior[clave] = Triple(psi, ahora, 0)
            return
        }

        val (psiAntes, cuando, ventanas) = previa
        val transcurrido = ahora - cuando
        if (transcurrido < MS_VENTANA_PINCHAZO) return

        val caida = psiAntes - psi
        if (caida >= PSI_CAIDA_PINCHAZO && ruedasPinchadas[clave] != true) {
            ruedasPinchadas[clave] = true
            runCatching { avisarPresionBaja(lectura, pinchazo = true, caida = caida) }
                .onFailure { Log.w(TAG, "no se pudo avisar del pinchazo: ${it.message}") }
        }
        // Se recoloca la referencia SIEMPRE, haya saltado o no: si no, una
        // fuga lenta acabaria acumulando diferencia contra una medida de hace
        // horas y se anunciaria como pinchazo cuando no lo es.
        //
        // La cuenta sube AQUI y en ningun otro sitio, porque este es el unico
        // punto al que se llega habiendo comparado de verdad contra una
        // muestra de hace la ventana entera. Las tres salidas de arriba —sin
        // presion, lectura rancia, ventana corta— no comparan nada, y contar
        // ahi seria mentir sobre lo unico que este numero sirve para decir.
        presionAnterior[clave] = Triple(psi, ahora, ventanas + 1)
        if (caida < PSI_CAIDA_PINCHAZO / 2f) ruedasPinchadas.remove(clave)
    }

    /**
     * Que sabe el detector de pinchazo de una rueda, en una linea.
     *
     * Existe porque /probar-alerta demuestra que la alarma SUENA, no que algo
     * la vaya a disparar. Sin esto, un viaje entero en silencio se lee igual
     * tanto si el detector comparo y la presion aguanto como si nunca llego a
     * comparar, y lo segundo significa que no funciona.
     *
     * No abre estado nuevo ni escribe nada: mira los mismos tres mapas que usa
     * [revisarPinchazo]. Lo llama el hilo del puente HTTP.
     */
    private fun estadoDetector(clave: String, psiAhora: Float?): String {
        val marcas = buildString {
            if (ruedasPinchadas[clave] == true) append("  PINCHADA")
            if (ruedasAvisadas[clave] == true) append("  AVISADA")
        }
        val previa = presionAnterior[clave]
            ?: return "detector: SIN REFERENCIA, no ha comparado nunca" +
                " (sensor sin presion o lectura rancia)$marcas"
        val (psiRef, cuando, ventanas) = previa
        val edadS = (System.currentTimeMillis() - cuando) / 1000
        val ventanaS = MS_VENTANA_PINCHAZO / 1000
        val caida = psiAhora?.let { psiRef - it }
        return buildString {
            append("detector: ref ").append("%.1f".format(psiRef)).append(" psi")
            append(" de hace ").append(edadS).append("s")
            // La edad es lo que dice si la ventana se llego a cumplir. Ojo con
            // leer una edad muy por encima de la ventana como "esta esperando":
            // significa lo contrario, que dejaron de entrar medidas buenas —en
            // cuanto entra una, la referencia se recoloca y la edad cae a cero.
            if (edadS >= ventanaS) {
                append(" (ventana de ").append(ventanaS).append("s CUMPLIDA)")
            } else {
                append(" (faltan ").append(ventanaS - edadS).append("s para la ventana)")
            }
            append("  caida ")
            if (caida == null) {
                append("?? el sensor no da presion ahora")
            } else {
                // En negativo a proposito: se pinta lo que la presion HIZO
                // contra lo que tendria que hacer para disparar, en las mismas
                // unidades y con el mismo signo. Un hueco aqui es que no mide.
                append("%+.1f".format(-caida))
                append(" de -").append("%.1f".format(PSI_CAIDA_PINCHAZO)).append(" PSI")
                append(" (faltan ").append("%.1f".format(PSI_CAIDA_PINCHAZO - caida)).append(")")
            }
            append("  ventanas comparadas=").append(ventanas)
            append(marcas)
        }
    }


    private fun avisarPresionBaja(
        lectura: com.nonosky.s2000dash.tpms.LecturaRueda,
        pinchazo: Boolean = false,
        caida: Float = 0f,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal APARTE del que sostiene el servicio, y con importancia
            // ALTA: el del servicio va en silencio a proposito para no
            // molestar, y si la alerta compartiera canal heredaria ese
            // silencio justo cuando hace falta que suene.
            //
            // El sonido es el de ALARMA, no el de notificacion. En un carro
            // con musica puesta un "ding" de notificacion se pierde debajo, y
            // este aviso llega mientras se maneja: o se oye, o no sirve. Con
            // USAGE_ALARM ademas suena aunque el aparato este en silencio.
            val sonido = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM,
            ) ?: android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION,
            )
            val atributos = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val canal = NotificationChannel(
                CANAL_ALERTA, "Avisos de llantas", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Presion baja o perdida rapida en una llanta"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                setSound(sonido, atributos)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(canal)
        }

        val psi = lectura.presionPsi?.let { String.format("%.0f", it) } ?: "?"
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
        )

        val titulo = if (pinchazo) {
            "PINCHAZO: ${lectura.rueda.corta}"
        } else {
            "Presion baja: ${lectura.rueda.corta}"
        }
        val cuerpo = if (pinchazo) {
            "perdio %.1f PSI de golpe — va en %s PSI. Parate a mirarla.".format(caida, psi)
        } else {
            "$psi PSI — la placa pide " +
                com.nonosky.s2000dash.tpms.Escalas.PSI_PLACA.toInt()
        }

        val aviso = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CANAL_ALERTA)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSound(
                    android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM,
                    )
                )
                .setVibrate(longArrayOf(0, 400, 200, 400, 200, 600))
        }
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setStyle(Notification.BigTextStyle().bigText(cuerpo))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .also {
                // Un pinchazo interrumpe lo que este sonando; una presion baja
                // no. La diferencia es que uno se atiende ahora y el otro el
                // sabado, y gastar la interrupcion en los dos es perderla.
                if (pinchazo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    it.setFullScreenIntent(abrir, true)
                    it.setCategory(Notification.CATEGORY_ALARM)
                }
            }
            .build()

        // El pinchazo usa OTRO id para no pisar el aviso de presion baja de
        // la misma rueda: son dos cosas distintas y las dos merecen verse.
        val id = NOTIF_PRESION_BASE + lectura.rueda.ordinal +
            if (pinchazo) NOTIF_PINCHAZO_OFFSET else 0
        nm.notify(id, aviso)
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
            // El canal AT por la radio interna, que es donde faltaba.
            //
            // /at solo funcionaba con el dongle porque solo arrancarObd()
            // registraba este gancho. En la configuracion que se usa a diario
            // —radio interna, dongle fuera— la ruta contestaba "el servicio no
            // registro el canal AT": la herramienta con la que se depura el
            // OBD estaba muerta justo donde hace falta.
            //
            // Va contra el sondeo y no contra un enlace nuevo: el ELM327
            // atiende a UNO, y montarle un segundo tumbaria el que mueve la
            // aguja.
            EstadoActual.comandoObd = { cmds -> sched.preguntar(cmds) }

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

        // El canal AT lo pone el que este sondeando —dongle o radio interna—
        // y aqui se paran los dos, asi que se desengancha una sola vez y fuera
        // del bloque del dongle, que es donde estaba y donde parecia suyo.
        // Dejarlo puesto apuntando a un sondeo parado seria peor que no
        // tenerlo: /at contestaria con la excusa del enlace en vez de decir
        // claro que no hay nadie sondeando.
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
            var ultimoLatido = System.currentTimeMillis()
            while (vivo || !arranco) {
                runCatching { Termometro.medir() }

                // Horas de motor, sumadas aqui porque este hilo ya late cada
                // cinco segundos y no cuesta nada mas. Se suma el tiempo REAL
                // transcurrido y no un 5 fijo: si la ROM congela el proceso un
                // rato, sumar la constante inventaria horas que no pasaron.
                runCatching {
                    val ahora = System.currentTimeMillis()
                    val delta = ((ahora - ultimoLatido) / 1000L).coerceIn(0L, 30L)
                    ultimoLatido = ahora
                    val st = EstadoActual.ultimo
                    val girando = (st.rpm ?: 0) >= Mantenimiento.RPM_MINIMO_MOTOR &&
                        !st.isStale(st.rpmAtMs, ahora)
                    if (girando) Mantenimiento.sumarSegundosMotor(delta)
                }
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
                        val teniaSondeo = sondeoInterno != null
                        runCatching { enlaceInterno?.cancel() }
                        runCatching { sondeoInterno?.stop() }
                        runCatching { alcanceInterno?.cancel() }
                        enlaceInterno = null
                        sondeoInterno = null
                        alcanceInterno = null
                        // El gancho de /at solo se suelta si era NUESTRO. Si
                        // quien sondea es el dongle —"motor-dongle" se apaga
                        // aparte— quitarselo aqui le mataria el canal AT a un
                        // lector que sigue vivo y contestando.
                        if (teniaSondeo) EstadoActual.comandoObd = null
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
        runCatching {
            oyenteGps?.let {
                (getSystemService(Context.LOCATION_SERVICE)
                    as? android.location.LocationManager)?.removeUpdates(it)
            }
        }
        oyenteGps = null
        runCatching { enlaceInterno?.cancel() }
        runCatching { sondeoInterno?.stop() }
        runCatching { alcanceInterno?.cancel() }
        runCatching { lectorObd?.detener() }
        lectorObd = null
        EstadoActual.lectorObd = null
        // EstadoActual es del proceso, no del servicio: sobrevive a este
        // onDestroy. Con el gancho puesto, el puente —que puede seguir en pie—
        // ofreceria un canal AT contra un sondeo que ya no existe. Faltaba
        // tambien para el dongle, no solo para la radio interna.
        EstadoActual.comandoObd = null
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

        /** Canal APARTE, con importancia alta: la alerta tiene que sonar. */
        private const val CANAL_ALERTA = "s2000dash-llantas"

        /** Una notificacion por rueda, para poder retirarlas por separado. */
        /**
         * Lo que se le da al adaptador para soltar el canal antes de reabrirlo.
         *
         * Medido contra el Steren del carro: con 2 s todavia estaba ocupado y
         * la reconexion se iba a minutos. No es un numero de manual, es lo que
         * hizo falta.
         */
        private const val MS_SOLTAR_ADAPTADOR = 6_000L

        private const val NOTIF_PRESION_BASE = 100

        /** El pinchazo va en su propio rango de ids. */
        private const val NOTIF_PINCHAZO_OFFSET = 50

        /**
         * Cuanto hay que perder para llamarlo pinchazo, y en cuanto tiempo.
         *
         * 3 PSI en dos minutos. Rodando, una llanta se CALIENTA y por tanto
         * SUBE casi una libra, asi que perder tres en ese rato no es
         * temperatura ni ruido del sensor: es aire saliendo.
         */
        private const val PSI_CAIDA_PINCHAZO = 3.0f
        private const val MS_VENTANA_PINCHAZO = 120_000L
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
