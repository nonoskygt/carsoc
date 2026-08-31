package com.nonosky.s2000dash

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.nonosky.s2000dash.tpms.Rueda

/**
 * El tablero de "In my element".
 *
 * ¿Por que WebView y no Canvas como en el S2000?
 *
 * En el AP1 la §4 del diseño obliga a Canvas porque aquel head unit es un
 * rk3326 de cuatro nucleos y no daba para mas. Este radio es otro aparato:
 * MediaTek AC8257 de OCHO nucleos, 4 GB, y medido en reposo con el tablero
 * corriendo esta al 72 % ocioso. La razon que justificaba Canvas aqui no
 * existe, y a cambio se gana que la pantalla se itere sin recompilar.
 *
 * El reparto de responsabilidades no cambia: esta clase NO sabe de Bluetooth
 * ni de OBD. Solo lee [EstadoActual] y lo sirve como JSON.
 */
class TableroActivity : Activity() {

    private lateinit var web: WebView

    /** El VTEC se deduce con histeresis, asi que hay que recordar el estado. */
    private var vtecEnganchado = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Un tablero que se apaga a media curva no sirve.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            // El tablero es un asset local: no hay red de por medio.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            // Sin zoom ni scroll: la pagina mide 1024x600 y ya.
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(0xFF131715.toInt())
            addJavascriptInterface(Puente(), "Puente")
            loadUrl("file:///android_asset/tablero.html")
        }
        setContentView(web)

        EstadoActual.vista = web
        DashService.arrancar(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) aPantallaCompleta()
    }

    /** Inmersivo: sin barras del sistema robando pixeles ni atencion. */
    @Suppress("DEPRECATION")
    private fun aPantallaCompleta() {
        web.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onDestroy() {
        if (EstadoActual.vista === web) EstadoActual.vista = null
        web.destroy()
        super.onDestroy()
    }

    /**
     * Lo unico que el JavaScript puede llamar.
     *
     * Devuelve JSON. La regla dura del proyecto se aplica AQUI, no en la
     * pantalla: un dato ausente o rancio sale como `null`, jamas como 0. Un
     * cero y un "no lo se" significan cosas opuestas, y confundirlos ya costo
     * caro una vez en este proyecto —MEZCLA llego a pintar "+3 %" en verde
     * sumando un ajuste que faltaba—.
     */
    inner class Puente {

        @JavascriptInterface
        fun estado(): String {
            val ahora = System.currentTimeMillis()
            val v = EstadoActual.ultimo
            val j = StringBuilder(1024)
            j.append('{')

            // ---------- fresco o no ----------
            fun fresco(atMs: Long) = atMs > 0 && (ahora - atMs) < EngineConstants.STALE_AFTER_MS
            fun num(clave: String, valor: Any?) {
                if (j.length > 1) j.append(',')
                j.append('"').append(clave).append("\":")
                j.append(valor?.toString() ?: "null")
            }
            fun txt(clave: String, valor: String?) {
                if (j.length > 1) j.append(',')
                j.append('"').append(clave).append("\":")
                if (valor == null) j.append("null")
                else j.append('"').append(valor.replace("\"", "'")).append('"')
            }

            // ---------- banco de vivienda ----------
            val bat = EstadoActual.vigilanteBateria?.estado
            // OJO: NO se usa detectada(), que solo mira si hay MAC. El lector
            // por la radio interna publica lecturas buenas con la MAC en null,
            // y exigirla dejaba la tarjeta en "--" teniendo el dato delante.
            // Lo que hace viva a una lectura es tener voltaje y ser reciente.
            val batViva = bat != null && bat.voltaje != null && !bat.rancia(ahora)
            num("vivSoc", if (batViva) bat!!.soc else null)
            num("vivV", if (batViva) bat!!.voltaje else null)
            num("vivA", if (batViva) bat!!.corrienteA else null)
            num("vivW", if (batViva) bat!!.potenciaW?.let { Math.round(it) } else null)
            num("vivT", if (batViva) bat!!.temperaturaC else null)
            txt("vivH", null)   // autonomia: pendiente de historial de consumo

            // ---------- deducidos del banco de vivienda ----------
            // El inversor y el cargador DC-DC no tienen Bluetooth. Se infieren
            // del signo de la corriente del banco; si no hay banco, no se
            // inventa nada.
            val amp = if (batViva) bat!!.corrienteA else null
            val motorGirando = (v.rpm ?: 0) > 300 && fresco(v.rpmAtMs)
            txt("dcdc", when {
                amp == null -> null
                amp > 0.5f && motorGirando -> "Cargando"
                motorGirando -> "Sin carga"
                else -> "Motor parado"
            })
            num("inversorW", if (amp != null && amp < -0.5f && batViva)
                bat!!.potenciaW?.let { Math.round(-it) } else null)

            // ---------- banco de arranque ----------
            // Pendiente: hoy el vigilante sostiene UN solo BMS. Hasta que
            // sostenga dos, la tarjeta entera sale en "--". Se manda null
            // campo por campo a proposito: dejar los valores del boceto
            // pintados seria pintar la lectura de un aparato que no esta
            // conectado, que es la mentira mas cara de este proyecto.
            num("arrSoc", null); num("arrV", null)
            num("arrA", null);   num("arrW", null); num("arrT", null)

            // ---------- nevera ----------
            // Pendiente de implementar el enlace Alpicool.
            num("nevT", null); num("nevSet", null)
            num("nevV", null); txt("nevComp", null); txt("nevOn", null)

            // ---------- motor ----------
            num("agua", if (fresco(v.coolantAtMs)) v.coolantC else null)
            num("rpm", if (fresco(v.rpmAtMs)) v.rpm else null)
            num("aire", if (fresco(v.iatAtMs)) v.iatC else null)
            num("carga", if (fresco(v.loadAtMs)) v.loadPct else null)
            num("avance", if (fresco(v.avanceAtMs)) v.avanceGrados else null)
            // kPa -> PSI, por peticion del dueño
            num("mapPsi", if (fresco(v.mapAtMs)) v.mapKpa?.let { it * 0.145038f } else null)

            // Los DOS ajustes o ninguno, y manda la edad del mas viejo.
            // Rellenar con cero el que falte diria "corrige perfecto", que es
            // la respuesta contraria a "no lo se".
            val corto = v.trimCortoPct
            val largo = v.trimLargoPct
            val ajusteVale = corto != null && largo != null &&
                fresco(minOf(v.trimCortoAtMs, v.trimLargoAtMs))
            num("trim", if (ajusteVale) corto!! + largo!! else null)

            // El reloj de mezcla exige lambda REAL del PID 0134. Todavia no se
            // ha confirmado que esta ECU lo soporte, asi que va null y la
            // esfera sale vacia. Nunca 14.7 por defecto.
            num("afr", null)

            // ---------- VTEC, deducido con histeresis ----------
            val rpmFresco = if (fresco(v.rpmAtMs)) v.rpm else null
            val cargaFresca = if (fresco(v.loadAtMs)) v.loadPct else null
            if (rpmFresco == null || cargaFresca == null) {
                vtecEnganchado = false
                txt("vtec", null)
            } else {
                vtecEnganchado =
                    EngineConstants.vtecActive(rpmFresco, cargaFresca, vtecEnganchado)
                num("vtec", vtecEnganchado)
            }

            // ---------- llantas ----------
            val tp = EstadoActual.lectorTpms?.estado()
            val orden = listOf(
                Rueda.DelanteraIzquierda, Rueda.DelanteraDerecha,
                Rueda.TraseraIzquierda, Rueda.TraseraDerecha,
            )
            orden.forEachIndexed { i, r ->
                val lec = tp?.de(r)
                val t = lec?.trama
                num("ll${i}psi", t?.presionPsi)
                num("ll${i}t", t?.temperaturaC)
                num("ll${i}baja", t?.presionBaja ?: false)
            }

            // ---------- aceite y radio ----------
            // Sin ancla de odometro puesta a mano, el contador de kilometros
            // no significa nada: diria "faltan 0 km" junto a un "100 %", que
            // es una contradiccion en pantalla. Hasta que el dueño ancle con
            // /aceite?odometro=, la tarjeta va en "--".
            val aceiteConfigurado = Mantenimiento.proximoCambioKm > 0f &&
                Mantenimiento.odometroAnclaKm > 0f
            num("acePct", if (aceiteConfigurado) Mantenimiento.vidaPct else null)
            num("aceKm", if (aceiteConfigurado) Math.round(Mantenimiento.kmRestantes) else null)
            num("aceH", if (aceiteConfigurado) Math.round(Mantenimiento.horasRestantes) else null)
            num("radioC", if (Termometro.gradosC > 0) Termometro.gradosC else null)

            // ---------- luz de averia ----------
            // Solo si el 0101 llego fresco: sin el, "no lo se" — nunca
            // "sin averia", que es la respuesta tranquilizadora y falsa.
            if (fresco(v.estadoAtMs)) {
                num("mil", v.milEncendida)
                num("codigos", v.codigosGuardados)
            } else {
                num("mil", null); num("codigos", null)
            }

            // ---------- puntos de enlace ----------
            // No es adorno. Si el receptor de las llantas muere, las cuatro
            // presiones se quedan congeladas en su ultimo valor bueno y
            // siguen pareciendo correctas: el punto es lo unico que lo
            // delata. Cada uno dice si ESA fuente esta dando datos ahora.
            num("okViv", batViva)
            num("okArr", false)   // pendiente: un solo BMS sostenido
            num("okNev", false)   // pendiente: enlace Alpicool
            num("okTpms", tp != null && tp.ruedas.isNotEmpty())
            num("okObd", fresco(v.rpmAtMs) || fresco(v.coolantAtMs))

            j.append('}')
            return j.toString()
        }
    }
}
