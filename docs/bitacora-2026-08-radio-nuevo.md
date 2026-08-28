# Bitácora — el radio nuevo, ADB por red, y el BLE medio roto

_2026-08-27/28 · rama `descubrimiento-ble-usb` · v7.5 (versionCode 79) · 131 pruebas verdes_

> **Continua en [bitacora-2026-08-tablero-completo.md](bitacora-2026-08-tablero-completo.md)**,
> que cubre lo funcional: que mide el tablero, de donde sale cada numero, el
> modulo de averias y la limpieza de 2.594 lineas de codigo muerto. Esta de
> aqui es la del **hardware y el acceso** al radio.

El head unit viejo murió con el táctil roto y se compró otro. Esta sesión va
entera sobre el nuevo: cómo se entra, qué hardware suyo sirve y cuál no, y por
qué la arquitectura acabó siendo **mitad radio interna, mitad dongle**.

Se escribe con detalle porque casi todo lo de aquí costó horas de medir y
ninguna de estas conclusiones se puede deducir leyendo el código.

---

## El radio nuevo

| Cosa | Valor |
|---|---|
| SoC | **Rockchip rk3326** (el mismo que el viejo) |
| Android | **11, API 30** |
| Pantalla | **1280×480** — idéntica al viejo, el layout no se tocó |
| IP | **192.168.2.102** (DHCP; el viejo era `.57`) |
| Modelo | `auto_rk_t20` / `auto_rk_t10` |
| MCU | `IAP90|CM06.21.03.1M_251020` |
| Config | `RK3326_003_251215_V1.0.001` |
| Root | **No** (`ro.debuggable=0`, `sys.rkadb.root=0`) |
| Bluetooth | `02:0D:14:AC:86:4C`, nombre "S2000 AP1" |

Puertos USB útiles: **dos**. `1-1` y `3-1`. Los ocupan el dongle Bluetooth y el
receptor TPMS, y no sobra ninguno — traía además un receptor Logitech que hubo
que sacrificar.

---

## ADB por red: cómo se entra (esto vale oro)

**Ya está emparejado y la vinculación es PERMANENTE.** No hace falta pedirle
otro código al dueño nunca más.

```bash
ADB="C:/Users/Usuario/Android/Sdk/platform-tools/adb.exe"
"$ADB" connect 192.168.2.102:<puerto>
```

El puerto **cambia en cada arranque** porque `service.adb.tls.port` es volátil.
Se encuentra barriendo, que tarda menos de un minuto:

```powershell
$ip='192.168.2.102'
for ($base=1024; $base -lt 65535; $base+=2048) {
  $hi=[Math]::Min($base+2047,65535); $t=@{}; $c=@{}
  foreach ($p in $base..$hi) { $s=New-Object System.Net.Sockets.TcpClient; $c[$p]=$s; $t[$p]=$s.ConnectAsync($ip,$p) }
  Start-Sleep -Milliseconds 1200
  foreach ($p in $c.Keys) { if ($c[$p].Connected) { "ABIERTO $p" }; $c[$p].Close() }
}
```

Lo que hace que sobreviva a apagar el carro:

- `persist.adb.tls_server.enable = 1` — propiedad **persist**
- `persist.adb.wifi.guid = adb-GFEDCBA098765330-tHg5zn`
- `/data/misc/bluetooth/../adb/adb_keys` guarda la llave del host
- Se le fijó `persist.adb.tcp.port = 5555`; **probar 5555 primero** tras un
  reinicio, por si esta ROM lo honra
- `persist.sys.usb.config = adb` — **ADB por cable USB activo de fábrica**, red
  de seguridad si la Wi-Fi falla

### El emparejamiento inicial, por si hay que repetirlo

Ajustes → Opciones de desarrollador → **Depuración inalámbrica** → *Vincular con
código*. Sale un `IP:puerto` distinto del de conexión y un código de 6 dígitos:

```bash
adb pair 192.168.2.102:45041 743830
```

**La contraseña del menú de fábrica no hace falta.** `development_settings_enabled`
ya vale `1` en este radio; la pantalla de Opciones de desarrollador se abre por
intent desde el propio tablero: `GET /ajustes?que=desarrollo`.

⚠️ **`startActivity` desde un servicio en segundo plano se descarta EN SILENCIO**
en Android 10+. No lanza excepción: contesta "abierto" y no abre nada. La ruta
`/ajustes` sólo funciona **con el tablero visible en pantalla**. Esto costó un
diagnóstico entero equivocado.

---

## El termómetro fallaba en abierto

