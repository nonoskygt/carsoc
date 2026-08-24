package com.nonosky.s2000dash.confirmador

/** Ventana de tiempo en la que el tablero espera confirmar una instalacion. */
object Armado {

    @Volatile
    private var hastaMs: Long = 0

    @Volatile
    var versionEsperada: Int = -1
        private set

    fun armar(versionCode: Int, duracionMs: Long) {
        versionEsperada = versionCode
        hastaMs = System.currentTimeMillis() + duracionMs.coerceIn(1_000, MAX_MS)
    }

    fun desarmar() {
        hastaMs = 0
        versionEsperada = -1
    }

    fun activo(): Boolean = System.currentTimeMillis() < hastaMs

    /** Tope duro: una ventana abierta de mas es una ventana de ataque. */
    private const val MAX_MS = 180_000L
}
