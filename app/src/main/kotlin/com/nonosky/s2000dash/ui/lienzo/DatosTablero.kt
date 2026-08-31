package com.nonosky.s2000dash.ui.lienzo

import org.json.JSONObject

/**
 * EL CONTRATO DE DATOS del tablero, tal cual lo emite `Puente.estado()`.
 *
 * ## Por que es una copia literal del JSON
 *
 * La variante HTML y la variante Canvas pintan **el mismo estado**. Si cada
 * una tuviera su propio modelo, el dia que se añada un campo una de las dos se
 * quedaria atras sin que nadie lo note: las dos seguirian compilando y una
 * seguiria pintando "--" para siempre. Por eso los nombres de las propiedades
 * de aqui son EXACTAMENTE las claves que escribe
 * `TableroActivity.Puente.estado()`, sin traducir ni reagrupar. Renombrar aqui
 * es romper el contrato, y se nota en el sitio: [desdeJson] busca por nombre.
 *
 * ## Todo anulable, y null significa una sola cosa
 *
 * **null = "no lo se"**, y se pinta `Pincel.SIN_DATO` y APAGADO. Nunca un cero.
 * El servicio ya hace ese trabajo: un valor viejo —mas de
 * `EngineConstants.STALE_AFTER_MS` sin refresco— sale del puente como `null`,
 * asi que el pintor no tiene que saber de relojes, solo de ausencias. Un 0 que
 * llega aqui es un CERO MEDIDO y se pinta como tal: 0 rpm con el motor parado
 * y "no se cuantas rpm hay" son cosas opuestas y no pueden verse igual.
 *
 * Los datos DEDUCIDOS —[dcdc], [inversorW], [nevComp], [vtec]— llegan por el
 * mismo camino que los medidos y solo se distinguen al pintarlos: quien los
 * dibuje esta obligado a marcarlos (`Pincel.recintoDeducido` o el rotulo
 * "DEDUCIDO"). Aqui no hay forma de saberlo mirando el tipo, y por eso se
 * enumeran en este parrafo.
 *
 * ## Nota para los demas pintores
 *
 * Este fichero lo creo el pintor de MOTOR porque no existia. Es de todos: si
 * falta un campo del contrato, se añade aqui — no se hace un segundo modelo.
 */
