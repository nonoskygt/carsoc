package com.nonosky.s2000dash.diag

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.concurrent.thread

/**
 * Caja negra: la unica evidencia que sobrevive al cuelgue del radio.
 *
 * ## Por que existe
 *
 * El head unit se cuelga entero —ni ping, pantalla trabada, hubo que cortarle
 * la corriente al carro— y cuando eso pasa el puente HTTP del 8099 se muere
 * con el. O sea que hasta ahora **no quedaba ni una sola medida** del instante
 * anterior al cuelgue: solo el recuerdo del dueño y nuestras conjeturas.
 *
 * Se conjeturo tres veces. Dos eran falsas:
 *
 *  - "Es el calor": se midio (59 C en reposo, 64 C con todo corriendo), se
 *    bajo el repintado a 5 fps, se escribio el [com.nonosky.s2000dash.Termometro]
 *    entero... y se volvio a colgar. Con ventilador puesto y el radio FRIO al
 *    tacto. La hipotesis termica esta muerta.
 *  - "Es el repintado": bajarlo no cambio nada.
 *
 * Lo unico solido es la cronologia: con SOLO el TPMS (un CH340 leyendo a
 * 19200 en un hilo) el tablero corrio HORAS sin colgarse. Los cuelgues
 * empezaron al añadir el dongle Bluetooth USB al que se le habla HCI crudo.
 *
 * Adivinar una cuarta vez no es un plan. **Medir si.**
 *
 * ## Que hace
 *
 * Un hilo escribe al disco cada 5 segundos una linea con todo lo que puede
 * distinguir una hipotesis de otra: temperatura, hilos (los de la JVM y los
 * de verdad, los del kernel), memoria de monton y nativa, RSS, fallos de
 * pagina mayores, carga del sistema, **descriptores abiertos clasificados por
 * tipo**, ciclos de apertura/cierre de USB, contadores de la bomba HCI, y
 * cuanto hace que cada hilo dio señales de vida.
 *
 * Y **hace `fsync` en cada escritura**. Esto no es paranoia: un buffer sin
 * volcar se pierde justo en el cuelgue, que es el unico momento en que el
 * archivo importa. Sin `fsync` la caja negra registraria perfectamente todo
 * menos los ultimos treinta segundos.
 *
 * ## Que NO hace
 *
 * No depende de `logcat` (solo ve su propio UID y ademas no sobrevive al
 * reinicio), ni de `dumpsys` (vetado sin root), ni del puente HTTP (se muere
 * con el radio). Escribe a un archivo y lo sincroniza. Nada mas.
 *
 * ## Presupuesto: tiene que ser barata de verdad
 *
 * Una caja negra que contribuya al problema es peor que no tenerla, porque
 * ademas contamina la medida. Por eso:
 *
 *  - **Un** hilo, dormido el 99.9% del tiempo.
 *  - El camino caliente ([latido]) es UNA escritura volatil a un array
 *    preasignado. Sin objetos, sin candados, sin mapas. Se puede llamar
 *    cincuenta veces por segundo desde la bomba HCI sin notarlo.
 *  - Las lecturas de estado salen de `/proc`, que es memoria, no disco.
 *  - Una escritura de ~250 bytes y un `fsync` cada 5 s.
 *  - El barrido de descriptores (que si cuesta ~150 llamadas al sistema) se
 *    hace una vez de cada [CADA_CUANTO_BARRE] vueltas, no en todas.
 *  - Los hitos que mandan otros hilos van a una cola ACOTADA: quien avisa
 *    nunca se bloquea y nunca toca el disco; si la cola se llena se cuentan
 *    los perdidos y ya.
 *  - El archivo rota a los [MAX_BYTES], asi que ocupa como mucho el doble.
 *
 * ## Aislamiento
 *
 * Todo va envuelto, de arriba a abajo. En Android una excepcion que escapa de
 * un hilo MATA el proceso, y una caja negra que tumbe el tablero seria el
 * colmo. La regla de este proyecto no es "envuelve lo que pueda fallar", es
 * **envuelve el metodo entero**.
 */
object CajaNegra {

    private const val TAG = "CajaNegra"

    /** Cada cuanto se escribe una linea de estado. */
    private const val PERIODO_MS = 5_000L

    /**
     * Cada cuantas vueltas se clasifican los descriptores y se censan los
     * hilos por nombre. Seis vueltas = 30 segundos.
     *
     * Se separa del periodo porque estas dos son las unicas medidas caras:
     * un `readlink` por descriptor (~150 llamadas) y un `enumerate` del grupo
     * de hilos. Cada 30 s basta de sobra para ver una fuga —una fuga que no se
     * note en 30 s no cuelga un radio en una hora— y mantiene el costo por
     * vuelta en practicamente nada.
     */
    private const val CADA_CUANTO_BARRE = 6

    /** Tope del archivo antes de rotar. Dos archivos = 8 MB como mucho. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    /** Nombre en el almacenamiento privado y en los espejos. */
    private const val NOMBRE = "caja-negra.log"
    private const val NOMBRE_VIEJO = "caja-negra.1.log"

