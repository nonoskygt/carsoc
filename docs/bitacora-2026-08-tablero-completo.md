# Bitácora — lo que el tablero mide, y por qué cada número es ese

_2026-08-27/28 · rama `descubrimiento-ble-usb` · v9.5 (versionCode 99) · 152 pruebas verdes_

Continuación de [bitacora-2026-08-radio-nuevo.md](bitacora-2026-08-radio-nuevo.md),
que cubre el **hardware y el acceso** al head unit nuevo. Esta cubre lo
**funcional**: qué se mide, de dónde sale cada dato, y las trampas que costaron
horas y no se deducen leyendo el código.

---

## Estado del tablero

```
MOTOR                    BATERIA DE LITIO           ADMISION
  AGUA      °C             %  ·  W                    COLECTOR   PSI
  AIRE      °C             V  ·  °C                    ACEITE      %
  MEZCLA    %            LLANTAS (4 cajas)             AVANCE      °
  ALTERNADOR V             PSI + C                     VTEC     SI/NO

  [!] diagnóstico          RADIO °C                    [X] cerrar
  VTEC (al enganchar)                        estado de enlaces (solo si falla)
```

Fuentes: **motor** por la radio interna (BR/EDR), **batería** por el dongle USB
(BLE), **llantas** por el CH340, **aceite** por GPS + horas contadas.

---

## Lo que la ECU de este carro SÍ tiene

Se le preguntó con el PID `0100` en vez de suponer. Contestó:

```
4100BE3EF810
```

| Soporta | No soporta |
|---|---|
| `0101` luz de avería + nº códigos | `0110` MAF — es **speed-density** |
| `0103` lazo abierto/cerrado | `011F` tiempo de motor |
| `0104` carga calculada | `01A6` odómetro |
| `0105` `010F` temperaturas | **nada por encima de `0x20`** |
| `0106` `0107` **ajustes de combustible** | |
| `010B` colector · `010C` rpm · `010D` velocidad | |
| `010E` avance · `0111` acelerador | |
| `0112` `0113` `0114` `0115` sondas | |
| `011C` norma OBD | |

El último bit del `0100` está en 0: **no hay segundo bloque**. Ni temperatura de
aceite, ni presión barométrica, ni AFR mandado, ni odómetro. Esa consulta cerró
de golpe varias suposiciones que el proyecto arrastraba.

### El `0104` de esta ECU llega ROTO

Contesta `414B` en vez de `41044B`: **le falta el byte del PID**. El `0100` lo
declara soportado y todos los demás contestan con su encabezado completo
(`41067A`, `410E88`, `411112`), así que no es el adaptador comiéndose bytes en
general — es este PID concreto.

Consecuencia: la carga salía vacía **para siempre**, y con ella se caía la
detección del VTEC. Se acepta el formato corto sólo ahí, con dos guardias:
exactamente `41` más un byte, y resultado en 0..100. Medido en el carro: **27%
a 799 rpm**, que es el ralentí de un F20C.

---

## Cada dato y de dónde sale

### MEZCLA es un porcentaje REAL, y no viene de la sonda

La sonda de este carro es de **banda estrecha** (`0114`): un voltaje que sólo
dice de qué lado de la estequiométrica está. Sacarle un porcentaje sería
inventarlo, y el AFR de banda ancha (`0134`) no existe aquí.

Lo que sí es un porcentaje medido es la **suma de los dos ajustes de
combustible** (`0106` + `0107`): cuánto corrige la centralita sobre la inyección
base. Cero es perfecto.

- **Positivo** → mete gasolina de más porque lee POBRE → **rojo**
- **Negativo** → la quita porque lee RICA → **ámbar**
- **±10%** → verde

Pobre se lleva el rojo porque es el lado que sube la temperatura de combustión;
rica ensucia y gasta, pero no funde nada.

**Medido en el carro con el motor andando: −4 / −5 %.** Motor sano de mezcla.

