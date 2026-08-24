package com.nonosky.s2000dash

/** Ver §9 del diseño: la rueda de estados de la conexion. */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Initializing,
    Polling,
}

/**
 * Lo que el tablero sabe del carro en este instante.
 *
 * Inmutable y con un timestamp por campo: al perder la conexion no se borra
 * la pantalla, se marcan los valores viejos en gris (§9). Un tablero en
 * blanco a media curva es peor que un dato viejo señalado como viejo.
 */
data class VehicleState(
    val rpm: Int? = null,
    val rpmAtMs: Long = 0,
    val speedKmh: Int? = null,
    val speedAtMs: Long = 0,
    val coolantC: Int? = null,
    val coolantAtMs: Long = 0,
    val iatC: Int? = null,
    val iatAtMs: Long = 0,
    val loadPct: Int? = null,
    val loadAtMs: Long = 0,
    val batteryV: Float? = null,
    val batteryAtMs: Long = 0,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val sessionMaxRpm: Int = 0,
    /** Lo que reporto `ATDP`, para mostrar que se negocio de verdad. */
    val protocol: String? = null,
) {
    /** No consume lecturas: se deriva del rpm y la carga que ya se leen. */
    val vtecActive: Boolean
        get() = EngineConstants.vtecActive(rpm, loadPct)

    fun isStale(atMs: Long, nowMs: Long): Boolean =
        atMs == 0L || (nowMs - atMs) > EngineConstants.STALE_AFTER_MS
}
