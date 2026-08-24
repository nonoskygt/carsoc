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

        // El tacometro manda: ocupa el bloque izquierdo, cuadrado respecto
        // al alto. Todo lo demas se acomoda en el ancho sobrante, para que
        // el layout aguante desde 800x480 hasta 1280x480 sin tocar codigo.
        val dialSize = min(h * 0.96f, w * 0.5f)
        val cx = dialSize * 0.52f
        val cy = h * 0.5f
        val radius = dialSize * 0.46f

        drawTachometer(canvas, cx, cy, radius)

        val panelLeft = dialSize * 1.02f
        val panelWidth = w - panelLeft
        drawSpeed(canvas, panelLeft, panelWidth, h)
        drawCoolantBar(canvas, panelLeft, panelWidth, h)
        drawFooter(canvas, panelLeft, panelWidth, h)
        drawConnectionBadge(canvas, panelLeft, panelWidth, h)

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

    // --- Panel derecho ------------------------------------------------------

    private fun drawSpeed(canvas: Canvas, left: Float, panelW: Float, h: Float) {
        val cx = left + panelW * 0.5f
        val now = System.currentTimeMillis()
        val stale = state.isStale(state.speedAtMs, now)

        textPaint.color = if (stale) COLOR_STALE else COLOR_TEXT
        textPaint.textSize = h * 0.44f
        canvas.drawText(state.speedKmh?.toString() ?: "--", cx, h * 0.44f, textPaint)

        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.075f
        canvas.drawText("km/h", cx, h * 0.53f, labelPaint)
    }

    private fun drawCoolantBar(canvas: Canvas, left: Float, panelW: Float, h: Float) {
        val barLeft = left + panelW * 0.10f
        val barRight = left + panelW * 0.90f
        val barTop = h * 0.60f
        val barH = h * 0.075f
        val now = System.currentTimeMillis()
        val stale = state.isStale(state.coolantAtMs, now)

        // Canaleta
        barPaint.color = COLOR_TRACK
        canvas.drawRoundRect(barLeft, barTop, barRight, barTop + barH, barH / 2, barH / 2, barPaint)

        val c = state.coolantC
        if (c != null) {
            // Escala util: de 40 a 120 °C. Debajo de 40 el motor esta frio y
            // arriba de 120 ya es problema — no hace falta mas resolucion.
            val t = ((c - 40f) / 80f).coerceIn(0f, 1f)
            barPaint.color = when {
                stale -> COLOR_STALE
                c >= EngineConstants.COOLANT_HIGH_C -> COLOR_REDLINE
                c >= EngineConstants.COOLANT_NORMAL_C -> COLOR_GREEN
                else -> COLOR_COLD
            }
            canvas.drawRoundRect(
                barLeft, barTop, barLeft + (barRight - barLeft) * t, barTop + barH,
                barH / 2, barH / 2, barPaint,
            )
        }

        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.062f
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("AGUA", barLeft, barTop - h * 0.022f, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.color = if (stale) COLOR_STALE else COLOR_TEXT
        canvas.drawText(c?.let { "$it °C" } ?: "-- °C", barRight, barTop - h * 0.022f, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawFooter(canvas: Canvas, left: Float, panelW: Float, h: Float) {
        val now = System.currentTimeMillis()
        val y = h * 0.86f
        val slot = panelW / 3f

        drawStat(canvas, left + slot * 0.5f, y, h, "AIRE",
            state.iatC?.let { "$it°" } ?: "--", state.isStale(state.iatAtMs, now))
        drawStat(canvas, left + slot * 1.5f, y, h, "CARGA",
            state.loadPct?.let { "$it%" } ?: "--", state.isStale(state.loadAtMs, now))
        drawStat(canvas, left + slot * 2.5f, y, h, "BATERIA",
            state.batteryV?.let { String.format("%.1fV", it) } ?: "--",
            state.isStale(state.batteryAtMs, now))
    }

    private fun drawStat(
        canvas: Canvas, cx: Float, y: Float, h: Float,
        label: String, value: String, stale: Boolean,
    ) {
        labelPaint.color = COLOR_TEXT_DIM
        labelPaint.textSize = h * 0.055f
        canvas.drawText(label, cx, y, labelPaint)
        textPaint.color = if (stale) COLOR_STALE else COLOR_TEXT
        textPaint.textSize = h * 0.105f
        canvas.drawText(value, cx, y + h * 0.105f, textPaint)
    }

    /** Un punto de color y una palabra: basta para saber si el dato es vivo. */
    private fun drawConnectionBadge(canvas: Canvas, left: Float, panelW: Float, h: Float) {
        val (color, text) = when (state.connection) {
            ConnectionState.Polling -> COLOR_GREEN to (state.protocol?.take(18) ?: "EN LINEA")
            ConnectionState.Initializing -> COLOR_AMBER to "INICIANDO"
            ConnectionState.Connecting -> COLOR_AMBER to "CONECTANDO"
            ConnectionState.Disconnected -> COLOR_REDLINE to "SIN ENLACE"
        }
        val r = h * 0.018f
        val cx = left + panelW * 0.5f
        val y = h * 0.055f

        labelPaint.textSize = h * 0.05f
        labelPaint.color = COLOR_TEXT_DIM
        val textW = labelPaint.measureText(text)
        barPaint.color = color
        canvas.drawCircle(cx - textW / 2 - r * 2.2f, y - h * 0.014f, r, barPaint)
        canvas.drawText(text, cx, y, labelPaint)
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
