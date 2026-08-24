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
        "aceptar", "ok",
        "continuar", "continue",
        "listo", "done",
    )

    /** Por si algun fabricante mete texto raro: nunca tocar esto. */
    private val NUNCA = setOf(
        "cancelar", "cancel",
        "no instalar", "dont install", "don't install",
        "rechazar", "deny", "denegar",
        "desinstalar", "uninstall",
        "atras", "back",
        "configuracion", "settings", "ajustes",
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
     * Solo confirmamos instalaciones de ESTA app. Un servicio que aceptara
     * cualquier instalacion convertiria el radio en una puerta abierta:
     * cualquier APK que llegara se instalaria sin que nadie lo viera.
     */
    fun mentionsOurApp(texts: List<String>, packageName: String, label: String): Boolean {
        val marcas = listOf(packageName.lowercase(), label.lowercase())
        return texts.any { t ->
            val n = t.lowercase()
            marcas.any { n.contains(it) }
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
