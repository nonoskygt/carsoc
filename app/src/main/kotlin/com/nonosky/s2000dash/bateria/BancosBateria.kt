package com.nonosky.s2000dash.bateria

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * Los DOS bancos de litio del Element, cada uno fijado por su MAC.
 *
 * ⚠️ POR QUE ESTO EXISTE, y no se reutiliza [VigilanteBateria].
 *
 * El vigilante barre el aire y se queda con **el primer BMS JBD que ve**.
 * Con un solo banco —el caso del S2000— eso funciona. Con DOS bancos del
 * mismo fabricante, cual le toca es cuestion de suerte: el dueño vio la
 * tarjeta rotulada "vivienda" mostrando la bateria de arranque. No estaban
 * cruzadas por un error de cableado; estaban **sin identificar**, y el
 * rotulo de la pantalla era una suposicion.
 *
 * La documentacion del ecosistema JBD es tajante en esto: dos BMS de fabrica
 * anuncian el mismo nombre y **hay que distinguirlos por direccion**. Asi que
 * aqui cada banco se fija por MAC y nunca se adivina.
 *
 * Se lee **de uno en uno**, no en paralelo. Sostener dos GATT a la vez es
 * posible, pero la especificacion solo admite un `LE Create Connection`
 * pendiente y encadenarlos mal es la causa mas citada de "solo me conecta a
 * N". Turnarse cuesta unos segundos de latencia y ahorra esa clase entera de
 * fallo.
 */
class BancosBateria(private val context: Context) {

    /** Un banco: quien es, y lo ultimo que dijo. */
    data class Banco(
        val clave: String,
        val mac: String,
        val rotulo: String,
        @Volatile var soc: Int? = null,
        @Volatile var voltaje: Float? = null,
        @Volatile var corrienteA: Float? = null,
        @Volatile var temperaturaC: Int? = null,
        @Volatile var celdas: Int = 0,
        @Volatile var leidoMs: Long = 0L,
        @Volatile var detalle: String? = null,
    ) {
        val potenciaW: Float?
            get() {
                val v = voltaje ?: return null
                val a = corrienteA ?: return null
                return v * a
            }

        /**
         * Un banco leido hace mas de un minuto ya no es una lectura: es un
         * recuerdo. Se pinta "--" y el punto de enlace se apaga.
         */
        fun vivo(ahoraMs: Long): Boolean =
            leidoMs > 0L && (ahoraMs - leidoMs) < SIN_VERSE_MS && voltaje != null
    }

    /**
     * Los dos bancos del Element, con la MAC que se midio en el carro.
     *
     * Los nombres los puso el dueño desde la app de JBD, lo que resuelve solo
     * el problema de saber cual es cual — pero el nombre viaja en el anuncio y
     * no siempre llega, asi que manda la MAC.
     */
    val vivienda = Banco("viv", MAC_VIVIENDA, "Elementos 300AH")
    val arranque = Banco("arr", MAC_ARRANQUE, "Element Motor")

    private val todos = listOf(vivienda, arranque)

    @Volatile private var vivo = false
    private var hilo: Thread? = null

    /** Avisa a la pantalla de que hay lectura nueva. */
    var alCambiar: (() -> Unit)? = null

    fun arrancar() {
        if (vivo) return
        vivo = true
        hilo = thread(name = "bancos-litio", isDaemon = true) {
            while (vivo) {
                // El guardian termico manda, igual que con las demas fuentes.
                // Este radio va mas holgado que el del S2000, pero aquel se
                // apago TRES veces por calor y la regla se hereda entera.
                if (!com.nonosky.s2000dash.Termometro.permiteBateria()) {
                    dormir(PERIODO_MS)
                    continue
                }
                for (b in todos) {
                    if (!vivo) break
                    // Un reintento. El banco de vivienda esta mas lejos
                    // (RSSI -72 contra -66) y falla la conexion una de cada
                    // dos: sin reintentar, caducaba antes de la siguiente
                    // lectura buena y la tarjeta parpadeaba a "--" teniendo
                    // la bateria a metro y medio.
                    if (!leer(b) && vivo) {
                        dormir(REINTENTO_MS)
                        leer(b)
                    }
                    // Respiro entre bancos: le da tiempo al controlador a
                    // cerrar el enlace anterior antes de abrir el siguiente.
                    dormir(PAUSA_ENTRE_BANCOS_MS)
                }
                dormir(PERIODO_MS)
            }
        }
    }

