package com.nonosky.s2000dash.config

import android.content.Context
import com.nonosky.s2000dash.PerfilVehiculo

/**
 * Que aparato hace cada papel en ESTE carro.
 *
 * Antes de esto, las MAC vivian escritas en el codigo —`BancosBateria` y
 * `LectorNevera` las traian como constantes— y cambiar una bateria obligaba
 * a recompilar. Peor: el vigilante viejo ni siquiera las tenia, barria el
 * aire y se quedaba con el primer BMS que veia, que con dos bancos iguales
 * significa que cual te toca es cuestion de suerte. El dueño vio la bateria
 * de arranque bajo el rotulo de la de vivienda.
 *
 * Aqui cada papel tiene UN aparato, elegido a mano y guardado. El perfil del
 * carro solo aporta el valor por omision.
 */
object Emparejados {

    /**
     * Los papeles que un aparato puede desempeñar.
     *
     * No todos existen en todos los carros: el S2000 no tiene banco de
     * vivienda ni nevera. [papelesDeEsteCarro] filtra por perfil para que el
     * menu no ofrezca emparejar algo que este carro no lleva.
     */
    enum class Papel(val clave: String, val rotulo: String, val esBle: Boolean) {
        BancoArranque("banco_arranque", "Batería de arranque", true),
        BancoVivienda("banco_vivienda", "Batería de vivienda", true),
        Nevera("nevera", "Refrigeradora", true),
        AdaptadorObd("obd", "Adaptador OBD-II", false),
    }

    private const val PREFS = "emparejados"

    fun papelesDeEsteCarro(): List<Papel> = buildList {
        add(Papel.BancoArranque)
        if (PerfilVehiculo.TIENE_BANCO_VIVIENDA) add(Papel.BancoVivienda)
        if (PerfilVehiculo.TIENE_NEVERA) add(Papel.Nevera)
        add(Papel.AdaptadorObd)
    }

    /** El valor de fabrica, medido en el carro. Puede estar vacio. */
    fun macPorOmision(p: Papel): String = when (p) {
        Papel.BancoArranque -> PerfilVehiculo.MAC_BANCO_ARRANQUE
        Papel.BancoVivienda -> PerfilVehiculo.MAC_BANCO_VIVIENDA
        Papel.Nevera -> PerfilVehiculo.MAC_NEVERA
        Papel.AdaptadorObd -> PerfilVehiculo.MAC_OBD_POR_OMISION
    }

    fun nombrePorOmision(p: Papel): String = when (p) {
        Papel.BancoArranque -> PerfilVehiculo.NOMBRE_BANCO_ARRANQUE
        Papel.BancoVivienda -> PerfilVehiculo.NOMBRE_BANCO_VIVIENDA
        Papel.Nevera -> "Alpicool"
        Papel.AdaptadorObd -> ""
    }

    /**
     * La MAC que toca usar. Lo guardado manda sobre el valor de fabrica.
     *
     * Devuelve cadena vacia si no hay ninguno: quien llame DEBE tratar ese
     * caso, y no inventarse un aparato.
     */
    fun mac(context: Context, p: Papel): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("${p.clave}_olvidado", false)) return ""
        return prefs.getString("${p.clave}_mac", null)?.takeIf { it.isNotBlank() }
            ?: macPorOmision(p)
    }

    fun nombre(context: Context, p: Papel): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("${p.clave}_olvidado", false)) return ""
        return prefs.getString("${p.clave}_nombre", null)?.takeIf { it.isNotBlank() }
            ?: nombrePorOmision(p)
    }

    /** ¿Hay aparato asignado a este papel? */
    fun hay(context: Context, p: Papel): Boolean = mac(context, p).isNotBlank()

    /** ¿Lo eligio el dueño, o es el valor de fabrica? Importa decirlo. */
    fun elegidoAMano(context: Context, p: Papel): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !prefs.getString("${p.clave}_mac", "").isNullOrBlank()
    }

    fun asignar(context: Context, p: Papel, mac: String, nombre: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${p.clave}_mac", mac.trim().uppercase())
            .putString("${p.clave}_nombre", nombre?.trim().orEmpty())
            .putBoolean("${p.clave}_olvidado", false)
            .apply()
    }

    /**
     * Olvida el aparato de este papel.
     *
     * Marca una bandera en vez de solo borrar la clave: si no, al borrar
     * volveria el valor de fabrica y parecería que el olvido no funciono.
     */
    fun olvidar(context: Context, p: Papel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("${p.clave}_mac")
            .remove("${p.clave}_nombre")
            .putBoolean("${p.clave}_olvidado", true)
            .apply()
    }

    /** Vuelve a los valores de fabrica del perfil. */
    fun restaurar(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Para el puente HTTP y para la pantalla de configuracion. */
    fun resumen(context: Context): List<String> = papelesDeEsteCarro().map { p ->
        val mac = mac(context, p)
        val quien = if (elegidoAMano(context, p)) "elegido" else "de fabrica"
        if (mac.isBlank()) "${p.rotulo}: SIN ASIGNAR"
        else "${p.rotulo}: $mac  ${nombre(context, p)}  ($quien)"
    }
}
