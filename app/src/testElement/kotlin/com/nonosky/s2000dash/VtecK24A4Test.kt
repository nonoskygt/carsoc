package com.nonosky.s2000dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El VTEC del K24A4, con SUS numeros.
 *
 * Vive en el sabor `element` porque afirma cifras de ESTE motor. Su gemela
 * en `src/testS2000/` afirma las del F20C, y las dos son correctas: el
 * mismo codigo con dos juegos de constantes.
 *
 * ⚠️ Estos umbrales estan SIN CALIBRAR contra el carro. Salen de datalogs de
 * un K24A4 de Accord —mismo motor, otro vehiculo— leyendo el solenoide con
 * scan tool: enganche a 2.200-2.345 rpm con ~91 % de carga, desenganche a
 * 2.108 con 71 %. Cuando se pueda registrar rpm y carga en el Element, estos
 * numeros se ajustan y estas pruebas se mueven con ellos.
 */
class VtecK24A4Test {

    @Test
    fun `este motor engancha MUY abajo, no como el F20C`() {
        // La diferencia que define al carro: 2.200 contra 5.850. Aqui el
        // VTEC entra y sale en cada cuesta, y por eso el aviso es una
        // lampara y no un fogonazo a pantalla completa.
        assertEquals(2_200, EngineConstants.RPM_VTEC)
        assertFalse(EngineConstants.vtecActive(rpm = 2_199, loadPct = 100))
        assertTrue(EngineConstants.vtecActive(rpm = 2_200, loadPct = 70))
    }

    @Test
    fun `necesita revoluciones Y carga`() {
        assertFalse(EngineConstants.vtecActive(rpm = 5_000, loadPct = 15))
        assertTrue(EngineConstants.vtecActive(rpm = 5_000, loadPct = 80))
    }

    @Test
    fun `la carga corta por debajo del setenta por ciento`() {
        // 69 % no basta ni a 6.000 rpm. La guarda existe para no cantar
        // VTEC en retencion, que es cuando las vueltas suben sin pedal.
        assertFalse(EngineConstants.vtecActive(rpm = 6_000, loadPct = 69))
        assertTrue(EngineConstants.vtecActive(rpm = 6_000, loadPct = 70))
    }

    @Test
    fun `una vez enganchado aguanta hasta el umbral de suelta`() {
        // Entre 2.100 y 2.200 el resultado DEPENDE de si ya venia
        // enganchado. Sin esta histeresis la lampara parpadearia sin parar
        // en ciudad, que en este motor es la situacion normal.
        assertFalse(
            "a 2150 sin venir de enganchado, no engancha",
            EngineConstants.vtecActive(rpm = 2_150, loadPct = 90, enganchadoAntes = false),
        )
        assertTrue(
            "a 2150 viniendo de enganchado, sigue",
            EngineConstants.vtecActive(rpm = 2_150, loadPct = 90, enganchadoAntes = true),
        )
        assertFalse(
            "a 2099 se suelta aunque viniera enganchado",
            EngineConstants.vtecActive(rpm = 2_099, loadPct = 90, enganchadoAntes = true),
        )
    }

    @Test
    fun `el perfil dice que este carro SI puede tener AFR real`() {
        // Lleva sonda LAF de banda ancha de fabrica, asi que el reloj de
        // mezcla tiene sentido — aunque siga sin confirmarse que la ECU
        // exponga el 0134.
        assertTrue(PerfilVehiculo.TIENE_AFR_REAL)
        assertFalse(PerfilVehiculo.VTEC_ES_ACONTECIMIENTO)
        assertTrue("es casa rodante: manda el litio", PerfilVehiculo.ES_CASA_RODANTE)
        assertTrue("lleva dos bancos", PerfilVehiculo.TIENE_BANCO_VIVIENDA)
    }
}