### VTEC es una DEDUCCIÓN, no una señal

OBD-II genérico **no expone el solenoide del VTEC** en ningún carro. Se infiere
de `rpm ≥ 5850` **y** `carga ≥ 60%`. Honda publica ese cruce para el AP1; el 60%
es una guarda para no cantar VTEC en retención.

Estuvo **doblemente muerto** hasta esta sesión: nunca se pintaba, y `loadPct`
salía siempre null por el defecto del `0104`. Además `COLOR_VTEC_ON` —la
constante reservada para él— se usaba para marcar una presión de llanta absurda.

Ahora: fila `VTEC SI/NO/--` y **el fondo entero parpadea en rojo** al enganchar.
El rojo es oscuro a propósito (`0xFF5A0000`): esto pasa a 5850 rpm con el pedal
a fondo, o sea de noche también, y un rojo saturado a pantalla completa borra
los números justo cuando el motor trabaja al máximo. Parpadea a 500 ms y no más
rápido, porque el tablero repinta entre 5 y 1 fps según lo caliente que esté.

Se exige **carga fresca**: las rpm se leen 60 veces por periodo y la carga 6, así
que cantar un VTEC mezclando datos de dos momentos sería inventarlo.

Para probarlo sin redlinear: `GET /vtec?segundos=10` enciende sólo el aviso.

### Vida del aceite: GPS + horas

**El odómetro no se puede leer de la ECU** (ver tabla arriba). Tampoco el tiempo
de motor. Así que:

- **Kilómetros por GPS.** El receptor del radio da 15 m de precisión. Se descartó
  integrar la velocidad del OBD: se muestrea cada 1,6 s y en ciudad —parar,
  arrancar, parar— el error se acumula; sobre 6000 km serían cientos de km.
- **Horas contadas en `DashService`**, sumando tiempo con `rpm ≥ 300` y dato
  fresco. Se suma el tiempo REAL transcurrido, no una constante: si la ROM
  congela el proceso, sumar 5 s fijos inventaría horas.

**El odómetro es un ANCLA, no una medida.** El tablero no sabe cuánto ha andado
el carro en su vida: sabe cuánto desde que el dueño le dio un número. Se reancla
con `GET /aceite?odometro=NNNNN`.

Valores del dueño: **ancla 73456 km, próximo cambio 77897, intervalo 6000 km /
200 h**.

#### De dónde salen las 200 horas

Honda **no publica** intervalo en horas — ningún fabricante de turismos lo hace.
Donde sí se usan es en flotas, con la equivalencia práctica de **1 hora ≈ 30 km**
de uso mixto. 6000 ÷ 30 = **200 h**. Coincide con la horquilla de 150–200 h que
se recomienda para servicio severo, y el tráfico parado *es* servicio severo: el
aceite se calienta y se cizalla igual, pero el odómetro no avanza.

Es una convención razonada, no una cifra de Honda. Se cambia con `?horas=`.

#### Dos guardias contra kilómetros fantasma

El radio vive enchufado al 12 V constante aunque saques la llave, y **el GPS
parado deriva metros por minuto**. Sin filtro, un carro aparcado una semana se
"recorrería" kilómetros solo. Se exige **velocidad > 5 km/h** y **precisión < 40
m**, y se descartan saltos de más de 500 m entre muestras — a 5 s por muestra
eso serían 360 km/h.

#### El porcentaje toma el PEOR de los dos

`vidaPct = min(km%, horas%)`. No la media: un carro de tráfico llega al final por
horas mucho antes que por kilómetros, y promediar dejaría pasarse siempre por el
lado que más corre.

La fila **se toca** para ciclar entre `%` → `km` → `h` → `odómetro`. Y se
**sostiene 5 s** para pedir el reinicio, que además exige un `SI` explícito.
Reiniciar borra la única cuenta que existe —no hay odómetro real de donde
recuperarla— así que van los dos filtros. Mientras se sostiene, una barra se
llena: cinco segundos sin que pase nada se leen como que no funciona.

