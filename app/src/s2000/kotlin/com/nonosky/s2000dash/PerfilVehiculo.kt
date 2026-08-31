package com.nonosky.s2000dash

/**
 * Quien es este carro. Sabor S2000.
 *
 * Gemelo de `src/element/PerfilVehiculo.kt`: misma API, otro carro. Todo lo
 * que el codigo compartido necesita saber sobre el vehiculo concreto entra
 * por aqui, para que ninguna clase de `main/` tenga que preguntar "¿en cual
 * de los dos estoy?".
 *
 * ⚠️ Las MAC son VALORES POR OMISION, no verdades. El menu de
 * emparejamiento las sobrescribe en preferencias.
 */
object PerfilVehiculo {

    const val CLAVE = "s2000"
    const val NOMBRE = "S2000 Dash"
    const val VEHICULO = "Honda S2000 AP1"
    const val MOTOR = "F20C"

    /** ISO 9141-2, confirmado en el carro por `ATDP`. */
    const val PROTOCOLO_ESPERADO = "ISO 9141-2"

    /** Es un roadster: aqui manda el motor. */
    const val ES_CASA_RODANTE = false

    /** Un solo banco de litio, el de arranque. */
    const val TIENE_BANCO_VIVIENDA = false

    /**
     * ⚠️ NO. Este carro lleva sonda de banda ESTRECHA (`0114`): un voltaje
     * que solo dice de que lado de la estequiometrica esta. Sacarle un AFR
     * seria inventarlo. Y su mapa de PIDs se corta en 0x20, asi que el
     * `0134` de banda ancha tampoco existe — se comprobo preguntando `0100`
     * en vez de suponerlo.
     *
     * Con esto en false, el reloj de mezcla se dibuja apagado y manda la
     * fila de ajustes de combustible, que es lo unico que este motor mide.
     */
    const val TIENE_AFR_REAL = false

    /** Sin nevera: es un descapotable de dos plazas. */
    const val TIENE_NEVERA = false

    /** Receptor TPMS por USB (CH340). */
    const val TIENE_TPMS = true

    /**
     * En el AP1 el VTEC ES un acontecimiento: engancha a 5.850 con el pedal
     * a fondo, una vez por marcha. Aqui el aviso puede permitirse ser
     * espectacular sin volverse ruido.
     */
    const val VTEC_ES_ACONTECIMIENTO = true

    // --- valores por omision del emparejamiento -------------------------

    /** BMS JBD de litio, medido en el carro. */
    const val MAC_BANCO_ARRANQUE = "A4:C1:38:CD:FA:C8"
    const val NOMBRE_BANCO_ARRANQUE = "Litio S2000"

    /** No tiene banco de vivienda. */
    const val MAC_BANCO_VIVIENDA = ""
    const val NOMBRE_BANCO_VIVIENDA = ""

    const val MAC_NEVERA = ""

    /** Steren SCAN-010, emparejado y verificado hablando ISO 9141-2. */
    const val MAC_OBD_POR_OMISION = "00:1D:A5:68:98:8B"

    /** Que tema de dibujo usa. Ver el paquete `ui/tema`. */
    const val TEMA = "cyberpunk"
}
