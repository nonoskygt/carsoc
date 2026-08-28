package com.nonosky.s2000dash.obd

import android.util.Log
import com.nonosky.s2000dash.ConnectionState
import com.nonosky.s2000dash.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Round-robin por prioridad sobre el enlace K-line (§5 del diseño).
 *
 * El K-line es serial: una peticion, una respuesta, ~70-120 ms de ida y
 * vuelta. No hay peticiones multi-PID (eso es exclusivo de CAN), asi que el
 * presupuesto total son ~9-14 lecturas por segundo repartidas entre todo.
 *
 * Cada ciclo pide RPM — es lo unico que la aguja necesita seguir de cerca —
 * y como mucho un dato secundario mas, segun [Plan]. El ritmo lo marca el
 * enlace, no un temporizador: si el adaptador resulta mas rapido de lo
 * previsto, todo sube de frecuencia sin cambios de codigo.
 */
class PollScheduler(
    private val transportFactory: () -> ObdTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * El transporte de la conexion en curso.
     *
     * Se guarda para poder cerrarlo desde [stop]: un `read` bloqueado sobre
     * un `BluetoothSocket` NO se interrumpe con cancelar la corrutina — la
     * cancelacion de Kotlin es cooperativa y el hilo esta dentro de una
     * llamada nativa. Cerrar el socket es lo unico que lo desatora.
     */
    @Volatile
    private var currentTransport: ObdTransport? = null

    /** PIDs que contestaron mal 3 veces seguidas: fuera de la rotacion. */
    private val failures = mutableMapOf<String, Int>()
    private val disabled = mutableSetOf<String>()

    fun start() {
        if (job?.isActive == true) return
        // Dispatchers.IO y no el del scope: el scope que nos pasan suele ser
        // lifecycleScope, que corre en el hilo principal. Todo aqui abajo es
        // I/O bloqueante (BluetoothSocket, sleeps del transporte), asi que en
        // Main congelaria la UI y daria ANR en cuanto conectara.
        job = scope.launch(Dispatchers.IO) { runForever() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { currentTransport?.close() }
        currentTransport = null
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    private suspend fun runForever() {
        var attempt = 0
        while (coroutineContext.isActive) {
            var transport: ObdTransport? = null
            try {
                _state.update { it.copy(connection = ConnectionState.Connecting) }
                transport = transportFactory()
                currentTransport = transport
                val t0 = clock()
                transport.connect()
                com.nonosky.s2000dash.EstadoActual.ultimoErrorEnlace =
                    "conectado en " + (clock() - t0) + " ms"

                _state.update { it.copy(connection = ConnectionState.Initializing) }
                val session = Elm327Session(transport)
                val info = session.initialize()

                _state.update {
                    it.copy(
                        connection = ConnectionState.Polling,
                        protocol = info.describedAs,
                    )
                }
                attempt = 0            // enlace sano: el backoff vuelve a cero
                failures.clear()
                disabled.clear()

                pollLoop(session)
            } catch (e: Exception) {
                coroutineContext.ensureActive()   // cancelacion no es un fallo
                Log.w(TAG, "Ciclo de conexion caido: ${e.message}")
                com.nonosky.s2000dash.EstadoActual.ultimoErrorEnlace =
                    e.javaClass.simpleName + ": " + e.message
            } finally {
                runCatching { transport?.close() }
                if (currentTransport === transport) currentTransport = null
            }

            coroutineContext.ensureActive()
            // Se conservan los ultimos valores; solo cambia el estado, y la
            // vista los pintara en gris cuando pasen de rancios.
            _state.update { it.copy(connection = ConnectionState.Disconnected) }

            val wait = backoffMs(attempt++)
            Log.i(TAG, "Reintento en ${wait} ms")
            delay(wait)
        }
    }

    /** 1 s, 2 s, 4 s, 8 s, con techo de 10 s (§9). */
    internal fun backoffMs(attempt: Int): Long =
        minOf(1000L shl attempt.coerceIn(0, 10), MAX_BACKOFF_MS)

    private suspend fun pollLoop(session: Elm327Session) {
        var cycle = 0
        var consecutiveDead = 0

        while (coroutineContext.isActive) {
            // El RPM va en todos los ciclos: es lo que mueve la aguja.
            val gotRpm = readAndApply(session, PidDecoder.PID_RPM)
            consecutiveDead = if (gotRpm) 0 else consecutiveDead + 1

            // Si ni el RPM contesta varias veces seguidas, el enlace se cayo
            // (motor apagado, adaptador desconectado): salir y reconectar.
            if (consecutiveDead >= DEAD_LINK_THRESHOLD) {
                Log.w(TAG, "Enlace muerto tras $consecutiveDead ciclos sin RPM")
                return
            }

            Plan.secondaryFor(cycle)
                ?.takeIf { it !in disabled }
                ?.let { readAndApply(session, it) }

            cycle++
            // Ceder el hilo sin frenar el ritmo: el round-trip del K-line ya
            // es el limitante real, no hace falta un delay artificial.
            delay(1)
        }
    }

    /** @return true si el PID entrego una muestra utilizable. */
    private fun readAndApply(session: Elm327Session, pid: String): Boolean {
        if (pid == PID_VOLTAGE) {
            val v = session.readVoltage()
            if (v != null) _state.update { it.copy(batteryV = v, batteryAtMs = clock()) }
            return v != null
        }

        val raw = session.queryRaw(pid)
        val now = clock()
        // `update` y no `value = value.copy(...)`: lo segundo es un
        // leer-modificar-escribir que puede perder una muestra si algo mas
        // toca el estado entre medias.
        val applied = when (pid) {
            PidDecoder.PID_RPM -> PidDecoder.decodeRpm(raw)?.also { rpm ->
                _state.update {
                    it.copy(
                        rpm = rpm,
                        rpmAtMs = now,
                        sessionMaxRpm = maxOf(it.sessionMaxRpm, rpm),
                    )
                }
            }
            PidDecoder.PID_SPEED -> PidDecoder.decodeSpeed(raw)?.also { v ->
                _state.update { it.copy(speedKmh = v, speedAtMs = now) }
            }
            PidDecoder.PID_LOAD -> PidDecoder.decodeLoad(raw)?.also { v ->
                _state.update { it.copy(loadPct = v, loadAtMs = now) }
            }
            PidDecoder.PID_COOLANT -> PidDecoder.decodeCoolant(raw)?.also { v ->
                _state.update { it.copy(coolantC = v, coolantAtMs = now) }
            }
            PidDecoder.PID_IAT -> PidDecoder.decodeIat(raw)?.also { v ->
                _state.update { it.copy(iatC = v, iatAtMs = now) }
            }
            // La columna de ADMISION. Estos cuatro tenian campo en
            // VehicleState, decodificador propio y sitio reservado en la
            // pantalla desde el principio — pero nadie los pedia, asi que
            // salian en guiones para siempre. Se vio con el motor andando:
            // COLECTOR, ACELERADOR y AVANCE vacios mientras una prueba
            // directa por /obd-spp devolvia 410B55, o sea 85 kPa.
            PidDecoder.PID_MAP -> PidDecoder.decodeMap(raw)?.also { v ->
                _state.update { it.copy(mapKpa = v, mapAtMs = now) }
            }
            PidDecoder.PID_ACELERADOR -> PidDecoder.decodeAcelerador(raw)?.also { v ->
                _state.update { it.copy(aceleradorPct = v, aceleradorAtMs = now) }
            }
            PidDecoder.PID_AVANCE -> PidDecoder.decodeAvance(raw)?.also { v ->
                _state.update { it.copy(avanceGrados = v, avanceAtMs = now) }
            }
            PidDecoder.PID_O2_V -> PidDecoder.decodeO2Voltaje(raw)?.also { v ->
                _state.update { it.copy(o2Voltaje = v, o2AtMs = now) }
            }
            PidDecoder.PID_TRIM_CORTO -> PidDecoder.decodeTrim(raw, pid)?.also { v ->
                _state.update { it.copy(trimCortoPct = v, trimCortoAtMs = now) }
            }
            PidDecoder.PID_TRIM_LARGO -> PidDecoder.decodeTrim(raw, pid)?.also { v ->
                _state.update { it.copy(trimLargoPct = v, trimLargoAtMs = now) }
            }
            // Devuelve el numero de codigos, no el Pair: todas las ramas de
            // este `when` tienen que compartir tipo o el `applied != null`
            // de abajo se queda sin `equals` utilizable.
            PidDecoder.PID_ESTADO -> PidDecoder.decodeMil(raw)?.let { (mil, n) ->
                _state.update {
                    it.copy(milEncendida = mil, codigosGuardados = n, estadoAtMs = now)
                }
                n
            }
            else -> null
        }

        if (applied != null) {
            failures.remove(pid)
            return true
        }

        // Tres fallos seguidos en el mismo PID: el carro no lo soporta.
        // Sacarlo libera su presupuesto para el resto (§10).
        val n = (failures[pid] ?: 0) + 1
        failures[pid] = n
        if (n >= UNSUPPORTED_THRESHOLD && pid != PidDecoder.PID_RPM) {
            Log.i(TAG, "$pid no soportado tras $n fallos; fuera de la rotacion")
            disabled += pid
        }
        return false
    }

    /**
     * Que dato secundario toca en cada ciclo. Pura y sin estado a proposito:
     * asi las proporciones de §5 se verifican en la JVM sin adaptador.
     *
     * Es una tabla explicita de 60 ciclos y no una cadena de modulos porque
     * los modulos se pisan entre si: "cada 3" y "cada 20" coinciden una de
     * cada tres veces, y el de mayor prioridad le robaria el turno al otro.
     * Con la tabla los turnos son disjuntos por construccion y cada PID
     * recibe exactamente la frecuencia que §5 le asigna.
     *
     * 60 es el minimo comun multiplo de 3, 10 y 20: el patron cierra justo.
     */
    object Plan {
        const val PERIOD = 60

        // LA VELOCIDAD SE FUE, y con ella 20 de los 35 turnos.
        //
        // La quito el dueño: el carro ya la tiene en el cuadro original a la
        // altura de los ojos, igual que el tacometro, y repetirla aqui gastaba
        // un tercio del presupuesto entero de la K-line en un dato duplicado.
        // Esos 20 turnos son los que hacen posible la columna de ADMISION.
        private val LOAD_SLOTS = listOf(1, 11, 22, 31, 41, 52)       // 6  -> ~0.6 Hz
        private val COOLANT_SLOTS = listOf(4, 25, 44)                // 3  -> ~0.3 Hz
        private val IAT_SLOTS = listOf(14, 34, 55)                   // 3, desfasado del agua
        private val VOLTAGE_SLOTS = listOf(8, 28, 49)                // 3, gratis

        // La columna de ADMISION, en huecos que estaban VACIOS.
        //
        // Se eligieron turnos libres, sin quitarselos a nadie: de los 60
        // slots habia 25 sin asignar y aqui se ocupan 16, dejando 9 de
        // margen. El colector es el que mas se mueve, asi que lleva mas.
        //
        // La mezcla va por 0114 —voltaje de sonda de banda estrecha— y no
        // por 0134: el mapa de PIDs de esta ECU no soporta nada por encima
        // del 0x20, y pedir el AFR de banda ancha solo gastaba turnos para
        // recibir vacio.
        // La columna de ADMISION, pagada con los turnos de la velocidad.
        //
        // El presupuesto manda: la K-line va a ~10 lecturas/s y §5 le reserva
        // al RPM 6 Hz, o sea 600/(60+S) >= 6, o sea S <= 40. Con la velocidad
        // fuera quedan 15 basicos, asi que hay 25 turnos para repartir y aqui
        // se usan 20 — el RPM queda en 6,3 Hz y sobra margen.
        //
        // El colector y el acelerador se mueven rapido y llevan 6 cada uno
        // (~0,6 Hz). El avance y la mezcla cambian mas despacio y llevan 4.
        private val MAP_SLOTS = listOf(0, 9, 18, 27, 36, 45)         // 6
        // El ACELERADOR ya no se pide: el dueño lo quito de la pantalla
        // porque el pie ya sabe donde esta. Sus seis turnos vuelven al
        // presupuesto y el RPM sube de 6,0 a 7,1 Hz.
        private val AVANCE_SLOTS = listOf(6, 20, 39, 51)             // 4
        private val O2_SLOTS = listOf(16, 30, 47, 57)                // 4

        // Los ajustes de combustible y la luz de averia: los cinco turnos
        // que quedaban del presupuesto (S llega justo a 40, RPM a 6,0 Hz).
        //
        // Se piden pocas veces a proposito y no es tacañeria: un ajuste de
        // combustible se mueve en decenas de segundos, no en decimas. Lo que
        // importa de el es la TENDENCIA — si esta en +2% o en +22% — y para
        // eso sobra con mirarlo cada diez segundos. La luz de averia cambia
        // aun menos: un turno basta.
        private val TRIM_CORTO_SLOTS = listOf(21, 42)                // 2
        private val TRIM_LARGO_SLOTS = listOf(12, 48)                // 2
        private val ESTADO_SLOTS = listOf(58)                        // 1

        private val table: Array<String?> = arrayOfNulls<String>(PERIOD).also { t ->
            LOAD_SLOTS.forEach { t[it] = PidDecoder.PID_LOAD }
            COOLANT_SLOTS.forEach { t[it] = PidDecoder.PID_COOLANT }
            IAT_SLOTS.forEach { t[it] = PidDecoder.PID_IAT }
            VOLTAGE_SLOTS.forEach { t[it] = PID_VOLTAGE }
            MAP_SLOTS.forEach { t[it] = PidDecoder.PID_MAP }
            AVANCE_SLOTS.forEach { t[it] = PidDecoder.PID_AVANCE }
            O2_SLOTS.forEach { t[it] = PidDecoder.PID_O2_V }
            TRIM_CORTO_SLOTS.forEach { t[it] = PidDecoder.PID_TRIM_CORTO }
            TRIM_LARGO_SLOTS.forEach { t[it] = PidDecoder.PID_TRIM_LARGO }
            ESTADO_SLOTS.forEach { t[it] = PidDecoder.PID_ESTADO }
        }

        fun secondaryFor(cycle: Int): String? = table[Math.floorMod(cycle, PERIOD)]
    }

    companion object {
        private const val TAG = "PollScheduler"
        const val PID_VOLTAGE = "ATRV"
        const val UNSUPPORTED_THRESHOLD = 3
        const val DEAD_LINK_THRESHOLD = 6
        const val MAX_BACKOFF_MS = 10_000L
    }
}
