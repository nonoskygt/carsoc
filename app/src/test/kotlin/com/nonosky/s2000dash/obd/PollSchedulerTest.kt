package com.nonosky.s2000dash.obd

import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que el reparto del presupuesto de K-line sea el de §5.
 *
 * El presupuesto es real: ~9-14 lecturas por segundo entre TODO. Si un PID
 * se lleva mas de lo suyo, se lo quita a la aguja.
 */
class PollSchedulerTest {

    private val plan = PollScheduler.Plan

    private fun countsOver(cycles: Int): Map<String?, Int> =
        (0 until cycles).map { plan.secondaryFor(it) }.groupingBy { it }.eachCount()

    @Test
    fun `las proporciones sobre un periodo completo son las de la seccion 5`() {
        val c = countsOver(PollScheduler.Plan.PERIOD)

        // Velocidad cada 3 ciclos -> 20 de 60
        assertEquals(20, c[PidDecoder.PID_SPEED])
        // Carga cada 10 -> 6 de 60
        assertEquals(6, c[PidDecoder.PID_LOAD])
        // Refrigerante cada 20 -> 3 de 60
        assertEquals(3, c[PidDecoder.PID_COOLANT])
        // Aire de admision cada 20 -> 3 de 60
        assertEquals(3, c[PidDecoder.PID_IAT])
        // Voltaje cada 20 -> 3 de 60
        assertEquals(3, c[PollScheduler.PID_VOLTAGE])
    }

    @Test
    fun `ningun ciclo pide dos datos secundarios`() {
        // La tabla devuelve un solo PID por ciclo por construccion; esto
        // atrapa que un slot pise a otro al editar las listas.
        val c = countsOver(PollScheduler.Plan.PERIOD)
        val asignados = c.filterKeys { it != null }.values.sum()
        assertEquals(20 + 6 + 3 + 3 + 3, asignados)
    }

    @Test
    fun `el agua y el aire nunca caen en el mismo ciclo`() {
        // Comparten frecuencia; si coincidieran, uno se quedaria sin turno.
        for (i in 0 until PollScheduler.Plan.PERIOD) {
            val pid = plan.secondaryFor(i)
            if (pid == PidDecoder.PID_COOLANT) {
                assertTrue(plan.secondaryFor(i) != PidDecoder.PID_IAT)
            }
        }
        val agua = (0 until 60).filter { plan.secondaryFor(it) == PidDecoder.PID_COOLANT }
        val aire = (0 until 60).filter { plan.secondaryFor(it) == PidDecoder.PID_IAT }
        assertTrue("agua y aire se traslapan", agua.intersect(aire.toSet()).isEmpty())
    }

    @Test
    fun `el patron se repite y aguanta ciclos negativos o desbordados`() {
        assertEquals(plan.secondaryFor(0), plan.secondaryFor(60))
        assertEquals(plan.secondaryFor(14), plan.secondaryFor(74))
        // Si el contador desborda a negativo, la tabla no debe reventar.
        assertEquals(plan.secondaryFor(0), plan.secondaryFor(-60))
        assertEquals(plan.secondaryFor(59), plan.secondaryFor(-1))
    }

    @Test
    fun `la aguja se queda con su parte del presupuesto`() {
        // 60 ciclos de RPM + 35 secundarios = 95 lecturas por periodo. A
        // ~100 ms de round-trip el periodo dura ~9.5 s, asi que el RPM sale
        // a ~6.3 Hz: el ~6 Hz que §5 le reserva a la aguja.
        val secundarios = countsOver(PollScheduler.Plan.PERIOD)
            .filterKeys { it != null }.values.sum()
        val lecturasPorPeriodo = PollScheduler.Plan.PERIOD + secundarios
        val segundosPorPeriodo = lecturasPorPeriodo * 0.1
        val rpmHz = PollScheduler.Plan.PERIOD / segundosPorPeriodo

        assertTrue("la aguja baja de 6 Hz: $rpmHz", rpmHz >= 6.0)
        val totalHz = lecturasPorPeriodo / segundosPorPeriodo
        assertTrue("se pasa del techo de §4: $totalHz", totalHz <= 14.0)
    }

    @Test
    fun `el backoff sube y se topa en 10 segundos`() {
        // Scope propio y no GlobalScope: el backoff es una funcion pura, no
        // queremos que el test deje corrutinas sueltas.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val s = PollScheduler({ FakeTransport(emptyMap()) }, scope)
        assertEquals(1_000L, s.backoffMs(0))
        assertEquals(2_000L, s.backoffMs(1))
        assertEquals(4_000L, s.backoffMs(2))
        assertEquals(8_000L, s.backoffMs(3))
        assertEquals(10_000L, s.backoffMs(4))
        assertEquals(10_000L, s.backoffMs(99))
        scope.cancel()
    }
}
