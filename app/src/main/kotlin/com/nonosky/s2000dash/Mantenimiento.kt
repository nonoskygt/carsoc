package com.nonosky.s2000dash

import android.content.Context
import android.content.SharedPreferences

/**
 * Vida del aceite: kilometros y horas de motor hasta el proximo cambio.
 *
 * ## De donde sale cada numero, y por que
 *
 * **El odometro NO se puede leer de la ECU.** Se le pregunto al carro que
 * PIDs soporta y contesto `4100BE3EF810`: se corta en el `0x20`. El odometro
 * estandar es el `01A6`, muy por encima, y ni siquiera esta el `011F` —tiempo
 * de motor encendido— que habria dado las horas gratis. Un AP1 de 2000 no
 * expone ninguna de las dos cosas.
 *
 * Asi que:
 *
 * - **Los kilometros se miden por GPS.** Se comprobo que el receptor del
 *   radio funciona y da 15 m de precision. Integrar la velocidad del OBD era
 *   la alternativa, pero se muestrea cada segundo y medio y en ciudad —parar,
 *   arrancar, parar— el error se acumula rapido. Sobre un intervalo de 6000
 *   km eso son cientos de kilometros de deriva; el GPS se queda en unidades.
 *
 * - **Las horas se cuentan aqui**, sumando el tiempo en que el motor esta
 *   girando de verdad (rpm por encima del ralenti minimo y dato fresco).
 *
 * ## El odometro es un ANCLA, no una medida
 *
 * Este objeto no sabe cuanto ha andado el carro en su vida: sabe cuanto ha
 * andado **desde que el dueño le dijo un numero**. El dueño lee el odometro
 * real del tablero del carro, lo escribe una vez, y de ahi en adelante se le
 * suma lo recorrido. Si al cabo de meses hay deriva, se vuelve a anclar y
 * listo. Fingir un odometro absoluto seria mentir sobre lo que se sabe.
 */
object Mantenimiento {

    private const val ARCHIVO = "mantenimiento"

    private const val K_ODOMETRO_ANCLA = "odometro_ancla_km"
    private const val K_METROS = "metros_desde_ancla"
    private const val K_SEGUNDOS = "segundos_motor"
    private const val K_PROXIMO = "proximo_cambio_km"
    private const val K_SEG_ULTIMO = "segundos_en_ultimo_cambio"
    private const val K_INTERVALO = "intervalo_km"
    private const val K_HORAS_INTERVALO = "intervalo_horas"

    /**
     * 200 horas por intervalo, y de donde sale el numero.
     *
     * Honda no publica un intervalo en horas para el S2000 — ningun
     * fabricante de turismos lo hace, porque el odometro les basta. Donde SI
     * se usan horas es en flotas y maquinaria, y ahi la equivalencia de
     * andar por casa es **1 hora de motor ~ 30 km** de uso mixto: es la
     * velocidad media real de un vehiculo que pasa tiempo parado en trafico.
     *
     * Con los 6000 km que usa el dueño, eso da 6000/30 = 200 horas. Coincide
     * ademas con la horquilla de 150-200 h que suele recomendarse para
     * servicio severo, y el trafico parado es servicio severo: el aceite se
     * calienta y se cizalla igual, pero el odometro no avanza.
     *
     * Es una convencion razonada, no una cifra de Honda. Se puede cambiar.
     */
    const val HORAS_POR_INTERVALO_POR_DEFECTO = 200f

    const val INTERVALO_KM_POR_DEFECTO = 6000f

    /** Por debajo de esto se avisa en ambar; en cero o menos, en rojo. */
    const val AVISO_KM = 500f
    const val AVISO_HORAS = 20f

    /**
     * Debajo de estas revoluciones no se cuenta como motor girando.
     *
     * No es cero a proposito: al soltar el contacto el ELM327 puede dejar una
     * ultima lectura colgada, y contar horas con el carro apagado inflaria la
     * cuenta justo en el dato que nadie puede verificar despues.
     */
    const val RPM_MINIMO_MOTOR = 300

