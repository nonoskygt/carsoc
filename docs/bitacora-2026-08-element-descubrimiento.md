# Bitácora — el Element, el radio nuevo y el día que el BLE sí funcionó

_2026-08-30 · rama `element` (parte de `descubrimiento-ble-usb`, v10.4 / versionCode 108)_

El proyecto se abre a un **segundo carro**: un Honda Element 2003-2006 (motor K24A4)
**convertido en casa rodante / overland**. No es un tablero de motor con extras: es un
panel de casa donde el motor es una sección más.

Esta bitácora recoge **lo medido**, no lo supuesto. Todo lo que aquí aparece como dato
salió de interrogar al aparato; lo que sigue abierto está marcado como tal.

---

## El radio del Element, medido (no leído de la caja)

⚠️ **Los Ajustes del fabricante MIENTEN sobre la versión de Android.** La pantalla de
"Información del sistema" dice **Android 12**; `ro.build.version.sdk` dice **28**, que es
**Android 9**. Se diseña contra API 28.

| Cosa | Valor |
|---|---|
| SoC | **MediaTek AC8257**, 8 núcleos (`ro.board.platform=ac8257`) |
| Android | **API 28 (9)**, aunque la UI diga 12 |
| ABI | **arm64-v8a** (el del S2000 era armeabi-v7a de 32 bits) |
| Pantalla | **1024×600 @160 dpi** — relación 1.71:1, casi cuadrada |
| RAM | 3,9 GB · Swap 1 GB |
| IP | `192.168.2.236` (DHCP) |
| Bluetooth | `00:00:46:3A:2D:ED`, nombre `CarKit8365` |
| Modelo | `ac8257_demo` / `full_ac8257_demo` |
| MCU | STM32 `20221111-11-BD2-33` |
| Root | **SÍ** — `ro.debuggable=1`, `adb root` devuelve `uid=0(root)`, contexto `u:r:su:s0` |

### ADB: abierto de par en par, sin vincular nada

```bash
adb connect 192.168.2.236:5555      # ya autorizado, responde "device"
adb -s 192.168.2.236:5555 root      # devuelve root de verdad
```

**No hace falta el flujo de "Depuración inalámbrica" con código de 6 dígitos** — esa
pantalla ni siquiera existe en Android 9. El puerto 5555 está abierto y autorizado de
fábrica. Es el único puerto TCP abierto del aparato (barrido completo 1-65535).

⚠️ **Trampa de PowerShell:** `Settings$DevelopmentSettingsDashboardActivity` se lo come el
shell **del radio**, no el de Windows. Hay que escapar el `$`:

```bash
adb shell 'am start -n com.android.settings/com.android.settings.Settings\$DevelopmentSettingsDashboardActivity'
```

⚠️ **Trampa de Git Bash:** convierte `/data/local/tmp/x.png` en
`C:/Program Files/Git/data/local/tmp/x.png`. Para rutas de Android, usar PowerShell.

### ⚠️ EL RADIO SE CAE DEL WI-FI EN CADA REINICIO, Y NO VUELVE SOLO

Dato del dueño, y **corrige una afirmación equivocada mía**: al ver que el ADB respondía
tras un reinicio se dio por hecho que el Wi-Fi se había reconectado solo. **Falso — lo
había reactivado el dueño a mano.** Se infirió y se presentó como medido, que es justo lo
que la regla del proyecto prohíbe.

**Consecuencia real:** después de cada arranque del carro el radio queda **incomunicado**
hasta que alguien toca la pantalla. Eso deja inservibles la auto-actualización, el puente
HTTP y el ADB — o sea, todo el control remoto — precisamente cuando más falta hace, que es
al volver de un viaje.

**Y aquí este radio tiene una ventaja que el del S2000 no tenía:** corre **API 28**, y
`WifiManager.setWifiEnabled(true)` **todavía funciona** en API 28. Google lo desactivó para
apps normales a partir de API 29. Así que el tablero del Element **puede encender el Wi-Fi
él mismo al arrancar**, sin root y sin que nadie toque nada.

Queda por verificar en este aparato concreto (algunas ROM chinas lo capan), y hay que
decidir si reconecta siempre o solo cuando reconoce la red de casa — un carro que enciende
la radio en cada arranque en medio del campo gasta batería para nada.

---

## EL HALLAZGO QUE MÁS CÓDIGO BORRA: aquí el BLE funciona

En el radio del S2000 el BLE estaba **medio roto**: descubría servicios pero no llegaba
un solo byte, y Google Fast Pair barrió **2 h 21 min para `Total number of results: 0`**.
De ahí nació la pila HCI propia sobre dongle USB (paquete `hci/`, 23 archivos).