    /**
     * Los hilos vigilados y cuanto pueden estar callados antes de que se
     * anote una alerta.
     *
     * Los plazos NO son redondos por gusto: salen del ritmo real de cada
     * bucle, porque un umbral demasiado corto llena el archivo de alertas
     * falsas y entonces nadie las mira.
     *
     *  - [BOMBA] y [REPARTO] dan una vuelta en menos de un segundo en
     *    condiciones normales (`ESPERA_EVENTO_MS` y `ESPERA_ACL_MS` son 15 ms,
     *    con tope de 8 operaciones por vuelta). Quince segundos callada
     *    significa que un `bulkTransfer` se quedo dentro del kernel — que es
     *    exactamente la forma que tendria el cuelgue si la culpa es del USB.
     *  - [TPMS] late cada 200 ms leyendo, pero entre reaperturas duerme con
     *    retroceso de hasta 30 s (`RESPALDO_MAX_MS`). Un minuto es el primer
     *    valor que no puede ser retroceso normal.
     *  - [BATERIA] duerme 30 s entre rondas y una ronda GATT completa puede
     *    tardar 20 s. Dos minutos.
     *  - [OBD] sondea cada segundo, pero una vuelta de PIDs sobre K-line
     *    tarda medio segundo largo y reconectar ronda los 10 s. Treinta.
     *  - [PRINCIPAL], [PUENTE] y [ACTUALIZADOR] llevan `mudoMs = 0`: se anota su
     *    edad pero NUNCA se alerta. El puente puede pasar horas sin una
     *    peticion y el tablero puede estar cerrado; alertar de eso seria
     *    ruido puro. Su edad igual se registra porque despues del cuelgue
     *    lo que importa es el ORDEN en que enmudecieron, no la alerta.
     */
    enum class Fuente(val etiqueta: String, val mudoMs: Long) {
        CAJA("caja", 20_000),
        TPMS("tpms", 60_000),
        BOMBA("bomba", 15_000),
        REPARTO("reparto", 15_000),
        BATERIA("bat", 120_000),
        OBD("obd", 30_000),
        TERMOMETRO("termo", 20_000),
        /** El hilo principal. Lo late la sonda de [sondearPrincipal] y, si el tablero esta abierto, tambien `DashView.onDraw`. */
        PRINCIPAL("ppal", 0),
        PUENTE("puente", 0),
        ACTUALIZADOR("actual", 0),
    }

    private val FUENTES = Fuente.values()

    /**
     * Ultimo latido de cada fuente, en reloj monotono.
     *
     * `AtomicLongArray` y no un mapa: [latido] se llama desde el bucle de la
     * bomba HCI, que da decenas de vueltas por segundo. Un `ConcurrentHashMap`
     * ahi seria un hash y una busqueda por vuelta; esto es una escritura
     * volatil a un indice ya conocido. La diferencia importa cuando la cosa
     * que estamos midiendo es precisamente el consumo del proceso.
     *
     * Monotono ([SystemClock.elapsedRealtime]) y no de pared: el reloj de
     * pared salta cuando la red pone en hora el radio, y un salto de dos
     * segundos convertiria un hilo sano en un hilo "mudo".
     */
    private val latidos = AtomicLongArray(FUENTES.size)

    /** Cuantas veces ha latido cada fuente. Un contador quieto delata tanto como una edad grande. */
    private val cuentaLatidos = AtomicLongArray(FUENTES.size)

    // --- Contadores de USB, que es el sospechoso principal ------------------

    /**
     * Aperturas y cierres del USB, por aparato.
     *
     * La resta es la medida que interesa: si `abre - cierra` crece sin volver
     * a bajar, hay descriptores del USB quedandose por el camino, y eso es
     * una de las tres hipotesis vivas. Si se mantiene en 0-2 y aun asi el
     * radio se cuelga, la hipotesis de la fuga queda MUERTA y nos ahorramos
     * la cuarta conjetura.
     */
    private val usbAbre = AtomicLong(0)
    private val usbCierra = AtomicLong(0)

    /** Hitos perdidos por cola llena. Si esto no es 0, faltan eventos en el archivo. */
    private val hitosPerdidos = AtomicLong(0)

    /**
     * Cola de hitos. ACOTADA a proposito.
     *
     * Quien anota un hito (abrir el USB, tomar la radio, una excepcion) es un
     * hilo que esta en mitad de su trabajo: no puede bloquearse esperando
     * disco, y sobre todo no puede bloquearse esperando a la caja negra. Si la
     * cola se llena se pierde el hito y se cuenta. Perder un hito es molesto;
     * bloquear la bomba HCI es un cuelgue.
     */
    private val hitos = ArrayBlockingQueue<String>(128)

    // --- Ganchos que rellenan otros modulos, sin acoplar nada --------------

    /**
     * Contadores de la bomba HCI, puestos desde fuera.
     *
     * Es una funcion y no una referencia a [com.nonosky.s2000dash.hci.BombaHci]
     * para que la caja negra no dependa de la pila Bluetooth: tiene que poder
     * arrancar aunque no haya dongle, y tiene que seguir escribiendo aunque
     * la radio este cerrada.
     */
    @Volatile
    var contadoresHci: (() -> String)? = null