    fun detener() {
        vivo = false
        hilo?.interrupt()
        hilo = null
    }

    private fun dormir(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private fun adaptador(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    /** Devuelve true si la lectura llego a publicar datos. */
    private fun leer(b: Banco): Boolean {
        var ok = false
        runCatching {
            // Turno unico para toda la radio BLE: con tres lectores sueltos,
            // el de vivienda dejo de leer del todo mientras el de arranque
            // seguia contestando. Ver TurnoBle.
            val lectura = TurnoBle.conLaRadio("banco-${b.clave}") {
                LectorBmsAndroid.leer(
                    context = context,
                    adaptador = adaptador(),
                    mac = b.mac,
                    pedirCeldas = true,
                )
            } ?: run {
                b.detalle = "sin turno de radio"
                Log.w(TAG, "banco ${b.clave}: sin turno")
                return@runCatching
            }
            val basico = lectura.basico
            if (basico == null) {
                // NO se borra lo anterior: se deja envejecer y que `vivo()`
                // decida. Borrar al primer fallo hace parpadear la tarjeta
                // cada vez que una lectura se pierde, que en BLE es normal.
                b.detalle = lectura.problemas.firstOrNull() ?: "sin datos del BMS"
                Log.w(TAG, "banco ${b.clave} sin basico: ${b.detalle}")
                return@runCatching
            }
            b.soc = basico.soc
            b.voltaje = basico.voltajeV
            b.corrienteA = basico.corrienteA
            b.temperaturaC = basico.temperaturasC.firstOrNull()?.let { Math.round(it) }
            b.celdas = basico.numeroCeldas
            b.leidoMs = System.currentTimeMillis()
            b.detalle = null
            Log.i(TAG, "banco ${b.clave}: soc=${b.soc} v=${b.voltaje} a=${b.corrienteA}")
            ok = true
            runCatching { alCambiar?.invoke() }
        }.onFailure {
            b.detalle = "fallo leyendo: ${it.javaClass.simpleName}"
            Log.w(TAG, "banco ${b.clave} fallo: ${it.message}")
        }
        return ok
    }

    /** Para el puente HTTP: que esta viendo cada banco. */
    fun diagnostico(): List<String> {
        val ahora = System.currentTimeMillis()
        return todos.map { b ->
            val edad = if (b.leidoMs > 0) "${(ahora - b.leidoMs) / 1000}s" else "nunca"
            "${b.clave}  ${b.mac}  ${b.rotulo}  " +
                "soc=${b.soc ?: "--"}  v=${b.voltaje ?: "--"}  a=${b.corrienteA ?: "--"}  " +
                "celdas=${b.celdas}  leido=$edad  ${b.detalle ?: ""}"
        }
    }

    companion object {
        private const val TAG = "BancosBateria"

        /**
         * Medidas en el carro con un barrido BLE del propio radio, y
         * confirmadas contra la app de fabrica del BMS, que las muestra con
         * los nombres que les puso el dueño.
         */
        const val MAC_VIVIENDA = "A5:C2:37:09:18:EE"
        const val MAC_ARRANQUE = "A4:C1:38:3B:B9:5E"

        /** Cada cuanto se da una vuelta completa a los dos bancos. */
        const val PERIODO_MS = 8_000L
        const val PAUSA_ENTRE_BANCOS_MS = 1_200L
        const val REINTENTO_MS = 1_500L

        /** Igual que en BateriaState: pasado un minuto, deja de ser dato. */
        const val SIN_VERSE_MS = 60_000L
    }
}