`/termica` decía "no legible" y el nivel se quedaba en `Fresco` **para siempre**,
así que `permiteObd()` decía que sí a todo. Justo en el aparato donde no se sabía
medir, tampoco se protegía.

**Causa raíz:** `leer()` envolvía el bucle ENTERO en un solo `runCatching`. Una
excepción en la primera ruta abortaba las tres candidatas. Ahora cada lectura va
envuelta por separado y se prueban 16 zonas por ruta directa, sin listar el
directorio — que es lo primero que SELinux le niega a un `untrusted_app` aunque
sí deje leer el archivo concreto.

### Mapa térmico de este radio (medido, no supuesto)

| Zona | Qué es | Confirmado por |
|---|---|---|
| `thermal_zone0` | **SoC** | pantalla de fábrica: `soc-thermal 85,384 °C` vs mis 85 °C |
| `thermal_zone1` | **GPU** | `gpu-thermal 81,923 °C` |
| `thermal_zone2` | basura (`2600`) | descartada por implausible |

- **Reposo con el tablero corriendo: 62–66 °C.** El viejo idleaba a 59.
- **Pico medido: 95–96 °C**, durante descargas, instalaciones y reinicios.
  Transitorio: bajó solo a 65 en pocos minutos.
- Gobernador: **`interactive`**, no `performance`. Los 1512 MHz que se vieron en
  la pantalla de fábrica eran un pico, no el reloj clavado.
- Umbrales: tibio 70, caliente 78, vuelta a fresco 66.

**Verificado en vivo:** subió a `Caliente` a 91 °C, bajó el repintado a 1 fps,
soltó OBD y batería, y volvió a `Fresco` al bajar de 66.

Un cero NO cuenta como lectura (`esPlausible` corta en 5 °C): una zona declarada
pero no implementada reporta `0`, y creérselo devolvía el mismo fallo en abierto
por la puerta de atrás.

En pantalla, abajo al centro: `RADIO 62 °C`, gris/ámbar/rojo. Si ninguna fuente
contesta: **`RADIO — SIN TERMOMETRO, VIGILA TU`** en ámbar.

---

## EL HALLAZGO GORDO: el BLE de este radio está medio roto

El Bluetooth **clásico funciona impecable**. El **BLE no recibe nada, nunca**.

| | BR/EDR clásico | BLE |
|---|---|---|
| Barrido | ✅ encuentra 3 aparatos al instante | ❌ **0 hallazgos siempre** |
| Emparejar | ✅ `10 → 11 → 12` (BOND_BONDED) | — |
| Conectar | ✅ RFCOMM abierto | ⚠️ conectaba; luego `status=1` |
| Descubrir servicios | — | ✅ **sí funciona** |
| Notificaciones | — | ❌ **cero, en ~10 intentos** |
| Datos | ✅ ELM327, ISO 9141-2 | ❌ nada |

### La evidencia que lo cierra

- **Google Fast Pair barrió 2 h 21 min seguidas: `Total number of results: 0`.**
  No es mi código: es el escáner del aparato.
- Mis 6 barridos BLE: 0 resultados los seis.
- El BMS **está vivo** — por el dongle da `13,09 V / 38% / −8,77 A / 40 °C`.
- Por la radio interna: conecta, descubre servicios, acepta el CCCD con
  `status=0`… y no llega ni un byte.

### Callejones sin salida, para no repetirlos

| Hipótesis | Cómo se descartó |
|---|---|
| "No tiene BLE" | `feature:android.hardware.bluetooth_le` declarado, y arranca `BLE_TURN_ON` **antes** que BR/EDR |
| Barrido ajeno colgado | Se mató el de Google Fast Pair (2,3 h). Sin cambio |
| Caché GATT rancia | `refresh()` por reflexión → `true`. Sin cambio |
| El BMS está dormido | Falso: el dongle lo lee perfecto |
| Falta vincular | El BMS no completa `createBond` (`bondState=10`) |
| El MTU rompe el ATT | **NO SE PUDO MEDIR** — queda anotada, ver abajo |

### La hipótesis que queda viva

El **descubrimiento de servicios SÍ funciona**, y eso exige mucho tráfico ATT
*entrante*. O sea que la recepción no está muerta del todo: se rompe **después**
de descubrir. Entre medias sólo hay una operación — el intercambio de MTU.

Se dejó `pedirMtu` como bandera, **apagada por omisión**. No se pudo probar
porque al intentarlo el BMS ya no aceptaba conexiones. Para medirlo hace falta:
dongle desenchufado, BMS descansado, y una conexión limpia.