    /** Temperatura del SoC. Se inyecta igual, para no depender del Termometro. */
    @Volatile
    var temperatura: (() -> Int)? = null

    // --- Estado interno ----------------------------------------------------

    @Volatile private var vivo = false
    private var hilo: Thread? = null

    @Volatile private var salidas: List<Salida> = emptyList()

    @Volatile private var arrancoEnMs = 0L
    private var secuencia = 0L
    private var bytesEscritos = 0L

    /** Quien enmudecio PRIMERO en esta sesion. Pegajoso: no se borra al recuperarse. */
    @Volatile private var primerMudo: String? = null

    /** Pico de hilos visto, para no perderlo entre dos muestras. */
    @Volatile private var picoHilos = 0

    private var ultimoCenso: String = ""
    private var ultimoJiffies = 0L
    private var ultimoJiffiesMs = 0L

    /** Sonda del hilo principal: [sondearPrincipal]. */
    private val principalPuestoEn = AtomicLong(0)
    private val principalTardoMs = AtomicLong(-1)
    @Volatile private var principalPendiente = false
    private var manejador: Handler? = null

    /** Buffer reutilizado. Una linea por vuelta no merece un StringBuilder nuevo. */
    private val sb = StringBuilder(512)

    // ------------------------------------------------------------------
    // API publica
    // ------------------------------------------------------------------

    /**
     * Marca que una fuente sigue viva. Es el camino caliente: una escritura.
     *
     * Se llama desde dentro de los bucles, NO desde fuera. La diferencia
     * importa: si se llamara desde quien arranca el hilo, se estaria midiendo
     * que el hilo existe, no que avanza. Un hilo atascado dentro de un
     * `bulkTransfer` existe perfectamente.
     */
    fun latido(f: Fuente) {
        val i = f.ordinal
        latidos.set(i, SystemClock.elapsedRealtime())
        cuentaLatidos.incrementAndGet(i)
    }

    /** Un aparato USB se abrio. [quien] identifica cual: "hci", "ch340". */
    fun usbAbierto(quien: String) {
        usbAbre.incrementAndGet()
        hito("USB abre $quien vivos=${usbAbre.get() - usbCierra.get()}")
    }

    /** Un aparato USB se cerro. Sin este par, la resta no significa nada. */
    fun usbCerrado(quien: String) {
        usbCierra.incrementAndGet()
        hito("USB cierra $quien vivos=${usbAbre.get() - usbCierra.get()}")
    }

    /**
     * Anota un hecho puntual con su instante exacto.
     *
     * Los hitos son lo que convierte una tabla de numeros en una historia:
     * treinta pares abrir/cerrar por minuto justo antes de que el archivo se
     * corte no es lo mismo que un archivo que se corta sin un solo hito.
     *
     * Nunca bloquea y nunca toca el disco desde el hilo que llama.
     */
    fun hito(texto: String) {
        if (!vivo) return
        if (!hitos.offer(texto)) hitosPerdidos.incrementAndGet()
    }

    /**
     * Arranca la caja negra. Idempotente y a prueba de todo.
     *
     * Debe ser **lo primero** que haga el servicio: si algo revienta durante
     * el arranque de las fuentes, queremos que quede escrito.
     */
    fun arrancar(context: Context) {
        if (vivo) return
        runCatching { arrancarDeVerdad(context.applicationContext) }
            .onFailure { Log.e(TAG, "la caja negra no arranco: ${it.message}", it) }
    }

