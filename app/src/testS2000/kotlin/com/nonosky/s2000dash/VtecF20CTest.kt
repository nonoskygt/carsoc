package com.nonosky.s2000dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El VTEC del F20C, con SUS numeros.
 *
 * Gemela de `VtecK24A4Test` en el sabor `element`. Las dos afirman cosas
 * distintas y las dos son ciertas: es el mismo codigo con dos juegos de
 * constantes, y por eso cada afirmacion vive con su carro.
 *
 * Estos numeros SI estan confirmados: 5.850 rpm es el cruce que Honda
 * publica para el AP1.
 */
class VtecF20CTest {

    @Test
    fun `este motor engancha arriba, y por eso es un acontecimiento`() {
        assertEquals(5_850, EngineConstants.RPM_VTEC)
        assertFalse(EngineConstants.vtecActive(rpm = 5_849, loadPct = 100))
        assertTrue(EngineConstants.vtecActive(rpm = 5_850, loadPct = 60))
    }

    @Test
    fun `necesita revoluciones Y carga`() {
        // A bajo pedal no engancha aunque las rpm esten arriba: es el caso
        // que se ve al desacelerar en marcha corta.
        assertFalse(EngineConstants.vtecActive(rpm = 7_000, loadPct = 15))
        assertTrue(EngineConstants.vtecActive(rpm = 7_000, loadPct = 80))
    }

    @Test
    fun `a cinco mil no engancha ni a fondo`() {
        // La prueba espejo de la del K24A4, que a 5.000 con 80 % SI
        // engancha. Es la diferencia entre los dos carros, escrita.
        assertFalse(EngineConstants.vtecActive(rpm = 5_000, loadPct = 80))
    }

    @Test
    fun `la carga corta por debajo del sesenta por ciento`() {
        assertFalse(EngineConstants.vtecActive(rpm = 7_000, loadPct = 59))
        assertTrue(EngineConstants.vtecActive(rpm = 7_000, loadPct = 60))
    }

    @Test
    fun `el perfil dice que este carro NO puede dar AFR real`() {
        // Sonda de banda ESTRECHA y mapa de PIDs cortado en 0x20: no hay
        // 0134. El reloj de mezcla se dibuja apagado, que es distinto de
        // no dibujarlo — y muy distinto de inventarle un numero.
        assertFalse(PerfilVehiculo.TIENE_AFR_REAL)
        assertTrue(PerfilVehiculo.VTEC_ES_ACONTECIMIENTO)
        assertFalse("no es casa rodante", PerfilVehiculo.ES_CASA_RODANTE)
        assertFalse("un solo banco", PerfilVehiculo.TIENE_BANCO_VIVIENDA)
        assertFalse("sin nevera", PerfilVehiculo.TIENE_NEVERA)
    }
}