### btsnoop NO se puede capturar en esta ROM

Se probó todo y no crea el fichero en ningún sitio:

```
settings put global bluetooth_btsnoop_enable 1
setprop persist.bluetooth.btsnoopenable true
setprop persist.bluetooth.btsnoopdefaultmode full
am force-stop com.android.bluetooth
```

`/data/misc/bluetooth/logs/` es `drwxrwxrwx` (legible), y queda vacío. El
`bt_stack.conf` de esta ROM es la config nueva en C++, sin claves de snoop.
**Sin root no hay captura HCI.**

---

## Arquitectura resultante

```
MOTOR    ->  radio INTERNA del head unit (BR/EDR, RFCOMM/SPP)
BATERIA  ->  dongle USB Broadcom (el único BLE que funciona)
LLANTAS  ->  receptor CH340 por USB
```

Los dos puertos USB quedan ocupados y ninguno sobra.

**La ironía que conviene recordar:** la pila HCI sobre USB se escribió porque el
Bluetooth del radio VIEJO estaba roto. El nuevo arregló la mitad clásica —por eso
el motor ya no la necesita— pero la mitad BLE sigue rota. Ese código no era deuda
técnica: era la solución correcta, y lo sigue siendo para la batería.

### Interruptores

```bash
curl "http://192.168.2.102:8099/fuente?cual=motor&on=1"           # radio interna
curl "http://192.168.2.102:8099/fuente?cual=motor-dongle&on=1"    # el de antes
curl "http://192.168.2.102:8099/fuente?cual=bateria&on=1"         # radio interna (NO sirve hoy)
curl "http://192.168.2.102:8099/fuente?cual=bateria-dongle&on=1"  # el que funciona
```

El servicio arranca **sólo con el TPMS**, a propósito: este aparato ya se apagó
tres veces por calor. Las fuentes se suben de una en una midiendo `/termica`.

⚠️ **Fragilidad conocida:** `/bateria-gatt` usa el lector de la ÚLTIMA fuente de
batería que se encendió. Si se enciende `bateria-dongle`, la ruta de diagnóstico
pasa a ser la del dongle aunque después se apague. Confunde; conviene arreglarlo.

### Hardware confirmado

| Aparato | VID:PID | Notas |
|---|---|---|
| Dongle Bluetooth | `0a5c:21ec` | Broadcom **BCM20702A0** |
| Receptor TPMS | `1a86:7523` | CH340 |
| Steren SCAN-010 | `00:1D:A5:68:98:8B` | BR/EDR, SPP `00001101` |
| BMS de litio | `A4:C1:38:CD:FA:C8` | JBD, servicio `ff00` (`ff01` notifica, `ff02` escribe) |

**Protocolo del AP1 CONFIRMADO por fin: `ISO 9141-2`** (K-line). El diseño lo
daba por esperado desde el principio y nunca se había podido verificar sin un
enlace vivo. `ATDP` lo dijo.

---

## El botón de cerrar

El tablero se queda la radio mientras está abierto y **la suelta entera al
cerrarse**, para que Android Auto la reciba libre. Fue la respuesta del dueño al
dilema de compartir la radio con el teléfono, y es mejor que las tres opciones
que se le plantearon: elimina el conflicto en vez de negociarlo.

- La **X** va arriba a la derecha y pide **pulsación larga (600 ms)**. Un toque
  suelto cerraría el tablero de un manotazo a media curva.
- `soltarBluetooth()` para el sondeo interno, el lector del dongle y el vigilante
  de batería, y limpia `EstadoActual.ultimo` para no dejar valores viejos
  colgados como si el enlace siguiera vivo.
- **NO** apaga el TPMS (va por USB) ni el puente HTTP. El radio sigue avisando de
  una llanta baja y sigue siendo alcanzable con el tablero cerrado.
- `DashView` no tenía ningún manejo de toques; se le añadió `onTouchEvent`.

---

## El confirmador y la falsa pista del overlay

Al conceder Accesibilidad salía *"Una aplicación está bloqueando una solicitud de
permiso"* — la protección anti-tapjacking de Android. Se persiguió durante una
hora la caza de overlays. **Era una pista falsa.**

El diagnóstico real sólo se vio con shell:

```
Enabled services:{...ConfirmarInstalacionService}
Crashed services:{...ConfirmarInstalacionService}
Bound services:{}
```

**Habilitado y vetado a la vez.** Android mantiene una lista negra de servicios
de accesibilidad que se cayeron y no los vuelve a enlazar. Escribir el ajuste por
`settings put` no bastaba.