    private fun arrancarDeVerdad(app: Context) {
        vivo = true
        arrancoEnMs = SystemClock.elapsedRealtime()
        val ahora = SystemClock.elapsedRealtime()
        for (i in FUENTES.indices) latidos.set(i, ahora)

        // Lo que quedo del vuelo anterior, ANTES de escribir nada encima.
        val cola = runCatching { colaDe(File(app.filesDir, NOMBRE), 4096) }.getOrDefault(emptyList())
        val finalAnterior = cola.lastOrNull { it.startsWith("S ") || it.startsWith("V ") }

        salidas = abrirSalidas(app)

        runCatching {
            manejador = Handler(Looper.getMainLooper())
        }

        // Banner de sesion. Documenta el formato EN EL PROPIO ARCHIVO: quien lo
        // lea dentro de tres meses no va a tener este codigo delante.
        val cab = StringBuilder()
        cab.append("S ").append(System.currentTimeMillis())
            .append(" ARRANQUE caja negra v1")
            .append(" pid=").append(android.os.Process.myPid())
            .append(" arranco=").append(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            .append(" salidas=").append(salidas.joinToString(",") { it.nombre })
            .append(" fdmax=").append(topeDescriptores())
        cab.append('\n')
        cab.append("# V=vuelta A=alerta E=evento H=censo de hilos S=sesion M=muerte\n")
        cab.append("# der=retraso del propio muestreo (ms)   arranco=instante de arranque del APARATO\n")
        cab.append("#   si 'arranco' cambia entre dos lineas, el radio SE REINICIO entre ellas\n")
        cab.append("# T=SoC C  ppal=cuanto tarda el hilo principal en atender (ms)\n")
        cab.append("# hOs=hilos del kernel  hJv=hilos de la JVM  pico=maximo visto\n")
        cab.append("# heap=usado/max MB  nat=monton nativo MB  rss=residente MB  mflt=fallos de pagina mayores\n")
        cab.append("# cpu=% de este proceso  carga=load average del sistema\n")
        cab.append("# fd=descriptores abiertos, desglosados en usb/sock/pipe/otro\n")
        cab.append("# uAbre/uCierra=aperturas y cierres de aparatos USB; uVivo=la resta (fuga si crece)\n")
        cab.append("# lat:X=segundos desde el ultimo latido de X  (#N = cuantos latidos lleva)\n")

        if (finalAnterior != null) {
            // ESTO es la caja negra haciendo su trabajo: decir, nada mas
            // arrancar, si el vuelo anterior termino bien o se corto en seco.
            val limpio = finalAnterior.contains("PARADA")
            cab.append("S ").append(System.currentTimeMillis())
                .append(if (limpio) " el vuelo anterior cerro LIMPIO" else " el vuelo anterior se corto EN SECO (cuelgue o muerte del proceso)")
                .append(" | ultima linea: ").append(finalAnterior.take(400)).append('\n')
        }
        escribir(cab.toString())

        instalarRedDeSeguridad()

        // Prioridad NORMAL, no de fondo. Es deliberado: si el sistema esta
        // muriendo de inanicion, una caja negra en prioridad de fondo es la
        // primera en dejar de correr — justo en el minuto que hay que medir.
        // Cuesta 1 ms cada 5 s; no le quita un cuadro a nadie.
        hilo = thread(name = "caja-negra", isDaemon = true) {
            var vuelta = 0L
            var previsto = SystemClock.elapsedRealtime() + PERIODO_MS
            while (vivo) {
                // Envuelta ENTERA. Si esto lanzara, se llevaria el proceso y la
                // caja negra habria causado el cuelgue que venia a investigar.
                runCatching {
                    // Se espera en la cola de hitos en vez de dormir: asi un
                    // hito se escribe en milisegundos (y no hasta 5 s despues,
                    // que es justo lo que se perderia antes de un cuelgue) y
                    // aun asi la vuelta periodica cae a su hora.
                    val queda = previsto - SystemClock.elapsedRealtime()
                    if (queda > 0) {
                        val h = hitos.poll(queda, TimeUnit.MILLISECONDS)
                        if (h != null) {
                            vaciarHitos(h)
                            latido(Fuente.CAJA)
                            return@runCatching
                        }
                    }
                    val ahoraM = SystemClock.elapsedRealtime()
                    val deriva = ahoraM - previsto
                    previsto = ahoraM + PERIODO_MS
                    latido(Fuente.CAJA)
                    unaVuelta(vuelta++, deriva)
                }.onFailure {
                    Log.w(TAG, "vuelta fallida: ${it.message}")
                    runCatching { Thread.sleep(500) }
                }
            }
        }
    }

    /**
     * Cierre limpio. Deja constancia de que fue limpio.
     *
     * Es la mitad util del par: sin una marca de parada ordenada, el proximo
     * arranque no puede distinguir "lo apagaron" de "se colgo", y esa es la
     * primera pregunta que uno se hace al abrir el archivo.
     */
    fun parada(motivo: String) {
        runCatching {
            if (!vivo) return
            escribir("S ${System.currentTimeMillis()} PARADA limpia: $motivo\n")
            vivo = false
            runCatching { hilo?.interrupt() }
            hilo = null
            synchronized(candadoDisco) {
                salidas.forEach { runCatching { it.cerrar() } }
                salidas = emptyList()
            }
        }
    }

    /**
     * La cola del archivo, para el puente HTTP.
     *
     * Se lee del privado, que es el que siempre existe. Devuelve texto plano
     * porque la caja negra se lee con los ojos y con `grep`, no con un parser.
     */
    fun texto(context: Context, lineas: Int = 300): String = runCatching {
        val f = File(context.applicationContext.filesDir, NOMBRE)
        if (!f.exists()) return "todavia no hay caja negra (¿arranco el servicio?)"
        val trozo = colaDe(f, 256 * 1024)
        val n = lineas.coerceIn(1, 5000)
        val recorte = if (trozo.size <= n) trozo else trozo.subList(trozo.size - n, trozo.size)
        buildString {
            append("archivo: ").append(f.absolutePath)
            append("  (").append(f.length() / 1024).append(" KB)\n")
            append("espejos: ").append(salidas.joinToString(", ") { it.nombre }).append('\n')
            append("hitos perdidos por cola llena: ").append(hitosPerdidos.get()).append('\n')
            append("primero en enmudecer esta sesion: ").append(primerMudo ?: "ninguno").append("\n\n")
            recorte.forEach { append(it).append('\n') }
        }
    }.getOrElse { "ERROR leyendo la caja negra: ${it.javaClass.simpleName}: ${it.message}" }

    /** El archivo anterior a la ultima rotacion, si lo hay. */
    fun textoViejo(context: Context, lineas: Int = 300): String = runCatching {
        val f = File(context.applicationContext.filesDir, NOMBRE_VIEJO)
        if (!f.exists()) return "no hay archivo rotado todavia"
        val trozo = colaDe(f, 256 * 1024)
        val n = lineas.coerceIn(1, 5000)
        (if (trozo.size <= n) trozo else trozo.subList(trozo.size - n, trozo.size)).joinToString("\n")
    }.getOrElse { "ERROR: ${it.message}" }

    /**
     * Copia el archivo a la carpeta de Descargas para poder sacarlo por scp.
     *
     * Existe porque `/data/data/<paquete>/files` NO lo puede leer el SSH del
     * radio: corre con otro UID y no hay root. Descargas si es compartida.
     * Es bajo peticion y no continua para no escribir dos veces lo mismo en
     * el camino caliente.
     */
    fun aDescargas(context: Context): String = runCatching {
        val app = context.applicationContext
        val origen = File(app.filesDir, NOMBRE)
        if (!origen.exists()) return "no hay nada que exportar"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "esta ROM es anterior a Android 10; usa el espejo de Android/data"
        }
        val destino = mediaStoreDestino(app, "caja-negra-${System.currentTimeMillis()}.log")
            ?: return "MediaStore rechazo crear el archivo"
        app.contentResolver.openOutputStream(destino, "w").use { salida ->
            if (salida == null) return "no se pudo abrir el destino"
            origen.inputStream().use { it.copyTo(salida) }
        }
        "copiado a Descargas: $destino  (busca /sdcard/Download/caja-negra-*.log)"
    }.getOrElse { "ERROR exportando: ${it.javaClass.simpleName}: ${it.message}" }