---

## Alertas de llanta

Dos averías distintas, tratadas distinto:

| | Umbral | Interrumpe |
|---|---|---|
| **Presión baja** | < 24 psi (75% de placa) | no |
| **Pinchazo** | −3 PSI en 2 minutos | **sí** (`setFullScreenIntent`, `CATEGORY_ALARM`) |

Los 3 PSI en 2 min no son arbitrarios: **rodando, una llanta se calienta y por
tanto SUBE casi una libra**, así que perder tres en ese rato no es temperatura ni
ruido del sensor — es aire saliendo.

El canal usa **sonido de ALARMA**, no de notificación:

```
mSound = content://settings/system/alarm_alert
mAudioAttributes = usage=USAGE_ALARM
mImportance = 4 (ALTA)
mVibration = [0, 400, 200, 400, 200, 600]
```

Con música puesta un "ding" de notificación se pierde debajo, y este aviso llega
manejando: o se oye, o no sirve. Con `USAGE_ALARM` suena aunque el radio esté en
silencio.

Se avisa **una vez por rueda** y no se repite hasta que se recupere: una
notificación que se repite cada trama es una que el dueño aprende a ignorar.

Probar sin pinchar nada: `GET /probar-alerta`.

---

## Códigos de avería (`diag/`)

Módulo **aparte**, su propia Activity. Carga 81 códigos desde `res/raw/dtc.txt` al
abrirse y los **suelta en `onDestroy`**. Con el tablero en marcha ocupa lo que
ocupa una clase sin instanciar.

### La tabla: 81, filtrados contra ESTE carro

Nada de MAF, EGR, banco 2, transmisión automática, turbo, cilindros 5-8, VTC ni
acelerador electrónico —el del AP1 es de **cable**—. **28 son `P1xxx` de Honda**,
que son justo los que un lector genérico enseña como número pelado.

Tres verificadores adversariales encontraron 32 problemas en la primera versión:
duplicados, explicaciones con síntomas de otro motor, y huecos. El más grave: **no
había ni un código de detonación**, y el F20C es 11.0:1 y vive de que el sensor
de knock funcione. Entró `P0325` y seis más.

Tres correcciones técnicas aplicadas a mano tras el último repaso:

- **`P1297`** — el ELD de Honda saca voltaje **inversamente** proporcional a la
  corriente. Señal clavada abajo = carga máxima, no carga cero. La explicación
  decía lo contrario y describía el síntoma del `P1298`.
- **`P0108`** — con el MAP fuera de rango físico la ECU no inyecta a partir de un
  valor imposible: lo descarta y sustituye.
- **`P0122`** — con acelerador de cable y motor speed-density, el TPS muerto no
  deja de inyectar; lo que se pierde es el enriquecimiento al pisar.

### ⚠️ La trampa del parser: `indexOf` daría códigos FANTASMA

El resto del proyecto localiza respuestas con `hex.indexOf(prefijo)`, correcto
para prefijos de 4 caracteres como `410C`. Para el modo 03 **NO**: su prefijo es
`43`, de dos caracteres, y **`P0143` se codifica literalmente `0143`** — así que
la trama `43014300000000` lleva un `43` en la posición 4.

Con `indexOf` se decodificaría basura y se le enseñarían al dueño averías que su
carro no tiene. En `Dtc.kt` el prefijo se **ancla al principio de la línea**, y hay
una prueba dedicada a ese caso exacto.

### Dos cosas que sin investigar no se sabían

**Hay que mandar `ATAT0` y `ATST FF` antes del modo 03.** Con la temporización
adaptativa que traen de fábrica, el ELM327 **corta después de la primera trama y
pierde los códigos 4 al 6 SIN AVISAR**. Seis averías, tres mostradas, y nada en
la respuesta que diga que faltan.