**Aquí no.** Medido con la app de fábrica del BMS:

```
com.jiabaida.little_elephant (Filtered)
LE scans (started/stopped)  : 1 / 1
Scan time in ms             : 3027
Total number of results     : 9
```

Y el GATT también: se vio al vigilante conectar y soltar el banco de vivienda
(`CONNECTED a5:c2:37:09:18:ee` → `DISCONNECTED reason=22`).

**Consecuencia de diseño: el paquete `hci/` entero NO viaja al Element.** No era deuda
técnica en el S2000 — era la solución correcta a un radio roto. Aquí sobra.

### Requisito que no se ve venir

En Android 9 un barrido BLE **sin permiso de ubicación devuelve cero en silencio**, sin
excepción ni aviso. Hay que conceder `ACCESS_FINE_LOCATION` **y** encender la ubicación:

```bash
adb shell settings put secure location_mode 3
adb shell pm grant <paquete> android.permission.ACCESS_FINE_LOCATION
```

---

## Inventario del aire (barrido con reporte de tipo y UUID)

| Aparato | MAC | Tipo | UUID anunciado |
|---|---|---|---|
| **Elementos 300AH** (banco de vivienda) | `A5:C2:37:09:18:EE` | BLE | `0000ff00` → **JBD** |
| **Element Motor** (banco de arranque) | `A4:C1:38:3B:B9:5E` | BLE | (JBD, visto en barrido previo) |
| **Nevera Alpicool** `A1-4XXXXXXXXXXX` | `ED:67:39:96:50:9B` | BLE | `00001234` → **protocolo confirmado** |
| `SMI-M1S` — **sin identificar** | `F3:15:4A:EB:79:4D` | BLE | `0000180a` (solo info de dispositivo) |
| Steren SCAN-010 — **es el del S2000** | `00:1D:A5:68:98:8B` | CLÁSICO | `00001101` (SPP) |

Los dos bancos vienen **ya bautizados por el dueño** desde la app de JBD, lo que resuelve
solo el problema de distinguirlos: la documentación del ecosistema es tajante en que dos
JBD de fábrica anuncian el mismo nombre y hay que ir por MAC.

### ⚠️ El error que casi se cuela: medir el carro equivocado

Se dio por hecho que el `Steren SCAN-010` era el adaptador OBD del Element, porque era el
único aparato con pinta de OBD en el barrido. Se le abrió sesión y contestó:

```
ATDP dijo: AUTO (fallback=true)
voltaje del adaptador: 13.3
RPM crudo: SEARCHING...
```

**Eran datos del S2000**, que estaba en el radio de alcance. El adaptador del Element es un
**OBDLink MX, modelo MX201** (OBD Solutions LLC, FCC ID X3ZBTMOD4), confirmado por foto del
aparato enchufado bajo el tablero.

Lección repetida por tercera vez en este proyecto: **verificar el instrumento antes de
creerle a la medida.** Un aparato que contesta no es prueba de que sea *tu* aparato.

**El MX no aparece en el barrido con el carro apagado:** los OBDLink se duermen para no
descargar la batería. Despiertan con el contacto puesto. Y atienden **un solo cliente**:
si el teléfono lo tiene tomado, el radio no entra.

Que sea un MX y no un clon es una mejora real: lleva chip **STN**, no un ELM327 clonado.
Obedece `ATST`/`ATAT` de verdad — que es justo lo que el lector de averías necesita para no
perder códigos del 4 al 6 sin avisar.

---

## Mapa térmico de este radio

Trece zonas, y a diferencia del S2000 **vienen con nombre**, así que no hay que adivinar
cuál es cuál comparando contra la pantalla de fábrica.

| Zona | Qué es | Lectura en reposo |
|---|---|---|
| `thermal_zone1` | `mtktscpu` — **el CPU** | 58,7 °C |
| `thermal_zone8` | `mtktsAP` — el AP | 56,0 °C |
| `thermal_zone2` | `mtktspmic` | 61,1 °C |
| `thermal_zone0` / `7` | batería | 24,0 °C |

⚠️ **`Termometro.kt` elige mal en este aparato.** Su sondeo se quedó con
`thermal_zone6` (`mt6357tsbuck2`, un regulador del PMIC) y reporta 67 °C como si fuera el
SoC. Hay que remapear a `mtktscpu` / `mtktsAP` por **nombre**, no por número de zona.

