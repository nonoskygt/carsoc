package com.nonosky.s2000dash

/**
 * Constantes del K24A4 (Honda Element 2003-2006).
 *
 * Todo lo ajustable del comportamiento del tablero vive aqui. Si un valor
 * resulta estar mal en el carro, se corrige una linea en este archivo y nada
 * mas: ni la vista ni el scheduler traen numeros magicos propios.
 *
 * ⚠️ VIENE DEL F20C DEL S2000 Y CASI TODO CAMBIA. Ver cada apartado.
 */
object EngineConstants {

    /**
     * Maximo del tacometro.
     *
     * ⚠️ SIN CONFIRMAR EN EL CARRO. Honda no publica el redline del K24A4:
     * no aparece ni en la hoja oficial de especificaciones de 2004 ni en el
     * manual del propietario. Las fuentes de terceros se contradicen entre
     * 6.500 (Wikipedia, que deja vacia la celda del limitador) y 6.800 (varias
     * webs, probablemente confundiendolo con el K24A8 de 2007 en adelante).
     *
     * Se toma 7.000 como tope de esfera para que el limitador quepa dentro sea
     * cual sea. La medida real: pedir 010C a fondo en 2a con el resto de PIDs
     * apagados y buscar el pico.
     */
    const val RPM_MAX = 7_000

    /**
     * Enganche del VTEC.
     *
     * ⚠️ ESTO NO ES UN F20C Y EL NUMERO NO SIGNIFICA LO MISMO.
     *
     * El AP1 engancha a 5.850 y es un acontecimiento: pasa una vez por marcha
     * y con el pedal a fondo. El K24A4 lleva i-VTEC de DOS balancines y solo
     * en admision, y engancha MUCHISIMO antes: logs reales del mismo motor con
     * scan tool leyendo el solenoide dan enganche a 2.200-2.345 rpm con ~91 %
     * de carga, y desenganche a 2.108 rpm con 71 %. O sea que entra y sale
     * constantemente en conduccion normal, hasta subiendo una cuesta a 50 km/h.
     *
     * Honda NO publica la cifra: su nota de prensa solo da una tabla
     * cualitativa. Estos valores salen de datalogs de un K24A4 de Accord —el
     * mismo motor, pero no este carro— y hay que calibrarlos registrando rpm y
     * carga en el Element.
     *
     * Por eso el aviso es una LAMPARA de estado y no el fondo rojo parpadeante
     * del S2000: a esta frecuencia, un fogonazo a pantalla completa tendria la
     * pantalla latiendo todo el viaje.
     */
    const val RPM_VTEC = 2_200

    /**
     * Histeresis del VTEC, en rpm.
     *
     * Sin esto la lampara parpadea sin parar cuando las revoluciones rondan el
     * umbral. Los logs dan enganche a 2.200 y desenganche a 2.108: ~100 rpm.
     */
    const val RPM_VTEC_SUELTA = 2_100

    /** Inicio de la zona roja pintada en la carátula. */
    const val RPM_REDLINE = 6_500

    /** Corte de combustible. ⚠️ Sin confirmar; ver RPM_MAX. */
    const val RPM_FUEL_CUT = 6_800

    /** Umbral ambar del shift light. Debajo de esto el arco va verde. */
    const val RPM_SHIFT_AMBER = 6_000

    /**
     * Carga minima (%) para considerar el VTEC enganchado.
     *
     * Los logs dan ~91 % al enganchar y 71 % al soltar. Se usa 70 como guarda:
     * sin ella, cualquier subida de vueltas en retencion cantaria VTEC.
     */
    const val VTEC_MIN_LOAD_PCT = 70

    /** Antiguedad (ms) a partir de la cual un valor se dibuja en gris. */
    const val STALE_AFTER_MS = 3_000L

    /** Zona normal de temperatura de refrigerante (°C), para la barra. */
    const val COOLANT_HIGH_C = 105

    /**
     * Escala de color del agua.
     *
     * El K24 trabaja algo mas frio que el F20C: el termostato abre sobre los
     * 80 grados y la temperatura de trabajo se asienta entre 85 y 95. Los
     * umbrales se dejan igual que en el AP1 porque el margen de peligro lo
     * marca el refrigerante, no el motor.
     */
    const val COOLANT_TIBIO_C = 80
    const val COOLANT_AVISO_C = 100

    /**
     * Estequiometrica de la gasolina, para el reloj de mezcla.
     *
     * El Element lleva sonda LAF de BANDA ANCHA aguas arriba (pieza
     * 36531-PZD-A01), que el AP1 no tiene. Si la ECU expone 0134, de ahi sale
     * una relacion de equivalencia (lambda) y el AFR real es lambda * 14.7.
     *
     * ⚠️ Si 0134 NO esta soportado, el reloj se queda vacio y manda la fila de
     * ajustes. Convertir los ajustes de combustible en un AFR seria inventarlo:
     * los ajustes dicen cuanto corrige la centralita, no que mezcla hay.
     */
    const val AFR_ESTEQUIOMETRICA = 14.7f

    /** Extremos de la esfera del reloj de mezcla. */
    const val AFR_MIN = 10.0f
    const val AFR_MAX = 20.0f

    /**
     * El VTEC no engancha a bajo pedal aunque las revoluciones esten arriba,
     * por eso la carga entra en la condicion. Sin lectura de carga se asume
     * que no esta enganchado: preferimos no prender la lampara de mas.
     *
     * [enganchadoAntes] permite aplicar la histeresis: una vez enganchado, hace
     * falta bajar de RPM_VTEC_SUELTA para soltarlo.
     */
    fun vtecActive(rpm: Int?, loadPct: Int?, enganchadoAntes: Boolean = false): Boolean {
        if (rpm == null || loadPct == null) return false
        if (loadPct < VTEC_MIN_LOAD_PCT) return false
        return if (enganchadoAntes) rpm >= RPM_VTEC_SUELTA else rpm >= RPM_VTEC
    }
}