    /**
     * Velocidad minima para sumar distancia, en m/s (~5 km/h).
     *
     * El GPS parado no esta quieto: deriva unos metros por minuto, y el radio
     * de este carro vive enchufado al 12 V constante aunque la llave este
     * fuera. Sin esta guarda, un carro aparcado una semana se "recorreria"
     * kilometros solo, y el aviso de cambio de aceite llegaria antes de
     * tiempo por un aceite que nadie uso.
     */
    const val VELOCIDAD_MINIMA_MS = 1.4f

    /** Precision peor que esto no se cree para medir distancia. */
    const val PRECISION_MAXIMA_M = 40f

    @Volatile
    private var prefs: SharedPreferences? = null

    fun iniciar(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
    }

    // --- Lo guardado ---------------------------------------------------------

    /** El odometro real que el dueño leyo del tablero del carro. */
    var odometroAnclaKm: Float
        get() = prefs?.getFloat(K_ODOMETRO_ANCLA, 0f) ?: 0f
        set(v) { prefs?.edit()?.putFloat(K_ODOMETRO_ANCLA, v)?.apply() }

    /** Metros recorridos por GPS desde el ancla. */
    var metrosDesdeAncla: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs?.getLong(K_METROS, 0L) ?: 0L
        )
        set(v) { prefs?.edit()?.putLong(K_METROS, java.lang.Double.doubleToRawLongBits(v))?.apply() }

    /** Segundos totales de motor girando desde que se instalo esto. */
    var segundosMotor: Long
        get() = prefs?.getLong(K_SEGUNDOS, 0L) ?: 0L
        set(v) { prefs?.edit()?.putLong(K_SEGUNDOS, v)?.apply() }

    /** Los segundos que habia acumulados cuando se cambio el aceite. */
    var segundosEnUltimoCambio: Long
        get() = prefs?.getLong(K_SEG_ULTIMO, 0L) ?: 0L
        set(v) { prefs?.edit()?.putLong(K_SEG_ULTIMO, v)?.apply() }

    var proximoCambioKm: Float
        get() = prefs?.getFloat(K_PROXIMO, 0f) ?: 0f
        set(v) { prefs?.edit()?.putFloat(K_PROXIMO, v)?.apply() }

    var intervaloKm: Float
        get() = prefs?.getFloat(K_INTERVALO, INTERVALO_KM_POR_DEFECTO) ?: INTERVALO_KM_POR_DEFECTO
        set(v) { prefs?.edit()?.putFloat(K_INTERVALO, v)?.apply() }

    var intervaloHoras: Float
        get() = prefs?.getFloat(K_HORAS_INTERVALO, HORAS_POR_INTERVALO_POR_DEFECTO)
            ?: HORAS_POR_INTERVALO_POR_DEFECTO
        set(v) { prefs?.edit()?.putFloat(K_HORAS_INTERVALO, v)?.apply() }

    // --- Lo derivado ---------------------------------------------------------

    /** Odometro estimado: el ancla mas lo andado por GPS desde entonces. */
    val odometroKm: Float
        get() = odometroAnclaKm + (metrosDesdeAncla / 1000.0).toFloat()

    /** Kilometros que faltan. Negativo = pasado de rosca. */
    val kmRestantes: Float
        get() = proximoCambioKm - odometroKm

    /** Horas de motor desde el ultimo cambio. */
    val horasDesdeCambio: Float
        get() = (segundosMotor - segundosEnUltimoCambio).coerceAtLeast(0L) / 3600f

    val horasRestantes: Float
        get() = intervaloHoras - horasDesdeCambio

    /**
     * VIDA DEL ACEITE en por ciento. Es la cara por omision.
     *
     * Se toma el PEOR de los dos desgastes, no la media ni los kilometros
     * solos. El aceite se gasta por andar y por estar caliente sin andar, y
     * un carro de trafico llega al final por horas mucho antes que por
     * kilometros. Promediarlos dejaria pasarse siempre por el lado que mas
     * corre, que es justo el que importa.
     *
     * 100% recien cambiado, 0% cuando toca. No baja de cero: pasado de rosca
     * sigue diciendo 0% y el color ya grita.
     */
    val vidaPct: Int
        get() {
            val porKm = if (proximoCambioKm <= 0f || intervaloKm <= 0f) 100f
                else (kmRestantes / intervaloKm) * 100f
            val porHoras = if (intervaloHoras <= 0f) 100f
                else (horasRestantes / intervaloHoras) * 100f
            return minOf(porKm, porHoras).coerceIn(0f, 100f).toInt()
        }

    /** Horas totales contadas, para curiosidad del dueño. */
    val horasTotales: Float
        get() = segundosMotor / 3600f

    /**
     * Toca cambiar por LO QUE LLEGUE PRIMERO.
     *
     * Los kilometros y las horas miden dos desgastes distintos del mismo
     * aceite: el de andar y el de estar caliente sin andar. Un carro de
     * trafico llega antes por horas y uno de carretera antes por kilometros,
     * asi que exigir las dos cosas dejaria pasarse siempre por una de ellas.
     */
    val toca: Boolean
        get() = (proximoCambioKm > 0f && kmRestantes <= 0f) || horasRestantes <= 0f

    val cerca: Boolean
        get() = (proximoCambioKm > 0f && kmRestantes <= AVISO_KM) ||
            horasRestantes <= AVISO_HORAS

    // --- Lo que cambia el estado ---------------------------------------------

    /**
     * Suma distancia recorrida. Devuelve false si no se creyo la muestra.
     *
     * Se filtra por velocidad y por precision, no por distancia: un salto de
     * GPS de 200 m con el carro parado tiene mucha distancia y ninguna
     * velocidad, y es exactamente lo que hay que descartar.
     */
    fun sumarDistancia(metros: Float, velocidadMs: Float, precisionM: Float): Boolean {
        if (metros <= 0f || !metros.isFinite()) return false
        if (velocidadMs < VELOCIDAD_MINIMA_MS) return false
        if (precisionM <= 0f || precisionM > PRECISION_MAXIMA_M) return false
        // Un salto absurdo entre dos muestras seguidas no es un viaje: es el
        // receptor reenganchandose. A 5 s por muestra, 500 m serian 360 km/h.
        if (metros > 500f) return false
        metrosDesdeAncla += metros.toDouble()
        return true
    }

    fun sumarSegundosMotor(segundos: Long) {
        if (segundos <= 0L) return
        segundosMotor += segundos
    }

    /** El dueño acaba de leer el odometro del carro y lo reancla. */
    fun anclarOdometro(km: Float) {
        odometroAnclaKm = km
        metrosDesdeAncla = 0.0
    }

    /**
     * Se cambio el aceite AHORA: se reinician las dos cuentas.
     *
     * El proximo cambio se pone en el odometro de hoy mas el intervalo, y las
     * horas se anclan a las acumuladas de hoy. Que las dos se reinicien a la
     * vez es el punto: si solo se reiniciara una, la otra seguiria contando
     * desde el cambio anterior y avisaria antes de tiempo para siempre.
     */
    fun aceiteCambiado() {
        proximoCambioKm = odometroKm + intervaloKm
        segundosEnUltimoCambio = segundosMotor
    }

    fun diagnostico(): List<String> = listOf(
        "VIDA DEL ACEITE: %d%%".format(vidaPct),
        "odometro estimado: %.1f km".format(odometroKm),
        "  ancla del dueño: %.0f km".format(odometroAnclaKm),
        "  recorrido por GPS desde el ancla: %.2f km".format(metrosDesdeAncla / 1000.0),
        "proximo cambio: %.0f km  (faltan %.1f)".format(proximoCambioKm, kmRestantes),
        "intervalo: %.0f km / %.0f h".format(intervaloKm, intervaloHoras),
        "horas de motor desde el cambio: %.1f  (faltan %.1f)".format(
            horasDesdeCambio, horasRestantes,
        ),
        "horas de motor totales contadas: %.1f".format(horasTotales),
        "toca cambiar: ${if (toca) "SI" else "no"}${if (!toca && cerca) " (pero ya cerca)" else ""}",
    )
}
