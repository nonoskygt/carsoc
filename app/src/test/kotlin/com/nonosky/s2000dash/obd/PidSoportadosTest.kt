package com.nonosky.s2000dash.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que puede medir este carro, dicho por el carro.
 *
 * El `0100` es la unica fuente de verdad sobre lo que soporta esta ECU, y de
 * el cuelga todo lo demas: que PIDs se piden en el reparto de turnos y cuales
 * ni se intentan. El proyecto ya pago el precio de suponerlo — se gastaron
 * turnos pidiendo el AFR de banda ancha `0134` a una centralita cuyo mapa se
 * corta en el `0x20`, y la respuesta era vacio SIEMPRE.
 *
 * La mascara se verifico una vez a mano contra el carro y luego nadie la
 * volvio a mirar. Un bit corrido al descodificar no se nota: la lista sigue
 * pareciendo una lista razonable, solo que con los PIDs equivocados. Estas
 * pruebas clavan la respuesta real, bit por bit.
 */
class PidSoportadosTest {

    /**
     * Lo que el S2000 AP1 contesto de verdad al `0100`.
     *
     * Mascara `BE 3E F8 10`. Desglosada, con el bit mas alto del primer byte
     * en el PID 0x01:
     *
     *     BE = 1011 1110 -> 01 . 03 04 05 06 07 .
     *     3E = 0011 1110 -> .  .  0B 0C 0D 0E 0F .
     *     F8 = 1111 1000 -> 11 12 13 14 15 .  .  .
     *     10 = 0001 0000 -> .  .  .  1C .  .  .  .
     */
    private val RESPUESTA_REAL = "4100BE3EF810"

