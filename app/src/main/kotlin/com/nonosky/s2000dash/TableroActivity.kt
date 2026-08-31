package com.nonosky.s2000dash

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
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

    private companion object {
        /** El receptor TPMS: un CH340/CH341. */
        const val VID_CH340 = 0x1A86
        const val PID_CH340 = 0x7523
        const val ACCION_PERMISO_USB = "com.nonosky.inmyelement.PERMISO_USB"
    }

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
            // Sin esto la animacion de entrada sale MUDA: Android exige por
            // omision un gesto del usuario antes de dejar sonar nada, y aqui
            // no hay nadie tocando la pantalla al arrancar el carro.
            settings.mediaPlaybackRequiresUserGesture = false
            // Sin zoom ni scroll: la pagina mide 1024x600 y ya.
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Transparente: el velo y las tarjetas los pinta el HTML, y
            // por los huecos asoma el fondo de pantalla del radio.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(Puente(), "Puente")
            loadUrl("file:///android_asset/tablero.html")
        }
        setContentView(web)

        EstadoActual.vista = web
        DashService.arrancar(this)
        pedirPermisoDelReceptorTpms()
    }

    /**
     * Pide el permiso USB del receptor TPMS, UNA vez y solo si falta.
     *
     * `TpmsReader` se niega a pedirlo, y hace bien: corre en el servicio, con
     * el tablero cerrado y el carro solo, y un dialogo que nadie contesta es
     * peor que un mensaje claro por el puente. Pero la Activity si tiene
     * pantalla y alguien delante, asi que este es su sitio.
     *
     * Junto con el filtro `USB_DEVICE_ATTACHED` del manifiesto, contestarlo
     * una vez marcando "usar por omision" lo deja concedido para siempre.
     */
    private fun pedirPermisoDelReceptorTpms() {
        runCatching {
            val um = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            val dev = um.deviceList.values.firstOrNull {
                it.vendorId == VID_CH340 && it.productId == PID_CH340
            } ?: return
            if (um.hasPermission(dev)) return
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACCION_PERMISO_USB).setPackage(packageName),
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_IMMUTABLE else 0,
            )
            um.requestPermission(dev, pi)
        }
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

        /**
         * Los mandos de la nevera. Devuelven si se pudo ENCOLAR, no si la
         * nevera obedecio: eso lo dira la siguiente lectura, y es la unica
         * confirmacion que vale. Un boton que se pone verde porque el
         * tablero mando algo miente igual que un valor inventado.
         */
        /**
         * Abre la pantalla de averias.
         *
         * Va APARTE del tablero a proposito: carga la tabla de codigos y abre
         * su propia conexion al adaptador, y nada de eso hace falta mientras
         * se maneja. Se abre a mano, se usa, se cierra, y al cerrarse suelta
         * la tabla.
         */
        @JavascriptInterface
        fun abrirDiagnostico() {
            runCatching {
                startActivity(Intent(this@TableroActivity,
                    com.nonosky.s2000dash.diag.DiagnosticoActivity::class.java))
            }
        }

        @JavascriptInterface
        fun neveraEncender(on: Boolean): Boolean =
            EstadoActual.nevera?.encender(on) ?: false

        @JavascriptInterface
        fun neveraEco(eco: Boolean): Boolean =
            EstadoActual.nevera?.modoEco(eco) ?: false

        @JavascriptInterface
        fun neveraConsigna(delta: Int): Boolean =
            EstadoActual.nevera?.moverConsigna(delta) ?: false

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
            // Cada banco viene fijado por su MAC. Ya no se adivina cual es
            // cual: el vigilante viejo se quedaba con el primer JBD que veia,
            // y con dos bancos iguales el rotulo de la pantalla era una
            // suposicion — el dueño vio la de arranque bajo el rotulo de
            // vivienda, y tenia razon.
            val bancos = EstadoActual.bancos
            val viv = bancos?.vivienda
            val arr = bancos?.arranque
            val vivViva = viv != null && viv.vivo(ahora)
            val arrViva = arr != null && arr.vivo(ahora)

            num("vivSoc", if (vivViva) viv!!.soc else null)
            num("vivV", if (vivViva) viv!!.voltaje else null)
            num("vivA", if (vivViva) viv!!.corrienteA else null)
            num("vivW", if (vivViva) viv!!.potenciaW?.let { Math.round(it) } else null)
            num("vivT", if (vivViva) viv!!.temperaturaC else null)
            txt("vivH", null)   // autonomia: pendiente de historial de consumo
            // El rotulo y la MAC salen del banco, no del HTML. Asi la tarjeta
            // no puede volver a decir que es una bateria que no es.
            txt("vivNom", viv?.rotulo)
            txt("vivMac", viv?.mac)
            txt("arrNom", arr?.rotulo)
            txt("arrMac", arr?.mac)

            // ---------- deducidos del banco de vivienda ----------
            // El inversor y el cargador DC-DC no tienen Bluetooth. Se infieren
            // del signo de la corriente del banco; si no hay banco, no se
            // inventa nada.
            val amp = if (vivViva) viv!!.corrienteA else null
            // ⚠️ SE DISTINGUE "el motor esta parado" de "no se si lo esta".
            // Sin enlace con la ECU no hay rpm, y traducir esa ausencia a
            // "Motor parado" es afirmar algo que nadie ha medido — el mismo
            // error que hacia decir "SIN AVERIAS" sin haber hablado con la
            // computadora. Con el motor girando de verdad y esta linea
            // diciendo "parado", el dueño creeria que el DC-DC no carga.
            val sabemosDelMotor = fresco(v.rpmAtMs)
            val motorGirando = sabemosDelMotor && (v.rpm ?: 0) > 300
            txt("dcdc", when {
                amp == null -> null
                !sabemosDelMotor -> "Sin enlace al motor"
                motorGirando && amp > 0.5f -> "Cargando"
                motorGirando -> "Sin carga"
                else -> "Motor parado"
            })
            num("inversorW", if (amp != null && amp < -0.5f && vivViva)
                viv!!.potenciaW?.let { Math.round(-it) } else null)

            // ---------- banco de arranque ----------
            num("arrSoc", if (arrViva) arr!!.soc else null)
            num("arrV", if (arrViva) arr!!.voltaje else null)
            num("arrA", if (arrViva) arr!!.corrienteA else null)
            num("arrW", if (arrViva) arr!!.potenciaW?.let { Math.round(it) } else null)
            num("arrT", if (arrViva) arr!!.temperaturaC else null)

            // ---------- nevera ----------
            val nev = EstadoActual.nevera
            val nevViva = nev != null && nev.vivoAhora(ahora)
            val ne = if (nevViva) nev!!.estado else null
            num("nevT", ne?.actual)
            num("nevSet", ne?.consigna)
            num("nevV", ne?.voltaje)
            num("nevEco", ne?.modoEco)
            txt("nevOn", when (ne?.encendida) {
                true -> "Encendida"; false -> "Apagada"; null -> null
            })
            // El compresor NO existe en el protocolo: se deduce comparando
            // temperatura contra consigna mas histeresis. Por eso la tarjeta
            // lo enseña dentro del recinto de "deducido".
            txt("nevComp", when (ne?.compresorEnMarcha()) {
                true -> "En marcha"; false -> "Parado"; null -> null
            })
            // Para colocar el punto y la marca en el carril termico.
            num("nevMin", ne?.minima)
            num("nevMax", ne?.maxima)

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
            num("okViv", vivViva)
            num("okArr", arrViva)
            num("okNev", nevViva)
            num("okTpms", tp != null && tp.ruedas.isNotEmpty())
            num("okObd", fresco(v.rpmAtMs) || fresco(v.coolantAtMs))

            j.append('}')
            return j.toString()
        }
    }
}