    // ------------------------------------------------------------------
    // La vuelta
    // ------------------------------------------------------------------

    private fun unaVuelta(vuelta: Long, deriva: Long) {
        val ahoraM = SystemClock.elapsedRealtime()
        val barreHoy = (vuelta % CADA_CUANTO_BARRE) == 0L

        // Sonda del hilo principal ANTES de nada: si esta atascado, lo que
        // interesa es cuanto lleva atascado, no cuanto tardo la ultima.
        sondearPrincipal(ahoraM)

        val hJv = runCatching { Thread.activeCount() }.getOrDefault(-1)
        if (hJv > picoHilos) picoHilos = hJv

        val rt = Runtime.getRuntime()
        val usadoMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
        val maxMb = rt.maxMemory() / 1048576.0
        val natMb = runCatching { Debug.getNativeHeapAllocatedSize() / 1048576.0 }.getOrDefault(-1.0)

        val stat = leerStat()
        val rssMb = runCatching {
            val statm = File("/proc/self/statm").readText().trim().split(' ')
            (statm.getOrNull(1)?.toLongOrNull() ?: 0L) * 4096.0 / 1048576.0
        }.getOrDefault(-1.0)

        val cpu = calcularCpu(stat.jiffies, ahoraM)
        val carga = runCatching {
            File("/proc/loadavg").readText().trim().split(' ').firstOrNull() ?: "?"
        }.getOrDefault("?")

        val fd = contarDescriptores(barreHoy)

        sb.setLength(0)
        sb.append("V ").append(System.currentTimeMillis())
        sb.append(" n=").append(vuelta)
        sb.append(" up=").append((ahoraM - arrancoEnMs) / 1000)
        sb.append(" der=").append(deriva)
        // Instante de arranque del APARATO. Si este numero cambia entre dos
        // lineas, el radio se reinicio en medio: es el detector de reinicio
        // mas barato que hay, y no depende de nadie.
        sb.append(" arranco=").append(System.currentTimeMillis() - ahoraM)
        sb.append(" T=").append(runCatching { temperatura?.invoke() ?: -1 }.getOrDefault(-1))
        sb.append(" ppal=").append(principalTardoMs.get())
        sb.append(" hOs=").append(stat.hilos)
        sb.append(" hJv=").append(hJv)
        sb.append(" pico=").append(picoHilos)
        sb.append(" heap=").append(fmt(usadoMb)).append('/').append(fmt(maxMb))
        sb.append(" nat=").append(fmt(natMb))
        sb.append(" rss=").append(fmt(rssMb))
        sb.append(" mflt=").append(stat.fallosMayores)
        sb.append(" cpu=").append(fmt(cpu))
        sb.append(" carga=").append(carga)
        sb.append(" fd=").append(fd.total)
        if (fd.clasificado) {
            sb.append(" usb=").append(fd.usb)
            sb.append(" sock=").append(fd.sockets)
            sb.append(" pipe=").append(fd.tuberias)
            sb.append(" otro=").append(fd.otros)
        }
        sb.append(" uAbre=").append(usbAbre.get())
        sb.append(" uCierra=").append(usbCierra.get())
        sb.append(" uVivo=").append(usbAbre.get() - usbCierra.get())
        runCatching { contadoresHci?.invoke() }.getOrNull()?.let { sb.append(' ').append(it) }
        sb.append(" hitosPerd=").append(hitosPerdidos.get())

        // Edades de los latidos. Este es el bloque que señala al culpable.
        sb.append(" lat:")
        for (f in FUENTES) {
            val edad = ahoraM - latidos.get(f.ordinal)
            sb.append(f.etiqueta).append('=').append(edad / 1000)
            sb.append('#').append(cuentaLatidos.get(f.ordinal)).append(' ')
        }
        sb.append('\n')

        // Alertas de mudez, en su propia linea para poder grepearlas.
        for (f in FUENTES) {
            if (f.mudoMs <= 0L) continue
            val edad = ahoraM - latidos.get(f.ordinal)
            if (edad < f.mudoMs) continue
            if (primerMudo == null) {
                primerMudo = "${f.etiqueta} a los ${(ahoraM - arrancoEnMs) / 1000}s de sesion"
            }
            sb.append("A ").append(System.currentTimeMillis())
                .append(" MUDO ").append(f.etiqueta)
                .append(' ').append(edad / 1000).append("s sin latir")
                .append(" (umbral ").append(f.mudoMs / 1000).append("s)")
                .append(" | primero en enmudecer: ").append(primerMudo)
                .append('\n')
        }

        if (barreHoy) {
            val censo = censarHilos()
            // Solo cuando cambia: un censo identico repetido cada 30 s llenaria
            // el archivo de ruido y taparia lo que si cambia.
            if (censo != ultimoCenso) {
                ultimoCenso = censo
                sb.append("H ").append(System.currentTimeMillis()).append(' ').append(censo).append('\n')
            }
        }

        escribir(sb.toString())
    }

