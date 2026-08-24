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
     * Se sigue repintando en bucle aunque ya no haya aguja que animar: el
     * parpadeo de una presion en alarma y el paso de un dato a "rancio"
     * ocurren con el tiempo, no con la llegada de datos. Sin bucle, una
     * llanta se quedaria pintada como fresca para siempre.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
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

        postInvalidateOnAnimation()
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

    /** Reservada. Vacia a proposito, y dicho para que no parezca un fallo. */
    private fun dibujarLibre(canvas: Canvas, left: Float, ancho: Float, h: Float) {
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = COLOR_SILUETA_TEXTO
        labelPaint.textSize = h * 0.048f
        canvas.drawText("LIBRE", left + ancho * 0.5f, h * 0.50f, labelPaint)
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

        val c = state.coolantC
        val aguaStale = state.isStale(state.coolantAtMs, ahora)
        fila(canvas, x0, x1, h * 0.26f, h, "AGUA", c?.let { "$it °C" } ?: "-- °C", aguaStale,
            colorAgua(c, aguaStale))

        // El agua lleva barra ademas del numero: es el unico dato del motor
        // donde importa la tendencia y no solo el valor.
        val barTop = h * 0.305f
        val barH = h * 0.045f
        barPaint.color = COLOR_CAJA
        canvas.drawRoundRect(x0, barTop, x1, barTop + barH, barH / 2, barH / 2, barPaint)
        if (c != null) {
            val t = ((c - 40f) / 80f).coerceIn(0f, 1f)
            barPaint.color = colorAgua(c, aguaStale)
            canvas.drawRoundRect(x0, barTop, x0 + (x1 - x0) * t, barTop + barH, barH / 2, barH / 2, barPaint)
        }

        fila(canvas, x0, x1, h * 0.50f, h, "AIRE",
            state.iatC?.let { "$it °C" } ?: "-- °C",
            state.isStale(state.iatAtMs, ahora), COLOR_TEXT)
        fila(canvas, x0, x1, h * 0.65f, h, "CARGA",
            state.loadPct?.let { "$it %" } ?: "-- %",
            state.isStale(state.loadAtMs, ahora), COLOR_TEXT)
        // Este voltaje es el que da el propio adaptador OBD con ATRV: es el
        // del sistema electrico, medido en el puerto. No es el BMS — ese va en
        // su columna. Se llama SISTEMA para que nadie confunda los dos.
        fila(canvas, x0, x1, h * 0.80f, h, "SISTEMA",
            state.batteryV?.let { String.format("%.1f V", it) } ?: "-- V",
            state.isStale(state.batteryAtMs, ahora), COLOR_TEXT)
    }

    private fun colorAgua(c: Int?, stale: Boolean): Int = when {
        c == null || stale -> COLOR_STALE
        c >= EngineConstants.COOLANT_HIGH_C -> COLOR_REDLINE
        c >= EngineConstants.COOLANT_NORMAL_C -> COLOR_GREEN
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
    }
}