**Carga del aparato:** `load average` marca 16 sobre 8 núcleos, que asusta, pero el CPU
está **72 % ocioso** (`575%idle` de `800%cpu`). No es un problema. Sobran casi 6 núcleos.

---

## USB

Tres buses (`usb1`, `usb2`, `usb3`). Ocupado solo `1-1`:

```
1-1  1a86:7523  USB Serial     ← receptor TPMS CH340
```

`CONFIG_USB_SERIAL_CH341=y` está compilado en el kernel, pero **no aparece `/dev/ttyUSB*`**
ni siquiera como root. Da igual: `tpms/Ch340.kt` habla por la API USB host de Android y no
necesita el driver del kernel. Con el BLE interno funcionando **no hace falta dongle**, así
que sobran puertos — al revés que en el S2000, donde no sobraba ninguno.

---

## Apps de fábrica ya instaladas (útiles como referencia)

`com.jiabaida.little_elephant` (BMS JBD), `com.alpicoolneutral.fridge.controller` (nevera),
`com.syt.tmps` (TPMS), `org.prowl.torque`, `com.ioverlander.ioverlander`,
`com.chartcross.gpstestplus`, `com.solvaig.forcepair`.

⚠️ **La app de la nevera NO barre en este Android**: nunca registra un escáner BLE
(`GATT Scanner Map: Entries 0`) ni con ubicación concedida. Por eso no encuentra la nevera.
La del BMS sí funciona. No es el radio: es esa app.

---

## Decisiones de diseño ya tomadas con el dueño

1. **Una sola pantalla con todo**, sin modos ni vistas que se cambien. Rejilla de secciones.
2. **Medir el carro antes de escribir el tablero.** Esta bitácora es ese paso.
3. **El inversor 12V→120V y el cargador DC-DC no tienen Bluetooth.** Se **deducen** del BMS
   de vivienda: si entra corriente con el motor girando, el DC-DC carga; si sale un pico,
   el inversor tira. Dato deducido y etiquetado como tal, nunca inventado.
4. **Kilómetros y °C**, igual que el S2000, aunque el cuadro del Element sea en millas.
   Criterio único para los dos carros y sin conversiones en el código.
5. **Nada de aviso de VTEC.** Ver abajo.

---

## Lo investigado sobre el K24A4 que cambia el tablero

**El protocolo es el mismo que el AP1: ISO 9141-2 (K-line).** Confirmado por la base de
compatibilidad de Klavkarr con diagnósticos reales de Element 2004/2005/2006. El cambio a
CAN fue en **2007**, con el K24A8. Todo el `PollScheduler` y su reparto de turnos se hereda.

**NO hay "momento VTEC" en este motor, y copiarlo del S2000 sería un error.** Logs reales de
un K24A4 con scan tool: engancha a **2.200-2.345 rpm con ~91 % de carga** y desengancha a
**2.108 rpm / 71 %**. Es un mapa rpm-contra-carga con histéresis que entra y sale
constantemente en conducción normal. Un aviso como el del F20C (5.850 rpm, fondo rojo
parpadeando) aquí estaría encendido a todas horas y no significaría nada.

**El K24A4 sí tiene VTC** (fase variable del árbol de admisión, ±25°), que el F20C no tiene.
Trae códigos propios: `P1009` (VTC Advance Malfunction), `P0010`, `P0011`. El `P1009` es un
fallo real y frecuente en Elements — típicamente aceite degradado o el filtro del VTC tapado.
La tabla de 81 DTC del S2000 hay que **rehacerla**, no heredarla.

**Capacidad nueva:** el Element lleva sensor **LAF de banda ancha** aguas arriba (pieza
`36531-PZD-A01`). El AP1 no lo tiene, y por eso allí MEZCLA se calcula sumando los dos
ajustes de combustible. Aquí `0134` podría dar relación de mezcla **de verdad**.
**Sin confirmar** — se decide leyendo el bitmask, no suponiendo.

**Igual que el AP1:** speed-density sin MAF (`0110` no soportado), acelerador de cable, y
**sin odómetro por OBD-II** (`01A6` es de coches modernos). Sin válvula EGR — el monitor de
EGR debería salir como no soportado en `0101`.

**Corte de combustible: sin cifra fiable.** Honda no lo publica ni en la nota de prensa ni en
el manual. Las fuentes se contradicen entre 6.500 y 6.800 rpm (lo segundo parece ser del
K24A8 de 2007+). El manual sí describe que el limitador es **cíclico** (corta y devuelve),
así que al muestrear `010C` a fondo se verá oscilación, no una meseta. Hay que medirlo.

---