    /** Escribe los hitos que haya, empezando por el que ya se saco de la cola. */
    private fun vaciarHitos(primero: String) {
        val b = StringBuilder(256)
        val ahora = System.currentTimeMillis()
        b.append("E ").append(ahora).append(' ').append(primero).append('\n')
        var n = 0
        while (n < 32) {
            val h = hitos.poll() ?: break
            b.append("E ").append(System.currentTimeMillis()).append(' ').append(h).append('\n')
            n++
        }
        escribir(b.toString())
    }

    // ------------------------------------------------------------------
    // Medidas
    // ------------------------------------------------------------------

    private class Stat(val hilos: Int, val jiffies: Long, val fallosMayores: Long)

    /**
     * Lee `/proc/self/stat`.
     *
     * Da el numero de hilos **de verdad** (los del kernel), que no es el mismo
     * que [Thread.activeCount]: ese solo cuenta los hilos Java del grupo
     * actual y se deja fuera los de Binder, los nativos y los del sistema
     * grafico. Una fuga de hilos nativos no se ve en el conteo de la JVM.
     *
     * El campo 2 es el nombre del proceso y puede traer parentesis y espacios,
     * asi que se corta por el ULTIMO parentesis. Partir por espacios a secas
     * desplaza todos los campos y da numeros creibles pero falsos.
     */
    private fun leerStat(): Stat = runCatching {
        val crudo = File("/proc/self/stat").readText()
        val cola = crudo.substring(crudo.lastIndexOf(')') + 2)
        val c = cola.split(' ')
        // Tras el ')' el indice 0 es el campo 3 (estado): indice = campo - 3.
        val fallosMayores = c.getOrNull(9)?.toLongOrNull() ?: 0L    // campo 12, majflt
        val utime = c.getOrNull(11)?.toLongOrNull() ?: 0L           // campo 14
        val stime = c.getOrNull(12)?.toLongOrNull() ?: 0L           // campo 15
        val hilos = c.getOrNull(17)?.toIntOrNull() ?: -1            // campo 20, num_threads
        Stat(hilos, utime + stime, fallosMayores)
    }.getOrDefault(Stat(-1, 0L, -1L))

    /**
     * Porcentaje de CPU del proceso desde la vuelta anterior.
     *
     * Se asume USER_HZ = 100, que es lo que traen todos los Android de ARM.
     * Si estuviera mal, el numero sale escalado pero su TENDENCIA —que es lo
     * unico que se usa para diagnosticar— sigue siendo correcta.
     */
    private fun calcularCpu(jiffies: Long, ahoraM: Long): Double {
        if (ultimoJiffiesMs == 0L) {
            ultimoJiffies = jiffies
            ultimoJiffiesMs = ahoraM
            return -1.0
        }
        val dt = ahoraM - ultimoJiffiesMs
        val dj = jiffies - ultimoJiffies
        ultimoJiffies = jiffies
        ultimoJiffiesMs = ahoraM
        if (dt <= 0) return -1.0
        return dj * 10.0 * 100.0 / dt
    }

    private class Descriptores(
        val total: Int,
        val clasificado: Boolean,
        val usb: Int = 0,
        val sockets: Int = 0,
        val tuberias: Int = 0,
        val otros: Int = 0,
    )

