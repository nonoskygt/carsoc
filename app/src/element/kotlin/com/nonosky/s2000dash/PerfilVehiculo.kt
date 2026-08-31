package com.nonosky.s2000dash

/**
 * Quien es este carro. Sabor ELEMENT.
 *
 * Gemelo de `src/s2000/PerfilVehiculo.kt`: misma API, otro carro. Todo lo
 * que el codigo compartido necesita saber sobre el vehiculo concreto entra
 * por aqui, para que ninguna clase de `main/` tenga que preguntar "¿en cual
 * de los dos estoy?".
 *
 * ⚠️ Las MAC son VALORES POR OMISION, no verdades. Son las que se midieron
 * en el carro, pero el menu de emparejamiento las sobrescribe en
 * preferencias: si el dueño cambia una bateria, no hay que recompilar.
 */
object PerfilVehiculo {

    const val CLAVE = "element"
    const val NOMBRE = "In my element"
    const val VEHICULO = "Honda Element 2003-2006"
    const val MOTOR = "K24A4"

    /** ISO 9141-2. Confirmado por la base de compatibilidad de Klavkarr. */
    const val PROTOCOLO_ESPERADO = "ISO 9141-2"

    /** Esta convertido en casa rodante: manda el litio, no el motor. */
    const val ES_CASA_RODANTE = true

    /** Dos bancos de litio, cada uno con su BMS JBD. */
    const val TIENE_BANCO_VIVIENDA = true

    /**
     * Lleva sonda LAF de banda ANCHA de fabrica (pieza 36531-PZD-A01), asi
     * que el PID 0134 podria dar una relacion de mezcla real y el reloj de
     * AFR tiene sentido.
     *
     * ⚠️ SIN CONFIRMAR en este carro: hace falta leer el bitmask con el
     * contacto puesto. Mientras no se confirme, el reloj va vacio — que es
     * distinto de no tenerlo.
     */
    const val TIENE_AFR_REAL = true

    /** Refrigeradora Alpicool por BLE. */
    const val TIENE_NEVERA = true

    /** Receptor TPMS por USB (CH340). */
    const val TIENE_TPMS = true

    /**
     * El VTEC de este motor engancha a ~2.200 rpm con carga y suelta a
     * ~2.100: entra y sale en cada cuesta. Por eso el aviso es una LAMPARA
     * de estado y no un fogonazo a pantalla completa.
     */
    const val VTEC_ES_ACONTECIMIENTO = false

    // --- valores por omision del emparejamiento -------------------------
    // Medidos en el carro con un barrido BLE del propio radio.

    const val MAC_BANCO_ARRANQUE = "A4:C1:38:3B:B9:5E"
    const val NOMBRE_BANCO_ARRANQUE = "Element Motor"

    const val MAC_BANCO_VIVIENDA = "A5:C2:37:09:18:EE"
    const val NOMBRE_BANCO_VIVIENDA = "Elementos 300AH"

    const val MAC_NEVERA = "ED:67:39:96:50:9B"

    /** Sin adaptador todavia: el dueño lo compra y el menu lo empareja. */
    const val MAC_OBD_POR_OMISION = ""

    /** Que tema de dibujo usa. Ver el paquete `ui/tema`. */
    const val TEMA = "topografico"
}