**`NO DATA` significa carro sano, no adaptador roto.** Muchas ECU de esta época no
contestan al `03` cuando no tienen nada. Tratarlo como fallo de comunicación es
decirle al dueño que su dongle no sirve justo cuando la noticia era buena.

### Los tres modos, no sólo el 03

| Modo | Qué da |
|---|---|
| `0101` | **primero siempre** — cuántos códigos hay, para saber si el 03 los trajo todos |
| `03` | guardados (encendieron la luz) |
| `07` | **pendientes** — la avería *antes* de que encienda la luz |
| `0A` | permanentes — obligatorio desde 2010, en un carro del 2000 lo normal es que no exista |
| `04` | borrar |

Al borrar se **relee para comprobar**: la ECU puede aceptar el `04` y no borrar
nada si la avería sigue activa. Y el menú avisa **antes** de tocar el botón que
el `04` también tumba los monitores de emisiones — si vas a pasar revisión, te
rebotan.

**Verificado contra el carro real:** conectó por RFCOMM, habló ISO 9141-2,
preguntó y cerró limpio. Sin averías.

---

## Arranque automático: lo resuelve el confirmador

Se midió con un reinicio de verdad. El `BootReceiver` del tablero **no recibió
nada**, mientras `com.carletter.car` sí recibía el suyo: esta ROM tiene lista
blanca de autoarranque y el tablero no está en ella. `QUICKBOOT_POWERON` tampoco
sirvió.

Pero en ese mismo log **sí apareció el confirmador arrancando**. Android inicia
los servicios de accesibilidad en cada arranque, **antes que cualquier lista del
fabricante**, porque el sistema debe garantizar que quien depende de accesibilidad
pueda usar el aparato. Es el único componente del proyecto que la ROM no puede
ignorar.

Así que el confirmador —que nació para pulsar "Instalar"— hereda un segundo
trabajo: levantar el tablero desde `onServiceConnected`. **Verificado con un
segundo reinicio: arrancó solo a los ~80 s y con la pantalla al frente.**

---

## Títulos quietos

Los títulos cambiaban de texto en cada transición del enlace y el resultado era
un tablero inquieto: carteles bailando encima de números parados. Ahora son
**fijos**, y el motivo de cada fallo se junta en **una línea de estado abajo a la
derecha** que sólo aparece cuando algo va mal. Con los tres enlaces leyendo, esa
línea está vacía.

---

## La limpieza: 2.594 líneas fuera

Barrido de 4 agentes y **cada candidato pasado por un verificador cuyo único
trabajo era demostrar que seguía vivo**: 44 agentes, 655 llamadas a herramientas.
34 confirmados muertos, 6 rescatados.

### La isla del RFCOMM sobre el dongle (100 KB)

`CajaNegra.kt`, `rfcomm/CanalRfcomm.kt`, `rfcomm/TramaRfcomm.kt`,
`rfcomm/SdpCanalSpp.kt`, `l2cap/CanalL2cap.kt`, `hci/SondaAcl.kt` — cinco
ficheros que sólo se referenciaban entre sí. Resto del intento de hablar RFCOMM
sobre la pila HCI propia; dejó de hacer falta cuando el OBD pasó a la radio
interna.

⚠️ **`GestorL2cap` (en `hci/`) es OTRA clase y SIGUE VIVA**: sostiene el GATT de
la batería por el dongle. Los nombres se parecen y confundirlas rompe la batería.

### La cadena del O2 — no sólo ocupaba, GASTABA

`o2Voltaje`, `o2AtMs`, `colorO2`, `decodeO2Voltaje` y **4 turnos de K-line por
periodo**. Le preguntaba a la ECU cuatro veces para tirar la respuesta, desde que
`MEZCLA` pasó a salir de los ajustes. Los turnos **no se repartieron**: se
devolvieron al presupuesto, y **el RPM subió de 6,0 a 6,7 Hz**.