    /**
     * Cuenta —y de vez en cuando clasifica— los descriptores del proceso.
     *
     * **Esta es la medida que mata o confirma la hipotesis de la fuga.** Un
     * proceso Android tiene su limite en `/proc/self/limits` (tipicamente
     * 1024 o 32768); llegar al tope hace fallar TODO a la vez —sockets,
     * archivos, aparatos USB— y ese fallo en cascada dentro del driver USB es
     * un candidato perfectamente plausible a colgar un kernel de rockchip.
     *
     * Clasificar por tipo es lo que convierte "fugan descriptores" en "fugan
     * descriptores DE USB" o "DE SOCKET". Son culpables distintos: los de USB
     * apuntan al ciclo abrir/reclamar/soltar/cerrar del dongle; los de socket,
     * al puente HTTP con su hilo por peticion.
     *
     * `readlink` sobre los propios descriptores no necesita root. Cuesta una
     * llamada por descriptor, por eso solo se hace cada 30 s.
     */
    private fun contarDescriptores(clasificar: Boolean): Descriptores = runCatching {
        val dir = File("/proc/self/fd")
        val nombres = dir.list() ?: return Descriptores(-1, false)
        if (!clasificar) return Descriptores(nombres.size, false)

        var usb = 0; var sock = 0; var tub = 0; var otros = 0
        for (n in nombres) {
            val destino = runCatching { Os.readlink("/proc/self/fd/$n") }.getOrNull() ?: continue
            when {
                destino.contains("/dev/bus/usb") || destino.contains("usbfs") -> usb++
                destino.startsWith("socket:") -> sock++
                destino.startsWith("pipe:") -> tub++
                else -> otros++
            }
        }
        Descriptores(nombres.size, true, usb, sock, tub, otros)
    }.getOrDefault(Descriptores(-1, false))

    private fun topeDescriptores(): String = runCatching {
        File("/proc/self/limits").readLines()
            .firstOrNull { it.startsWith("Max open files") }
            ?.split(Regex("\\s+"))?.getOrNull(3) ?: "?"
    }.getOrDefault("?")

