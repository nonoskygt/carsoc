package com.nonosky.s2000dash.selfupdate

/**
 * Bitacora corta y en memoria de lo que va pasando con las actualizaciones.
 *
 * Existe porque en este radio `logcat` no es accesible desde fuera de la app
 * (sin root solo se ven los logs del propio UID), asi que sin esto no habria
 * forma de saber en que se atoro una actualizacion remota.
 */
object UpdateState {

    private const val MAX = 60
    private val lines = ArrayDeque<String>()

    @Volatile
    var lastCheckMs: Long = 0
        private set

    @Volatile
    var remoteVersionCode: Int = -1
        private set

    @Synchronized
    fun note(line: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${System.currentTimeMillis()} $line")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun recordCheck(remote: Int) {
        lastCheckMs = System.currentTimeMillis()
        remoteVersionCode = remote
    }
}