Se conserva `PID_O2_V` porque la prueba lo usa para verificar que ese PID ya
**no** se pide. Es un centinela, no un resto.

### El selector de adaptador de `DashActivity` (130 líneas)

`savedDevice`, `showPicker`, `label`, `beginScan`, `beginPolling`, `observe`, más
`scheduler`, `observeJob`, `pickerDialog` y `requestScanPermission`. `showPicker`
era la raíz y nadie la llamaba. Elegir y emparejar se hace por el puente.

### Y el rastro del AFR de banda ancha

`decodeLambda`, `afrDesdeLambda`, `PID_O2_ANCHA`, `PID_O2_ESTRECHA` (duplicado
exacto de `PID_O2_V`) y los campos `lambda`/`lambdaAtMs`. Se pidió el `0134`
durante quién sabe cuánto para recibir vacío, hasta que la consulta del `0100` lo
zanjó.

---

## Presupuesto de la K-line

La §5 le reserva al RPM 6 Hz. Con 60 ciclos de RPM más S secundarios y ~0,1 s por
lectura: `RPM = 600/(60+S)` Hz, o sea **S ≤ 40**.

| Dato | Turnos por periodo |
|---|---|
| colector | 6 |
| carga | 6 |
| avance | 4 |
| agua · aire · voltaje | 3 cada uno |
| ajuste corto · ajuste largo | 2 cada uno |
| estado (`0101`) | 1 |
| **total** | **30** → RPM a 6,7 Hz |

Fuera del reparto, por decisión del dueño: **velocidad** (ya está en el cuadro
original y gastaba 20 turnos en un dato duplicado), **acelerador** (el pie ya sabe
dónde está) y **O2**.

La prueba `la aguja se queda con su parte del presupuesto` lo vigila y ya cazó un
intento de meter 16 turnos que habría bajado el RPM a 5,4 Hz.

---

## Consumo real, medido

Con el tablero cerrado y sólo el servicio:

```
hilos míos: 4   (tpms-lector, termometro, revisor-actuali, debug-server)
heap Java:  1 MB
PSS total:  27 MB   (casi todo framework y driver de GPU)
```

Los otros 23 hilos son `mali-*` (GPU), `RenderThread` y runtime.

---

## 28 de agosto: el día que la ECU habló de verdad

Hasta esta sesión, el módulo de diagnóstico **nunca había leído un código con
el motor girando**. Sólo estaba probado el camino del fallo: con el carro
apagado salía el error rojo, que ya era mucho más que el «SIN AVERIAS» verde
mentiroso de antes. Pero el camino bueno no se había ejercitado jamás.

Con el motor a 906 rpm y el agua a 91 °C, y capturando el tráfico SPP crudo
del propio radio (`logcat` vuelca los `send:` del stack Bluetooth), quedó
demostrado que los tres arreglos de la sesión anterior salen **de verdad por
el cable**:

| bytes en el log | ASCII | qué es |
|---|---|---|
| `41 54 41 54 30 0D` | `ATAT0\r` | temporización fija |
| `41 54 53 54 20 46 46 0D` | `ATST FF\r` | su otra mitad |
| `41 54 53 50 35 0D` | `ATSP5\r` | ISO 9141-2 |
| `30 33 0D` `30 37 0D` `30 41 0D` | `03` `07` `0A` | los tres modos |

El stack **no registra lo que entra**, sólo lo que sale, así que la respuesta
tuvo que sacarse por la ruta nueva `/dtc`:

```
0101 -> 410100076D25       la ECU contesta: luz apagada, 0 códigos
03 (guardados)   -> NO DATA
07 (pendientes)  -> NO DATA
0A (permanentes) -> NO DATA
```

**`NO DATA` es una respuesta, no un silencio.** El carro está sano de verdad,
y por primera vez está demostrado en lugar de supuesto — que es exactamente la
distinción que el arreglo de `huboRespuesta()` existía para defender.

