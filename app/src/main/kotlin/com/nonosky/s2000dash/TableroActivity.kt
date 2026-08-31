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
import com.nonosky.s2000dash.ui.lienzo.TableroLienzo

/**
 * El tablero de "In my element".
 *
 * ## DOS VARIANTES, UN SOLO DATO
 *
 * La misma pantalla se puede pintar de dos maneras, y la elige el dueño con
 * [Variante]:
 *
 * - **html** (la de omision) — un `WebView` sobre `assets/tablero.html`. Es lo
 *   que hay hoy en el carro. En este radio se puede permitir: es un MediaTek
 *   AC8257 de OCHO nucleos con 4 GB, y medido en reposo con el tablero
 *   corriendo esta al 72 % ocioso. A cambio se gana iterar la pantalla sin
 *   recompilar.
 * - **lienzo** — [TableroLienzo], `Canvas` nativo. Repinta al ritmo que manda
 *   `Termometro.msEntreCuadros()`, que baja a UN cuadro por segundo con el
 *   radio caliente. Eso el WebView no lo sabe hacer, y este head unit ya se
 *   apago dos veces por calor.
 *
 * Las dos leen exactamente el mismo [EstadoDelTablero]: la de HTML lo recibe en
 * JSON y la de Canvas como objeto, pero es **la misma lectura y las mismas
 * reglas de frescura**. Ese es el punto — dos pantallas que se leen los
 * sensores por su cuenta acaban contradiciendose, y entonces no hay forma de
 * saber cual miente.
 *
 * El reparto de responsabilidades no cambia: esta clase NO sabe de Bluetooth ni
 * de OBD. Lee [EstadoActual] y lo sirve.
 */
class TableroActivity : Activity(), TableroLienzo.Mandos {

    /** Una de las dos esta viva; la otra es null. */
    private var web: WebView? = null
    private var lienzo: TableroLienzo? = null

    /** La que se puso como `contentView`. Para pantalla completa y limpieza. */
    private var pantalla: View? = null

    private companion object {
        /** El receptor TPMS: un CH340/CH341. */
        const val VID_CH340 = 0x1A86
        const val PID_CH340 = 0x7523
        const val ACCION_PERMISO_USB = "com.nonosky.inmyelement.PERMISO_USB"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Un tablero que se apaga a media curva no sirve.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val vista = if (Variante.actual(this) == Variante.LIENZO) crearLienzo()
        else crearWeb()
        pantalla = vista
        setContentView(vista)

        EstadoActual.vista = vista
        DashService.arrancar(this)
        pedirPermisoDelReceptorTpms()
    }

    /** La variante Canvas. Sin XML: la vista se construye y se pone, y ya. */
    private fun crearLienzo(): View {
        val v = TableroLienzo(this, this)
        lienzo = v
        return v
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun crearWeb(): View {
        val v = WebView(this).apply {
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
        web = v
        return v
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
        pantalla?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onDestroy() {
        if (EstadoActual.vista === pantalla) EstadoActual.vista = null
        web?.destroy()
        web = null
        lienzo = null
        pantalla = null
        super.onDestroy()
    }

    // =========================================================================
    // Las acciones del tablero. UNA implementacion para las dos variantes:
    // el JavaScript llega por `Puente` y el dedo del Canvas por esta interfaz,
    // pero acaban en las mismas cuatro lineas.
    // =========================================================================

    /**
     * Los mandos de la nevera devuelven si se pudo ENCOLAR, no si la nevera
     * obedecio: eso lo dira la siguiente lectura, y es la unica confirmacion
     * que vale. Un boton que se pone verde porque el tablero mando algo miente
     * igual que un valor inventado.
     */
    override fun neveraEncender(on: Boolean): Boolean =
        EstadoActual.nevera?.encender(on) ?: false

    override fun neveraEco(eco: Boolean): Boolean =
        EstadoActual.nevera?.modoEco(eco) ?: false

    override fun neveraConsigna(delta: Int): Boolean =
        EstadoActual.nevera?.moverConsigna(delta) ?: false

    /**
     * Abre la pantalla de averias.
     *
     * Va APARTE del tablero a proposito: carga la tabla de codigos y abre su
     * propia conexion al adaptador, y nada de eso hace falta mientras se
     * maneja. Se abre a mano, se usa, se cierra, y al cerrarse suelta la tabla.
     */
    override fun abrirAverias() {
        runCatching {
            startActivity(Intent(this, com.nonosky.s2000dash.diag.DiagnosticoActivity::class.java))
        }
    }

    /** Abre el menu de configuracion y emparejamiento. */
    override fun abrirAjustes() {
        runCatching {
            startActivity(
                Intent(this, com.nonosky.s2000dash.config.ConfiguracionActivity::class.java)
            )
        }
    }

    /**
     * Lo unico que el JavaScript puede llamar.
     *
     * Ojo: estos metodos NO corren en el hilo de interfaz, sino en el del
     * WebView. Por eso lo que toca vistas o arranca Activities va envuelto en
     * `runCatching` y lo demas solo lee estado `@Volatile`.
     */
    inner class Puente {

        @JavascriptInterface
        fun abrirConfiguracion() {
            abrirAjustes()
        }

        @JavascriptInterface
        fun abrirDiagnostico() {
            abrirAverias()
        }

        @JavascriptInterface
        fun neveraEncender(on: Boolean): Boolean = this@TableroActivity.neveraEncender(on)

        @JavascriptInterface
        fun neveraEco(eco: Boolean): Boolean = this@TableroActivity.neveraEco(eco)

        @JavascriptInterface
        fun neveraConsigna(delta: Int): Boolean = this@TableroActivity.neveraConsigna(delta)

        /**
         * Cambia de variante desde el propio tablero HTML.
         *
         * `Puente.cambiarVariante("lienzo")` y la pantalla se rehace con el
         * tablero Canvas; `"html"` vuelve. Cualquier otra cadena no hace nada:
         * viene de JavaScript, y guardar basura dejaria el arranque
         * decidiendose por un `else`.
         *
         * @return false si el nombre no vale o no se pudo guardar. Se contesta
         *   en vez de fallar en silencio para que el HTML pueda decirlo.
         */
        @JavascriptInterface
        fun cambiarVariante(cual: String): Boolean {
            if (!Variante.poner(this@TableroActivity, cual)) return false
            // `recreate()` toca la Activity, asi que tiene que ir al hilo de
            // interfaz: esto corre en el del WebView.
            runOnUiThread { runCatching { recreate() } }
            return true
        }

        /** Cual esta puesta ahora. Para que el HTML pinte el interruptor. */
        @JavascriptInterface
        fun variante(): String = Variante.actual(this@TableroActivity)

        /**
         * El estado del carro en JSON.
         *
         * La regla dura del proyecto —un dato ausente o rancio es `null`, jamas
         * 0— se aplica en [EstadoDelTablero], no aqui y no en la pantalla. Esto
         * solo serializa lo que ya vino decidido, y por eso la variante Canvas
         * no puede divergir: lee el mismo objeto antes de serializarlo.
         */
        @JavascriptInterface
        fun estado(): String =
            EstadoDelTablero.aJson(EstadoDelTablero.leer(System.currentTimeMillis()))
    }
}
