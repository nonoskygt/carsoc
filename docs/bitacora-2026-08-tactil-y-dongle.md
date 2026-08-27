# Bitácora — OBD por dongle y diagnóstico de la pantalla táctil

_2026-08-27 · rama `descubrimiento-ble-usb` · commit de código `5ded86c`_

Nota de memoria de la sesión: qué se cambió, qué se probó en el carro, y el
estado del táctil roto. Para que no se pierda entre reinicios de contexto.

## Cambios de código (v5.6 / versionCode 60 · 113 pruebas verdes)

1. **OBD y batería SOLO por el dongle USB.** El Bluetooth interno del radio
   queda libre para Android Auto. `DashActivity` ya no abre RFCOMM por la pila
   de Android: `PollScheduler` (interno) y `LectorObdHci` (dongle) escribían
   los dos en `EstadoActual.ultimo`, y el interno lo pisaba con `Disconnected`
   porque el Steren solo le contesta al dongle. Se quitó el selector, el
   auto-arranque interno y el `Disconnected` de `onStop`. El motor vive en el
   servicio.

2. **Steren fuera del Bluetooth del carro** (`removeBond` por reflexión), sin
   apagar la radio. Ruta `/desvincular` + hook `desvincularAdaptador`; corre
   solo al abrir el tablero. Android Auto (M11, elperegrinocosmico) intacto.

3. **Defecto raíz #1 — faltaba `HCI Reset` al abrir el dongle en frío.** Tras
   morir el proceso (update / crash / la ROM matando servicios) el controlador
   conservaba sus enlaces y toda conexión posterior a la batería recibía
   `0x0B` (*ACL Connection Already Exists*) contra un enlace huérfano que nadie
   cerraba — había que desconectar el dongle a mano. Fix en
   `RadioBt.reiniciarControlador` (opcode `0x0C03`, reposo 300 ms).

4. **Defecto raíz #2 — `EnlaceBrEdr.preparar` reseteaba el controlador en cada
   intento del motor.** Con la radio compartida, `HCI Reset` borraba el control
   de flujo del controlador mientras la bomba conservaba sus contadores → los
   paquetes salientes se caían **en silencio** (el enlace abría, L2CAP y RFCOMM
   abrían, pero ni un `ATI` respondía) y de paso tumbaba el enlace LE de la
   batería. Fix: quitar el reset de `preparar()`; además se corrigió el offset
   del conteo de buffers ACL (leía `numSco` por `numAcl`). El cuerpo se extrajo
   a `EnlaceBrEdr.Companion.preparacion()` para poder probarlo.

5. **Ruta `/obd-traza`** (solo lectura) para ver la traza del lector vivo sin
   abrir otra conexión.

Pruebas nuevas: `ResetDelControladorTest` y `PreparacionBrEdrTest` clavan que
el reset va en la apertura en frío y **nunca** en el enlace clásico.

**Verificado en el carro (v59):** batería `13,13 V / 39% / −120 W / 45 °C` y
motor en `Polling` **al mismo tiempo** sobre un solo dongle, con las 4 llantas
frescas.

## Pantalla táctil rota — diagnóstico

Murió al desarmar el panel. **No es software** — el kernel arranca perfecto.

- Controlador **Goodix gt9xx** en bus **i2c-1**, direcciones `0x14` (y `0x5d`).
  En i2c-1 hay 3 candidatos (`gt9xx`, `fts`, `jdcommon`) y **los tres cuelgan
  del mismo cable plano** del táctil.
- Síntoma del kernel: `gtp_i2c_test failed` ×5, `probe of 1-0014 failed with
  error -11`, **ningún** nodo de entrada táctil registrado. i2c mudo en ambas
  direcciones.
- Descartado: placa base, SoC, software, la app. Es la **conexión mecánica del
  FPC** del táctil.
- Reasentar el extremo de la placa (conector `TP`) no lo revivió; girar la
  orientación tampoco. **Pendiente:** revisar el **otro extremo** (el del
  vidrio, chip COF) y buscar daño físico del cable.
- **Repuesto:** la etiqueta del panel dice `TP100-028 RK46`.
- Una **app de calibración NO sirve**: no hay táctil registrado que calibrar,
  el controlador está eléctricamente mudo.
- El driver Goodix solo prueba el i2c **en el arranque**; reasentar en caliente
  no reintenta → hay que **reiniciar** para re-probar.

## Acceso al radio (192.168.2.57)

- **Puente HTTP** de diagnóstico en `:8099` (`/state /usb /dongle /bateria
  /tpms /fuente /desvincular /obd-traza /update` …). Se cae si la app crashea
  en bucle.
- **SSH:** SimpleSSHD / dropbear en puerto **2222**, solo *publickey*. Usuario
  `root` pero uid real `u0_a84` (untrusted_app, sin privilegios root reales —
  no puede rebind de drivers ni escribir sysfs). Clave que funciona: scratchpad
  de la sesión `1d9ff5bc-…/radio_key2` (`claude-radio-s2000-2`). SimpleSSHD
  auto-arranca tras boot.
- **Se puede reiniciar** el radio con `reboot` por SSH (funciona pese al uid).
  Cortar el ACC/llave **no** reinicia — el SoC sigue vivo por el 12 V constante
  (BATT amarillo); el uptime sigue subiendo. Para forzar el re-probe del
  táctil: `reboot` por SSH.
- **Servidor de APKs** de la laptop: `python http.server` en `192.168.2.20:8000`
  (sirve `dash-vNN.apk` + `version.json`). Anunciador UDP en `tools/anunciador.py`.
  Gradle está en `~/tools/gradle-8.7` (el wrapper `gradlew` nunca se commiteó).

## Deuda pendiente

- El `BootReceiver` resucita la app en cada arranque → cualquier crash se
  vuelve un bucle del que el usuario no sale sin desinstalar. **Quitarlo.**
- `RadioBt.Piezas` declara `HciUsb` concreto en vez de `CanalUsbHci` — bloquea
  el test de ciclo completo con USB falso.
- `gradlew` wrapper nunca commiteado.
