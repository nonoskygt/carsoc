package com.nonosky.s2000dash

/**
 * Constantes del F20C (Honda S2000 AP1).
 *
 * Todo lo ajustable del comportamiento del tablero vive aqui. Si un valor
 * resulta estar mal en el carro, se corrige una linea en este archivo y nada
 * mas: ni la vista ni el scheduler traen numeros magicos propios.
 */
object EngineConstants {

    /** Maximo del tacometro. La carátula se dibuja de 0 a este valor. */
    const val RPM_MAX = 9000

    /** Enganche del VTEC. Ver [vtecActive]: hace falta carga, no solo rpm. */
    const val RPM_VTEC = 5850

    /** Inicio de la zona roja pintada en la carátula. */
    const val RPM_REDLINE = 8300

    /** Corte de combustible. Cerca de aqui el shift light parpadea. */
    const val RPM_FUEL_CUT = 9000

    /** Umbral ambar del shift light. Debajo de esto el arco va verde. */
    const val RPM_SHIFT_AMBER = 7500

    /** Carga minima (%) para considerar el VTEC enganchado. */
    const val VTEC_MIN_LOAD_PCT = 60

    /**
     * Constante de tiempo del amortiguamiento exponencial de la aguja (ms).
     *
     * El RPM llega cada ~160 ms; sin suavizado la aguja salta. Subir este
     * valor da una aguja mas suave pero mas retrasada. Ver §8 del diseño.
     */
    const val NEEDLE_TAU_MS = 120f

    /** Antiguedad (ms) a partir de la cual un valor se dibuja en gris. */
    const val STALE_AFTER_MS = 3_000L

    /** Zona normal de temperatura de refrigerante (°C), para la barra. */
    const val COOLANT_NORMAL_C = 88
    const val COOLANT_HIGH_C = 105

    /**
     * El VTEC no engancha a bajo pedal aunque las revoluciones esten arriba,
     * por eso la carga entra en la condicion. Sin lectura de carga se asume
     * que no esta enganchado: preferimos no prender la banda de mas.
     */
    fun vtecActive(rpm: Int?, loadPct: Int?): Boolean =
        rpm != null && loadPct != null && rpm >= RPM_VTEC && loadPct >= VTEC_MIN_LOAD_PCT
}
