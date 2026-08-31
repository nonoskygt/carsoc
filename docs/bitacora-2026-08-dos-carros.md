# Bitácora — el día que fueron dos carros

_2026-08-30 · rama `element` · dos sabores, 205 pruebas por sabor_

El proyecto pasa de una app a **dos hermanas**. Esta bitácora recoge lo que
costó y, sobre todo, lo que se rompió por el camino — que es lo que no se
deduce leyendo el código.

---

## La decisión: sabores, no forks

Un solo módulo con dos `productFlavors`. Solo cuatro cosas viven en
`src/<sabor>/`:

| | |
|---|---|
| `EngineConstants.kt` | los números del motor |
| `PerfilVehiculo.kt` | quién es el carro y qué hardware lleva |
| `res/raw/dtc.txt` | la tabla de averías de ESE motor |
| `res/values/strings.xml` | el nombre de la app |

Todo lo demás es común.

**`applicationId` distinto es obligatorio, no cosmético.** Con el mismo, el
actualizador de un carro se traga el APK del otro, lo rechaza por nombre de
paquete, y **se queda sin actualizar para siempre sin que nadie se entere**.
Estaba apuntado como trampa desde antes de que existiera el segundo carro.
Por la misma razón, el token del descubrimiento UDP también va por sabor.

`PerfilVehiculo` es la pieza que evita que ninguna clase compartida tenga
que preguntar «¿en cuál de los dos estoy?». Ahí vive la diferencia que más
se nota en pantalla:

- El Element declara `TIENE_AFR_REAL = true` — lleva sonda LAF de banda
  ancha de fábrica, así que el reloj de mezcla tiene sentido.
- El S2000 lo declara `false` — su sonda es de banda **estrecha** y su mapa
  de PIDs se corta en `0x20`, así que el `0134` no existe. Su reloj se
  dibuja **apagado**, que es distinto de no dibujarlo y muy distinto de
  inventarle un número.

---

## Lo que se rompió al separar, y por qué estuvo bien que se rompiera

Al compilar el sabor S2000 por primera vez, **cuatro pruebas fallaron**.
Eran las del VTEC, y afirmaban `2 200 rpm`.

El fallo era **correcto**. Una prueba que dice «el VTEC engancha a 2 200»
es una afirmación sobre el K24A4, no sobre el código. En el F20C engancha a
5 850 y la misma prueba tiene que decir otra cosa.

Ahora cada juego vive con su sabor, en `src/testElement/` y `src/testS2000/`,
y en el común quedan solo las que valen para los dos carros —la disciplina
de rancio, los invariantes de orden de los umbrales—.

**La regla que sale de esto:** si una prueba afirma una cifra concreta del
vehículo, no es una prueba del código y no pertenece al juego común.

---

## Responsive: la lección que ya estaba pagada

El `DashView` del S2000 **ya estaba escrito con medidas relativas**. Su
diseño lo exigía por escrito: *«medidas relativas al viewport, no píxeles
fijos»*. Y aun así se rompió al pasarlo a 1024×600: los números se pisaban.

La causa, medida en el código:

```
  57 medidas dependen de  h  (el alto)
  10 medidas dependen de  w  (el ancho)
```

Las columnas salen de `w / 3` pero **toda la tipografía sale de `h`**. A
2.67:1 los dos números se llevaban bien; a 1.71:1 la columna se estrecha un
20 % mientras la letra crece un 25 %.

> **Ser relativo no es ser responsive.**

De ahí salen las dos soluciones de esta sesión:

- **En HTML**: el tablero se *diseña* en 1024×600 y se *escala* al viewport.
  Entra en cualquier pantalla sin descuadrarse; el precio son bandas cuando
  la relación de aspecto no coincide, y es barato.
- **En Canvas**: un repartidor de cajas por pesos, y **la tipografía se
  deriva de la caja, no de la pantalla**. Con una prueba que comprueba que
  las cajas no se solapan a 1280×480, 1024×600 y 800×480.

---

## El emparejamiento: dejar de adivinar

Las MAC vivían escritas en el código. Peor: el vigilante viejo ni eso —
barría el aire y se quedaba con **el primer BMS que veía**. Con dos bancos
del mismo fabricante, cuál te toca es cuestión de suerte, y el dueño vio la
batería de arranque bajo el rótulo de la de vivienda.

Ahora cada papel tiene un aparato elegido a mano y guardado. El perfil solo
aporta el valor por omisión, **y la pantalla lo dice**: un valor de fábrica
se muestra marcado como tal, porque uno que parece elegido hace creer que
alguien lo comprobó.

Un papel sin aparato asignado **no se sondea**: conectar a una cadena vacía
gasta un turno de radio por ciclo y llena el registro de fallos que no son
fallos.

---

## El emulador: dos horas por un diálogo

El emulador **nunca arrancaba**. El proceso vivía, `qemu` consumía memoria,
y ADB no lo veía jamás. Sin errores en el log.

La causa estaba en una línea perdida entre trazas de GPU:

```
INFO | Showing crashdialog to get consent.
```

El emulador se había caído en un intento anterior y al arrancar sacaba un
**diálogo de consentimiento que bloquea el arranque esperando un clic**. En
una prueba automática ese clic no llega nunca.

Con `-no-metrics` arranca en **40 segundos**.

Se probaron antes dos callejones sin salida que conviene no repetir:

| Hipótesis | Cómo se descartó |
|---|---|
| El AVD clonado a mano está corrupto | Se recreó con `avdmanager`. Mismo síntoma. |
| El servidor ADB no lo descubre | `kill-server` + `start-server`. Mismo síntoma. |

Y el segundo: **`uiautomator dump /dev/tty` se cuelga**. A fichero funciona.

---

## Trabajar con agentes en paralelo: la carrera del contrato

Los cuatro pintores del Canvas se escribieron a la vez, y **divergieron del
contrato de datos**: uno llamaba a helpers de `DatosTablero` que otro no
había escrito. Compilar es lo único que lo detecta.

Y un fallo de Kotlin que merece anotarse: en un `object`, las propiedades se
inicializan **en orden de declaración**. Una constante declarada 70 líneas
por debajo de su uso falla con `must be initialized`, aunque sea `const`.

---

## Lo que sigue abierto

- **El motor del Element no se ha leído nunca.** Falta el adaptador OBD.
  Hasta entonces, todo lo que el proyecto afirma sobre qué PIDs tiene ese
  carro es expectativa razonada, no dato.
- **Los umbrales del VTEC del K24A4 están sin calibrar** contra este
  vehículo: salen de datalogs de un Accord con el mismo motor.
- **Nadie ha medido el consumo** de la variante WebView en el radio. Es la
  media razón de ser de la variante Canvas, y sigue siendo una sospecha.