### La colisión de enlaces: estaba en `main` sin que nadie la hubiera escrito

`LectorDtc` abre su **propia** conexión SPP —no le queda otra: el modo 03 exige
fijarle la temporización al adaptador, y hacérselo al sondeo en marcha le
estropearía el ritmo a mitad de una lectura— y el Steren atiende **un solo
enlace RFCOMM**.

`DiagnosticoActivity` llama al lector **a pelo**, sin parar el sondeo. `/pids` y
`/obd-spp` tenían el mismo agujero. Con el sondeo corriendo son dos sockets
contra el mismo clon: o el segundo muere, o —peor— el clon acepta los dos, una
respuesta de RPM cae dentro del buffer del modo 03 y se decodifica como averías
que el carro no tiene.

Medido: con el sondeo vivo, `/pids` contestaba

```
RFCOMM fallo por todas las vias: inseguro-SPP=read failed... seguro-canal1=read failed
```

La ruta `/dtc` sí apaga el sondeo antes, y `/pids` pasó a preguntar **por el
enlace que ya está abierto** en vez de abrir uno propio.

### Seis segundos, medidos, no de manual

La primera versión de `/dtc` esperaba 2 s a que el adaptador soltara el canal.
Contra el carro real no bastaba: `SppTransport` se lanzaba a sus cuatro vías
contra un adaptador todavía ocupado y **la petición tardó cuatro minutos** en
vez de medio. El socket del puente muere a los 120 s, así que quien preguntaba
se quedaba sin respuesta de una lectura que sí estaba ocurriendo — y peor, el
sondeo se quedó sin volver: el tablero en marcha y ciego.

Ahora son 6 s, y las reanudaciones van en `runCatching` para que un fallo al
volver no arrastre a las otras dos.

---

## MEZCLA podía decir «+3 %» en verde con el motor al límite

El hallazgo más grave de la sesión, y salió de barrer el proyecto entero
buscando la trampa que ya nos había mordido tres veces.

`totalAjuste()` sumaba los dos ajustes de combustible **rellenando con cero el
que faltara**. Pero un cero en un ajuste no significa «no lo sé»: significa
«está corrigiendo perfecto», que es la respuesta contraria.

`PollScheduler` saca un PID de la rotación tras tres fallos seguidos y no lo
vuelve a pedir en esa conexión. Si le toca al `0107`, el ajuste largo se queda
en `null` para siempre y MEZCLA pasa a ser el corto solo. **Un motor con el
corto en +3 % y el largo en +22 %** —fuga de vacío, la centralita al límite de
lo que puede corregir— **se pintaba «+3 %» en VERDE**.

Y desde que la fila AJUSTE dejó de pintarse aparte, ésta es la única fila donde
el dueño ve los ajustes: no queda ningún sitio donde notar que falta la mitad
del número.

Segundo defecto en la misma fila: el color miraba **una sola edad**, la del
corto. `trimLargoAtMs` existía, se escribía en cada lectura, y **no lo leía
nadie en todo el proyecto**. Los dos sumandos casi nunca son del mismo
instante —el corto se pide en los turnos 21 y 42, el largo en el 12 y el 48— y
los dos huecos del largo (~4,8 s y ~3,2 s) pasan de los 3 s de
`STALE_AFTER_MS`. La mitad larga se pintaba con color vivo estando rancia.

Ahora: los dos ajustes o ninguno, y manda la edad del sumando más viejo. MEZCLA
se pone gris más a menudo que antes; eso no es una regresión, es que antes se
pintaba viva cuando no le tocaba.

---

## Rutas nuevas del puente (`:8099`)

