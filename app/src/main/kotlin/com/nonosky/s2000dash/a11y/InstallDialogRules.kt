package com.nonosky.s2000dash.a11y

/**
 * Reglas de que se toca y que no en el dialogo del instalador.
 *
 * Vive aparte del [InstallAutoClickService] para poder probarse en la JVM.
 * Es la parte del sistema con riesgo real: equivocarse aqui significa un
 * servicio de accesibilidad que toca botones que no debia.
 */
object InstallDialogRules {

    /** Paquetes de instalador del sistema. Fuera de ellos no actuamos. */
    private val INSTALLER_PACKAGES = listOf(
        "packageinstaller",
        "permissioncontroller",
    )

    /**
     * Textos que confirman. Se comparan EXACTOS y normalizados, no por
     * "contiene": "no instalar" contiene "instalar", y "cancelar" contiene
     * "cancel". Una coincidencia parcial aqui tocaria justo el boton
     * contrario al que queremos.
     */
    private val CONFIRMA = setOf(
        "instalar", "install",
        "actualizar", "update",
    )

    /**
     * Nunca se toca esto. "aceptar", "ok" y "listo" estaban antes en la
     * lista de confirmacion y se quitaron: son los botones del dialogo de
     * DESINSTALAR y de los de permisos, asi que el auto-confirmador podia
     * acabar borrando la propia app o concediendo permisos que nadie vio.
     * Ahora solo se pulsa un boton que diga literalmente instalar o
     * actualizar.
     */
    private val NUNCA = setOf(
        "cancelar", "cancel",
        "no instalar", "dont install", "don't install",
        "rechazar", "deny", "denegar",
        "desinstalar", "uninstall",
        "aceptar", "ok", "listo", "done",
        "atras", "back",
        "configuracion", "settings", "ajustes",
        "permitir", "allow", "conceder", "grant",
    )

    fun isInstallerPackage(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return INSTALLER_PACKAGES.any { p.contains(it) }
    }

    fun isConfirmButton(label: String?): Boolean {
        val n = normalize(label) ?: return false
        if (n in NUNCA) return false
        return n in CONFIRMA
    }

    /**
     * Palabras que delatan que el dialogo NO es una instalacion nuestra.
     * Si aparece alguna, el servicio se aparta aunque haya sesion armada.
     */
    private val SENALES_PELIGRO = listOf(
        "desinstalar", "uninstall",
        "permitir que", "allow ", "conceder", "grant ",
        "acceso a", "access to",
    )

    /**
     * Decide si se puede confirmar la ventana.
     *
     * **No se mira el nombre de la app.** Se miraba antes, y era un agujero:
     * el dialogo del sistema muestra el `android:label` que trae el APK que
     * se esta instalando, o sea una cadena que controla quien fabrico ese
     * APK. Bastaba llamarse "S2000 Dash" para que el servicio confirmara la
     * instalacion de cualquier cosa.
     *
     * Ahora manda [sesionArmada]: solo se confirma si fuimos nosotros
     * quienes acabamos de pedir una instalacion, y el APK ya paso la
     * verificacion de firma antes de llegar aqui.
     */
    fun puedeConfirmar(texts: List<String>, sesionArmada: Boolean): Boolean {
        if (!sesionArmada) return false
        return texts.none { t ->
            val n = t.lowercase()
            SENALES_PELIGRO.any { n.contains(it) }
        }
    }

    /** Minusculas, sin acentos, sin puntuacion ni espacios de sobra. */
    private fun normalize(raw: String?): String? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        val sinAcentos = s
            .replace('á', 'a').replace('é', 'e').replace('í', 'i')
            .replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n')
        return sinAcentos.filter { it.isLetterOrDigit() || it == ' ' || it == '\'' }
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}
