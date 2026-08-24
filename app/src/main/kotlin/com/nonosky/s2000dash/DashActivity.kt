package com.nonosky.s2000dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nonosky.s2000dash.bt.ObdPairing
import com.nonosky.s2000dash.selfupdate.UpdateChecker
import com.nonosky.s2000dash.obd.PollScheduler
import com.nonosky.s2000dash.obd.SppTransport
import com.nonosky.s2000dash.ui.DashView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pantalla unica del tablero.
 *
 * Se encarga de lo que solo Android puede dar: pantalla encendida, inmersivo
 * horizontal, permisos de Bluetooth en los dos modelos (el viejo y el de
 * Android 12+), buscar y emparejar el adaptador, y recordar cual se eligio.
 */
class DashActivity : ComponentActivity() {

    private lateinit var dashView: DashView
    private var scheduler: PollScheduler? = null
    private var observeJob: Job? = null

    private var pairing: ObdPairing? = null
    private var pickerDialog: AlertDialog? = null

    private val updater by lazy { UpdateChecker(applicationContext) }
    private val revisoActualizacion = java.util.concurrent.atomic.AtomicBoolean(false)

    /** El adaptador elegido, para poder arrancar y parar con el ciclo de vida. */
    private var chosen: BluetoothDevice? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startDash()
        } else {
            toast(getString(R.string.needs_bluetooth))
        }
    }

    /** Ubicacion: en API 30 y menores, sin ella el barrido no devuelve nada. */
    private val requestScanPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pairing?.scan() else toast(getString(R.string.needs_location_scan))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Manejando, la pantalla no se puede apagar sola.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        dashView = DashView(this)
        setContentView(dashView)

        // El puente de diagnostico y la revision de actualizaciones viven en
        // un servicio, no aqui: metidos en la pantalla se morian en cuanto el
        // usuario abria otra app, y el radio dejaba de ser alcanzable justo
        // cuando mas falta hacia.
        EstadoActual.vista = dashView
        DashService.arrancar(this)

        // Mantener presionado para cambiar de adaptador: la unica
        // configuracion que existe, escondida donde no estorba al manejar.
        dashView.setOnLongClickListener {
            showPicker()
            true
        }

        ensurePermissions()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // --- Ciclo de vida del sondeo ------------------------------------------

    override fun onStop() {
        // Sin esto el sondeo sigue hablandole al adaptador con la app en
        // segundo plano: gasta bateria del carro y estorba a cualquier otra
        // app que quiera el mismo ELM327.
        scheduler?.stop()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        chosen?.let { beginPolling(it) }
        revisarActualizacionUnaVez()
    }

    /**
     * Revisa si hay version nueva, UNA sola vez por vida del proceso.
     *
     * Antes se revisaba en cada `onStart`, y eso creaba un bucle: al
     * cerrarse el dialogo del instalador el tablero vuelve al primer plano,
     * lo que dispara otro `onStart`, que pide otra instalacion... El radio
     * se quedaba pidiendo confirmacion sin parar y no se podia usar.
     *
     * Con una sola revision por arranque basta: tras instalar, el sistema
     * mata el proceso y [BootReceiver] vuelve a abrir el tablero, asi que
     * la siguiente revision llega igual.
     */
    private fun revisarActualizacionUnaVez() {
        if (!revisoActualizacion.compareAndSet(false, true)) return
        // Hilo aparte: hace red y descubrimiento UDP, nada de eso puede
        // tocar el hilo principal.
        Thread { runCatching { updater.checkAndInstall() } }.start()
    }

    override fun onDestroy() {
        // Soltar la vista para que no la retenga el servicio. El puente
        // seguira contestando el estado; solo dejara de haber captura, que
        // es la verdad: sin pantalla no hay nada que fotografiar.
        if (EstadoActual.vista === dashView) EstadoActual.vista = null
        pickerDialog?.dismiss()
        pickerDialog = null
        pairing?.stop()
        pairing = null
        scheduler?.stop()
        super.onDestroy()
    }

    // --- Permisos -----------------------------------------------------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Antes de Android 12 estos son permisos normales, concedidos al
            // instalar; se listan igual para no ramificar el resto del flujo.
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    private fun ensurePermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startDash() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun startDash() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            toast(getString(R.string.enable_bluetooth))
            return
        }
        val saved = savedDevice(adapter)
        if (saved != null) {
            chosen = saved
            beginPolling(saved)
            return
        }
        // Primera vez: buscar y emparejar el adaptador desde aqui.
        showPicker()
    }

    @SuppressLint("MissingPermission")
    private fun savedDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val mac = prefs.getString(KEY_DEVICE, null) ?: return null
        return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
    }

    // --- Elegir y emparejar el adaptador ------------------------------------

    @SuppressLint("MissingPermission")
    private fun showPicker() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            toast(getString(R.string.enable_bluetooth))
            return
        }
        pickerDialog?.dismiss()

        val shown = mutableListOf<BluetoothDevice>()
        val names = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)

        val p = pairing ?: ObdPairing(this, adapter).also { pairing = it }
        p.start(object : ObdPairing.Listener {
            override fun onDevices(devices: List<BluetoothDevice>) {
                shown.clear()
                shown += devices
                names.clear()
                names.addAll(devices.map { d -> label(d) })
                names.notifyDataSetChanged()
            }

            override fun onBonded(device: BluetoothDevice) {
                prefs.edit().putString(KEY_DEVICE, device.address).apply()
                chosen = device
                pickerDialog?.dismiss()
                pickerDialog = null
                p.stop()
                toast(getString(R.string.paired_with, device.name ?: device.address))
                beginPolling(device)
            }

            override fun onBondFailed(device: BluetoothDevice) {
                toast(getString(R.string.pair_failed))
            }

            override fun onScanFinished() {
                if (shown.isEmpty()) toast(getString(R.string.nothing_found))
            }
        })

        pickerDialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_adapter)
            .setAdapter(names) { _, i -> shown.getOrNull(i)?.let { p.bond(it) } }
            .setNeutralButton(R.string.scan) { _, _ -> beginScan() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { goImmersive() }
            .create()
            .also { it.show() }

        // Arrancar el barrido de una: si el adaptador no esta emparejado,
        // la lista sale vacia y esperar a que toquen "Buscar" es un paso de mas.
        beginScan()
    }

    @SuppressLint("MissingPermission")
    private fun label(d: BluetoothDevice): String {
        val name = runCatching { d.name }.getOrNull() ?: "(sin nombre)"
        val bonded = d.bondState == BluetoothDevice.BOND_BONDED
        val mark = if (ObdPairing.looksLikeObd(d)) "★ " else ""
        val estado = if (bonded) getString(R.string.bonded) else getString(R.string.tap_to_pair)
        return "$mark$name\n${d.address}  ·  $estado"
    }

    private fun beginScan() {
        // En API 30 y menores el barrido de Bluetooth exige ubicacion fina;
        // sin ella startDiscovery no devuelve nada y parece que no hay nadie.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestScanPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                return
            }
        }
        pairing?.scan()
    }

    // --- Sondeo -------------------------------------------------------------

    private fun beginPolling(device: BluetoothDevice) {
        scheduler?.stop()
        val adapter = bluetoothAdapter
        val fresh = PollScheduler(
            transportFactory = { SppTransport(device, adapter) },
            scope = lifecycleScope,
        )
        scheduler = fresh
        fresh.start()
        observe(fresh)
    }

    /**
     * Se reengancha al scheduler que este vivo. Cancelar el anterior importa:
     * al cambiar de adaptador la vista se quedaria escuchando al scheduler
     * viejo, ya detenido, y el tablero se congelaria sin decir por que.
     */
    private fun observe(target: PollScheduler) {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                target.state.collect {
                    dashView.setState(it)
                    // Publicarlo para que el puente lo vea aunque esta
                    // pantalla se destruya despues.
                    EstadoActual.ultimo = it
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val PREFS = "s2000dash"
        const val KEY_DEVICE = "adapter_mac"
    }
}