    /**
     * Censo de hilos por nombre, con los numeros de serie colapsados.
     *
     * Los hilos anonimos de Kotlin se llaman `Thread-12`, `Thread-13`... y
     * contarlos por separado esconde justo lo que se busca. Colapsados en
     * `Thread-#`, una fuga del puente HTTP —que crea un hilo por peticion—
     * salta a la vista como `Thread-#x47`.
     *
     * Se usa `enumerate` del grupo y NO `Thread.getAllStackTraces()`: ese
     * segundo captura la pila de cada hilo, que es carisimo y ademas para el
     * mundo un instante. Aqui solo hacen falta los nombres.
     */
    private fun censarHilos(): String = runCatching {
        var grupo = Thread.currentThread().threadGroup ?: return "?"
        while (grupo.parent != null) grupo = grupo.parent
        val arr = arrayOfNulls<Thread>(grupo.activeCount() + 32)
        val n = grupo.enumerate(arr, true)
        val cuenta = HashMap<String, Int>()
        for (i in 0 until n) {
            val nombre = arr[i]?.name ?: continue
            val clave = nombre.replace(Regex("[0-9]+$"), "#")
            cuenta[clave] = (cuenta[clave] ?: 0) + 1
        }
        cuenta.entries.sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}x${it.value}" }
    }.getOrDefault("?")

    /**
     * Mide cuanto tarda el hilo principal en atender una tarea trivial.
     *
     * Es un detector de ANR casero, y hace falta porque el sintoma que ve el
     * dueño es "pantalla trabada": si el hilo principal se atasca ANTES que
     * los hilos de USB, la historia es completamente otra —un bloqueo de
     * Binder o de la UI— y no habria que seguir mirando al dongle.
     *
     * No se reusa el latido de `onDraw` porque el tablero puede estar cerrado
     * y entonces no se dibuja nada aunque el hilo principal este perfecto.
     */
    private fun sondearPrincipal(ahoraM: Long) {
        runCatching {
            val h = manejador ?: return
            if (principalPendiente) {
                // Sigue sin contestar: se reporta cuanto lleva esperando, que
                // es mas util que el ultimo valor bueno.
                principalTardoMs.set(ahoraM - principalPuestoEn.get())
                return
            }
            principalPendiente = true
            principalPuestoEn.set(ahoraM)
            h.post {
                principalTardoMs.set(SystemClock.elapsedRealtime() - principalPuestoEn.get())
                principalPendiente = false
                latido(Fuente.PRINCIPAL)
            }
        }
    }

    /**
     * Deja escrito el ultimo suspiro cuando el proceso muere por su cuenta.
     *
     * No cubre el cuelgue del sistema entero —ahi no corre nada— pero si el
     * otro final posible: que la app muera y se lleve el tablero. Ha pasado ya
     * con una excepcion escapando de un hilo. Sin esto, ese caso y el cuelgue
     * duro se ven identicos desde fuera: pantalla muerta y sin archivo.
     */
    private fun instalarRedDeSeguridad() {
        runCatching {
            val anterior = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching {
                    val b = StringBuilder(512)
                    b.append("M ").append(System.currentTimeMillis())
                        .append(" MUERTE en hilo '").append(t.name).append("': ")
                        .append(e.javaClass.name).append(": ").append(e.message).append('\n')
                    e.stackTrace.take(8).forEach { b.append("M     ").append(it).append('\n') }
                    escribir(b.toString())
                    salidas.forEach { runCatching { it.sincronizar() } }
                }
                // Se encadena SIEMPRE: secuestrar el manejador del sistema
                // cambiaria el comportamiento de la app, y una caja negra no
                // puede cambiar lo que mide.
                anterior?.uncaughtException(t, e)
            }
        }
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching {
                    escribir("S ${System.currentTimeMillis()} PARADA por apagado de la maquina virtual\n")
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // Disco
    // ------------------------------------------------------------------

    /**
     * Un destino de escritura. Hay varios a proposito.
     *
     * El privado (`filesDir`) es el unico que SIEMPRE funciona y no pide
     * permisos, pero el SSH del radio no lo puede leer: corre con otro UID y
     * aqui no hay root. Por eso ademas se intenta el de `Android/data`, que a
     * veces si es alcanzable por scp segun la ROM — y se anota en el banner
     * cuales abrieron, para no tener que adivinarlo despues.
     */
    private class Salida(
        val nombre: String,
        private val archivo: File?,
        private var flujo: FileOutputStream?,
    ) {
        fun escribir(datos: ByteArray) {
            val f = flujo ?: return
            f.write(datos)
            f.flush()
            // EL fsync. Sin esto la caja negra pierde exactamente los ultimos
            // segundos, que son los unicos que importan: lo que esta en el
            // cache de paginas y no en el flash se evapora con el cuelgue.
            runCatching { f.fd.sync() }
        }

        fun sincronizar() {
            runCatching { flujo?.fd?.sync() }
        }

        /** Rota: cierra, renombra a `.1` y vuelve a abrir vacio. */
        fun rotar() {
            runCatching { flujo?.close() }
            flujo = null
            val a = archivo ?: return
            runCatching {
                val viejo = File(a.parentFile, NOMBRE_VIEJO)
                if (viejo.exists()) viejo.delete()
                a.renameTo(viejo)
            }
            flujo = runCatching { FileOutputStream(a, true) }.getOrNull()
        }

        fun cerrar() {
            runCatching { flujo?.fd?.sync() }
            runCatching { flujo?.close() }
            flujo = null
        }
    }

    private fun abrirSalidas(app: Context): List<Salida> {
        val lista = mutableListOf<Salida>()

        // 1. El privado. Este no puede fallar y es el que lee el puente.
        runCatching {
            val f = File(app.filesDir, NOMBRE)
            lista += Salida("privado:${f.absolutePath}", f, FileOutputStream(f, true))
        }.onFailure { Log.e(TAG, "sin archivo privado: ${it.message}") }

        // 2. El de Android/data. Sin permisos, y a veces alcanzable por scp.
        //    Si la ROM lo bloquea, simplemente no se abre y se sigue.
        runCatching {
            val dir = app.getExternalFilesDir(null)
            if (dir != null) {
                dir.mkdirs()
                val f = File(dir, NOMBRE)
                lista += Salida("externo:${f.absolutePath}", f, FileOutputStream(f, true))
            }
        }.onFailure { Log.w(TAG, "sin espejo externo: ${it.message}") }

        return lista
    }

    /**
     * Candado de escritura.
     *
     * No es por rendimiento —son cuatro escrituras por minuto— sino por
     * INTEGRIDAD. Escriben tres sitios distintos: el hilo de la caja, el
     * manejador de excepciones no capturadas (que corre en el hilo que se
     * muere) y el gancho de apagado. Sin candado, la linea de la MUERTE puede
     * salir entrelazada con una vuelta normal, y esa linea es precisamente la
     * que uno va a leer despues del cuelgue. Una linea rota ahi es lo mismo
     * que no tenerla.
     */
    private val candadoDisco = Any()

    private fun escribir(texto: String) {
        val datos = texto.toByteArray(Charsets.US_ASCII)
        synchronized(candadoDisco) {
            val s = salidas
            for (x in s) runCatching { x.escribir(datos) }
            bytesEscritos += datos.size
            if (bytesEscritos >= MAX_BYTES) {
                bytesEscritos = 0
                for (x in s) runCatching { x.rotar() }
                runCatching {
                    val cab = "S ${System.currentTimeMillis()} rotado; lo anterior esta en $NOMBRE_VIEJO\n"
                        .toByteArray(Charsets.US_ASCII)
                    for (x in s) x.escribir(cab)
                }
            }
        }
    }

    /** Las ultimas lineas de un archivo, sin cargarlo entero en memoria. */
    private fun colaDe(f: File, bytes: Int): List<String> = runCatching {
        if (!f.exists()) return emptyList()
        RandomAccessFile(f, "r").use { raf ->
            val largo = raf.length()
            val desde = maxOf(0L, largo - bytes)
            raf.seek(desde)
            val buf = ByteArray((largo - desde).toInt())
            raf.readFully(buf)
            val texto = String(buf, Charsets.US_ASCII)
            // Si se corto a mitad de linea, esa primera linea se tira.
            val lineas = texto.split('\n').filter { it.isNotBlank() }
            if (desde > 0 && lineas.isNotEmpty()) lineas.drop(1) else lineas
        }
    }.getOrDefault(emptyList())

    private fun mediaStoreDestino(app: Context, nombre: String): android.net.Uri? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nombre)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
    }.getOrNull()

    private fun fmt(d: Double): String =
        if (d < 0) "-1" else String.format("%.1f", d)
}
