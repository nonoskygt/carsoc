package com.nonosky.s2000dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los valores derivados de §7. Ninguno consume lecturas del K-line. */
class DerivedValuesTest {

    @Test
    fun `el VTEC necesita revoluciones Y carga`() {
        // A bajo pedal el VTEC no engancha aunque las rpm esten arriba: es
        // justo el caso que se ve al desacelerar en marcha corta.
        assertFalse(EngineConstants.vtecActive(rpm = 5000, loadPct = 15))
        assertTrue(EngineConstants.vtecActive(rpm = 5000, loadPct = 80))
    }

    @Test
    fun `el VTEC no engancha debajo del umbral por mucha carga que haya`() {
        // ⚠️ ESTE MOTOR NO ES EL F20C. El AP1 enganchaba a 5850 y era un
        // acontecimiento; el K24A4 engancha a ~2200 con carga y suelta a
        // ~2100, o sea que entra y sale en cada cuesta. La prueba vieja
        // afirmaba `5849 al 100% -> no engancha`, que aqui es FALSO.
        assertFalse(EngineConstants.vtecActive(rpm = 2199, loadPct = 100))
        assertTrue(EngineConstants.vtecActive(rpm = 2200, loadPct = 70))
    }

    @Test
    fun `la carga manda por debajo del umbral de carga`() {
        // 69 % no basta ni a 6000 rpm: sin pedal no hay VTEC.
        assertFalse(EngineConstants.vtecActive(rpm = 6000, loadPct = 69))
        assertTrue(EngineConstants.vtecActive(rpm = 6000, loadPct = 70))
    }

    @Test
    fun `una vez enganchado aguanta hasta el umbral de suelta`() {
        // La histeresis existe porque sin ella la lampara PARPADEA sin parar
        // cuando las revoluciones rondan el umbral, que en este motor es la
        // situacion NORMAL de ciudad. Los logs reales dan enganche a 2200 y
        // desenganche a 2108: unas 100 rpm de margen.
        //
        // Entre 2100 y 2200, el resultado depende de si YA estaba enganchado.
        assertFalse(
            "a 2150 sin venir de enganchado, no engancha",
            EngineConstants.vtecActive(rpm = 2150, loadPct = 90, enganchadoAntes = false),
        )
        assertTrue(
            "a 2150 viniendo de enganchado, sigue",
            EngineConstants.vtecActive(rpm = 2150, loadPct = 90, enganchadoAntes = true),
        )
        // Por debajo del umbral de suelta se cae aunque viniera enganchado.
        assertFalse(
            "a 2099 se suelta aunque viniera enganchado",
            EngineConstants.vtecActive(rpm = 2099, loadPct = 90, enganchadoAntes = true),
        )
    }

    @Test
    fun `soltar el pedal suelta el VTEC aunque las rpm sigan arriba`() {
        // La histeresis es de REVOLUCIONES, no de carga: al levantar el pie
        // en retencion, el VTEC sale aunque el motor siga girando alto. Sin
        // esto la lampara se quedaria encendida bajando un puerto.
        assertFalse(EngineConstants.vtecActive(rpm = 6000, loadPct = 20, enganchadoAntes = true))
    }

    @Test
    fun `sin dato de carga se asume que no engancha`() {
        // Preferimos no prender la banda de mas: una banda encendida cuando
        // no lo esta enseña a desconfiar del tablero.
        assertFalse(EngineConstants.vtecActive(rpm = 8000, loadPct = null))
        assertFalse(EngineConstants.vtecActive(rpm = null, loadPct = 90))
    }

    @Test
    fun `el estado expone el VTEC ya derivado`() {
        assertTrue(VehicleState(rpm = 6500, loadPct = 75).vtecActive)
        assertFalse(VehicleState(rpm = 6500, loadPct = 20).vtecActive)
    }

    @Test
    fun `un valor viejo se marca como rancio`() {
        val s = VehicleState()
        val ahora = 100_000L
        assertFalse(s.isStale(atMs = ahora - 1_000, nowMs = ahora))
        assertTrue(s.isStale(atMs = ahora - 5_000, nowMs = ahora))
        // Timestamp cero = nunca hubo muestra.
        assertTrue(s.isStale(atMs = 0, nowMs = ahora))
    }

    @Test
    fun `los umbrales del K24A4 mantienen su orden`() {
        // Si alguien ajusta una constante, este test evita dejar la carátula
        // en un estado imposible (zona roja antes del ambar, etc).
        assertTrue(EngineConstants.RPM_VTEC < EngineConstants.RPM_SHIFT_AMBER)
        assertTrue(EngineConstants.RPM_SHIFT_AMBER < EngineConstants.RPM_REDLINE)
        assertTrue(EngineConstants.RPM_REDLINE <= EngineConstants.RPM_FUEL_CUT)
        assertTrue(EngineConstants.RPM_FUEL_CUT <= EngineConstants.RPM_MAX)
        // Y el umbral de suelta del VTEC va por DEBAJO del de enganche, o la
        // histeresis no seria histeresis sino un parpadeo garantizado.
        assertTrue(EngineConstants.RPM_VTEC_SUELTA < EngineConstants.RPM_VTEC)
    }
}
