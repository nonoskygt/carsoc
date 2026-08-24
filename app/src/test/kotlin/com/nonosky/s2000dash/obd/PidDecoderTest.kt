package com.nonosky.s2000dash.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El grueso de las pruebas del proyecto.
 *
 * Un clon de ELM327 escupe basura constantemente; la regla dura del diseño
 * (§10) es que nada de eso puede producir una excepcion — solo `null`.
 */
class PidDecoderTest {

    // --- Valores reales -----------------------------------------------------

    @Test
    fun `rpm se decodifica con la formula A por 256 mas B entre 4`() {
        // 0x1AF8 = 6904 -> 1726 rpm
        assertEquals(1726, PidDecoder.decodeRpm("410C1AF8"))
    }

    @Test
    fun `rpm en ralenti`() {
        // 0x0FA0 = 4000 -> 1000 rpm
        assertEquals(1000, PidDecoder.decodeRpm("410C0FA0"))
    }

    @Test
    fun `rpm en cero con el motor apagado`() {
        assertEquals(0, PidDecoder.decodeRpm("410C0000"))
    }

    @Test
    fun `rpm cerca del corte del F20C`() {
        // 8800 rpm -> 35200 = 0x8980
        assertEquals(8800, PidDecoder.decodeRpm("410C8980"))
    }

    @Test
    fun `velocidad es A directo en km por hora`() {
        assertEquals(0, PidDecoder.decodeSpeed("410D00"))
        assertEquals(100, PidDecoder.decodeSpeed("410D64"))
        assertEquals(255, PidDecoder.decodeSpeed("410DFF"))
    }

    @Test
    fun `refrigerante resta 40 grados`() {
        assertEquals(0, PidDecoder.decodeCoolant("410528"))    // 0x28 = 40
        assertEquals(90, PidDecoder.decodeCoolant("410582"))   // 0x82 = 130
    }

    @Test
    fun `refrigerante puede ser negativo en frio`() {
        assertEquals(-30, PidDecoder.decodeCoolant("41050A"))  // 0x0A = 10
    }

    @Test
    fun `aire de admision resta 40 grados`() {
        assertEquals(25, PidDecoder.decodeIat("410F41"))       // 0x41 = 65
    }

    @Test
    fun `carga se escala de 255 a 100 por ciento`() {
        assertEquals(0, PidDecoder.decodeLoad("410400"))
        assertEquals(100, PidDecoder.decodeLoad("4104FF"))
        assertEquals(50, PidDecoder.decodeLoad("410480"))      // 0x80 = 128
    }

    // --- Tolerancia de formato ---------------------------------------------

    @Test
    fun `tolera espacios si ATS0 no tomo efecto`() {
        assertEquals(1726, PidDecoder.decodeRpm("41 0C 1A F8"))
    }

    @Test
    fun `tolera el eco del comando si ATE0 no tomo efecto`() {
        assertEquals(1726, PidDecoder.decodeRpm("010C\r410C1AF8"))
    }

    @Test
    fun `tolera el prompt y los saltos de linea`() {
        assertEquals(1726, PidDecoder.decodeRpm("\r\r410C1AF8\r\r>"))
    }

    @Test
    fun `tolera minusculas`() {
        assertEquals(1726, PidDecoder.decodeRpm("410c1af8"))
    }

    @Test
    fun `ignora bytes de sobra al final`() {
        // Algunos adaptadores rellenan la trama; solo importan A y B.
        assertEquals(1726, PidDecoder.decodeRpm("410C1AF80000"))
    }

    // --- Toda la basura de la seccion 10 ------------------------------------

    @Test
    fun `la basura del adaptador nunca lanza y siempre da null`() {
        val basura = listOf(
            "SEARCHING...", "BUS INIT", "BUSINIT", "BUS ERROR", "BUS BUSY",
            "STOPPED", "NO DATA", "NODATA", "?", "UNABLE TO CONNECT",
            "CAN ERROR", "DATA ERROR", "ERROR", "", "   ", "\r\n>",
            "410C",          // encabezado sin carga util
            "410C1",         // truncado a medio byte
            "7F0C12",        // respuesta negativa de la ECU
            "41",            // truncado antes del PID
            "ZZZZ",          // ruido no hexadecimal
            "410D64",        // respuesta de OTRO pid
        )
        for (s in basura) {
            assertNull("debio ser null para '$s'", PidDecoder.decodeRpm(s))
        }
    }

    @Test
    fun `null y vacio no truenan`() {
        assertNull(PidDecoder.decodeRpm(null))
        assertNull(PidDecoder.decodeSpeed(null))
        assertNull(PidDecoder.decodeCoolant(""))
        assertNull(PidDecoder.decodeIat("   "))
        assertNull(PidDecoder.decodeLoad(null))
    }

    @Test
    fun `no confunde la respuesta de un pid con la de otro`() {
        assertNull(PidDecoder.decodeSpeed("410C1AF8"))
        assertNull(PidDecoder.decodeCoolant("410F41"))
        assertNull(PidDecoder.decodeIat("410528"))
    }

    @Test
    fun `SEARCHING pegado a una respuesta valida se descarta`() {
        // Preferimos perder una muestra a mostrar un numero que quiza no sea.
        assertNull(PidDecoder.decodeRpm("SEARCHING...410C1AF8"))
    }

    // --- Voltaje ------------------------------------------------------------

    @Test
    fun `voltaje de ATRV`() {
        assertEquals(12.6f, PidDecoder.decodeVoltage("12.6V")!!, 0.001f)
        assertEquals(14.2f, PidDecoder.decodeVoltage("14.2V\r>")!!, 0.001f)
        assertEquals(12.0f, PidDecoder.decodeVoltage("12V")!!, 0.001f)
    }

    @Test
    fun `voltaje fuera de rango o ilegible da null`() {
        assertNull(PidDecoder.decodeVoltage("0.0V"))
        assertNull(PidDecoder.decodeVoltage("99.9V"))
        assertNull(PidDecoder.decodeVoltage("ERROR"))
        assertNull(PidDecoder.decodeVoltage(""))
        assertNull(PidDecoder.decodeVoltage(null))
    }
}
