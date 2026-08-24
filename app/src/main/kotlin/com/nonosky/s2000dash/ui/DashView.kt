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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * El tablero entero, dibujado a mano en un [Canvas].
 *
 * Canvas y no Compose por decision de diseño (§4): el head unit trae una CPU
 * debil (Rockchip rk3326) y esto tiene que sostener 60 fps sin pelear.
 *
 * No conoce Bluetooth ni OBD: recibe un [VehicleState] y lo pinta.
 */
class DashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // --- Estado -------------------------------------------------------------

    private var state = VehicleState()

    /** Valor suavizado que realmente se dibuja. Ver §8: la aguja no salta. */
    private var displayedRpm = 0f
    private var lastFrameNs = 0L

    fun setState(newState: VehicleState) {
        state = newState
        // No invalidamos aqui: el bucle de animacion ya corre a 60 fps y
        // repintar dos veces por muestra solo gasta CPU.
    }

    // --- Pinceles (asignados una sola vez; onDraw no aloca) -----------------

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dialRect = RectF()

    /** Reutilizado cada frame: `onDraw` no debe alocar en una CPU debil. */
    private val needlePath = android.graphics.Path()

    // --- Geometria del tacometro -------------------------------------------

    /** Barrido clasico: empieza abajo-izquierda y termina abajo-derecha. */
    private val startAngleDeg = 150f
    private val sweepDeg = 240f

    // --- Bucle de animacion -------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNs = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val nowNs = System.nanoTime()
        val dtMs = if (lastFrameNs == 0L) 16f else (nowNs - lastFrameNs) / 1_000_000f
        lastFrameNs = nowNs
        advanceNeedle(dtMs)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(COLOR_BG)

        // Tres columnas. La pantalla del radio es una barra de 2.67:1, asi
        // que apilar todo a la derecha del tacometro dejaba media pantalla
        // vacia y amontonaba la velocidad con la temperatura. Repartido en
        // columnas cada dato tiene su sitio y se lee de un vistazo.
        //
        //   [ tacometro ] [   velocidad   ] [ agua / aire / carga / bateria ]
        //
        // Las proporciones son relativas al viewport: el mismo codigo sirve
        // para 800x480 o 1024x600 sin tocar nada.
        val dialSize = min(h * 0.98f, w * 0.38f)
        val cx = dialSize * 0.50f
        val cy = h * 0.5f
        val radius = dialSize * 0.45f

        drawTachometer(canvas, cx, cy, radius)

        val midLeft = dialSize
        val restante = w - midLeft
        val midWidth = restante * 0.55f
        val rightLeft = midLeft + midWidth
        val rightWidth = w - rightLeft

        drawConnectionBadge(canvas, midLeft, restante, h)
        drawSpeed(canvas, midLeft, midWidth, h)
        drawRightColumn(canvas, rightLeft, rightWidth, h)

        postInvalidateOnAnimation()
    }

    /**
     * Amortiguamiento exponencial hacia el valor objetivo.
     *
     * Independiente de la duracion del frame: si se pierde un frame, la
     * aguja avanza lo que le corresponde y no se atora.
     */
    private fun advanceNeedle(dtMs: Float) {
        val target = (state.rpm ?: 0).toFloat()
        val alpha = 1f - exp(-dtMs / EngineConstants.NEEDLE_TAU_MS)
        displayedRpm += (target - displayedRpm) * alpha.coerceIn(0f, 1f)
    }

    // --- Tacometro ----------------------------------------------------------

    private fun drawTachometer(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val ringWidth = radius * 0.16f
        dialRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Canaleta de fondo: marca el recorrido completo aunque no haya dato.
        arcPaint.strokeWidth = ringWidth
        arcPaint.color = COLOR_TRACK
        canvas.drawArc(dialRect, startAngleDeg, sweepDeg, false, arcPaint)

        // 2. Banda VTEC: siempre dibujada, tenue. Marca DONDE vive el VTEC.
        //    Cuando engancha se ilumina — el resaltado dice CUANDO (§6.6).
        val vtecOn = state.vtecActive
        arcPaint.color = if (vtecOn) COLOR_VTEC_ON else COLOR_VTEC_IDLE
        arcPaint.strokeWidth = if (vtecOn) ringWidth else ringWidth * 0.55f
        canvas.drawArc(
            dialRect,
            angleFor(EngineConstants.RPM_VTEC),
            sweepFor(EngineConstants.RPM_VTEC, EngineConstants.RPM_MAX),
            false,
            arcPaint,
        )

        // 3. Zona roja: siempre pintada en la carátula.
        arcPaint.color = COLOR_REDLINE_BAND
        arcPaint.strokeWidth = ringWidth
        canvas.drawArc(
            dialRect,
            angleFor(EngineConstants.RPM_REDLINE),
            sweepFor(EngineConstants.RPM_REDLINE, EngineConstants.RPM_MAX),
            false,
            arcPaint,
        )

        // 4. Arco de valor, coloreado por el umbral de cambio. El shift light
        //    no es una luz aparte: es este color, que se capta con vision
        //    periferica sin mover la mirada del camino (§6.6).
        val rpmStale = state.isStale(state.rpmAtMs, System.currentTimeMillis())
        val shown = displayedRpm.coerceIn(0f, EngineConstants.RPM_MAX.toFloat())
        if (shown > 0f) {
            arcPaint.color = if (rpmStale) COLOR_STALE else shiftColor(shown)
            arcPaint.strokeWidth = ringWidth
            canvas.drawArc(dialRect, startAngleDeg, sweepFor(0, shown.toInt()), false, arcPaint)
        }

        // 5. Marcas y numeros cada 1000 rpm.
        drawTicks(canvas, cx, cy, radius, ringWidth)

        // 6. Maximo de la sesion: una marca fina que se queda donde llego.
        if (state.sessionMaxRpm > 0) {
            tickPaint.color = COLOR_SESSION_MAX
            tickPaint.strokeWidth = radius * 0.028f
            val a = Math.toRadians(angleFor(state.sessionMaxRpm).toDouble())
            canvas.drawLine(
                cx + (radius - ringWidth * 1.35f) * cos(a).toFloat(),
                cy + (radius - ringWidth * 1.35f) * sin(a).toFloat(),
                cx + (radius + ringWidth * 0.22f) * cos(a).toFloat(),
                cy + (radius + ringWidth * 0.22f) * sin(a).toFloat(),
                tickPaint,
            )
        }

        drawNeedle(canvas, cx, cy, radius, ringWidth, shown, rpmStale)

        // 7. Lectura numerica al centro: confirma lo que dice la aguja.
        textPaint.color = if (rpmStale) COLOR_STALE else COLOR_TEXT
        textPaint.textSize = radius * 0.36f
        canvas.drawText(state.rpm?.toString() ?: "----", cx, cy + radius * 0.50f, textPaint)
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = radius * 0.13f
        canvas.drawText("RPM", cx, cy + radius * 0.66f, labelPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, ringWidth: Float) {
        labelPaint.textSize = radius * 0.15f
        val inner = radius - ringWidth * 1.15f
        for (rpm in 0..EngineConstants.RPM_MAX step 1000) {
            val a = Math.toRadians(angleFor(rpm).toDouble())
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()

            tickPaint.color = if (rpm >= EngineConstants.RPM_REDLINE) COLOR_REDLINE else COLOR_TEXT_DIM
            tickPaint.strokeWidth = radius * 0.022f
            canvas.drawLine(
                cx + (inner - radius * 0.06f) * cosA,
                cy + (inner - radius * 0.06f) * sinA,
                cx + inner * cosA,
                cy + inner * sinA,
                tickPaint,
            )

            val labelR = inner - radius * 0.20f
            labelPaint.color = if (rpm >= EngineConstants.RPM_REDLINE) COLOR_REDLINE else COLOR_TEXT_DIM
            canvas.drawText(
                (rpm / 1000).toString(),
                cx + labelR * cosA,
                cy + labelR * sinA + labelPaint.textSize * 0.35f,
                labelPaint,
            )
        }
    }

    private fun drawNeedle(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        ringWidth: Float, shown: Float, stale: Boolean,
    ) {
        val a = Math.toRadians(angleFor(shown.toInt()).toDouble())
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        val tip = radius - ringWidth * 1.25f
        val tail = radius * 0.13f
        // La aguja se dibuja como un triangulo delgado: ancha en el pivote y
        // en punta afuera, para que se lea de reojo.
        val halfBase = radius * 0.035f
        val perpX = -sinA * halfBase
        val perpY = cosA * halfBase

        needlePaint.color = if (stale) COLOR_STALE else COLOR_NEEDLE
        needlePath.rewind()
        needlePath.moveTo(cx + tip * cosA, cy + tip * sinA)
        needlePath.lineTo(cx - tail * cosA + perpX, cy - tail * sinA + perpY)
        needlePath.lineTo(cx - tail * cosA - perpX, cy - tail * sinA - perpY)
        needlePath.close()
        canvas.drawPath(needlePath, needlePaint)
        canvas.drawCircle(cx, cy, radius * 0.075f, needlePaint)
        needlePaint.color = COLOR_BG
        canvas.drawCircle(cx, cy, radius * 0.038f, needlePaint)
    }

    /**
     * Verde, ambar, rojo — y parpadeo cerca del corte de combustible, que es
     * el unico momento donde el parpadeo se gana el costo de distraer.
     */
    private fun shiftColor(rpm: Float): Int = when {
        rpm >= EngineConstants.RPM_FUEL_CUT - 300 -> {
            val on = (System.currentTimeMillis() / 90) % 2 == 0L
            if (on) COLOR_REDLINE else COLOR_AMBER
        }
        rpm >= EngineConstants.RPM_REDLINE -> COLOR_REDLINE
        rpm >= EngineConstants.RPM_SHIFT_AMBER -> COLOR_AMBER
        else -> COLOR_GREEN
    }

    private fun angleFor(rpm: Int): Float =
        startAngleDeg + sweepDeg * (rpm.toFloat() / EngineConstants.RPM_MAX).coerceIn(0f, 1f)

    private fun sweepFor(fromRpm: Int, toRpm: Int): Float =
        sweepDeg * ((toRpm - fromRpm).toFloat() / EngineConstants.RPM_MAX).coerceIn(0f, 1f)

    // --- Columna central: velocidad ----------------------------------------

    private fun drawSpeed(canvas: Canvas, left: Float, ancho: Float, h: Float) {
        val cx = left + ancho * 0.5f
        val stale = state.isStale(state.speedAtMs, System.currentTimeMillis())

        textPaint.color = if (stale) COLOR_STALE else COLOR_TEXT
        // El numero se mide antes de pintarlo y se encoge si tres digitos no
        // caben en la columna. En una pantalla mas angosta que la del radio
        // se recortaria en silencio, y un tablero no puede mentir por recorte.
        textPaint.textSize = h * 0.46f
        val texto = state.speedKmh?.toString() ?: "--"
        val maximo = ancho * 0.92f
        val medido = textPaint.measureText(texto)
        if (medido > maximo) textPaint.textSize = textPaint.textSize * (maximo / medido)

        canvas.drawText(texto, cx, h * 0.62f, textPaint)

        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.085f
        canvas.drawText("km/h", cx, h * 0.75f, labelPaint)
    }

    // --- Columna derecha: agua, aire, carga, bateria -----------------------

    private fun drawRightColumn(canvas: Canvas, left: Float, ancho: Float, h: Float) {
        val now = System.currentTimeMillis()
        val margen = ancho * 0.06f
        val x0 = left + margen
        val x1 = left + ancho - margen

        // El agua lleva barra ademas del numero: es el unico dato donde
        // importa la tendencia y no solo el valor.
        val c = state.coolantC
        val aguaStale = state.isStale(state.coolantAtMs, now)
        drawRow(canvas, x0, x1, h * 0.20f, h, "AGUA", c?.let { "$it °C" } ?: "-- °C", aguaStale)

        val barTop = h * 0.245f
        val barH = h * 0.055f
        barPaint.color = COLOR_TRACK
        canvas.drawRoundRect(x0, barTop, x1, barTop + barH, barH / 2, barH / 2, barPaint)
        if (c != null) {
            // Escala util: de 40 a 120 °C. Debajo el motor esta frio y arriba
            // ya es problema; mas resolucion no ayudaria a decidir nada.
            val t = ((c - 40f) / 80f).coerceIn(0f, 1f)
            barPaint.color = when {
                aguaStale -> COLOR_STALE
                c >= EngineConstants.COOLANT_HIGH_C -> COLOR_REDLINE
                c >= EngineConstants.COOLANT_NORMAL_C -> COLOR_GREEN
                else -> COLOR_COLD
            }
            canvas.drawRoundRect(x0, barTop, x0 + (x1 - x0) * t, barTop + barH, barH / 2, barH / 2, barPaint)
        }

        drawRow(canvas, x0, x1, h * 0.48f, h, "AIRE",
            state.iatC?.let { "$it °C" } ?: "-- °C", state.isStale(state.iatAtMs, now))
        drawRow(canvas, x0, x1, h * 0.65f, h, "CARGA",
            state.loadPct?.let { "$it %" } ?: "-- %", state.isStale(state.loadAtMs, now))
        drawRow(canvas, x0, x1, h * 0.82f, h, "BATERIA",
            state.batteryV?.let { String.format("%.1f V", it) } ?: "-- V",
            state.isStale(state.batteryAtMs, now))
    }

    /** Etiqueta a la izquierda, valor a la derecha, ambos en la misma linea. */
    private fun drawRow(
        canvas: Canvas, x0: Float, x1: Float, y: Float, h: Float,
        etiqueta: String, valor: String, stale: Boolean,
    ) {
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.075f
        canvas.drawText(etiqueta, x0, y, labelPaint)

        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.color = if (stale) COLOR_STALE else COLOR_TEXT
        labelPaint.textSize = h * 0.095f
        canvas.drawText(valor, x1, y, labelPaint)

        labelPaint.textAlign = Paint.Align.CENTER
    }

    /** Un punto de color y una palabra: basta para saber si el dato es vivo. */
    private fun drawConnectionBadge(canvas: Canvas, left: Float, ancho: Float, h: Float) {
        // El texto tiene que decir QUE HACER, no solo que algo va mal. Un
        // "SIN ENLACE" identico para "no hay adaptador elegido" y para "el
        // adaptador no contesta" deja al conductor sin saber si le toca
        // configurar algo o esperar.
        val (color, texto) = when (state.connection) {
            ConnectionState.Polling -> COLOR_GREEN to (state.protocol?.take(22) ?: "EN LINEA")
            ConnectionState.Initializing -> COLOR_AMBER to "INICIANDO"
            ConnectionState.Connecting -> COLOR_AMBER to "CONECTANDO"
            ConnectionState.SinAdaptador -> COLOR_VTEC_ON to "TOCA PARA ELEGIR ADAPTADOR"
            ConnectionState.BluetoothApagado -> COLOR_AMBER to "ENCIENDE EL BLUETOOTH"
            ConnectionState.Disconnected -> COLOR_REDLINE to "SIN ENLACE"
        }
        val r = h * 0.018f
        val cx = left + ancho * 0.5f
        val y = h * 0.11f

        labelPaint.textSize = h * 0.058f
        labelPaint.color = COLOR_TEXT_DIM
        val anchoTexto = labelPaint.measureText(texto)
        barPaint.color = color
        canvas.drawCircle(cx - anchoTexto / 2 - r * 2.4f, y - h * 0.017f, r, barPaint)
        canvas.drawText(texto, cx, y, labelPaint)
    }

    private companion object {
        // Paleta oscura: es un tablero para manejar, casi siempre de noche o
        // con sol directo. El fondo negro maximiza el contraste en ambos.
        const val COLOR_BG = 0xFF07090C.toInt()
        const val COLOR_TRACK = 0xFF1B2129.toInt()
        const val COLOR_TEXT = Color.WHITE
        const val COLOR_TEXT_DIM = 0xFF8A96A3.toInt()
        const val COLOR_STALE = 0xFF5A6470.toInt()
        const val COLOR_NEEDLE = 0xFFF5F7FA.toInt()
        const val COLOR_GREEN = 0xFF35D07F.toInt()
        const val COLOR_AMBER = 0xFFFFB020.toInt()
        const val COLOR_REDLINE = 0xFFFF3B30.toInt()
        const val COLOR_REDLINE_BAND = 0xFF4A1512.toInt()
        const val COLOR_VTEC_IDLE = 0xFF15303D.toInt()
        const val COLOR_VTEC_ON = 0xFF00C2FF.toInt()
        const val COLOR_SESSION_MAX = 0xFFB388FF.toInt()
        const val COLOR_COLD = 0xFF3D8BFF.toInt()
    }
}