## La nevera: protocolo cerrado antes de tocarla

Cuatro implementaciones independientes coinciden, y el checksum se verificó aritméticamente
contra capturas reales. Que **tu** nevera anuncie el servicio `0x1234` confirma que es esta
familia.

| Cosa | Valor |
|---|---|
| Servicio | `00001234-...` (algunas unidades anuncian `0000fff0`) |
| Escritura | `00001235-...` |
| Notificación | `00001236-...` (CCCD `2902`) |
| Emparejamiento | **No hace falta.** Sin PIN, sin vínculo, sin cifrado |
| Trama | `FE FE <largo> <cmd> <datos...> <checksum 16 bits big-endian>` |
| Checksum | **Suma simple**, no CRC: `sum(bytes previos) & 0xFFFF` |
| Pedir estado | `fe fe 03 01 02 00` |
| Fijar consigna | cmd `0x05`, un byte int8 — **usar largo `0x04`**, no el `0x03` de la app |

Offsets dentro del payload: `0x01` encendida, `0x04` **consigna**, `0x07` histéresis,
`0x09` unidad (0=°C), `0x0E` **temperatura actual** (int8 con signo), `0x10`+`0x11`/10
**voltaje de entrada**.

⚠️ **El estado del compresor NO existe en el protocolo.** Ninguna de las cuatro
implementaciones lo expone en modelos de una zona. **No inventar un byte.** Se deduce
comparando temperatura contra consigna más histéresis, o se mide por fuera con una pinza.

⚠️ **Conectarse por BLE bloquea a los demás:** mientras el tablero esté conectado, la app
del móvil no podrá, y la nevera deja de anunciarse.

⚠️ **Las notificaciones vienen fragmentadas o pegadas.** Hay que acumular en buffer, buscar
`FE FE` y cortar por el byte de largo, en bucle — puede haber varias tramas en una sola
notificación. Al mandar un SET responde con **dos tramas pegadas**: el eco y el estado.

---

## Los BMS JBD

`BmsJbd.kt` del S2000 sirve: el checksum del código coincide con el documento oficial
`Smart bms protocol V4` de JBD, verificado numéricamente contra sus dos ejemplos.

Peticiones: `DD A5 03 00 FF FD 77` (básico), `DD A5 04 00 FF FC 77` (celdas).

⚠️ **El largo del registro `0x03` NO es fijo** — el propio PDF de JBD se contradice (`0x1B`
en el ejemplo, `0x1F` en la explicación). Depende del firmware y del número de sondas NTC.
Hay que parsear por offsets usando el contador de NTC y **aceptar bytes de cola desconocidos
sin tirar la trama**.

⚠️ **Reensamblado:** con MTU 23 cada notificación lleva 20 bytes útiles, y una respuesta
`0x03` son 34. Llega partida en 2-3 notificaciones: acumular hasta ver `DD ... 77` con largo
y checksum coherentes.

**`VigilanteBateria` es monodispositivo de raíz** — un `@Volatile` estado, una MAC en disco,
y un `elegir()` que deja de barrer para siempre en cuanto encuentra una. Con dos bancos hay
que rehacerlo, no parametrizarlo.

---

## ⚠️ Trampa heredada: la auto-actualización pisaría a los dos carros

El descubrimiento UDP comparte token y puerto (`S2000DASH=` en el 8098). **Con los dos
carros en la misma red, el Element se bajaría el APK del S2000, lo rechazaría por nombre de
paquete, y se quedaría sin actualizar para siempre sin que nadie se entere.** Hay que separar
token y `applicationId` antes de que los dos aparatos convivan.

---

## Lo que sigue abierto

1. **El bitmask real de PIDs (`0100`, `0120`, `0140`).** Todo lo dicho arriba sobre qué
   soporta esta ECU es expectativa razonada, **no dato**. Bloqueado por: contacto puesto y
   el OBDLink MX despierto y libre.
2. **`ATDP` en este carro concreto** — confirmar ISO 9141-2 aquí, no en una base de datos.
3. **Si `0134` (banda ancha) existe.** Decide cómo se calcula MEZCLA.
4. **Corte de combustible real.** Banda esperada 6.500-6.800.
5. **Qué es `SMI-M1S`** (`F3:15:4A:EB:79:4D`). Solo anuncia `180a`.
6. **Volcado GATT de la nevera** para confirmar características y cazar el compresor
   comparando bytes con el compresor arrancado y parado.
7. **Cuántas conexiones BLE simultáneas aguanta este radio.** Hacen falta tres (dos BMS +
   nevera). Se mide abriéndolas de una en una.
