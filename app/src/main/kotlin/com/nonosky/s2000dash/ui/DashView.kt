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
        canvas.drawColor(COLOR_BG)

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
        dibujarLibre(canvas, col * 2f, col, h)

        // Separadores tenues: tres columnas sin linea se leen como un solo
        // amontonamiento, sobre todo de reojo.
        trazoPaint.color = COLOR_SILUETA
        trazoPaint.strokeWidth = h * 0.004f
        canvas.drawLine(col, h * 0.06f, col, h * 0.94f, trazoPaint)
        canvas.drawLine(col * 2f, h * 0.06f, col * 2f, h * 0.94f, trazoPaint)

        dibujarSaludDelRadio(canvas, w, h)
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
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.048f
        canvas.drawText(tituloLlantas(ahora), left + ancho * 0.5f, top + h * 0.055f, labelPaint)

        // Silueta del carro: un rectangulo redondeado tenue detras de las
        // cuatro cajas. No decora — es lo que dice que arriba es adelante.
        val margenX = ancho * 0.13f
        val topRejilla = top + h * 0.085f
        val altoRejilla = h * 0.93f - topRejilla
        caja.set(left + margenX, topRejilla, left + ancho - margenX, topRejilla + altoRejilla)
        trazoPaint.color = COLOR_SILUETA
        trazoPaint.strokeWidth = h * 0.006f
        canvas.drawRoundRect(caja, ancho * 0.10f, ancho * 0.10f, trazoPaint)

        // "ADELANTE" arriba: sin esto, un tablero de cuatro numeros no dice
        // por si solo cual esquina es cual.
        labelPaint.textSize = h * 0.036f
        labelPaint.color = COLOR_SILUETA_TEXTO
        canvas.drawText("ADELANTE", left + ancho * 0.5f, topRejilla + h * 0.045f, labelPaint)

        val anchoCelda = ancho * 0.36f
        val altoCelda = altoRejilla * 0.36f
        val xIzq = left + ancho * 0.30f
        val xDer = left + ancho * 0.70f
        val yArriba = topRejilla + altoRejilla * 0.33f
        val yAbajo = topRejilla + altoRejilla * 0.76f

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
    private fun tituloLlantas(ahora: Long): String = when (enlaceTpms) {
        EnlaceTpms.SinReceptor -> "LLANTAS — sin receptor USB"
        EnlaceTpms.SinPermiso -> "LLANTAS — sin permiso USB"
        EnlaceTpms.Abriendo -> "LLANTAS — conectando receptor"
        EnlaceTpms.Leyendo ->
            if (tpms.ruedas.isEmpty()) "LLANTAS — esperando sensores"
            else "LLANTAS · psi (placa ${Escalas.PSI_PLACA.toInt()})"
        EnlaceTpms.Fallo -> "LLANTAS — el receptor no responde"
    }

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

        // Etiqueta de la esquina, discreta: la posicion ya lo dice, esto solo
        // confirma para quien mira por primera vez.
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = alto * 0.20f
        canvas.drawText(rueda.corta, caja.left + ancho * 0.06f, caja.top + alto * 0.26f, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER

        textPaint.color = color
        textPaint.textSize = alto * 0.50f
        val texto = psi?.let { String.format("%.0f", it) } ?: "--"
        canvas.drawText(texto, cx, cy + alto * 0.16f, textPaint)

        labelPaint.color = if (rancia) COLOR_STALE else COLOR_TEXT_DIM
        labelPaint.textSize = alto * 0.16f
        canvas.drawText(pieDeRueda(lectura, rancia, ahora), cx, caja.bottom - alto * 0.09f, labelPaint)
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

    private fun tituloBateria(ahora: Long): String = when (bateria.enlace) {
        EnlaceBateria.SinDongle -> "BATERIA — sin dongle USB"
        EnlaceBateria.DongleMudo -> "BATERIA — el dongle no contesta"
        EnlaceBateria.Buscando -> "BATERIA — buscando"
        EnlaceBateria.Detectada ->
            if (bateria.rancia(ahora)) "BATERIA — se dejo de oir" else "BATERIA DE LITIO"
        EnlaceBateria.Leyendo -> "BATERIA DE LITIO · en linea"
        EnlaceBateria.Fallo -> "BATERIA — fallo el dongle"
    }

    /**
     * Explica POR QUE no hay voltaje, en vez de dejar los guiones mudos.
     *
     * Sin esto, un "-- V" no distingue entre "no encuentro la bateria" y
     * "la encuentro pero aun no se leerla", que son dos problemas con dos
     * soluciones completamente distintas.
     */
    private fun pieBateria(): String {
        if (!bateria.detectada()) return bateria.detalle ?: "no localizada"
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
    private fun dibujarLibre(canvas: Canvas, left: Float, ancho: Float, h: Float) {
        val ahora = System.currentTimeMillis()
        val margen = ancho * 0.08f
        val x0 = left + margen
        val x1 = left + ancho - margen

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.055f
        canvas.drawText("ADMISION", left + ancho * 0.5f, h * 0.10f, labelPaint)

        // MAP con barra: en un atmosferico es un vacuometro, y lo que se mira
        // ahi es la TENDENCIA —cuanto esta pidiendo el motor— no la cifra.
        val map = state.mapKpa
        val mapStale = state.isStale(state.mapAtMs, ahora)
        filaGrande(canvas, x0, x1, h * 0.30f, h, "COLECTOR",
            map?.let { "$it kPa" } ?: "-- kPa",
            if (mapStale) COLOR_STALE else COLOR_TEXT)

        val barTop = h * 0.345f
        val barH = h * 0.045f
        barPaint.color = COLOR_CAJA
        canvas.drawRoundRect(x0, barTop, x1, barTop + barH, barH / 2, barH / 2, barPaint)
        if (map != null) {
            // De 20 a 105 kPa: por debajo es vacio de retencion y por encima
            // ya no sube un atmosferico. Mas rango solo aplastaria la escala.
            val t = ((map - 20f) / 85f).coerceIn(0f, 1f)
            barPaint.color = if (mapStale) COLOR_STALE else COLOR_GREEN
            canvas.drawRoundRect(x0, barTop, x0 + (x1 - x0) * t, barTop + barH, barH / 2, barH / 2, barPaint)
        }

        val ace = state.aceleradorPct
        filaGrande(canvas, x0, x1, h * 0.55f, h, "ACELERADOR",
            ace?.let { "$it %" } ?: "-- %",
            if (state.isStale(state.aceleradorAtMs, ahora)) COLOR_STALE else COLOR_TEXT)

        filaGrande(canvas, x0, x1, h * 0.76f, h, "AVANCE",
            state.avanceGrados?.let { "$it °" } ?: "-- °",
            if (state.isStale(state.avanceAtMs, ahora)) COLOR_STALE else COLOR_TEXT)

        filaGrande(canvas, x0, x1, h * 0.95f, h, "VELOCIDAD",
            state.speedKmh?.let { "$it" } ?: "--",
            if (state.isStale(state.speedAtMs, ahora)) COLOR_STALE else COLOR_TEXT)
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
        canvas.drawText(tituloMotor(), left + ancho * 0.5f, h * 0.10f, labelPaint)

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

        // MEZCLA desde la sonda que el carro SI expone.
        //
        // Se pedia el AFR de banda ancha (PID 0134) y siempre salia vacio: el
        // mapa de PIDs de esta ECU dice que no soporta nada por encima del
        // 0x20. Lo que si hay es el voltaje de la sonda (0114), que es de
        // banda estrecha y **no da un AFR**: solo de que lado de la
        // estequiometrica esta. Se muestra como RICA/POBRE, que es lo que esa
        // señal puede decir de verdad — poner un numero seria inventarlo.
        val o2 = state.o2Voltaje
        filaGrande(canvas, x0, x1, h * 0.74f, h, "MEZCLA",
            textoMezcla(o2), colorO2(o2, state.isStale(state.o2AtMs, ahora)))

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
    private fun textoMezcla(v: Float?): String = when {
        v == null -> "--"
        v >= 0.60f -> "RICA"
        v <= 0.30f -> "POBRE"
        else -> "OK"
    }

    private fun colorO2(v: Float?, stale: Boolean): Int = when {
        v == null || stale -> COLOR_STALE
        v <= 0.20f -> if (parpadeo()) COLOR_REDLINE else COLOR_AMBER
        v >= 0.70f -> COLOR_AMBER
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
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.075f
        canvas.drawText(etiqueta, x0, y, labelPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = color
        textPaint.textSize = h * 0.125f
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
    private fun tituloMotor(): String = when (state.connection) {
        ConnectionState.Polling -> "MOTOR · " + (state.protocol?.take(18) ?: "en linea")
        ConnectionState.Initializing -> "MOTOR — iniciando"
        ConnectionState.Connecting -> "MOTOR — conectando"
        ConnectionState.SinAdaptador -> "MOTOR — sin adaptador"
        ConnectionState.BluetoothApagado -> "MOTOR — Bluetooth apagado"
        ConnectionState.Disconnected -> "MOTOR — sin enlace"
    }

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
        const val COLOR_COLD = 0xFF3D8BFF.toInt()

        /**
         * 200 ms entre cuadros: 5 por segundo.
         *
         * Suficiente para que el parpadeo de media segundo se vea limpio, y
         * doce veces menos trabajo que los 60 fps que tenia antes.
         */
        const val MS_ENTRE_CUADROS = 200L
    }
}
