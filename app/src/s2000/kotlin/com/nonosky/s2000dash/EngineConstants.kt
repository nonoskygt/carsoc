package com.nonosky.s2000dash

/**
 * Constantes del F20C (Honda S2000 AP1, 1999-2003).
 *
 * Este fichero es el GEMELO del de `src/element/`. Los dos exponen la MISMA
 * API porque el codigo compartido llama a las dos por igual; lo que cambia
 * son los numeros, que son de motores muy distintos.
 *
 * Todo lo ajustable del comportamiento del tablero vive aqui. Si un valor
 * resulta estar mal en el carro, se corrige una linea y nada mas: ni la
 * vista ni el scheduler traen numeros magicos propios.
 */
object EngineConstants {

    /** Maximo del tacometro. La carátula se dibuja de 0 a este valor. */
    const val RPM_MAX = 9_000

    /**
     * Enganche del VTEC.
     *
     * En el AP1 esto ES un acontecimiento: pasa una vez por marcha, con el
     * pedal a fondo, y por eso aqui el aviso puede ser espectacular. El
     * K24A4 del Element engancha a 2.200 y entra y sale en cada cuesta —
     * comparar los dos ficheros es la mejor forma de ver por que el mismo
     * tablero necesita dos avisos distintos.
     */
    const val RPM_VTEC = 5_850

    /**
     * Umbral de suelta.
     *
     * El F20C no necesita histeresis de verdad —engancha una vez y se queda
     * hasta el cambio de marcha— pero la API es comun a los dos carros, asi
     * que se define un margen pequeño en lugar de dejar el hueco.
     */
    const val RPM_VTEC_SUELTA = 5_750

    /** Inicio de la zona roja pintada en la carátula. */
    const val RPM_REDLINE = 8_300

    /** Corte de combustible. Cerca de aqui el shift light parpadea. */
    const val RPM_FUEL_CUT = 9_000

    /** Umbral ambar del shift light. Debajo de esto el arco va verde. */
    const val RPM_SHIFT_AMBER = 7_500

    /** Carga minima (%) para considerar el VTEC enganchado. */
    const val VTEC_MIN_LOAD_PCT = 60

    /** Antiguedad (ms) a partir de la cual un valor se dibuja en gris. */
    const val STALE_AFTER_MS = 3_000L

    /** Zona normal de temperatura de refrigerante (°C), para la barra. */
    const val COOLANT_HIGH_C = 105

    /**
     * Escala de color del agua, pensada para el F20C.
     *
     * El termostato del AP1 abre sobre los 82 grados y la temperatura de
     * trabajo se asienta entre 85 y 95. Por encima de 100 el ventilador ya
     * deberia estar corriendo, y 105 es donde empieza el problema de verdad.
     */
    const val COOLANT_TIBIO_C = 82
    const val COOLANT_AVISO_C = 100

    /**
     * Estequiometrica de la gasolina, para el reloj de mezcla.
     *
     * ⚠️ EN ESTE CARRO EL RELOJ NO TIENE FUENTE. El AP1 lleva sonda de banda
     * ESTRECHA: da un voltaje que solo dice de que lado de la
     * estequiometrica esta, no una relacion. Su mapa de PIDs se corta en
     * 0x20, asi que tampoco existe el 0134.
     *
     * Se definen las constantes para que la API sea comun, pero el perfil de
     * este carro declara `tieneAfrReal = false` y la esfera va apagada. La
     * fila de MEZCLA se calcula con la suma de los ajustes de combustible,
     * que es lo unico que este motor mide de verdad.
     */
    const val AFR_ESTEQUIOMETRICA = 14.7f
    const val AFR_MIN = 10.0f
    const val AFR_MAX = 20.0f

    /**
     * El VTEC no engancha a bajo pedal aunque las revoluciones esten arriba,
     * por eso la carga entra en la condicion. Sin lectura de carga se asume
     * que no esta enganchado: preferimos no prender la banda de mas.
     */
    fun vtecActive(rpm: Int?, loadPct: Int?, enganchadoAntes: Boolean = false): Boolean {
        if (rpm == null || loadPct == null) return false
        if (loadPct < VTEC_MIN_LOAD_PCT) return false
        return if (enganchadoAntes) rpm >= RPM_VTEC_SUELTA else rpm >= RPM_VTEC
    }
}
