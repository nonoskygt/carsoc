package com.nonosky.s2000dash.obd

import android.util.Log

/** Lo que reporto `ATDP` tras inicializar, y si hubo que caer a automatico. */
data class ProtocolInfo(
    val describedAs: String,
    val usedFallback: Boolean,
)

/**
 * El dialogo AT con el adaptador ELM327.
 *
 * Depende solo de un [ObdTransport]. No sabe de Bluetooth ni de corrutinas.
 */
class Elm327Session(private val transport: ObdTransport) {

    /**
     * Lleva el adaptador a un estado conocido y fija el protocolo.
     *
     * `ATSP3` es una apuesta a que el AP1 usa ISO 9141-2 (riesgo R5). Si la
     * apuesta falla, `ATDP` lo delata y caemos a `ATSP0` automatico, dejando
     * registrado el protocolo real que se negocio.
     */
    fun initialize(): ProtocolInfo {
        // ATZ resetea el adaptador entero; tarda bastante mas que el resto.
        send("ATZ", RESET_TIMEOUT_MS)
        send("ATE0")   // eco apagado: no reenviar lo que mandamos
        send("ATL0")   // sin saltos de linea extra
        send("ATS0")   // sin espacios: menos bytes sobre un enlace lento
        send("ATH0")   // sin encabezados: no se necesitan
        send("ATSP3")  // ISO 9141-2 explicito, evita el barrido de autodeteccion
        send("ATAT1")  // temporizacion adaptativa

        var described = send("ATDP", RESET_TIMEOUT_MS).cleaned()
        var fallback = false

        // Una carátula util solo aparece si de verdad hay bus. Probamos con
        // el PID que mas nos importa: si no contesta, el protocolo fijado no
        // sirve y vale mas dejar que el adaptador lo busque solo.
        if (!probeBus()) {
            Log.w(TAG, "ISO 9141-2 no respondio; cayendo a ATSP0 automatico")
            send("ATSP0")
            fallback = true
            probeBus()
            described = send("ATDP", RESET_TIMEOUT_MS).cleaned()
        }

        Log.i(TAG, "Protocolo activo: '$described' (fallback=$fallback)")
        return ProtocolInfo(described, fallback)
    }

    /** Una peticion de PID sale bien si de ella se saca carga util. */
    private fun probeBus(): Boolean =
        PidDecoder.payloadOf(queryRaw(PidDecoder.PID_RPM, BUS_INIT_TIMEOUT_MS), PidDecoder.PID_RPM) != null

    /** Respuesta cruda a [pid], tal cual la escupio el adaptador. */
    fun queryRaw(pid: String, timeoutMs: Long = QUERY_TIMEOUT_MS): String? =
        runCatching { send(pid, timeoutMs) }
            .onFailure { Log.w(TAG, "Fallo consultando $pid: ${it.message}") }
            .getOrNull()

    /** Carga util de [pid], ya sin encabezado ni basura, o `null`. */
    fun query(pid: String, timeoutMs: Long = QUERY_TIMEOUT_MS): ByteArray? =
        PidDecoder.payloadOf(queryRaw(pid, timeoutMs), pid)

    /** Voltaje de bateria. Lo da el adaptador, no la ECU: no gasta K-line. */
    fun readVoltage(): Float? =
        PidDecoder.decodeVoltage(runCatching { send("ATRV") }.getOrNull())

    private fun send(command: String, timeoutMs: Long = QUERY_TIMEOUT_MS): String {
        // Tirar la cola de la respuesta anterior antes de preguntar de nuevo.
        // Sin esto, un solo timeout desincroniza comando y respuesta para
        // siempre: cada PID se quedaria leyendo lo que contesto el anterior.
        transport.drain()
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        return transport.readUntilPrompt(timeoutMs)
    }

    private fun String.cleaned(): String =
        replace("\r", " ").replace("\n", " ").trim()

    companion object {
        private const val TAG = "Elm327Session"

        /** `ATZ` y `ATDP` son lentos: el adaptador se reinicia entero. */
        const val RESET_TIMEOUT_MS = 5_000L

        /** La primera peticion dispara el BUS INIT del K-line, que tarda. */
        const val BUS_INIT_TIMEOUT_MS = 10_000L

        /**
         * Round-trip normal en ISO 9141-2 es de ~70-120 ms; 350 ms deja
         * margen sobrado sin colgar la rotacion cuando un PID no contesta.
         */
        const val QUERY_TIMEOUT_MS = 350L
    }
}
