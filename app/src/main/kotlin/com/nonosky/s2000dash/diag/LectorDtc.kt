package com.nonosky.s2000dash.diag

import android.bluetooth.BluetoothAdapter
import com.nonosky.s2000dash.obd.Dtc
import com.nonosky.s2000dash.obd.Elm327Session
import com.nonosky.s2000dash.obd.PidDecoder
import com.nonosky.s2000dash.obd.SppTransport

/**
 * Lee y borra los codigos de averia hablando con la ECU.
 *
 * Abre su PROPIA conexion y la cierra al terminar, en vez de colgarse del
 * sondeo del tablero. Dos razones: el sondeo puede no estar encendido cuando
 * el dueño abre el diagnostico, y sobre todo el modo 03 necesita cambiar la
 * temporizacion del adaptador (ver [prepararParaDtc]) — tocarsela al sondeo
 * en marcha le estropearia el ritmo a la mitad de una lectura.
 */
class LectorDtc(
    private val adapter: BluetoothAdapter?,
    private val mac: String,
) {

    data class Resultado(
        val guardados: List<Dtc.Codigo> = emptyList(),
        val pendientes: List<Dtc.Codigo> = emptyList(),
        val permanentes: List<Dtc.Codigo> = emptyList(),
        /** Luz de averia encendida segun el PID 0101. */
        val luzEncendida: Boolean = false,
        /** Cuantos dice la ECU que hay. Sirve para cazar tramas perdidas. */
        val cuantosDiceLaEcu: Int = -1,
        val error: String? = null,
        val traza: List<String> = emptyList(),
    ) {
        val hayAlgo: Boolean get() = guardados.isNotEmpty() || pendientes.isNotEmpty() ||
            permanentes.isNotEmpty()
    }

    /**
     * Lo hace todo: conecta, pregunta y cierra. Nunca lanza.
     *
     * Corre en un hilo de fondo; quien llama se encarga de no bloquear la UI.
     */
    fun leer(): Resultado {
        val traza = mutableListOf<String>()
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return Resultado(error = "no se pudo resolver el adaptador OBD", traza = traza)

        val t = SppTransport(dev, adapter)
        return try {
            t.connect()
            traza += "enlace abierto"
            val s = Elm327Session(t)
            s.initialize()
            traza += "adaptador listo"

            prepararParaDtc(s, traza)

            // El 0101 PRIMERO: dice si la luz esta encendida y CUANTOS codigos
            // confirmados hay. Sin eso no se puede saber si el modo 03 devolvio
            // todos o se perdio una trama por el camino — y perder codigos en
            // silencio es el peor fallo posible en una pantalla de averias.
            val estado = s.queryRaw(PidDecoder.PID_ESTADO)
            traza += "0101 -> ${estado ?: "sin respuesta"}"
            val mil = PidDecoder.decodeMil(estado)

            val guardados = pedirLista(s, Dtc.Tipo.GUARDADOS, traza)
            val pendientes = pedirLista(s, Dtc.Tipo.PENDIENTES, traza)
            // El modo 0A se hizo obligatorio en 2010: en un carro del 2000 lo
            // normal es que no exista. Se pregunta igual —cuesta una lectura—
            // y su silencio no se trata como fallo.
            val permanentes = pedirLista(s, Dtc.Tipo.PERMANENTES, traza)

            val esperados = mil?.second ?: -1
            if (esperados >= 0 && esperados != guardados.size) {
                traza += "OJO: la ECU dice $esperados guardados y llegaron ${guardados.size}"
            }

            Resultado(
                guardados = guardados,
                pendientes = pendientes,
                permanentes = permanentes,
                luzEncendida = mil?.first ?: false,
                cuantosDiceLaEcu = esperados,
                traza = traza,
            )
        } catch (e: Exception) {
            Resultado(error = "${e.javaClass.simpleName}: ${e.message}", traza = traza)
        } finally {
            runCatching { t.close() }
        }
    }

    /**
     * Borra los codigos con el modo 04 y comprueba que de verdad se fueron.
     *
     * Comprobar importa: la ECU puede aceptar el 04 y no borrar nada si el
     * motor esta girando o si la averia sigue activa. Decir "borrado" sin
     * mirar seria mentirle al dueño sobre lo unico que vino a hacer.
     */
    fun borrar(): Resultado {
        val traza = mutableListOf<String>()
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return Resultado(error = "no se pudo resolver el adaptador OBD", traza = traza)

        val t = SppTransport(dev, adapter)
        return try {
            t.connect()
            val s = Elm327Session(t)
            s.initialize()
            prepararParaDtc(s, traza)

            val r = s.queryRaw("04")
            traza += "04 -> ${r ?: "sin respuesta"}"
            // La respuesta correcta al 04 es "44". Cualquier otra cosa se
            // reporta tal cual en vez de suponer que salio bien.
            val acepto = r?.uppercase()?.replace(" ", "")?.contains("44") == true
            traza += if (acepto) "la ECU acepto el borrado" else "la ECU NO confirmo el borrado"

            // Esperar un momento: la ECU necesita tiempo para reescribir su
            // memoria, y preguntar de inmediato devuelve lo de antes.
            runCatching { Thread.sleep(1_500) }

            val quedan = pedirLista(s, Dtc.Tipo.GUARDADOS, traza)
            val estado = s.queryRaw(PidDecoder.PID_ESTADO)
            val mil = PidDecoder.decodeMil(estado)
            traza += "tras borrar quedan ${quedan.size} guardados; luz=${mil?.first}"

            Resultado(
                guardados = quedan,
                luzEncendida = mil?.first ?: false,
                cuantosDiceLaEcu = mil?.second ?: -1,
                error = if (!acepto && quedan.isNotEmpty()) "la ECU no borro" else null,
                traza = traza,
            )
        } catch (e: Exception) {
            Resultado(error = "${e.javaClass.simpleName}: ${e.message}", traza = traza)
        } finally {
            runCatching { t.close() }
        }
    }

    private fun pedirLista(
        s: Elm327Session,
        tipo: Dtc.Tipo,
        traza: MutableList<String>,
    ): List<Dtc.Codigo> {
        val crudo = runCatching { s.queryRaw(tipo.modo) }.getOrNull()
        traza += "${tipo.modo} (${tipo.etiqueta}) -> ${crudo ?: "sin respuesta"}"
        if (crudo == null) return emptyList()
        // Un "NO DATA" aqui NO es un fallo: es la ECU diciendo que no tiene
        // nada de ese tipo. Solo los errores de bus de verdad se reportan.
        if (Dtc.esFalloDeEnlace(crudo)) {
            traza += "  (fallo de enlace en ${tipo.modo}, no es que no haya codigos)"
            return emptyList()
        }
        return Dtc.leerLista(crudo, tipo)
    }

    /**
     * Apaga la temporizacion adaptativa antes de pedir codigos.
     *
     * Esta es la trampa que cuesta codigos SIN AVISAR. Con `ATAT1` —que es
     * como viene el adaptador— el ELM327 aprende cuanto tarda la ECU en
     * contestar y corta la escucha en cuanto cree que termino. Para el modo
     * 01, donde cada respuesta es una sola trama, va perfecto. Para el modo
     * 03 con mas de tres codigos, la ECU manda VARIAS tramas seguidas y el
     * adaptador corta despues de la primera: se leen tres averias, existen
     * seis, y nada en la respuesta dice que faltan.
     *
     * `ATAT0` fija la temporizacion y `ATST FF` le da el plazo maximo para
     * esperar. Se paga con lecturas mas lentas, que aqui no importan: esto
     * se abre a mano y una vez.
     */
    private fun prepararParaDtc(s: Elm327Session, traza: MutableList<String>) {
        runCatching {
            s.queryRaw("ATAT0")
            s.queryRaw("ATST FF")
            traza += "temporizacion fija (ATAT0 + ATST FF) para no perder tramas"
        }.onFailure { traza += "no se pudo fijar la temporizacion: ${it.message}" }
    }
}