data class DatosTablero(

    // ---------- banco de vivienda ----------
    /** Estado de carga del banco de vivienda, en %. */
    val vivSoc: Int? = null,
    val vivV: Float? = null,
    val vivA: Float? = null,
    /** Potencia con SIGNO: positivo entra al banco, negativo sale. */
    val vivW: Int? = null,
    val vivT: Int? = null,
    /** Autonomia en texto. Pendiente de historial de consumo: hoy siempre null. */
    val vivH: String? = null,
    /** Rotulo y MAC salen del BMS, no del pintor: la tarjeta no puede mentir
     *  sobre que bateria esta enseñando. */
    val vivNom: String? = null,
    val vivMac: String? = null,
    val arrNom: String? = null,
    val arrMac: String? = null,

    // ---------- deducidos del banco de vivienda ----------
    /** DEDUCIDO. Texto de estado del cargador DC-DC, inferido del signo de la
     *  corriente y de si hay enlace con el motor. */
    val dcdc: String? = null,
    /** DEDUCIDO. Consumo del inversor, inferido de la descarga del banco. */
    val inversorW: Int? = null,

    // ---------- banco de arranque ----------
    val arrSoc: Int? = null,
    val arrV: Float? = null,
    val arrA: Float? = null,
    val arrW: Int? = null,
    val arrT: Int? = null,

    // ---------- nevera ----------
    val nevT: Int? = null,
    val nevSet: Int? = null,
    val nevV: Float? = null,
    val nevEco: Boolean? = null,
    /** "Encendida" / "Apagada" / null. Lo que dice la NEVERA, no lo que se mando. */
    val nevOn: String? = null,
    /** DEDUCIDO. "En marcha" / "Parado": el protocolo no expone el compresor. */
    val nevComp: String? = null,
    /** Extremos del carril termico. Los dicta la nevera, no una constante. */
    val nevMin: Int? = null,
    val nevMax: Int? = null,

    // ---------- motor ----------
    /** Temperatura de refrigerante, °C. */
    val agua: Int? = null,
    val rpm: Int? = null,
    /** Temperatura del aire de admision, °C. */
    val aire: Int? = null,
    /** Carga calculada del motor, %. */
    val carga: Int? = null,
    /** Avance de encendido, grados. */
    val avance: Int? = null,
    /** Presion del colector ya convertida a PSI por el puente. */
    val mapPsi: Float? = null,
    /** Ajuste de combustible TOTAL: corto + largo, %. Los dos o ninguno. */
    val trim: Int? = null,
    /**
     * Relacion de mezcla REAL de la sonda de banda ancha (PID 0134).
     *
     * null mientras no haya lambda medida, y entonces el reloj va sin aguja.
     * Nunca 14,7 por omision: eso seria inventar el unico numero que el reloj
     * existe para enseñar.
     */
    val afr: Float? = null,
    /** DEDUCIDO de rpm + carga con histeresis. OBD-II no expone el solenoide. */
    val vtec: Boolean? = null,

    // ---------- llantas, en orden de lectura: DI, DD, TI, TD ----------
    val ll0psi: Float? = null,
    val ll0t: Int? = null,
    val ll0baja: Boolean? = null,
    val ll1psi: Float? = null,
    val ll1t: Int? = null,
    val ll1baja: Boolean? = null,
    val ll2psi: Float? = null,
    val ll2t: Int? = null,
    val ll2baja: Boolean? = null,
    val ll3psi: Float? = null,
    val ll3t: Int? = null,
    val ll3baja: Boolean? = null,

    // ---------- aceite y radio ----------
    val acePct: Int? = null,
    val aceKm: Int? = null,
    val aceH: Int? = null,
    /** Temperatura del propio radio, °C. La que baja los cuadros por segundo. */
    val radioC: Int? = null,

    // ---------- averias ----------
    /** Luz de averia. null = no se ha hablado con la ECU; NUNCA "sin averia". */
    val mil: Boolean? = null,
    val codigos: Int? = null,

    // ---------- puntos de enlace ----------
    // No son adorno: si muere el receptor de las llantas, las cuatro presiones
    // se quedan congeladas en el ultimo valor bueno y siguen pareciendo
    // correctas. El punto es lo unico que lo delata.
    val okViv: Boolean? = null,
    val okArr: Boolean? = null,
    val okNev: Boolean? = null,
    val okTpms: Boolean? = null,
    val okObd: Boolean? = null,
) {

    /** Presion de la rueda [i] (0=DI, 1=DD, 2=TI, 3=TD), o null. */
    fun llantaPsi(i: Int): Float? = when (i) {
        0 -> ll0psi; 1 -> ll1psi; 2 -> ll2psi; 3 -> ll3psi; else -> null
    }

    /** Temperatura de la rueda [i]. */
    fun llantaT(i: Int): Int? = when (i) {
        0 -> ll0t; 1 -> ll1t; 2 -> ll2t; 3 -> ll3t; else -> null
    }

    /** ¿Presion baja en la rueda [i]? null = no se sabe. */
    fun llantaBaja(i: Int): Boolean? = when (i) {
        0 -> ll0baja; 1 -> ll1baja; 2 -> ll2baja; 3 -> ll3baja; else -> null
    }

    companion object {

        /** Lo que se pinta antes del primer dato: todo en "no lo se". */
        val VACIO = DatosTablero()

        /**
         * Traduce el JSON de `Puente.estado()`.
         *
         * **Fuera del camino de dibujo.** Esto aloca —un JSONObject y una
         * instancia nueva— y por eso se llama UNA vez por lectura, no por
         * cuadro. La vista guarda el resultado en un campo y `onDraw` solo lo
         * lee.
         *
         * Un JSON roto NO tumba el tablero: devuelve [VACIO], que se pinta
         * entero en "--". Un tablero apagado se entiende; un tablero caido en
         * mitad de una carretera, no.
         */
        fun desdeJson(json: String): DatosTablero = try {
            val o = JSONObject(json)
            DatosTablero(
                vivSoc = ent(o, "vivSoc"), vivV = dec(o, "vivV"), vivA = dec(o, "vivA"),
                vivW = ent(o, "vivW"), vivT = ent(o, "vivT"), vivH = txt(o, "vivH"),
                vivNom = txt(o, "vivNom"), vivMac = txt(o, "vivMac"),
                arrNom = txt(o, "arrNom"), arrMac = txt(o, "arrMac"),
                dcdc = txt(o, "dcdc"), inversorW = ent(o, "inversorW"),
                arrSoc = ent(o, "arrSoc"), arrV = dec(o, "arrV"), arrA = dec(o, "arrA"),
                arrW = ent(o, "arrW"), arrT = ent(o, "arrT"),
                nevT = ent(o, "nevT"), nevSet = ent(o, "nevSet"), nevV = dec(o, "nevV"),
                nevEco = bool(o, "nevEco"), nevOn = txt(o, "nevOn"),
                nevComp = txt(o, "nevComp"),
                nevMin = ent(o, "nevMin"), nevMax = ent(o, "nevMax"),
                agua = ent(o, "agua"), rpm = ent(o, "rpm"), aire = ent(o, "aire"),
                carga = ent(o, "carga"), avance = ent(o, "avance"),
                mapPsi = dec(o, "mapPsi"), trim = ent(o, "trim"), afr = dec(o, "afr"),
                vtec = bool(o, "vtec"),
                ll0psi = dec(o, "ll0psi"), ll0t = ent(o, "ll0t"), ll0baja = bool(o, "ll0baja"),
                ll1psi = dec(o, "ll1psi"), ll1t = ent(o, "ll1t"), ll1baja = bool(o, "ll1baja"),
                ll2psi = dec(o, "ll2psi"), ll2t = ent(o, "ll2t"), ll2baja = bool(o, "ll2baja"),
                ll3psi = dec(o, "ll3psi"), ll3t = ent(o, "ll3t"), ll3baja = bool(o, "ll3baja"),
                acePct = ent(o, "acePct"), aceKm = ent(o, "aceKm"), aceH = ent(o, "aceH"),
                radioC = ent(o, "radioC"),
                mil = bool(o, "mil"), codigos = ent(o, "codigos"),
                okViv = bool(o, "okViv"), okArr = bool(o, "okArr"), okNev = bool(o, "okNev"),
                okTpms = bool(o, "okTpms"), okObd = bool(o, "okObd"),
            )
        } catch (e: Throwable) {
            VACIO
        }

        // Ausente y `null` explicito son lo MISMO aqui: los dos son "no lo se".
        // Distinguirlos no daria ninguna informacion util y si daria una
        // excepcion el dia que el puente deje de escribir una clave.

        private fun ent(o: JSONObject, k: String): Int? =
            if (o.isNull(k)) null else runCatching { o.getInt(k) }.getOrNull()

        private fun dec(o: JSONObject, k: String): Float? =
            if (o.isNull(k)) null else runCatching { o.getDouble(k).toFloat() }.getOrNull()

        private fun bool(o: JSONObject, k: String): Boolean? =
            if (o.isNull(k)) null else runCatching { o.getBoolean(k) }.getOrNull()

        private fun txt(o: JSONObject, k: String): String? =
            if (o.isNull(k)) null else runCatching { o.getString(k) }.getOrNull()
    }
}
