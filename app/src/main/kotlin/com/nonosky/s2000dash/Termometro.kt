package com.nonosky.s2000dash

import android.util.Log
import java.io.File

/**
 * Vigila la temperatura del SoC y hace que el tablero se aparte cuando quema.
 *
 * Existe porque el head unit se apago **dos veces** por calor. Y no fue mala
 * suerte: este rk3326 idlea a 59 grados sin hacer nada —medido por SSH con la
 * app cerrada— asi que el margen es estrecho desde el principio. Encima se le
 * puso un tablero repintando, sondeo OBD, conexiones BLE, lectura USB, un
 * servidor HTTP y un actualizador. Cada pieza parecia barata por separado.
 *
 * La leccion es que en este aparato **no basta con que cada parte sea
 * razonable**: hay que medir el total y ceder cuando sube. Un tablero que
 * apaga el radio del carro no es un tablero, es una averia.
 *
 * Por eso esto no aconseja, MANDA: los consumidores preguntan por [nivel] y
 * se apagan solos. Y el TPMS nunca se apaga — cuesta casi nada y es lo unico
 * que avisa de algo que puede reventar en carretera.
 */
object Termometro {

    private const val TAG = "Termometro"

    /**
     * Las zonas donde suele estar la temperatura del SoC.
     *
     * Se prueban varias porque el nombre cambia entre ROMs, y quedarse sin
     * termometro por buscar en un solo sitio dejaria al regulador ciego —
     * o sea, sin regular nada.
     */
    private val CANDIDATAS = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp",
    )

    @Volatile
    var gradosC: Int = -1
        private set

    @Volatile
    var nivel: Nivel = Nivel.Fresco
        private set

    @Volatile
    var vecesQueBajo: Int = 0
        private set

    enum class Nivel {
        /** Todo corriendo. */
        Fresco,

        /** Se espacia el sondeo y se baja el repintado. */
        Tibio,

        /** Solo el TPMS y el puente. Se sueltan OBD y bateria. */
        Caliente,
    }

    /**
     * Relee la temperatura. Devuelve el nivel resultante.
     *
     * Con histeresis: se sube de nivel antes de lo que se baja. Sin ella, un
     * aparato oscilando en el umbral encenderia y apagaria el OBD cada pocos
     * segundos, que gasta mas que dejarlo quieto en cualquiera de los dos
     * estados.
     */
    fun medir(): Nivel {
        val t = leer()
        if (t <= 0) return nivel
        gradosC = t

        val nuevo = when {
            t >= UMBRAL_CALIENTE -> Nivel.Caliente
            t >= UMBRAL_TIBIO -> Nivel.Tibio
            t <= UMBRAL_VUELTA_FRESCO -> Nivel.Fresco
            // En la franja intermedia se conserva el nivel: eso es la
            // histeresis, y es lo que evita el parpadeo.
            else -> if (nivel == Nivel.Caliente) Nivel.Tibio else nivel
        }

        if (nuevo != nivel) {
            if (nuevo.ordinal > nivel.ordinal) vecesQueBajo++
            Log.i(TAG, "$gradosC C: $nivel -> $nuevo")
            nivel = nuevo
        }
        return nivel
    }

    /** ¿Puede correr el sondeo del motor? Es lo que mas cuesta. */
    fun permiteObd(): Boolean = nivel == Nivel.Fresco

    /** ¿Puede el vigilante conectarse a la bateria? */
    fun permiteBateria(): Boolean = nivel != Nivel.Caliente

    /** Milisegundos entre cuadros segun lo caliente que este. */
    fun msEntreCuadros(): Long = when (nivel) {
        Nivel.Fresco -> 200L
        Nivel.Tibio -> 500L
        Nivel.Caliente -> 1_000L
    }

    private fun leer(): Int = runCatching {
        for (ruta in CANDIDATAS) {
            val f = File(ruta)
            if (!f.canRead()) continue
            val crudo = f.readText().trim().toIntOrNull() ?: continue
            // Unas ROMs dan milesimas de grado y otras grados enteros.
            return if (crudo > 1000) crudo / 1000 else crudo
        }
        -1
    }.getOrDefault(-1)

    fun diagnostico(): List<String> = listOf(
        "temperatura del SoC: ${if (gradosC > 0) "$gradosC C" else "no legible"}",
        "nivel: $nivel",
        "veces que hubo que ceder: $vecesQueBajo",
        "umbrales: tibio $UMBRAL_TIBIO C, caliente $UMBRAL_CALIENTE C, " +
            "vuelta a fresco $UMBRAL_VUELTA_FRESCO C",
    )

    /**
     * Umbrales, elegidos sobre lo MEDIDO en este radio y no sobre una tabla.
     *
     * En reposo y sin app marca 59. Con el tablero entero corriendo a 5 fps
     * marco 64. Se apago por encima de eso. Asi que 70 es donde hay que
     * empezar a ceder y 78 donde hay que soltarlo casi todo — con margen para
     * reaccionar antes de que la proteccion del fabricante corte la corriente,
     * que es lo que deja al conductor sin radio.
     */
    private const val UMBRAL_TIBIO = 70
    private const val UMBRAL_CALIENTE = 78
    private const val UMBRAL_VUELTA_FRESCO = 66
}
