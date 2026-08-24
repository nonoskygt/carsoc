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
import com.nonosky.s2000dash.bateria.CanalGattDisponible
import com.nonosky.s2000dash.bateria.LectorBmsGatt
import com.nonosky.s2000dash.bateria.CanalGattHci
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
        EstadoActual.mandarAlConfirmador = { comando, a, b, c, d ->
            runCatching {
                sendBroadcast(
                    Intent("com.nonosky.s2000dash.MANDO")
                        .setPackage("com.nonosky.s2000dash.confirmador")
                        .putExtra("comando", comando)
                        .putExtra("a", a)
                        .putExtra("b", b)
                        .putExtra("c", c)
                        .putExtra("d", d)
                )
            }.onFailure { Log.w(TAG, "no se pudo mandar '$comando': ${it.message}") }
        }
        EstadoActual.probarObdHci = { mac ->
            val salida = mutableListOf<String>()
            // El OBD necesita el dongle sin interrupciones: se pausa el
            // vigilante de la bateria y se le da tiempo a soltarlo. Sin esto,
            // el OBD pierde todas las carreras contra un vigilante que entra
            // cada 30 segundos y se queda dentro veinte.
            val v = EstadoActual.vigilanteBateria
            v?.pausar()
            salida += "vigilante de bateria en pausa; esperando que suelte el dongle"
            var esperas = 0
            while (com.nonosky.s2000dash.hci.DuenoDongle.ocupadoPor() != null && esperas < 30) {
                Thread.sleep(1_000); esperas++
            }
            salida += "dongle libre tras ${esperas}s"
            val t = com.nonosky.s2000dash.obd.HciObdTransport(ctx, mac)
            try {
                t.connect()
                salida += t.traza
                salida += "--- dialogo AT ---"
                val sesion = com.nonosky.s2000dash.obd.Elm327Session(t)
                val info = sesion.initialize()
                salida += "ATDP dijo: ${info.describedAs} (fallback=${info.usedFallback})"
                salida += "voltaje del adaptador: ${sesion.readVoltage() ?: "n/d"}"
                salida += "RPM crudo: ${sesion.queryRaw("010C") ?: "sin respuesta"}"
                salida += "agua crudo: ${sesion.queryRaw("0105") ?: "sin respuesta"}"
            } catch (e: Exception) {
                salida += t.traza
                salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { t.close() }
                v?.reanudar()
                salida += "vigilante de bateria reanudado"
            }
            salida
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
            // Enchufa la capa ACL/L2CAP al lector del BMS. Mientras esto fuera
            // null, el vigilante sabia que el GATT no estaba cableado y lo
            // decia en el tablero en vez de fingir que buscaba.
            CanalGattDisponible.fabrica = { mac ->
                CanalGattHci.abrir(applicationContext, mac).first
            }
            val v = VigilanteBateria(applicationContext)
            vigilante = v
            EstadoActual.vigilanteBateria = v
            v.alCambiar = { runCatching { EstadoActual.alCambiarBateria?.invoke() } }
            v.arrancar()

            EstadoActual.leerBmsAhora = { mac ->
                val salida = mutableListOf<String>()
                val (canal, traza) = CanalGattHci.abrir(applicationContext, mac)
                salida += traza
                if (canal == null) {
                    salida += "no se pudo abrir el canal GATT"
                } else {
                    try {
                        val lector = LectorBmsGatt(canal)
                        val lectura = lector.leerTodo()
                        salida += lectura.traza
                        salida += lectura.problemas
                        lectura.basico?.let { salida += "BASICO: $it" }
                        lectura.celdas?.let { salida += "CELDAS: $it" }
                    } finally {
                        runCatching { canal.cerrar() }
                    }
                }
                salida
            }
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
