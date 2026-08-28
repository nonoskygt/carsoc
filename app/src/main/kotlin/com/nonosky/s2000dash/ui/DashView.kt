package com.nonosky.s2000dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.nonosky.s2000dash.ConnectionState
import com.nonosky.s2000dash.EngineConstants
import com.nonosky.s2000dash.Termometro
import com.nonosky.s2000dash.VehicleState
import com.nonosky.s2000dash.bateria.BateriaState
import com.nonosky.s2000dash.bateria.EnlaceBateria
import com.nonosky.s2000dash.tpms.EnlaceTpms
import com.nonosky.s2000dash.tpms.Escalas
import com.nonosky.s2000dash.tpms.EstadoTpms
import com.nonosky.s2000dash.tpms.Rueda

/**
 * El tablero entero, dibujado a mano en un [Canvas].
 *
 * Canvas y no Compose por decision de diseño (§4): el head unit trae una CPU
 * debil (Rockchip rk3326) y esto tiene que sostener 60 fps sin pelear.
 *
 * **Sin tacometro y sin velocimetro, por peticion expresa del dueño.** El
 * carro ya tiene los dos en el cuadro original a la altura de los ojos;
 * repetirlos aqui gastaba el 70% de la pantalla en informacion duplicada. Lo
 * que este tablero aporta es lo que el carro NO muestra: las cuatro presiones
 * y las temperaturas del motor.
 *
 * Eso cambia el criterio de diseño. Un tacometro se mira constantemente; una
 * presion de llanta no se mira nunca — hasta que importa. Asi que los numeros
 * viven tranquilos y **gritan al salirse de rango**, que es cuando sirven.
 *
 * No conoce Bluetooth ni OBD ni USB: recibe estado y lo pinta.
 */
class DashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // --- Estado -------------------------------------------------------------

    /** Abrir la pantalla de averias. Lo pone la Activity. */
    var alAbrirDiagnostico: (() -> Unit)? = null

    /** Donde quedo el acceso al diagnostico. */
    private val cajaDiagnostico = RectF()

    /**
     * Que hacer cuando se pulsa la X. Lo pone la Activity.
     *
     * La vista no cierra nada por su cuenta ni sabe que es el Bluetooth: solo
     * avisa. Quien decide que se suelta es el servicio, que es el que lo tomo.
     */
    var alCerrar: (() -> Unit)? = null

    /** Donde quedo dibujado el boton, para poder acertarle. */
    private val cajaCerrar = RectF()

    /** El dedo esta encima de la X esperando a que se cumpla el medio segundo. */
    private var cerrando = false

    private var state = VehicleState()
    private var tpms = EstadoTpms()
    private var enlaceTpms: EnlaceTpms = EnlaceTpms.SinReceptor
    private var bateria = BateriaState()

    fun setState(newState: VehicleState) {
        state = newState
    }

    fun setTpms(nuevo: EstadoTpms, enlace: EnlaceTpms) {
        tpms = nuevo
        enlaceTpms = enlace
    }

    fun setBateria(nuevo: BateriaState) {
        bateria = nuevo
    }

    // --- Pinceles (asignados una sola vez; onDraw no aloca) -----------------

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val caja = RectF()

    // --- Bucle de animacion -------------------------------------------------

    /**
     * Repinta unas pocas veces por segundo, NO a 60 fps.
     *
     * Los 60 fps tenian sentido cuando habia una aguja de tacometro que
     * animar. Al quitar el tacometro me quede con el bucle, y eso dejo a un
     * rk3326 barato redibujando sesenta veces por segundo, para siempre,
     * encima de los hilos de OBD, bateria, TPMS, servidor HTTP y USB. El
     * dueño reporto que el head unit se apagaba: calor y CPU saturada.
     *
     * Ya no hay nada que se mueva rapido. Lo mas veloz que hay que mostrar es
     * un parpadeo de alarma cada medio segundo, y para eso sobran 5 cuadros
     * por segundo. Un tablero de vigilancia no es un videojuego.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        programarRepintado()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(repintar)
        super.onDetachedFromWindow()
    }

    private val repintar = Runnable {
        invalidate()
        programarRepintado()
    }

    /**
     * El ritmo lo pone el termometro, no una constante.
     *
     * Con el radio fresco van 5 cuadros por segundo; tibio, 2; caliente, 1.
     * Un tablero de vigilancia sigue siendo legible a un cuadro por segundo,
     * y a esas alturas lo que importa es que el radio no se apague.
     */
    private fun programarRepintado() {
        removeCallbacks(repintar)
        postDelayed(repintar, com.nonosky.s2000dash.Termometro.msEntreCuadros())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ahora = System.currentTimeMillis()

        // El VTEC se resuelve LO PRIMERO porque tiñe el fondo, y el fondo se
        // pinta antes que todo lo demas.
        val vtec = vtecVisible(ahora)
        canvas.drawColor(
            if (vtec && parpadeo()) COLOR_VTEC_FONDO else COLOR_BG
        )

        // TRES columnas iguales, como las pidio el dueño:
        //
        //   [ MOTOR / OBD2 ] [ BATERIA + LLANTAS ] [ libre ]
        //
        // La tercera queda a proposito vacia, reservada. Se marca como libre
        // en vez de estirar las otras dos para taparla: si mañana entra un
        // dato nuevo, el sitio ya esta y nada se mueve de donde el ojo
        // aprendio a buscarlo.
        val col = w / 3f
        dibujarMotor(canvas, 0f, col, h, ahora)
        dibujarBateria(canvas, col, col, h * 0.42f, ahora)
        dibujarLlantas(canvas, col, col, h, ahora, h * 0.42f)
        dibujarLibre(canvas, col * 2f, col, h, vtec)

        // Separadores tenues: tres columnas sin linea se leen como un solo
        // amontonamiento, sobre todo de reojo.
        trazoPaint.color = COLOR_SILUETA
        trazoPaint.strokeWidth = h * 0.004f
        canvas.drawLine(col, h * 0.06f, col, h * 0.94f, trazoPaint)
        canvas.drawLine(col * 2f, h * 0.06f, col * 2f, h * 0.94f, trazoPaint)

        dibujarSaludDelRadio(canvas, w, h)
        // El AJUSTE ya no se pinta aparte: MEZCLA muestra la suma de los dos.
        // En su hueco de abajo a la izquierda va el VTEC.
        dibujarVtec(canvas, w, h, vtec)
        dibujarCerrar(canvas, w, h)
        dibujarAccesoDiagnostico(canvas, w, h)
        dibujarConfirmacionAceite(canvas, w, h)
    }

    /**
     * El acceso a la pantalla de averias, arriba a la izquierda.
     *
     * Discreto y en la esquina OPUESTA a la X de cerrar: son las dos unicas
     * cosas de este tablero que sacan al dueño de donde esta, y ponerlas
     * juntas seria pedir que confunda una con otra manejando.
     *
     * Se enciende en ambar cuando hay codigos guardados, que es la unica vez
     * que este boton importa de verdad.
     */
    private fun dibujarAccesoDiagnostico(canvas: Canvas, w: Float, h: Float) {
        val lado = h * 0.13f
        val margen = h * 0.03f
        cajaDiagnostico.set(margen, margen, margen + lado, margen + lado)

        val hayCodigos = state.milEncendida || state.codigosGuardados > 0
        val color = if (hayCodigos) COLOR_AMBER else COLOR_SILUETA

        trazoPaint.color = color
        trazoPaint.strokeWidth = h * 0.008f
        canvas.drawRoundRect(cajaDiagnostico, lado * 0.25f, lado * 0.25f, trazoPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (hayCodigos) COLOR_AMBER else COLOR_TEXT_DIM
        textPaint.textSize = lado * 0.55f
        canvas.drawText(
            "!", cajaDiagnostico.centerX(), cajaDiagnostico.centerY() + lado * 0.20f, textPaint,
        )
    }

    /**
     * La pregunta de "aceite cambiado?", encima de todo.
     *
     * Reiniciar el intervalo borra la unica cuenta que existe: no hay
     * odometro real de donde recuperarla, porque esta ECU no lo expone. Un
     * reinicio sin querer significa que el proximo cambio caiga 6000 km tarde
     * y que nadie se entere hasta que el aceite ya este hecho barro. Por eso
     * van los dos filtros seguidos: cinco segundos sosteniendo el dedo, y
     * encima un SI explicito.
     */
    private fun dibujarConfirmacionAceite(canvas: Canvas, w: Float, h: Float) {
        if (!confirmandoAceite) return

        // Velo oscuro sobre el tablero: deja claro que lo de abajo esta
        // esperando y que esto es lo unico que se puede tocar ahora.
        barPaint.color = 0xE6000000.toInt()
        canvas.drawRect(0f, 0f, w, h, barPaint)

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT
        labelPaint.textSize = h * 0.085f
        canvas.drawText("ACEITE CAMBIADO?", w * 0.5f, h * 0.28f, labelPaint)

        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.048f
        canvas.drawText(
            "se reinician los kilometros Y las horas",
            w * 0.5f, h * 0.40f, labelPaint,
        )

        val anchoBoton = w * 0.22f
        val altoBoton = h * 0.22f
        val yTop = h * 0.52f
        cajaConfirmarSi.set(
            w * 0.28f - anchoBoton / 2, yTop, w * 0.28f + anchoBoton / 2, yTop + altoBoton,
        )
        cajaConfirmarNo.set(
            w * 0.72f - anchoBoton / 2, yTop, w * 0.72f + anchoBoton / 2, yTop + altoBoton,
        )

        trazoPaint.strokeWidth = h * 0.008f
        trazoPaint.color = COLOR_GREEN
        canvas.drawRoundRect(cajaConfirmarSi, altoBoton * 0.2f, altoBoton * 0.2f, trazoPaint)
        trazoPaint.color = COLOR_SILUETA
        canvas.drawRoundRect(cajaConfirmarNo, altoBoton * 0.2f, altoBoton * 0.2f, trazoPaint)

        textPaint.textSize = h * 0.10f
        textPaint.color = COLOR_GREEN
        canvas.drawText(
            "SI", cajaConfirmarSi.centerX(), cajaConfirmarSi.centerY() + h * 0.035f, textPaint,
        )
        textPaint.color = COLOR_TEXT_DIM
        canvas.drawText(
            "NO", cajaConfirmarNo.centerX(), cajaConfirmarNo.centerY() + h * 0.035f, textPaint,
        )
    }

    /**
     * El boton de cerrar, arriba a la derecha.
     *
     * Existe porque el tablero se queda con la radio Bluetooth mientras esta
     * abierto —sondeo OBD y BMS— y el dueño necesita poder devolversela a
     * Android Auto sin desinstalar nada ni reiniciar el radio.
     *
     * Va arriba a la derecha y no en el centro por una razon practica: es la
     * esquina mas lejos de donde cae la mano al alcanzar la pantalla desde el
     * asiento, y esto NO es un boton que uno quiera pulsar sin querer a media
     * curva. Por eso tambien se pide una pulsacion larga y no un toque.
     */
    private fun dibujarCerrar(canvas: Canvas, w: Float, h: Float) {
        val lado = h * 0.13f
        val margen = h * 0.03f
        cajaCerrar.set(w - margen - lado, margen, w - margen, margen + lado)

        trazoPaint.color = if (cerrando) COLOR_REDLINE else COLOR_SILUETA
        trazoPaint.strokeWidth = h * 0.008f
        canvas.drawRoundRect(cajaCerrar, lado * 0.25f, lado * 0.25f, trazoPaint)

        // La X, dos trazos. Se dibuja en vez de usar una fuente porque una
        // aspa tipografica cambia de tamaño y de centro entre ROMs.
        val d = lado * 0.28f
        val cx = cajaCerrar.centerX()
        val cy = cajaCerrar.centerY()
        trazoPaint.color = if (cerrando) COLOR_REDLINE else COLOR_TEXT_DIM
        trazoPaint.strokeWidth = h * 0.012f
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, trazoPaint)
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, trazoPaint)
    }

    /**
     * Pulsacion LARGA para cerrar, no un toque.
     *
     * Un toque suelto cerraria el tablero de un manotazo involuntario
     * manejando, y recuperarlo exige soltar el volante y buscar un icono. El
     * medio segundo de mas es barato; el susto no.
     */
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // Con la confirmacion abierta, lo unico que existe son SI y
                // NO. Cualquier otro sitio la cierra sin hacer nada, que es
                // la respuesta segura ante un toque perdido.
                if (confirmandoAceite) {
                    if (cajaConfirmarSi.contains(event.x, event.y)) {
                        runCatching { alConfirmarAceite?.invoke() }
                    }
                    confirmandoAceite = false
                    invalidate()
                    performClick()
                    return true
                }

                // El aceite: se empieza a contar los 5 s del reinicio, y si
                // se suelta antes solo cambia de cara. Un mismo sitio hace
                // las dos cosas y las separa el TIEMPO, no la posicion, asi
                // que no hay que buscar un segundo boton en una pantalla que
                // ya no tiene sitio.
                if (cajaDiagnostico.contains(event.x, event.y)) {
                    runCatching { alAbrirDiagnostico?.invoke() }
                    performClick()
                    return true
                }

                if (cajaAceite.contains(event.x, event.y)) {
                    sosteniendoAceite = true
                    aceiteDesdeMs = System.currentTimeMillis()
                    postDelayed(pedirConfirmacionAceite, MS_RESET_ACEITE)
                    invalidate()
                    return true
                }
                if (!cajaCerrar.contains(event.x, event.y)) return false
                cerrando = true
                invalidate()
                postDelayed(confirmarCierre, MS_PULSACION_LARGA)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // Si el dedo se sale de la caja, ya no cuenta.
                if (cerrando && !cajaCerrar.contains(event.x, event.y)) cancelarCierre()
                if (sosteniendoAceite && !cajaAceite.contains(event.x, event.y)) {
                    cancelarSostenidoAceite(cambiarCara = false)
                }
                return cerrando || sosteniendoAceite
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val estaba = cerrando || sosteniendoAceite
                cancelarCierre()
                // Soltar antes de los 5 s NO reinicia nada: solo pasa a la
                // siguiente cara. Es el atajo natural, y ademas hace que
                // trastear con la pantalla sea inofensivo.
                cancelarSostenidoAceite(
                    cambiarCara = event.actionMasked == android.view.MotionEvent.ACTION_UP,
                )
                return estaba
            }
        }
        return false
    }

    private fun cancelarSostenidoAceite(cambiarCara: Boolean) {
        if (!sosteniendoAceite) return
        sosteniendoAceite = false
        removeCallbacks(pedirConfirmacionAceite)
        if (cambiarCara) {
            caraAceite++
            performClick()
        }
        invalidate()
    }

    private val pedirConfirmacionAceite = Runnable {
        if (!sosteniendoAceite) return@Runnable
        sosteniendoAceite = false
        confirmandoAceite = true
        invalidate()
    }

    private fun cancelarCierre() {
        if (!cerrando) return
        cerrando = false
        removeCallbacks(confirmarCierre)
        invalidate()
    }

    private val confirmarCierre = Runnable {
        if (!cerrando) return@Runnable
        cerrando = false
        invalidate()
        performClick()
        runCatching { alCerrar?.invoke() }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * La temperatura del propio radio, en la franja de abajo.
     *
     * No es un dato del carro, por eso vive fuera de las tres columnas y en
     * pequeño. Pero vive SIEMPRE en pantalla, y por una razon concreta: este
     * head unit ya se apago dos veces por calor, y en el radio nuevo el
     * guardian puede quedarse **ciego** porque no hay ninguna zona termica
     * legible. Cuando eso pasa, el unico termometro que queda es el dueño
     * mirando — asi que hay que darle algo que mirar, y decirle claramente
     * cuando nadie mas esta vigilando.
     *
     * Sigue la regla del resto del tablero: callado mientras todo va bien,
     * y grita al salirse de rango.
     */
    private fun dibujarSaludDelRadio(canvas: Canvas, w: Float, h: Float) {
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = h * 0.040f

        val texto: String
        val color: Int
        when {
            Termometro.ciego -> {
                // En ambar y con todas las letras: no es un detalle tecnico,
                // es el aviso de que la proteccion automatica no esta puesta.
                texto = "RADIO — SIN TERMOMETRO, VIGILA TU"
                color = COLOR_AMBER
            }
            Termometro.gradosC > 0 -> {
                texto = "RADIO  ${Termometro.gradosC} °C"
                color = when (Termometro.nivel) {
                    Termometro.Nivel.Caliente -> COLOR_REDLINE
                    Termometro.Nivel.Tibio -> COLOR_AMBER
                    Termometro.Nivel.Fresco -> COLOR_TEXT_DIM
                }
            }
            else -> {
                // Hay fuente pero no da grados: el sistema solo opina por
                // niveles. Se dice tal cual en vez de inventar un numero.
                texto = "RADIO — ${Termometro.nivel.name.uppercase()}"
                color = when (Termometro.nivel) {
                    Termometro.Nivel.Caliente -> COLOR_REDLINE
                    Termometro.Nivel.Tibio -> COLOR_AMBER
                    Termometro.Nivel.Fresco -> COLOR_TEXT_DIM
                }
            }
        }

        labelPaint.color = color
        canvas.drawText(texto, w * 0.5f, h * 0.985f, labelPaint)

        dibujarEstadoEnlaces(canvas, w, h)
    }

    /** Que cara del aceite se esta mostrando. Se cambia tocandola. */
    private var caraAceite = 0

    /** Donde quedo la fila del aceite, para poder tocarla. */
    private val cajaAceite = RectF()

    /** Se esta sosteniendo el dedo sobre el aceite, contando los 5 s. */
    private var sosteniendoAceite = false
    private var aceiteDesdeMs = 0L

    /** Esta abierta la pregunta de confirmacion del cambio de aceite. */
    private var confirmandoAceite = false
    private val cajaConfirmarSi = RectF()
    private val cajaConfirmarNo = RectF()

    /** Que hacer cuando se confirma que se cambio el aceite. */
    var alConfirmarAceite: (() -> Unit)? = null

    /**
     * La vida del aceite. Tres caras, una por toque.
     *
     * El color NO depende de la cara que se este mirando: si toca cambiar, lo
     * dice igual estando en horas que en kilometros. Que el aviso dependiera
     * de por donde se dejo la pantalla seria la peor clase de defecto — uno
     * que solo aparece cuando nadie mira.
     */
    private fun dibujarAceite(canvas: Canvas, x0: Float, x1: Float, h: Float) {
        val m = com.nonosky.s2000dash.Mantenimiento
        val y = h * 0.55f
        cajaAceite.set(x0, y - h * 0.11f, x1, y + h * 0.06f)

        val color = when {
            m.toca -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
            m.cerca -> COLOR_AMBER
            else -> COLOR_TEXT
        }

        val etiqueta: String
        val valor: String
        when (caraAceite % 4) {
            // El PORCENTAJE es la cara por omision: es lo unico de las cuatro
            // que se entiende sin saber cual era el intervalo ni cuando fue
            // el ultimo cambio. Las otras tres son el detalle, para quien
            // quiera mirarlo.
            0 -> {
                etiqueta = "ACEITE"
                valor = "${m.vidaPct} %"
            }
            1 -> {
                etiqueta = "ACEITE km"
                valor = if (m.proximoCambioKm <= 0f) "--"
                    else "%.0f".format(m.kmRestantes)
            }
            2 -> {
                etiqueta = "ACEITE h"
                valor = "%.0f h".format(m.horasRestantes)
            }
            else -> {
                etiqueta = "ODOMETRO"
                valor = "%.0f".format(m.odometroKm)
            }
        }
        filaGrande(canvas, x0, x1, y, h, etiqueta, valor, color)

        // Mientras se sostiene, una barra que se llena. Sin ella, cinco
        // segundos aguantando sin que pase nada se leen como que no funciona
        // y el dedo se levanta antes de tiempo.
        if (sosteniendoAceite) {
            val t = ((System.currentTimeMillis() - aceiteDesdeMs).toFloat() /
                MS_RESET_ACEITE).coerceIn(0f, 1f)
            barPaint.color = COLOR_AMBER
            canvas.drawRect(x0, y + h * 0.05f, x0 + (x1 - x0) * t, y + h * 0.075f, barPaint)
        }
    }

    /** Hasta cuando se sigue pintando el VTEC despues de soltarlo. */
    private var vtecHastaMs = 0L

    /**
     * El VTEC, abajo a la izquierda. Solo aparece cuando engancha.
     *
     * ## Es una DEDUCCION, no una señal
     *
     * OBD-II generico no expone el solenoide del VTEC: no hay PID que lo
     * diga, ni en este carro ni en ninguno. Lo que hay es lo que lo provoca —
     * revoluciones por encima del cruce y pedal suficiente— asi que se deduce
     * de rpm >= 5850 y carga >= 60%. Honda publica ese cruce para el AP1; el
     * 60% de carga es la guarda que evita cantar VTEC en retencion, cuando
     * las revoluciones estan arriba pero el motor no esta empujando.
     *
     * Estuvo DOBLEMENTE muerto hasta hoy: nunca se pinto en pantalla, y
     * ademas `loadPct` salia siempre null porque esta ECU contesta el 0104
     * sin el byte del PID. Arreglado eso, la deduccion por fin puede darse.
     *
     * ## Por que se queda encendido un momento
     *
     * El VTEC engancha en un pico y se suelta al cambiar de marcha. Con el
     * radio caliente el tablero repinta a un cuadro por segundo, asi que un
     * enganche corto caeria entre dos cuadros y no se veria nunca. Se
     * sostiene [MS_VTEC_VISIBLE] tras soltarlo: lo justo para que el ojo lo
     * cace sin mentir sobre cuanto duro.
     *
     * Se exige ademas que la carga NO sea rancia. Se lee seis veces por
     * periodo y las revoluciones sesenta, asi que al pisar a fondo es muy
     * facil tener rpm de ahora con una carga de hace un segundo — y cantar un
     * VTEC con datos de dos momentos distintos es inventarlo.
     */
    /**
     * Decide si el VTEC cuenta como enganchado AHORA, y sostiene el aviso.
     *
     * Se separa de lo que pinta porque el fondo tambien lo necesita, y el
     * fondo va antes que todo. Llamarlo dos veces por cuadro adelantaria el
     * plazo dos veces; se llama UNA y el resultado se pasa.
     */
    private fun vtecVisible(ahora: Long): Boolean {
        val cargaFresca = !state.isStale(state.loadAtMs, ahora)
        if (state.vtecActive && cargaFresca) vtecHastaMs = ahora + MS_VTEC_VISIBLE
        // Forzado desde el puente, solo para poder verlo sin redlinear.
        if (ahora <= com.nonosky.s2000dash.EstadoActual.vtecForzadoHastaMs) return true
        return ahora <= vtecHastaMs
    }

    private fun dibujarVtec(canvas: Canvas, w: Float, h: Float, visible: Boolean) {
        if (!visible) return

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.075f
        textPaint.color = COLOR_VTEC_ON
        canvas.drawText("VTEC", w * 0.012f, h * 0.985f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * UN solo sitio para el estado de los tres enlaces, abajo a la derecha.
     *
     * Los titulos de las columnas cambiaban de texto con cada transicion y el
     * resultado era un tablero inquieto: carteles moviendose encima de
     * numeros que estaban parados. El dueño pidio lo contrario — titulos
     * quietos, valores vivos.
     *
     * Asi que el estado se junta aqui, y **solo aparece lo que NO va bien**.
     * Con las tres fuentes leyendo, esta linea esta vacia y el tablero entero
     * se queda callado. Es la misma regla del resto: callado mientras todo
     * va bien, y habla al salirse de rango.
     */
    private fun dibujarEstadoEnlaces(canvas: Canvas, w: Float, h: Float) {
        val partes = mutableListOf<String>()

        when (state.connection) {
            ConnectionState.Polling -> {}
            ConnectionState.Initializing -> partes += "OBD iniciando"
            ConnectionState.Connecting -> partes += "OBD conectando"
            ConnectionState.SinAdaptador -> partes += "OBD sin adaptador"
            ConnectionState.BluetoothApagado -> partes += "Bluetooth apagado"
            ConnectionState.Disconnected -> partes += "OBD sin enlace"
        }

        when (bateria.enlace) {
            EnlaceBateria.Leyendo -> {}
            EnlaceBateria.SinDongle -> partes += "BMS sin dongle"
            EnlaceBateria.DongleMudo -> partes += "BMS dongle mudo"
            EnlaceBateria.Buscando -> partes += "BMS buscando"
            EnlaceBateria.Detectada -> partes += "BMS sin leer"
            EnlaceBateria.Fallo -> partes += "BMS fallo"
        }

        when (enlaceTpms) {
            EnlaceTpms.Leyendo -> if (tpms.ruedas.isEmpty()) partes += "TPMS sin sensores"
            EnlaceTpms.SinReceptor -> partes += "TPMS sin receptor"
            EnlaceTpms.SinPermiso -> partes += "TPMS sin permiso"
            EnlaceTpms.Abriendo -> partes += "TPMS abriendo"
            EnlaceTpms.Fallo -> partes += "TPMS no responde"
        }

        if (partes.isEmpty()) return

        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.textSize = h * 0.040f
        labelPaint.color = COLOR_TEXT_DIM
        canvas.drawText(partes.joinToString("  ·  "), w * 0.988f, h * 0.985f, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
    }


    // --- Llantas ------------------------------------------------------------

    /**
     * Las cuatro esquinas, colocadas como estan en el carro.
     *
     * La POSICION en pantalla es el dato: no hace falta leer "trasera
     * izquierda" si el numero ya esta abajo a la izquierda. Eso es lo que
     * permite entenderlo de reojo, que es todo lo que se puede pedir a un
     * tablero mientras alguien maneja.
     */
    private fun dibujarLlantas(
        canvas: Canvas, left: Float, ancho: Float, h: Float, ahora: Long, top: Float,
    ) {
        // SIN TITULO y SIN "ADELANTE".
        //
        // Los quito el dueño, y tiene razon: eran texto fijo ocupando el
        // sitio de los numeros. Que arriba es adelante lo dice la silueta, y
        // que la caja de arriba a la izquierda es la delantera izquierda lo
        // dice su posicion — que es el argumento con el que se diseño esta
        // rejilla desde el principio. El estado del receptor vive ahora en la
        // linea de abajo, con el de los otros dos enlaces.
        //
        // Lo que se gana con ese espacio son cajas MAS GRANDES, que es lo
        // unico que se mira de reojo manejando.
        // La silueta se ensancha para ENMARCAR las cajas nuevas. Con el
        // margen viejo las cajas grandes se le salian por los lados y el
        // contorno asomaba por detras, que se leia como un fallo de pintado.
        val margenX = ancho * 0.02f
        val topRejilla = top + h * 0.03f
        val altoRejilla = h * 0.93f - topRejilla
        caja.set(left + margenX, topRejilla, left + ancho - margenX, topRejilla + altoRejilla)
        trazoPaint.color = COLOR_SILUETA
        trazoPaint.strokeWidth = h * 0.006f
        canvas.drawRoundRect(caja, ancho * 0.10f, ancho * 0.10f, trazoPaint)

        val anchoCelda = ancho * 0.42f
        val altoCelda = altoRejilla * 0.44f
        val xIzq = left + ancho * 0.28f
        val xDer = left + ancho * 0.72f
        val yArriba = topRejilla + altoRejilla * 0.26f
        val yAbajo = topRejilla + altoRejilla * 0.74f

        dibujarRueda(canvas, Rueda.DelanteraIzquierda, xIzq, yArriba, anchoCelda, altoCelda, ahora)
        dibujarRueda(canvas, Rueda.DelanteraDerecha, xDer, yArriba, anchoCelda, altoCelda, ahora)
        dibujarRueda(canvas, Rueda.TraseraIzquierda, xIzq, yAbajo, anchoCelda, altoCelda, ahora)
        dibujarRueda(canvas, Rueda.TraseraDerecha, xDer, yAbajo, anchoCelda, altoCelda, ahora)
    }

    /**
     * Encabezado que dice si hay que creerle a los numeros de abajo.
     *
     * Un titulo fijo que dijera "LLANTAS" mientras el receptor esta
     * desconectado seria mentir por omision: los cuatro valores viejos
     * seguirian ahi, y nada avisaria de que ya no son de ahora.
     */
    /**
     * FIJO. El estado del enlace no vive aqui.
     *
     * Antes este titulo cambiaba de texto con cada transicion del receptor
     * —"sin receptor", "conectando", "esperando sensores", "psi (placa 32)"—
     * y con el enlace inestable eso es un cartel bailando encima de unos
     * numeros que si estan quietos. El dueño lo pidio claro: que solo se
     * actualicen los valores.
     *
     * Cuando no hay datos, las celdas ya dicen "sin sensor" y el porque vive
     * en la linea de estado de abajo, en UN solo sitio.
     */
    private fun tituloLlantas(ahora: Long): String =
        "LLANTAS · psi (placa ${Escalas.PSI_PLACA.toInt()})"

    /**
     * Una esquina: presion grande, temperatura pequeña.
     *
     * La presion manda porque es la que hace parar el carro. La temperatura
     * acompaña, mas chica, porque casi nunca cambia la decision.
     */
    private fun dibujarRueda(
        canvas: Canvas, rueda: Rueda, cx: Float, cy: Float,
        ancho: Float, alto: Float, ahora: Long,
    ) {
        val lectura = tpms.de(rueda)
        val psi = lectura?.presionPsi
        val rancia = lectura?.rancia(ahora) ?: true

        // Nunca se pinta un cero como lectura: el decodificador ya devuelve
        // null cuando el receptor no tiene dato, y aqui eso son guiones. Un
        // "0.0" haria creer en un reventon que no existe.
        val color = when {
            psi == null || rancia -> COLOR_STALE
            psi !in Escalas.PSI_PLAUSIBLE -> COLOR_VTEC_ON   // escala sospechosa
            psi < Escalas.PSI_AVISO_BAJA -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
            psi < Escalas.PSI_PLACA - 3f -> COLOR_AMBER
            psi > Escalas.PSI_PLACA + 8f -> COLOR_AMBER
            else -> COLOR_GREEN
        }

        // Fondo de la caja: casi negro cuando todo va bien, tintado cuando no.
        // Asi una llanta baja se localiza sin leer un solo numero.
        caja.set(cx - ancho * 0.5f, cy - alto * 0.5f, cx + ancho * 0.5f, cy + alto * 0.5f)
        barPaint.color = if (color == COLOR_GREEN || color == COLOR_STALE) COLOR_CAJA else color
        if (barPaint.color != COLOR_CAJA) {
            // Aviso: el relleno va tenue para no encandilar de noche, y el
            // borde marca la caja con fuerza.
            barPaint.color = COLOR_CAJA_AVISO
        }
        canvas.drawRoundRect(caja, alto * 0.18f, alto * 0.18f, barPaint)
        trazoPaint.color = color
        trazoPaint.strokeWidth = alto * 0.035f
        canvas.drawRoundRect(caja, alto * 0.18f, alto * 0.18f, trazoPaint)

        // DOS valores, del MISMO tamaño, con sus unidades. Nada mas.
        //
        // Antes esta caja llevaba la etiqueta de la esquina (DI/DD/TI/TD), la
        // presion grande y un pie de texto explicando por que faltaba el
        // dato. El dueño lo dejo claro: fuera los textos, los dos numeros
        // igual de grandes, y con C y PSI para saber cual es cual.
        //
        // La temperatura no es decoracion: una llanta que se calienta mas que
        // sus tres hermanas esta rozando, arrastrando un freno o perdiendo
        // aire — y eso se ve antes en los grados que en la presion.
        val tam = alto * 0.30f
        textPaint.textSize = tam
        textPaint.color = color
        canvas.drawText(
            psi?.let { String.format("%.0f PSI", it) } ?: "-- PSI",
            cx, cy - alto * 0.03f, textPaint,
        )

        val tempC = lectura?.temperaturaC
        textPaint.color = when {
            tempC == null || rancia -> COLOR_STALE
            tempC >= 80 -> COLOR_REDLINE
            tempC >= 65 -> COLOR_AMBER
            else -> COLOR_TEXT
        }
        canvas.drawText(
            tempC?.let { "$it C" } ?: "-- C",
            cx, cy + alto * 0.32f, textPaint,
        )
    }

    /**
     * El pie de cada caja explica POR QUE un numero no esta o no vale.
     *
     * "--" a secas deja al conductor sin saber si el sensor murio, si el
     * receptor esta desconectado o si simplemente no ha llegado nada todavia.
     */
    private fun pieDeRueda(
        lectura: com.nonosky.s2000dash.tpms.LecturaRueda?,
        rancia: Boolean,
        ahora: Long,
    ): String {
        if (lectura == null) return "sin sensor"
        if (rancia) return "dato viejo"
        val psi = lectura.presionPsi
        if (psi != null && psi !in Escalas.PSI_PLAUSIBLE) return "escala dudosa"
        val t = lectura.temperaturaC ?: return "psi"
        return "$t °C"
    }

    /** 500 ms encendido, 500 ms apagado. Solo para presion bajo el aviso. */
    private fun parpadeo(): Boolean = (System.currentTimeMillis() / 500) % 2 == 0L


    // --- Bateria de litio ---------------------------------------------------

    /**
     * El BMS de litio, arriba de las llantas en la columna del medio.
     *
     * Hoy esto solo puede decir que la bateria ESTA: el barrido BLE por el
     * dongle USB la encuentra y da su MAC y su señal. El voltaje y el SoC
     * viven detras de una conexion GATT que todavia no esta escrita, y
     * mientras no lo este se pintan como huecos.
     *
     * Es deliberado y es lo unico honesto: un "0.0 V" en un tablero de carro
     * significa bateria muerta. Un hueco significa "no lo se todavia". Pintar
     * el primero cuando la verdad es el segundo es la clase de mentira que
     * hace que nadie vuelva a creerle al tablero.
     */
    private fun dibujarBateria(canvas: Canvas, left: Float, ancho: Float, alto: Float, ahora: Long) {
        val margen = ancho * 0.06f
        val x0 = left + margen
        val x1 = left + ancho - margen
        val cx = left + ancho * 0.5f
        val rancia = bateria.rancia(ahora)

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = alto * 0.115f
        canvas.drawText(tituloBateria(ahora), cx, alto * 0.15f, labelPaint)

        // Lo que el dueño pidio ver: **porcentaje y vatios**, los dos grandes y
        // uno a cada lado. El voltaje baja a la linea de abajo — es el dato que
        // menos decide: 13.3 V no dice si la bateria se esta llenando o
        // vaciando, y 520 W si.
        val soc = bateria.soc
        val w = bateria.potenciaW

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = alto * 0.34f
        textPaint.color = if (soc == null || rancia) COLOR_STALE else colorSoc(soc, rancia)
        canvas.drawText(soc?.let { "$it%" } ?: "--%", x0, alto * 0.47f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = when {
            w == null || rancia -> COLOR_STALE
            w > 5f -> COLOR_GREEN
            w < -5f -> COLOR_AMBER
            else -> COLOR_TEXT_DIM
        }
        // El numero se encoge si no cabe: mil y pico vatios son cuatro digitos
        // y un tablero no puede recortar una cifra en silencio.
        val textoW = w?.let { "%+.0f W".format(it) } ?: "-- W"
        textPaint.textSize = alto * 0.34f
        val maximo = ancho * 0.52f
        val medido = textPaint.measureText(textoW)
        if (medido > maximo) textPaint.textSize *= maximo / medido
        canvas.drawText(textoW, x1, alto * 0.47f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Barra del SoC: un porcentaje se entiende de un vistazo como barra.
        val barTop = alto * 0.56f
        val barH = alto * 0.085f
        barPaint.color = COLOR_CAJA
        canvas.drawRoundRect(x0, barTop, x1, barTop + barH, barH / 2, barH / 2, barPaint)
        if (soc != null) {
            barPaint.color = colorSoc(soc, rancia)
            canvas.drawRoundRect(
                x0, barTop, x0 + (x1 - x0) * (soc / 100f).coerceIn(0f, 1f),
                barTop + barH, barH / 2, barH / 2, barPaint,
            )
        }

        // Tercera linea: voltaje, temperatura y sentido, cada uno en su sitio
        // fijo. Antes iban dos textos centrados en la misma altura y se
        // pisaban — ilegibles justo cuando habia datos que leer.
        labelPaint.textSize = alto * 0.115f
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = if (bateria.voltaje == null || rancia) COLOR_STALE else COLOR_TEXT
        canvas.drawText(
            bateria.voltaje?.let { "%.2f V".format(it) } ?: "-- V",
            x0, alto * 0.80f, labelPaint,
        )

        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.color = colorTemperaturaBateria(bateria.temperaturaC, rancia)
        canvas.drawText(
            bateria.temperaturaC?.let { "$it °C" } ?: "-- °C",
            x1, alto * 0.80f, labelPaint,
        )

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = alto * 0.10f
        canvas.drawText(pieBateria(), cx, alto * 0.95f, labelPaint)
    }

    /**
     * La temperatura de una LiFePO4 no es decoracion.
     *
     * Cargar por encima de 45 grados degrada las celdas, y muchos BMS cortan
     * ahi. Se pinta ambar antes y rojo despues para que se vea sin leer.
     */
    private fun colorTemperaturaBateria(t: Int?, rancia: Boolean): Int = when {
        t == null || rancia -> COLOR_STALE
        t >= 45 -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
        t >= 40 -> COLOR_AMBER
        else -> COLOR_TEXT
    }

    /** FIJO, por la misma razon que el de las llantas. */
    private fun tituloBateria(ahora: Long): String = "BATERIA DE LITIO"

    /**
     * Explica POR QUE no hay voltaje, en vez de dejar los guiones mudos.
     *
     * Sin esto, un "-- V" no distingue entre "no encuentro la bateria" y
     * "la encuentro pero aun no se leerla", que son dos problemas con dos
     * soluciones completamente distintas.
     */
    private fun pieBateria(): String {
        // El motivo del fallo NO va aqui: se solapaba con el titulo de las
        // llantas y ademas cambiaba de texto solo. Vive en la linea de estado
        // de abajo, que es un sitio fijo y no pisa a nadie.
        if (!bateria.detectada()) return "sin datos"
        if (bateria.voltaje != null) {
            val celdas = bateria.celdas.size
            return if (celdas > 0) "$celdas celdas · ${bateria.nombre ?: "BMS"}"
            else (bateria.nombre ?: "BMS")
        }
        val señal = bateria.rssi?.let { " · ${it} dBm" } ?: ""
        return "detectada$señal · falta leer el BMS"
    }


    /**
     * Umbrales de una **LiFePO4**, que es lo que resulto ser: 4 celdas a
     * 3.3 V. Importa la quimica: una Li-ion de 4 celdas daria 14.8 V
     * nominales y los mismos umbrales gritarian sin motivo todo el tiempo.
     */
    private fun colorSoc(soc: Int, rancia: Boolean): Int = when {
        rancia -> COLOR_STALE
        soc <= 15 -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
        soc <= 35 -> COLOR_AMBER
        else -> COLOR_GREEN
    }


    // --- Columna libre ------------------------------------------------------

    /**
     * Tercera columna: lo que el carro SI da y no estabamos usando.
     *
     * Salio de preguntarle a la ECU su mapa de PIDs en vez de suponer. No hay
     * nivel de combustible —el bit del PID 0x20 esta en cero, o sea que nada
     * por encima del 0x20 existe en este carro— pero si hay presion de
     * colector, acelerador y avance, que en un atmosferico exprimido dicen
     * bastante mas que un flotador de gasolina.
     */
    private fun dibujarLibre(
        canvas: Canvas, left: Float, ancho: Float, h: Float,
        vtecEnganchado: Boolean,
    ) {
        val ahora = System.currentTimeMillis()
        val margen = ancho * 0.08f
        val x0 = left + margen
        val x1 = left + ancho - margen

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.055f
        canvas.drawText("ADMISION", left + ancho * 0.5f, h * 0.10f, labelPaint)

        // Sin barra. La llevaba porque en un atmosferico el MAP es un
        // vacuometro y se penso que importaba la tendencia, pero el numero ya
        // dice lo mismo y la barra solo metia una cosa mas moviendose. La
        // quito el dueño.
        val map = state.mapKpa
        val mapStale = state.isStale(state.mapAtMs, ahora)
        // En PSI, como lo pidio el dueño. Es presion ABSOLUTA, no manometrica:
        // a esta altitud —1503 m, medidos por el GPS del propio radio— la
        // atmosferica ronda 12,3 PSI, asi que el motor parado marca eso y no
        // cero. Al ralenti baja a ~4 PSI, que es el vacio de admision.
        filaGrande(canvas, x0, x1, h * 0.30f, h, "COLECTOR",
            map?.let { "%.1f PSI".format(it * KPA_A_PSI) } ?: "-- PSI",
            if (mapStale) COLOR_STALE else COLOR_TEXT)

        // ACEITE en vez de ACELERADOR.
        //
        // El acelerador se quito por inutil manejando —el pie ya sabe donde
        // esta— y su sitio se lo lleva lo unico de esta pantalla que sirve
        // para no romper el motor: cuanto le queda al aceite.
        //
        // Se TOCA para cambiar entre kilometros, horas y odometro. Son tres
        // caras del mismo dato y no caben las tres a la vez, pero ninguna
        // sobra: los kilometros son el intervalo de siempre, las horas
        // atrapan el trafico parado —donde el aceite se cocina sin que el
        // odometro avance— y el odometro es lo que se compara con el tablero
        // del carro para reanclar cuando derive.
        dibujarAceite(canvas, x0, x1, h)

        filaGrande(canvas, x0, x1, h * 0.76f, h, "AVANCE",
            state.avanceGrados?.let { "$it °" } ?: "-- °",
            if (state.isStale(state.avanceAtMs, ahora)) COLOR_STALE else COLOR_TEXT)

        // VTEC en vez de CARGA.
        //
        // Aqui vivio primero la VELOCIDAD, que el dueño quito porque ya la
        // tiene en el cuadro original. Luego la CARGA, que tambien quito: es
        // un dato de taller y no dice nada manejando.
        //
        // La carga NO deja de pedirse — es justo lo que decide si el VTEC
        // cuenta como enganchado, y sin ella la deduccion no existe. Lo que
        // se quita es el sitio en pantalla, no la lectura.
        //
        // Y se dicen TRES cosas, no dos. Cuando no hay carga fresca no se
        // puede saber si el VTEC esta o no, y contestar "NO" en ese caso
        // seria afirmar algo que no se sabe. Eso sale como guiones.
        val puedeSaberse = state.loadPct != null && !state.isStale(state.loadAtMs, ahora)
        filaGrande(canvas, x0, x1, h * 0.95f, h, "VTEC",
            when {
                !puedeSaberse -> "--"
                vtecEnganchado -> "SI"
                else -> "NO"
            },
            when {
                !puedeSaberse -> COLOR_STALE
                vtecEnganchado -> COLOR_VTEC_ON
                else -> COLOR_TEXT_DIM
            })
    }

    // --- Motor --------------------------------------------------------------

    /**
     * Agua, aire, carga y voltaje. Formato de vigilancia: chico y quieto.
     *
     * Va aparte del TPMS a proposito, y se pinta aunque el OBD este muerto:
     * antes todo el tablero colgaba del enlace OBD, asi que sin adaptador no
     * habia NADA en pantalla. Con dos fuentes independientes, las llantas se
     * ven aunque el motor no conteste — que es exactamente el caso hoy.
     */
    private fun dibujarMotor(canvas: Canvas, left: Float, ancho: Float, h: Float, ahora: Long) {
        val margen = ancho * 0.08f
        val x0 = left + margen
        val x1 = left + ancho - margen

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.055f
        // Rojo fijo, NO parpadeando. Un titulo que titila es ruido; el color
        // ya dice todo lo que hay que decir y se queda quieto.
        labelPaint.color = when {
            state.milEncendida -> COLOR_REDLINE
            state.codigosGuardados > 0 -> COLOR_AMBER
            else -> COLOR_TEXT_DIM
        }
        canvas.drawText(tituloMotor(), left + ancho * 0.5f, h * 0.10f, labelPaint)
        labelPaint.color = COLOR_TEXT_DIM

        // El agua va SIN barra y con el numero coloreado, por peticion del
        // dueño. Una barra obliga a estimar la posicion y ademas ocupa sitio;
        // un numero de color da el valor exacto y el estado a la vez, que es
        // justo lo que se necesita de reojo.
        val c = state.coolantC
        val aguaStale = state.isStale(state.coolantAtMs, ahora)
        filaGrande(canvas, x0, x1, h * 0.30f, h, "AGUA",
            c?.let { "$it °C" } ?: "-- °C", colorAgua(c, aguaStale))

        filaGrande(canvas, x0, x1, h * 0.52f, h, "AIRE",
            state.iatC?.let { "$it °C" } ?: "-- °C",
            if (state.isStale(state.iatAtMs, ahora)) COLOR_STALE else COLOR_TEXT)

        // MEZCLA como PORCENTAJE REAL, no como palabra.
        //
        // Y el numero NO sale de la sonda. La sonda de este carro es de banda
        // estrecha (0114): un voltaje que solo dice de que lado de la
        // estequiometrica esta, y sacarle un porcentaje seria inventarlo. El
        // AFR de banda ancha (0134) no existe aqui — el mapa de PIDs de esta
        // ECU se corta en el 0x20.
        //
        // Lo que SI es un porcentaje real y medido es la suma de los dos
        // ajustes de combustible: cuanto esta corrigiendo la centralita sobre
        // la inyeccion base para mantener la mezcla donde quiere. Cero es
        // perfecto. POSITIVO significa que mete gasolina de mas porque lee
        // POBRE; negativo, que la quita porque lee RICA.
        //
        // Es el mismo dato que usa cualquier taller para diagnosticar mezcla,
        // y a diferencia del voltaje de la sonda tiene unidades honestas.
        val mezcla = totalAjuste()
        filaGrande(canvas, x0, x1, h * 0.74f, h, "MEZCLA",
            mezcla?.let { "%+d %%".format(it) } ?: "-- %",
            // La edad que manda es la del sumando MAS VIEJO. Mirando solo la
            // del corto, un largo de hace cinco segundos se pintaba con el
            // mismo color vivo que un dato de ahora: el corto se pide en los
            // turnos 21 y 42 y el largo en el 12 y el 48, asi que casi nunca
            // son del mismo instante y los dos huecos del largo (~4,8 s y
            // ~3,2 s) pasan de los 3 s de STALE_AFTER_MS. Sumar dos momentos
            // distintos y pintarlos como una medida es inventar el numero.
            colorMezcla(mezcla, state.isStale(
                minOf(state.trimCortoAtMs, state.trimLargoAtMs), ahora)))

        // El voltaje del puerto OBD es el del sistema con el motor en marcha,
        // o sea lo que da el ALTERNADOR. Se llama por su nombre para que no se
        // confunda con la bateria de litio, que tiene su propia columna.
        filaGrande(canvas, x0, x1, h * 0.93f, h, "ALTERNADOR",
            state.batteryV?.let { String.format("%.1f V", it) } ?: "-- V",
            colorAlternador(state.batteryV, state.isStale(state.batteryAtMs, ahora)))
    }

    /**
     * Rica, pobre, o en lazo cerrado.
     *
     * Una sonda de banda estrecha oscila alrededor de 0.45 V cuando la ECU
     * esta corrigiendo bien; los extremos son los topes del sensor. Asi que
     * lo unico honesto que se puede decir es de que lado esta, y si esta
     * oscilando, que es lo que se quiere ver.
     */
    /**
     * La correccion total de mezcla: corto mas largo, en por ciento.
     *
     * Se suman porque lo que importa para juzgar la mezcla es cuanto se esta
     * desviando la centralita EN TOTAL de su inyeccion base. Que la
     * correccion venga de la parte que oscila o de la ya aprendida es una
     * distincion util para diagnosticar, y por eso las dos siguen viendose
     * por separado abajo — pero no para mirar de reojo si el motor va bien.
     */
    private fun totalAjuste(): Int? {
        // Los DOS o ninguno. Rellenar con un cero el que falte no es
        // conservador: convierte "no tengo esa mitad" en "esa mitad no esta
        // corrigiendo nada", que es la respuesta contraria. Si el 0107 se cae
        // de la rotacion —PollScheduler saca un PID tras tres fallos
        // seguidos— un motor con el corto en +3% y el largo en +22% se
        // pintaba "+3 %" en VERDE, o sea mezcla perfecta, con la centralita
        // al limite de lo que puede corregir. Y desde que el AJUSTE dejo de
        // pintarse aparte, esta fila es el unico sitio donde el dueño ve los
        // ajustes: si falta la mitad del numero, no hay donde notarlo.
        val c = state.trimCortoPct ?: return null
        val l = state.trimLargoPct ?: return null
        return c + l
    }

    /**
     * Rojo POBRE, verde bien, ambar RICA — como lo pidio el dueño.
     *
     * El corte esta en +-10%, que es donde cualquier taller empieza a mirar,
     * y en +-25% se pone mas fuerte porque ahi la centralita se esta quedando
     * sin margen de correccion y la luz de averia esta cerca.
     *
     * Pobre es el lado peligroso y por eso se lleva el rojo: una mezcla pobre
     * sube la temperatura de combustion. Rica ensucia y gasta, pero no funde
     * nada — de ahi el ambar.
     */
    private fun colorMezcla(total: Int?, stale: Boolean): Int = when {
        total == null || stale -> COLOR_STALE
        total >= 10 -> COLOR_REDLINE      // pobre: la ECU mete gasolina de mas
        total <= -10 -> COLOR_AMBER       // rica: la ECU esta quitando
        else -> COLOR_GREEN
    }

    /**
     * Color del voltaje del alternador.
     *
     * Por debajo de 13 V con el motor en marcha, no esta cargando. Por encima
     * de 15 V el regulador se paso, y eso mata baterias — sobre todo de litio.
     */
    private fun colorAlternador(v: Float?, stale: Boolean): Int = when {
        v == null || stale -> COLOR_STALE
        v >= 15.0f -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
        v >= 13.2f -> COLOR_GREEN
        v >= 12.4f -> COLOR_AMBER
        else -> COLOR_REDLINE
    }

    /** Etiqueta a la izquierda, valor grande y de color a la derecha. */
    private fun filaGrande(
        canvas: Canvas, x0: Float, x1: Float, y: Float, h: Float,
        etiqueta: String, valor: String, color: Int,
    ) {
        // El VALOR se mide primero, porque es el que manda.
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = color
        textPaint.textSize = h * 0.125f
        val anchoValor = textPaint.measureText(valor)

        // La etiqueta cede si no cabe; el numero nunca.
        //
        // A tamaño fijo, "ALTERNADOR" y "13,0 V" se pisaban uno encima del
        // otro y la fila quedaba ilegible — justo la que avisa de que el
        // alternador no esta cargando. El numero es el dato; la palabra solo
        // dice de que es, asi que encoger la palabra no pierde nada.
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.075f
        val disponible = (x1 - anchoValor - h * 0.03f) - x0
        if (disponible > 0f) {
            val anchoEtiqueta = labelPaint.measureText(etiqueta)
            if (anchoEtiqueta > disponible) {
                // Con suelo: por debajo de esto no se lee de reojo, y una
                // etiqueta ilegible es tan inutil como una tapada.
                labelPaint.textSize =
                    (h * 0.075f * (disponible / anchoEtiqueta)).coerceAtLeast(h * 0.044f)
            }
        }
        canvas.drawText(etiqueta, x0, y, labelPaint)

        canvas.drawText(valor, x1, y + h * 0.012f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        labelPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * Azul, verde, ambar, rojo — la escala que pidio el dueño.
     *
     * Los cortes son los del F20C: el termostato abre sobre 82, la
     * temperatura de trabajo se asienta entre 85 y 95, a 100 el ventilador ya
     * deberia estar corriendo, y 105 es problema.
     */
    private fun colorAgua(c: Int?, stale: Boolean): Int = when {
        c == null || stale -> COLOR_STALE
        c >= EngineConstants.COOLANT_HIGH_C -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
        c >= EngineConstants.COOLANT_AVISO_C -> COLOR_AMBER
        c >= EngineConstants.COOLANT_TIBIO_C -> COLOR_GREEN
        else -> COLOR_COLD
    }

    /** El estado del OBD, que ya no ocupa una insignia aparte. */
    /**
     * FIJO — salvo por una cosa que SI merece salirse: una averia guardada.
     *
     * El protocolo, el "conectando" y el "sin enlace" se fueron a la linea de
     * estado de abajo. Un codigo de averia se queda: no es estado del enlace,
     * es estado del CARRO, y es lo primero que hay que ver al mirar esta
     * columna. Ademas no oscila — o hay codigo o no lo hay.
     */
    private fun tituloMotor(): String =
        if (state.milEncendida || state.codigosGuardados > 0) {
            "MOTOR · AVERIA (${state.codigosGuardados})"
        } else "MOTOR"

    /** Etiqueta a la izquierda, valor a la derecha, en la misma linea. */
    private fun fila(
        canvas: Canvas, x0: Float, x1: Float, y: Float, h: Float,
        etiqueta: String, valor: String, stale: Boolean, color: Int,
    ) {
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.070f
        canvas.drawText(etiqueta, x0, y, labelPaint)

        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.color = if (stale) COLOR_STALE else color
        labelPaint.textSize = h * 0.095f
        canvas.drawText(valor, x1, y, labelPaint)

        labelPaint.textAlign = Paint.Align.CENTER
    }

    private companion object {
        // Paleta oscura: es un tablero para manejar, casi siempre de noche o
        // con sol directo. El fondo negro maximiza el contraste en ambos.
        const val COLOR_BG = 0xFF07090C.toInt()
        const val COLOR_CAJA = 0xFF141A21.toInt()
        const val COLOR_CAJA_AVISO = 0xFF23161A.toInt()
        const val COLOR_SILUETA = 0xFF1B2129.toInt()
        const val COLOR_SILUETA_TEXTO = 0xFF3A4550.toInt()
        const val COLOR_TEXT = Color.WHITE
        const val COLOR_TEXT_DIM = 0xFF8A96A3.toInt()
        const val COLOR_STALE = 0xFF5A6470.toInt()
        const val COLOR_GREEN = 0xFF35D07F.toInt()
        const val COLOR_AMBER = 0xFFFFB020.toInt()
        const val COLOR_REDLINE = 0xFFFF3B30.toInt()
        const val COLOR_VTEC_ON = 0xFF00C2FF.toInt()

        /**
         * Rojo del fondo cuando engancha el VTEC.
         *
         * Oscuro a proposito, no un rojo puro. Esto pasa a 5850 rpm con el
         * pedal a fondo —o sea de noche tambien— y un fondo saturado a
         * pantalla completa deslumbra y borra los numeros justo en el
         * momento en que el motor esta trabajando al maximo. Asi tiñe lo
         * suficiente para que el ojo lo cace de reojo sin cegar ni tapar
         * nada.
         *
         * Parpadea al ritmo de [parpadeo] (500 ms). No mas rapido: el
         * tablero repinta entre 5 y 1 cuadros por segundo segun lo caliente
         * que este el radio, y un parpadeo mas corto que dos cuadros no se
         * veria como parpadeo sino como ruido.
         */
        const val COLOR_VTEC_FONDO = 0xFF5A0000.toInt()
        const val COLOR_COLD = 0xFF3D8BFF.toInt()

        /** Lo que hay que sostener la X para que el tablero se cierre. */
        const val MS_PULSACION_LARGA = 600L

        /** Cuanto se sostiene el aviso de VTEC tras soltarlo. */
        const val MS_VTEC_VISIBLE = 2_000L

        /** 1 kPa son 0,145038 PSI. */
        const val KPA_A_PSI = 0.145038f

        /** Lo que hay que sostener el aceite para que pregunte si reiniciar. */
        const val MS_RESET_ACEITE = 5_000L

        /**
         * 200 ms entre cuadros: 5 por segundo.
         *
         * Suficiente para que el parpadeo de media segundo se vea limpio, y
         * doce veces menos trabajo que los 60 fps que tenia antes.
         */
        const val MS_ENTRE_CUADROS = 200L
    }
}
