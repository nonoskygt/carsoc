# Bitácora — medir por fin las dos variantes

_2026-08-31 · lo que costó la calibración de llantas en Canvas, y las cuatro
cosas que aparecieron al probarla de verdad_

Esta entrada existe porque una tarea pequeña —«que se pueda recalibrar la
presión sosteniendo el dedo»— destapó cuatro defectos que llevaban semanas
ahí. Ninguno se veía leyendo el código.

---

## 1. La variante Canvas gastaba SIETE VECES más CPU que la de HTML

Este es el gordo, y va **en contra de lo que el proyecto llevaba meses
afirmando**. La variante Canvas se justificaba diciendo que es más ligera y
que por eso cuida un radio que ya se apagó dos veces por calor. Nadie lo
había medido. Medido, con el carro parado y sin un solo sensor conectado:

| | hilos | PSS | CPU en 30 s |
|---|---|---|---|
| HTML sobre WebView | 56 | 83,6 MB | **0,5 %** de un núcleo |
| Canvas nativo | 32 | 29,6 MB | **3,4 %** de un núcleo |

Memoria: la mitad de hilos y un tercio de la huella. Eso se cumplía.
**CPU: siete veces peor.** Y la CPU es lo que calienta el radio, que era el
argumento entero.

La causa no era el dibujo sino *cuándo*: el lienzo repintaba la pantalla
entera cinco veces por segundo pasara lo que pasara, mientras que el WebView
solo recompone cuando algún nodo cambia. Con el carro parado no cambia
ninguno. El tablero estaba repintando cuarenta veces seguidas exactamente los
mismos guiones.

Arreglado en `Latido`: se repinta cuando la lectura **cambió** —`DatosTablero`
es `data class`, compararla es comparar todos sus campos— o cuando algo
parpadeaba en el último cuadro.

```
  Canvas, después:   33 hilos    29,4 MB    0,2 %
```

De 3,4 % a 0,2 %. Ahora sí es más ligera en las dos cosas.

**El detalle que importa del diseño.** Lo obvio era preguntarle a los datos
«¿hay alguna alarma?» en un solo sitio. Se descartó: el día que un pintor
empiece a parpadear algo nuevo, esa lista se queda vieja y **el parpadeo nuevo
se congela sin que nadie lo note** — un aviso que no se mueve no parece un
fallo, parece un aviso. Así que la cuenta la lleva quien parpadea: pedir la
hora del parpadeo deja huella, y un pintor que empiece a parpadear queda
apuntado por el mismo acto de hacerlo.

> ⚠️ De ahí la única regla de `Latido`: **pedir la hora solo cuando el
> resultado va a cambiar lo que se pinta.** Pedirla «por si acaso» deja el
> tablero repintando para siempre.

**Aviso honesto:** esto está medido **en el emulador**, no en el radio de la
Element. Los números relativos valen; los absolutos, no. Falta repetirlo en
el aparato.

---

## 2. El S2000 se había quedado sin puerta al menú de emparejamiento

El tablero cyberpunk no tenía botón de Ajustes. `ConfiguracionActivity` **no
está exportada a propósito**, así que sin botón en la pantalla no había forma
de asignar el BMS ni el adaptador OBD en ese carro: quedaba usable solo con
lo que trajera de fábrica. Y el motivo de que ese menú exista es justamente
que las MAC de fábrica cruzaron las dos baterías.

El `AVERÍAS --` de la esquina no servía: es un rótulo de estado, no un mando.

Lo delató el E2E, **y solo porque toca el botón de verdad** en vez de lanzar
la Activity por intent. Una prueba que abre la pantalla por atajo habría
pasado en verde con la pantalla inalcanzable para el dueño.

---

## 3. «Atrás» cerraba el tablero entero, y debajo había otro fallo

Con el calibrador HTML abierto, la tecla de atrás salía del tablero en vez de
cerrar el modal. El modal es un `div` y el WebView es una caja negra desde
Kotlin: la app no sabía que había algo delante. Se arregló haciendo que el
HTML lo avise por el puente.

Y al arreglarlo apareció el de debajo, que es el interesante:

```js
  // dentro de (function(){ ... })()
  function calCerrar(){ ... }
```

`calCerrar` vivía dentro del cierre del script, o sea **no estaba en
`window`**. `evaluateJavascript` se traga el `ReferenceError` sin decir nada,
así que la llamada no hacía absolutamente nada y el modal seguía tapando el
tablero. Cero errores en el log.

> **Regla que sale de esto:** todo lo que Kotlin llame por
> `evaluateJavascript` tiene que estar exportado a `window` **a propósito y
> con un comentario que diga por qué**, o el día que alguien envuelva el
> script en un cierre la llamada se vuelve muda.

---

## 4. La misma piedra del tablero viejo, otra vez

El Canvas del S2000 tachaba de naranja **cinco de las seis celdas** del motor.

Causa: la letra de una fila salía **solo del alto de la caja**. Ese carro no
lleva nevera, así que su columna del medio es del motor entera, y las celdas
pasaron de 150×40 a 150×110. El tamaño ideal se triplicó y dejó de caber a lo
ancho.

Es exactamente el defecto que rompió el tablero viejo del S2000 —columnas del
ancho, letra del alto— y que este fichero llevaba documentado desde entonces.
Volvió a pasar en el código nuevo, escrito por quien lo había documentado.

> **Ser relativo no es ser responsive**, y saberlo escrito tampoco basta. Una
> fila es `etiqueta … número unidad` en UNA línea: su ancho manda tanto como
> su alto, y el tamaño tiene que salir de las dos medidas.

El tope se eligió **midiendo**, no por bonito: se subió hasta que dejó de
tocar las celdas que ya cabían —las del Element, de 110×38, que con el primer
valor salían un 7 % más chicas sin motivo— y se comprobó que seguía salvando
las estiradas del S2000. Un tope demasiado apretado no rompe nada, pero
encoge la letra de un tablero que se mira de reojo.

---

## Lo que aprendió la prueba E2E

Pasa de 15 comprobaciones a 33: recorre **las dos variantes de los dos
carros** —cuatro tableros— en vez de una.

Dos cosas de las que se sacó al escribirla:

- **La primera versión del conmutador de variante comparaba mal.** El detalle
  de la fila dice `"<la puesta>  ·  toca para usar <la otra>"`, y sin recortar
  el espacio la comparación fallaba *siempre*; al fallar tocaba la fila, con
  lo que la prueba **conmutaba la variante que venía a fijar**. Resultado:
  probaba dos veces el mismo tablero diciendo que probaba dos distintos, en
  verde. Ahora **comprueba** que quedó puesta y falla si no.

- **Se comprueba el efecto, no la pantalla.** La calibración se da por buena
  cuando aparece en las preferencias, no cuando se ve el modal. Una captura
  del modal solo probaría que se dibujó bien; lo que importa es que la
  corrección se guarde, porque es la que después cambia el número por el que
  suena la alarma de presión baja.

Y se comprueba **lo contrario también**: que un toque suelto sobre una rueda
NO abra nada. En un carro que se mueve se toca la pantalla sin querer.

---

## Lo que sigue abierto

- **El consumo real en el radio de la Element sigue sin medirse.** Lo de
  arriba es emulador. Es la tercera vez que esta línea aparece en una
  bitácora; ahora al menos hay una cifra con la que comparar.
- El motor de la Element no se ha leído nunca: falta el adaptador OBD.
- Los umbrales del VTEC del K24A4 siguen sin calibrar contra este vehículo.