    private val PIDS_DEL_CARRO = listOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x11, 0x12, 0x13, 0x14, 0x15,
        0x1C,
    )

    // --- La respuesta real del carro ----------------------------------------

    @Test
    fun `la mascara real del carro es BE3EF810 y son diecisiete PIDs`() {
        assertEquals(PIDS_DEL_CARRO, PidDecoder.soportados(RESPUESTA_REAL, 0x00))
    }

    @Test
    fun `la velocidad y el acelerador SI los tiene este carro`() {
        // Se afirma porque el tablero los pide en cada vuelta del reparto: si
        // un dia la mascara dejara de traerlos, la pantalla se quedaria con
        // dos huecos y nadie sabria si es la ECU o el descodificador.
        val lista = PidDecoder.soportados(RESPUESTA_REAL, 0x00)
        assertTrue("el 0D (velocidad) tiene que estar", lista.contains(0x0D))
        assertTrue("el 11 (acelerador) tiene que estar", lista.contains(0x11))
        // Y los otros cuatro de los que vive la pantalla.
        assertTrue(lista.contains(0x0C))   // rpm
        assertTrue(lista.contains(0x05))   // agua
        assertTrue(lista.contains(0x0F))   // aire de admision
        assertTrue(lista.contains(0x04))   // carga
    }

    @Test
    fun `lo que este carro NO tiene, y por eso no hay que pedirlo`() {
        val lista = PidDecoder.soportados(RESPUESTA_REAL, 0x00)
        // El complemento exacto dentro del bloque 01-20. Se enumera entero en
        // vez de mirar solo dos o tres porque un bit corrido mueve PIDs de un
        // lado al otro, y el fallo se ve justo aqui.
        val ausentes = listOf(
            0x02, 0x08, 0x09, 0x0A, 0x10,
            0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B,
            0x1D, 0x1E, 0x1F, 0x20,
        )
        for (pid in ausentes) {
            assertFalse("el %02X no lo soporta este carro".format(pid), lista.contains(pid))
        }
        // Dos que valen la pena decir en voz alta:
        // - el 0x10 (MAF) no esta y el 0x0B (MAP) si: este motor es de
        //   densidad-velocidad, no lleva caudalimetro. Pedir MAF es tirar turno.
        // - el 0x08/0x09 (banco 2) no estan porque el F20C es de un solo banco.
        assertTrue(lista.contains(0x0B))
        assertFalse(lista.contains(0x10))
    }

    @Test
    fun `la mascara real dice que no hay bloque 21-40`() {
        // Este es el hallazgo que zanjo la discusion del AFR de banda ancha:
        // el mapa de esta ECU se corta en el 0x20, asi que el 0134 no es que
        // no conteste, es que no existe y nunca existio.
        assertFalse(PidDecoder.hayMasBloques(RESPUESTA_REAL, 0x00))
    }

    // --- El bit que anuncia el bloque siguiente ------------------------------

    @Test
    fun `el bit de mas abajo es el que anuncia el bloque siguiente`() {
        // 0x00000001 = solo el ultimo bit -> solo el PID 0x20, que no es un
        // sensor sino el indice del bloque 21-40.
        assertEquals(listOf(0x20), PidDecoder.soportados("410000000001", 0x00))
        assertTrue(PidDecoder.hayMasBloques("410000000001", 0x00))

        // 0x00000002 es el bit de al lado (PID 0x1F). Si alguien confundiera
        // uno con otro, el recorrido de bloques se cortaria o se iria de largo.
        assertEquals(listOf(0x1F), PidDecoder.soportados("410000000002", 0x00))
        assertFalse(PidDecoder.hayMasBloques("410000000002", 0x00))
    }

    @Test
    fun `cada extremo de la mascara cae en el PID que le toca`() {
        // Los cuatro bits que fijan el orden sin ambiguedad: si alguien
        // invirtiera la mascara, o cambiara shr por shl, estos cuatro cambian.
        assertEquals(listOf(0x01), PidDecoder.soportados("410080000000", 0x00))
        assertEquals(listOf(0x08), PidDecoder.soportados("410001000000", 0x00))
        assertEquals(listOf(0x09), PidDecoder.soportados("410000800000", 0x00))
        assertEquals(listOf(0x20), PidDecoder.soportados("410000000001", 0x00))
    }

    // --- Fronteras ----------------------------------------------------------

    @Test
    fun `mascara a cero no soporta nada y no hay bloque siguiente`() {
        assertEquals(emptyList<Int>(), PidDecoder.soportados("410000000000", 0x00))
        assertFalse(PidDecoder.hayMasBloques("410000000000", 0x00))
    }

    @Test
    fun `mascara a FFFFFFFF soporta los treinta y dos y anuncia el siguiente`() {
        // (0x01..0x20) enteros. Vale como frontera y ademas confirma que el
        // ultimo elemento es exactamente 0x20 y no 0x21: un off-by-one aqui
        // desplazaria la lista entera un PID.
        assertEquals((0x01..0x20).toList(), PidDecoder.soportados("4100FFFFFFFF", 0x00))
        assertTrue(PidDecoder.hayMasBloques("4100FFFFFFFF", 0x00))
    }

    // --- El segundo bloque --------------------------------------------------

    @Test
    fun `el segundo bloque se pide con 0120 y responde con 4120`() {
        // Este carro no llega aqui, pero el recorrido de DashService si lo
        // intenta hasta cuatro veces y la numeracion tiene que desplazarse.
        assertEquals(listOf(0x21), PidDecoder.soportados("412080000000", 0x20))
        assertEquals(listOf(0x40), PidDecoder.soportados("412000000001", 0x20))
        assertTrue(PidDecoder.hayMasBloques("412000000001", 0x20))
        assertFalse(PidDecoder.hayMasBloques("412080000000", 0x20))
    }

    @Test
    fun `no confunde la respuesta de un bloque con la de otro`() {
        // El peor escenario del recorrido: la respuesta del bloque anterior
        // sigue en el buffer cuando ya se pregunto por el siguiente. Si colara,
        // el tablero creeria soportados PIDs del 0x21 al 0x40 que no existen.
        assertEquals(emptyList<Int>(), PidDecoder.soportados(RESPUESTA_REAL, 0x20))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4120BE3EF810", 0x00))
        assertFalse(PidDecoder.hayMasBloques(RESPUESTA_REAL, 0x20))
    }

    // --- Tolerancia de formato ----------------------------------------------

    @Test
    fun `tolera espacios, minusculas, eco, banner y prompt`() {
        // El `0100` es la PRIMERA peticion que se le hace a la ECU en cada
        // conexion, asi que es justo la que siempre trae delante el `BUS INIT`
        // de ISO 9141-2 y el eco del comando si `ATE0` aun no tomo efecto.
        // Rechazar cualquiera de estas formas dejaria al tablero sin mapa.
        val variantes = listOf(
            "41 00 BE 3E F8 10",
            "4100be3ef810",
            "0100\r4100BE3EF810",
            "SEARCHING...\r4100BE3EF810\r\r>",
            "BUS INIT: ...OK\r41 00 BE 3E F8 10\r\r>",
            "\r\r4100BE3EF810\r\r>",
            "0100\rSEARCHING...\r41 00 BE 3E F8 10\r\r>",
        )
        for (v in variantes) {
            assertEquals("fallo con '$v'", PIDS_DEL_CARRO, PidDecoder.soportados(v, 0x00))
        }
    }

    @Test
    fun `una trama de mas detras no cambia la lectura`() {
        // Se queda con la primera trama valida. Importa fijarlo: si un dia se
        // quedara con la ultima, un eco tardio o un resto del buffer podria
        // sustituir el mapa bueno por otro sin que nadie lo notara.
        assertEquals(
            PIDS_DEL_CARRO,
            PidDecoder.soportados("4100BE3EF810\r4100FFFFFFFF\r\r>", 0x00),
        )
    }

    @Test
    fun `los bytes de relleno del final se ignoran`() {
        // Un ELM327 en modo CAN rellena la trama hasta ocho bytes. Solo los
        // cuatro primeros son la mascara.
        assertEquals(PIDS_DEL_CARRO, PidDecoder.soportados("4100BE3EF8100000", 0x00))
        // Y un nibble suelto al final (respuesta cortada a mitad de byte) no
        // puede tirar los cuatro bytes que si llegaron enteros.
        assertEquals(PIDS_DEL_CARRO, PidDecoder.soportados("4100BE3EF8100", 0x00))
    }

    // --- Basura -------------------------------------------------------------

    @Test
    fun `la basura nunca lanza y nunca inventa PIDs`() {
        val basura = listOf<String?>(
            null, "", "   ", "\r\n>",
            "SEARCHING...", "BUS INIT: ...OK", "NO DATA", "NODATA", "STOPPED",
            "?", "UNABLE TO CONNECT", "CAN ERROR", "DATA ERROR", "ERROR",
            "ZZZZ",
            "7F0012",                            // respuesta negativa de la ECU
            "410C1AF8",                          // la respuesta de OTRO pid
            "410D64",
            "BUS INIT: ERROR\r\r>",
            "SEARCHING...\rUNABLE TO CONNECT\r\r>",
        )
        for (s in basura) {
            assertEquals("debio ser vacio para '$s'", emptyList<Int>(), PidDecoder.soportados(s, 0x00))
            assertFalse("debio ser false para '$s'", PidDecoder.hayMasBloques(s, 0x00))
        }
    }

    @Test
    fun `una mascara corta se descarta entera, no a medias`() {
        // Media mascara es peor que ninguna: daria una lista corta con pinta
        // de buena y el tablero dejaria de pedir sensores que el carro si
        // tiene, sin ningun sintoma visible.
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE3E", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE3EF8", 0x00))
        // Tres bytes y medio: el medio se cae y quedan tres, que no bastan.
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE3EF81", 0x00))
    }

    @Test
    fun `hayMasBloques con basura dice que no, y eso es lo peligroso`() {
        // Se fija a proposito el comportamiento actual: sin respuesta, la
        // funcion contesta `false`, o sea "no hay mas bloques". Pero lo cierto
        // es "no lo se". Es la misma trampa que ya mordio con los DTC — la
        // ausencia de respuesta no es una respuesta — y aqui el sintoma seria
        // un mapa de PIDs truncado en silencio, no un carro declarado sano.
        assertFalse(PidDecoder.hayMasBloques(null, 0x00))
        assertFalse(PidDecoder.hayMasBloques("SEARCHING...", 0x00))
        assertFalse(PidDecoder.hayMasBloques("NO DATA", 0x00))
        // Si algun dia esto se separa en "no hay mas" y "no se pudo leer",
        // esta prueba es la que hay que cambiar, y a conciencia.
    }
}
