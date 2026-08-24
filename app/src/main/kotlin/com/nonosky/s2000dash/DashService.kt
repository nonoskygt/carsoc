package com.nonosky.s2000dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nonosky.s2000dash.bateria.VigilanteBateria
import com.nonosky.s2000dash.debug.DebugServer
import com.nonosky.s2000dash.descubrimiento.Descubridor
import com.nonosky.s2000dash.hci.SondaHci
import com.nonosky.s2000dash.tpms.TpmsReader
import com.nonosky.s2000dash.selfupdate.UpdateChecker
import kotlin.concurrent.thread

/**
 * Servicio en primer plano que sostiene el puente de diagnostico y la
 * revision periodica de actualizaciones.
 *
 * Nace de un fallo concreto: el puente y el buscador vivian dentro de la
 * pantalla del tablero, asi que en cuanto el usuario abria otra app y la
 * actividad se destruia, el radio dejaba de ser alcanzable y de revisar
 * actualizaciones. Justo lo contrario de lo que se buscaba — un tablero
 * que se mantiene solo no puede depender de que alguien lo tenga abierto.
 */
class DashService : Service() {

    private var puente: DebugServer? = null
    private var lectorTpms: TpmsReader? = null
    private var vigilante: VigilanteBateria? = null
    private val actualizador by lazy { UpdateChecker(applicationContext) }

    @Volatile
    private var vivo = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        arrancarEnPrimerPlano()

        puente = DebugServer(
            stateProvider = { EstadoActual.ultimo },
            viewProvider = { EstadoActual.vista },
            updaterProvider = { actualizador },
        ).also { it.start() }

        registrarDescubrimiento()

        arrancarTpms()
        arrancarBateria()

        vivo = true
        arrancarRevisionPeriodica()
        Log.i(TAG, "Servicio arriba")
    }

    /**
     * El descubrimiento vive AQUI, no en la pantalla.
     *
     * El emparejamiento OBD se registra desde la Activity porque necesita
     * mostrar la lista al usuario. Estas fuentes no: la bateria y el TPMS
     * hay que poder buscarlos con el tablero cerrado, desde la laptop, sin
     * que nadie toque el radio. Colgarlas de la Activity las mataria en
     * cuanto el usuario abriera otra app — el mismo error que ya dejo al
     * radio incomunicado una vez.
     */
    private fun registrarDescubrimiento() {
        val ctx = applicationContext
        val adapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        }.getOrNull()

        EstadoActual.barrerBle = { segundos ->
            Descubridor.barrerBle(ctx, adapter, segundos)
        }
        EstadoActual.volcarGatt = { mac, segundos ->
            Descubridor.volcarGatt(ctx, adapter, mac, segundos)
        }
        EstadoActual.listarUsb = {
            Descubridor.listarUsb(ctx)
        }
        EstadoActual.volcarUsbSerial = { baudios, segundos ->
            Descubridor.volcarUsbSerial(ctx, baudios, segundos)
        }
        EstadoActual.interrogarHci = { vid, pid ->
            SondaHci.interrogar(ctx, vid, pid)
        }
        EstadoActual.barrerBleHci = { segundos, vid, pid, activo, crudo ->
            SondaHci.barrerBle(ctx, segundos, vid, pid, activo, crudo)
        }
        EstadoActual.encenderBluetooth = { encender ->
            Descubridor.encenderBluetooth(adapter, encender)
        }
    }

    /**
     * Arranca la lectura del TPMS en su propio hilo.
     *
     * Envuelto entero: si el receptor no esta, o el USB falla, el tablero
     * tiene que seguir en pie. Antes TODO colgaba del enlace OBD y sin
     * adaptador no habia nada en pantalla; repetir ese error con el TPMS
     * seria no haber aprendido nada.
     */
    private fun arrancarTpms() {
        runCatching {
            val lector = TpmsReader(applicationContext)
            lectorTpms = lector
            EstadoActual.lectorTpms = lector
            lector.alCambiar = { runCatching { EstadoActual.alCambiarTpms?.invoke() } }
            lector.arrancar()
        }.onFailure { Log.w(TAG, "TPMS no arranco: ${it.message}") }
    }

    /**
     * Arranca la vigilancia de la bateria. Envuelto, como todo lo demas: si
     * el dongle no esta, el resto del tablero sigue en pie.
     */
    private fun arrancarBateria() {
        runCatching {
            val v = VigilanteBateria(applicationContext)
            vigilante = v
            EstadoActual.vigilanteBateria = v
            v.alCambiar = { runCatching { EstadoActual.alCambiarBateria?.invoke() } }
            v.arrancar()
        }.onFailure { Log.w(TAG, "vigilante de bateria no arranco: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: si el sistema lo mata por memoria, que vuelva. En un
        // head unit con poca RAM eso pasa mas de lo que uno quisiera.
        return START_STICKY
    }

    override fun onDestroy() {
        vivo = false
        runCatching { vigilante?.detener() }
        vigilante = null
        EstadoActual.vigilanteBateria = null
        runCatching { lectorTpms?.detener() }
        lectorTpms = null
        EstadoActual.lectorTpms = null
        puente?.stop()
        puente = null
        Log.i(TAG, "Servicio abajo")
        super.onDestroy()
    }

    /**
     * Revisa actualizaciones cada cierto rato, no solo al abrir la app.
     *
     * El intervalo es largo a proposito: descargar e instalar interrumpe el
     * tablero, y en un carro eso no puede pasar cada dos por tres.
     */
    private fun arrancarRevisionPeriodica() {
        thread(name = "revisor-actualizaciones", isDaemon = true) {
            // Un respiro al arrancar: dejar que la red del carro se asiente
            // antes de ponerse a difundir y descargar.
            Thread.sleep(20_000)
            while (vivo) {
                runCatching { actualizador.checkAndInstall() }
                    .onFailure { Log.w(TAG, "Revision fallida: ${it.message}") }
                Thread.sleep(INTERVALO_MS)
            }
        }
    }

    private fun arrancarEnPrimerPlano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL, "S2000 Dash", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Mantiene el tablero disponible" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(canal)
        }

        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
        )

        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CANAL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("S2000 Dash")
            .setContentText("Tablero disponible")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()

        startForeground(ID_NOTIFICACION, n)
    }

    companion object {
        private const val TAG = "DashService"
        private const val CANAL = "s2000dash"
        private const val ID_NOTIFICACION = 1
        private const val INTERVALO_MS = 15 * 60 * 1000L

        /** Arranca el servicio si no lo esta. Idempotente. */
        fun arrancar(context: Context) {
            val i = Intent(context, DashService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure { Log.w(TAG, "No se pudo arrancar: ${it.message}") }
        }
    }
}