| Ruta | Qué hace |
|---|---|
| `/pids` | le pregunta a la ECU qué PIDs soporta y los nombra |
| `/aceite` | estado; `?odometro=` reancla, `?cambiado=1` reinicia, `?proximo=` `?intervalo=` `?horas=` |
| `/vtec?segundos=` | fuerza el aviso de VTEC para verlo sin redlinear |
| `/probar-alerta` | lanza una alerta de llanta de prueba, **y dice si Android la dejará sonar** |
| `/dtc` | lee los códigos de avería con la traza cruda; `?borrar=1` los borra |
| `/vtec?forzar=0` | pregunta sin encender el aviso: rpm máximas **con carga**, veces que enganchó |
| `/obd-spp?mac=` | diálogo AT por la radio interna |
| `/soltar-bt` | suelta la radio Bluetooth sin tocar la pantalla |

---

## Deuda y avisos

- **El servicio GNSS de la ROM se cayó** durante las pruebas
  (`Fatal signal 6 (SIGABRT) in gnss@2.0-service`). No es la app: es el HAL de
  GPS del fabricante. Volvió solo y el registro sobrevivió, pero si se repite el
  odómetro acumulará huecos y habrá que reanclar más seguido.
- **El radio llegó a 78–81 °C** con el carro al ralentí y el vano caliente. El
  guardián cedió correctamente (1 fps, OBD y batería bloqueados), pero el margen
  es estrecho.
- **`AIRE` marcó hasta 135 °C** al ralentí parado. Es plausible por remojo de
  calor sin flujo de aire, pero **si andando no baja de 100, el sensor está mal**.
- `BootReceiver` sigue sin recibir nada en esta ROM. Se conserva por si cambia.
- `gradlew` wrapper nunca commiteado. Gradle en `~/tools/gradle-8.7`.
- Queda sin probar la hipótesis del MTU sobre el BLE interno (ver la otra
  bitácora): hace falta el dongle fuera y el BMS descansado.
- **`AIRE` 130 °C confirmado una segunda vez** (28-ago, al ralentí parado con
  el vano caliente). Sigue siendo plausible por remojo de calor. **La prueba
  pendiente es la misma: si andando no baja de 100, el sensor está mal.**
- **Tres cosas siguen sin verificarse conduciendo**, pero ya se pueden
  *observar*, que era el problema real:
  - El odómetro por GPS nunca se ha contrastado contra el real en un viaje.
    Primera lectura de los contadores nuevos: `fijas=1`, `±15 m`. El receptor
    está vivo y dentro del guarda de 40 m — eso antes era indistinguible de una
    antena muerta.
  - El fondo rojo del VTEC no se ha visto nunca. `/vtec?forzar=0` dice ahora
    cuántas rpm se alcanzaron **con carga suficiente**, o sea cuánto faltó.
  - La alarma de pinchazo no la ha oído nadie. `/probar-alerta` usa la ruta
    real (verificado: misma función, mismos argumentos, mismo canal) y ahora
    avisa si Android tiene el canal silenciado, que es la forma de fallar que
    no lanza excepción.
- **`ventanas comparadas` es del proceso, no del viaje.** Si Android relanza el
  servicio a mitad de camino vuelve a cero y parece que midió menos. La edad de
  la referencia y `reaperturas`, que salen al lado, dan el contexto.
- **`LectorBmsGatt.utilizable()` no la llama nadie.** Está documentada como la
  puerta que impide pintar un voltaje de litio equivocado, y `publicarLectura`
  publica sin consultarla. No se tocó porque no se pudo demostrar que muerda
  hoy, y cablear `creible()` entero sería peor el remedio (`largoInesperado`
  dejaría la columna en blanco con un módulo de campos de más). Lo mínimo
  seguro sería `c?.takeIf { it.creible() }?.sumaV`.
- **`LectorDtc` da `luzEncendida = false` si el `0101` se pierde**, en vez de
  «no lo sé». Es el único de los cuatro modos que no alimenta la lista de
  `fallos`. Miente en la dirección tranquilizadora, pero con la luz encendida
  los códigos existen y el modo 03 los trae, así que la contradicción se ve.