**La cura es reinstalar el paquete:**

```bash
adb install -r confirmador/build/outputs/apk/release/confirmador-release.apk
adb shell settings put secure enabled_accessibility_services \
  com.nonosky.s2000dash.confirmador/com.nonosky.s2000dash.confirmador.ConfirmarInstalacionService
adb shell settings put secure accessibility_enabled 1
```

→ `Crashed services:{}` y enlazado al instante.

**Con ADB el confirmador ya casi no hace falta:** `pm install` no muestra diálogo,
`input tap` no necesita accesibilidad y `screencap` da la pantalla real. Su único
trabajo que ADB no cubre es pulsar "Instalar" en la auto-actualización **cuando
no hay laptop delante**. El tecleo del PIN quedó obsoleto: la pila HCI del dongle
contesta el PIN a nivel de protocolo (`0x040D`), sin pasar por Android.

Otros permisos que se concedieron por ADB (Android 11 los exige para barrer):

```bash
adb shell pm grant com.nonosky.s2000dash android.permission.ACCESS_FINE_LOCATION
adb shell settings put secure location_mode 3
```

---

## Defectos encontrados por el camino

1. **Los tres ganchos de la vista estaban ANIDADOS uno dentro de otro.**
   `alCambiarBateria` se asignaba dentro del cuerpo de `alCambiarTpms`, y
   `alCambiarObd` dentro del de batería. El motor sólo quedaba cableado si
   llegaba una trama de TPMS **y además** un evento de batería. Con la batería
   apagada, el gancho del motor no se registraba nunca: el servicio contestaba
   `Polling` por el puente mientras la pantalla decía "sin enlace".

2. **`soltarObdDeLaRadio()` desvinculaba el Steren en cada apertura.** Tenía
   sentido cuando el OBD iba por el dongle. Con el OBD en la pila de Android
   habría impedido conectar nunca.

3. **`filaGrande` no medía.** "ALTERNADOR" y "13,0 V" se pisaban — justo la fila
   que avisa de que el alternador no carga. Ahora se mide el valor primero y la
   etiqueta cede, con suelo. El número nunca encoge.

4. **Las sondas de diagnóstico atascaban la cola GATT.** Una lectura que expira
   deja la operación en vuelo y Android rechaza todo lo que venga detrás: la
   sonda tumbaba la petición que venía a depurar. Van apagadas por omisión.

---

## Errores de diagnóstico míos, para no repetirlos

- **Afirmé que el BMS estaba "mudo a nivel ATT y no hay código que lo arregle".**
  Falso. El dongle lo leyó en un minuto. Saqué una conclusión fuerte de una sonda
  que envenenaba su propia medición.
- **Culpé a `development_settings_enabled = 0`.** Valía `1`. La pantalla nunca
  llegaba a lanzarse por lo del `startActivity` en segundo plano.
- **Di por bueno un `status=0` de escritura con acuse** como prueba de que el BMS
  recibía. Con `WRITE_TYPE_NO_RESPONSE` ese callback es local y no prueba nada.

La lección común a las tres: **verificar el instrumento antes de creerle a la
medida.**

---

## Deuda pendiente

- **`BootReceiver` resucita la app en cada arranque** → cualquier crash se vuelve
  un bucle del que el dueño no sale sin desinstalar. Sigue sin quitarse. Es la
  deuda #1 desde la bitácora anterior.
- **`/bateria-gatt` depende de la última fuente encendida** (ver arriba).
- **`gradlew` wrapper nunca commiteado.** Gradle vive en `~/tools/gradle-8.7`.
- **Probar la hipótesis del MTU** con el dongle fuera y el BMS descansado.
- `RadioBt.Piezas` declara `HciUsb` concreto en vez de `CanalUsbHci` — bloquea el
  test de ciclo completo con USB falso.

---

## Rutas nuevas del puente (`:8099`)

| Ruta | Qué hace |
|---|---|
| `/zonas` | zona térmica por zona: si no existe, si SELinux la niega, o si da basura |
| `/cpu` | frecuencia y gobernador por núcleo, y la carga |
| `/interruptores` | lee de `Settings` si están puestos ADB, desarrollador y accesibilidad |
| `/overlays` | quién declara `SYSTEM_ALERT_WINDOW` |
| `/ajustes?que=` | abre pantallas del sistema por intent (**con el tablero visible**) |
| `/soltar-bt` | suelta la radio Bluetooth sin tocar la pantalla |
| `/obd-spp?mac=` | diálogo AT por la radio interna |
