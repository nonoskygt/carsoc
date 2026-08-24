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
import com.nonosky.s2000dash.obd.PollScheduler
import com.nonosky.s2000dash.obd.SppTransport
import com.nonosky.s2000dash.ui.DashView
import kotlinx.coroutines.launch

/**
 * Pantalla unica del tablero.
 *
 * Se encarga de lo que solo Android puede dar: pantalla encendida, inmersivo
 * horizontal, permisos de Bluetooth en los dos modelos (el viejo y el de
 * Android 12+), y recordar cual adaptador se eligio.
 */
class DashActivity : ComponentActivity() {

    private lateinit var dashView: DashView
    private var scheduler: PollScheduler? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Manejando, la pantalla no se puede apagar sola.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        dashView = DashView(this)
        setContentView(dashView)

        // Mantener presionado para cambiar de adaptador: la unica
        // configuracion que existe, escondida donde no estorba al manejar.
        dashView.setOnLongClickListener {
            pickDevice(force = true)
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
        val device = savedDevice(adapter)
        if (device == null) {
            pickDevice(force = false)
            return
        }
        beginPolling(device)
    }

    @SuppressLint("MissingPermission")
    private fun savedDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val mac = prefs.getString(KEY_DEVICE, null) ?: return null
        return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice(force: Boolean) {
        val adapter = bluetoothAdapter ?: return
        val bonded = runCatching { adapter.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())

        if (bonded.isEmpty()) {
            toast(getString(R.string.pair_first))
            return
        }
        if (!force && bonded.size == 1) {
            // Un solo adaptador emparejado: no vale preguntar.
            select(bonded.first())
            return
        }

        val names = bonded.map { "${it.name ?: "?"}  ·  ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_adapter)
            .setItems(names) { _, i -> select(bonded[i]) }
            .setOnCancelListener { if (force) goImmersive() }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun select(device: BluetoothDevice) {
        prefs.edit().putString(KEY_DEVICE, device.address).apply()
        beginPolling(device)
    }

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
    private var observeJob: kotlinx.coroutines.Job? = null

    private fun observe(target: PollScheduler) {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                target.state.collect { dashView.setState(it) }
            }
        }
    }

    override fun onDestroy() {
        scheduler?.stop()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val PREFS = "s2000dash"
        const val KEY_DEVICE = "adapter_mac"
    }
}
