package com.nonosky.s2000dash.config

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nonosky.s2000dash.EstadoActual
import com.nonosky.s2000dash.PerfilVehiculo
import com.nonosky.s2000dash.Variante
import kotlin.concurrent.thread

/**
 * El menu de configuracion y emparejamiento.
 *
 * Aqui se decide QUE aparato hace cada papel: bateria de arranque, bateria
 * de vivienda, refrigeradora y adaptador OBD-II. Antes eso vivia en
 * constantes del codigo, y el vigilante viejo ni eso: barria y se quedaba
 * con el primer BMS que veia.
 *
 * Se dibuja con vistas nativas y a mano, sin XML, igual que el resto del
 * proyecto. No es el tablero: se abre con el carro parado, se usa una vez y
 * se cierra, asi que no compite por pixeles ni por milisegundos.
 */
class ConfiguracionActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private var barriendo = false

    /** Si no es null, el barrido esta buscando aparato para ESTE papel. */
    private var papelBuscado: Emparejados.Papel? = null

    private var hallazgos: List<Hallazgo> = emptyList()

    /** Un aparato visto en el aire. */
    private data class Hallazgo(val mac: String, val nombre: String, val linea: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(FONDO)
            addView(raiz, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        pintar()
    }

    // ---------------------------------------------------------------- vista

    private fun pintar() {
        raiz.removeAllViews()

        raiz.addView(titulo("Configuración"))
        raiz.addView(nota("${PerfilVehiculo.VEHICULO} · ${PerfilVehiculo.MOTOR}"))

        val buscando = papelBuscado
        if (buscando == null) pintarPapeles() else pintarHallazgos(buscando)
    }

    private fun pintarPapeles() {
        raiz.addView(nota("Toca un papel para asignarle un aparato."))
        for (p in Emparejados.papelesDeEsteCarro()) {
            val mac = Emparejados.mac(this, p)
            val nombre = Emparejados.nombre(this, p)
            val asignado = mac.isNotBlank()
            val detalle = when {
                !asignado -> "sin asignar"
                Emparejados.elegidoAMano(this, p) -> "$mac  ·  ${nombre.ifBlank { "sin nombre" }}"
                // Se DICE que es de fabrica. Un valor por omision que parece
                // elegido hace creer que alguien lo comprobo.
                else -> "$mac  ·  ${nombre.ifBlank { "sin nombre" }}  (de fábrica)"
            }
            raiz.addView(fila(p.rotulo, detalle, asignado) {
                papelBuscado = p
                hallazgos = emptyList()
                pintar()
                barrer(p)
            })
            if (asignado) {
                raiz.addView(botonPequeno("Olvidar ${p.rotulo.lowercase()}") {
                    Emparejados.olvidar(this, p)
                    pintar()
                })
            }
        }

        raiz.addView(separador())
        pintarVariante()

        raiz.addView(separador())
        raiz.addView(botonPequeno("Restaurar los valores de fábrica") {
            Emparejados.restaurar(this)
            pintar()
        })
        raiz.addView(nota(
            "Los cambios se aplican al reiniciar el tablero. " +
                "Un aparato sin asignar no se sondea: no gasta radio."
        ))
    }

    /**
     * COMO SE PINTA EL TABLERO: HTML o Canvas.
     *
     * Es una fila y no un menu porque solo hay dos, y con dos un interruptor se
     * entiende sin leer nada: dice cual esta puesta y a cual se cambia.
     *
     * Los dos tableros enseñan lo mismo con los mismos datos —la lectura y las
     * reglas de frescura son las de `EstadoDelTablero`, compartidas— asi que
     * esto no cambia lo que se ve, sino quien lo pinta y lo que cuesta. El
     * Canvas repinta al ritmo del termometro del radio y baja a un cuadro por
     * segundo cuando el aparato se calienta; el WebView, no.
     */
    private fun pintarVariante() {
        val actual = Variante.actual(this)
        val otra = Variante.contraria(actual)
        raiz.addView(fila(
            "Tablero",
            "${Variante.rotulo(actual)}  ·  toca para usar ${Variante.rotulo(otra)}",
            ok = true,
        ) {
            Variante.poner(this, otra)
            pintar()
        })
        raiz.addView(nota(
            "El tablero Canvas repinta al ritmo del termómetro del radio: " +
                "5 cuadros por segundo en frío y uno en caliente. " +
                "El cambio se ve al volver a abrir el tablero."
        ))
    }

    private fun pintarHallazgos(p: Emparejados.Papel) {
        raiz.addView(nota("Buscando aparato para: ${p.rotulo}"))
        raiz.addView(botonPequeno("← Volver sin cambiar") {
            papelBuscado = null
            pintar()
        })

        if (barriendo) {
            raiz.addView(nota(
                if (p.esBle) "Barriendo Bluetooth LE… (unos 12 s)"
                else "Barriendo Bluetooth clásico… (unos 15 s)"
            ))
            return
        }

        if (hallazgos.isEmpty()) {
            raiz.addView(nota(
                "No apareció ningún aparato.\n\n" +
                    "Comprueba que está encendido y cerca. Si es un adaptador " +
                    "OBD-II, muchos se duermen: enciende el contacto o " +
                    "desenchúfalo y vuélvelo a enchufar.\n\n" +
                    "Y si Android no tiene permiso de ubicación, el barrido " +
                    "devuelve cero EN SILENCIO, sin error."
            ))
            raiz.addView(botonPequeno("Buscar otra vez") { barrer(p) })
            return
        }

        raiz.addView(nota("${hallazgos.size} aparatos. Toca el que corresponda."))
        for (h in hallazgos) {
            raiz.addView(fila(
                h.nombre.ifBlank { "(sin nombre)" },
                h.linea.removePrefix(h.mac).trim().ifBlank { h.mac },
                false,
            ) {
                Emparejados.asignar(this, p, h.mac, h.nombre)
                papelBuscado = null
                pintar()
            })
        }
        raiz.addView(botonPequeno("Buscar otra vez") { barrer(p) })
    }

    // -------------------------------------------------------------- barrido

    private fun barrer(p: Emparejados.Papel) {
        if (barriendo) return
        barriendo = true
        pintar()
        thread(isDaemon = true) {
            val lineas = runCatching {
                if (p.esBle) EstadoActual.barrerBle?.invoke(12) ?: emptyList()
                else EstadoActual.buscarAdaptadores?.invoke() ?: emptyList()
            }.getOrDefault(emptyList())

            val vistos = lineas.mapNotNull { parsear(it) }
                // Un aparato ya asignado a OTRO papel se deja ver igual: el
                // dueño puede querer moverlo, y esconderlo pareceria que el
                // barrido no lo encontro.
                .distinctBy { it.mac }

            runOnUiThread {
                barriendo = false
                hallazgos = vistos
                pintar()
            }
        }
    }

    /**
     * Saca MAC y nombre de una linea del puente.
     *
     * Las rutas de barrido devuelven texto pensado para leerse por HTTP, del
     * tipo `AA:BB:CC:DD:EE:FF  Nombre  tipo=BLE  uuids=...`. Se parsea con
     * cuidado: una linea que no empiece por algo con forma de MAC se
     * descarta en vez de inventarse un aparato.
     */
    private fun parsear(linea: String): Hallazgo? {
        val t = linea.trim()
        val mac = MAC.find(t)?.value ?: return null
        val resto = t.removePrefix(mac).trim()
        val nombre = resto.substringBefore("  ").trim()
            .removeSuffix("(emparejado)").trim()
            .takeIf { it.isNotEmpty() && it != "?" } ?: ""
        return Hallazgo(mac.uppercase(), nombre, t)
    }

    // ------------------------------------------------------------- piezas UI

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(TINTA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t
        setTextColor(APAGADO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(6), 0, dp(12))
    }

    private fun separador() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(10); it.bottomMargin = dp(10)
        }
        setBackgroundColor(LINEA)
    }

    private fun fila(rotulo: String, detalle: String, ok: Boolean, alTocar: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 44 dp de alto minimo: es lo que se acierta con el dedo.
            minimumHeight = dp(56)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
            setBackgroundColor(TARJETA)
            isClickable = true
            setOnClickListener { alTocar() }
            addView(TextView(context).apply {
                text = rotulo
                setTextColor(if (ok) VIVO else TINTA)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = detalle
                setTextColor(APAGADO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun botonPequeno(t: String, alTocar: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(ARENA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        minimumHeight = dp(46)
        setPadding(dp(12), dp(13), dp(12), dp(13))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .also { it.bottomMargin = dp(8) }
        setBackgroundColor(TARJETA)
        isClickable = true
        setOnClickListener { alTocar() }
    }

    private companion object {
        val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

        // Misma familia de color que los tableros, sin depender de ellos.
        val FONDO = Color.parseColor("#131715")
        val TARJETA = Color.parseColor("#1A201C")
        val LINEA = Color.parseColor("#2A312B")
        val TINTA = Color.parseColor("#EDE4D3")
        val ARENA = Color.parseColor("#BEB39A")
        val APAGADO = Color.parseColor("#8E968A")
        val VIVO = Color.parseColor("#9CBE7A")
    }
}
