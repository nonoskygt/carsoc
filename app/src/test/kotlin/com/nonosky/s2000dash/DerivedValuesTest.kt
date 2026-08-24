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
        assertFalse(EngineConstants.vtecActive(rpm = 7000, loadPct = 15))
        assertTrue(EngineConstants.vtecActive(rpm = 7000, loadPct = 80))
    }

    @Test
    fun `el VTEC no engancha debajo del umbral por mucha carga que haya`() {
        assertFalse(EngineConstants.vtecActive(rpm = 5849, loadPct = 100))
        assertTrue(EngineConstants.vtecActive(rpm = 5850, loadPct = 60))
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
    fun `los umbrales del F20C mantienen su orden`() {
        // Si alguien ajusta una constante, este test evita dejar la carátula
        // en un estado imposible (zona roja antes del ambar, etc).
        assertTrue(EngineConstants.RPM_VTEC < EngineConstants.RPM_SHIFT_AMBER)
        assertTrue(EngineConstants.RPM_SHIFT_AMBER < EngineConstants.RPM_REDLINE)
        assertTrue(EngineConstants.RPM_REDLINE <= EngineConstants.RPM_FUEL_CUT)
        assertTrue(EngineConstants.RPM_FUEL_CUT <= EngineConstants.RPM_MAX)
    }
}
