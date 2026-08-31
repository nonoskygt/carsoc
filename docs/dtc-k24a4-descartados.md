# Qué se dejó fuera de la tabla del Honda Element 2003-2006 (K24A4, automático) y por qué

Este documento vale tanto como la tabla. La tabla dice qué códigos puede dar este carro;
esto dice qué códigos NO puede dar y por qué, para que nadie los vuelva a meter "porque
salen en internet" o "porque están en el boletín".

Tabla final: **102 códigos**, todos únicos, ordenados de forma ascendente.

---

## 0. La regla que decide todo

El fallo que hundió la tabla anterior (la del S2000) fue incluir de más: códigos copiados
de la lista SAE genérica o del boletín general de Honda, para piezas que ese carro no
monta. Por eso aquí se aplica esta jerarquía de evidencia, de más fuerte a más débil:

1. **Imposibilidad física.** No existe la pieza (no hay banco 2, no hay 5ª marcha, no hay
   MAF, no hay EGR, no hay sensor de leva B). Descarte inmediato, sin discusión.
2. **Comprobación directa contra el índice de DTC del manual de fábrica del Element**
   (2003 2WD 2.4L y 2006 4WD 2.4L).
3. **Confirmación en el mundo real sobre un Element** (o sobre un Accord/CR-V K24 del
   mismo ECM), no sobre "otro Honda".
4. **Coincidencia de varios verificadores citando el mismo texto de la fuente.**
5. Aparición en el Service Bulletin 03-020 de American Honda.

**El punto 5, por sí solo, no prueba nada.** El boletín 03-020 cubre todos los Honda con
OBD II menos el Passport: dentro hay códigos de V6, de híbridos, de cajas de 5 velocidades
y de acelerador electrónico. "Está en el boletín" es exactamente el argumento que metió
IMRC, MAF y bancos 2 en la tabla del S2000. Se usa para *confirmar*, nunca para *admitir*.

Cuando dos fuentes se contradicen y no se puede verificar, **se deja fuera** y se anota
aquí como punto abierto. Es preferible un hueco documentado a una ficha inventada.

---

## 1. Códigos que estaban en la tabla en bruto y se han QUITADO

### P0133 — quitado (imposible en un 4 cilindros)
Dos verificadores independientes citan el mismo texto del boletín 03-020: P0133 es
**"Rear Air/Fuel Ratio (A/F) Sensor (Bank 1, Sensor 1) Circuit Slow Response"**, emparejado
con P0153 "Front A/F Sensor (Bank 2, Sensor 1)". Ese esquema de banco delantero y banco
trasero solo existe en los V6 transversales de Honda (J-series). Un 4 cilindros en línea
tiene un solo banco: no hay sonda "trasera de banco 1". Además la fila contaba la misma
historia que P2A00 (sonda de banda ancha envejecida y lenta), así que su contenido no se
ha perdido: **la respuesta lenta y la deriva de la sonda LAF viven ahora en P2A00**.

### P0365 y P0369 — quitados (la pieza no existe)
Son los códigos del **sensor de árbol de levas B, el del árbol de ESCAPE**. El K24A4 del
Element monta un único sensor de leva (el catálogo Honda lista una sola pieza,
37510-RAA-A01, para Element 2003-2011) más el sensor de cigüeñal. El "CMP sensor B"
aparece con las culatas de dos sensores posteriores (K24Z / R18: Accord 2008+, CR-V 2012+).
Sin sensor físico el ECM no puede emitir el código. Las dos filas, además, describían una
ubicación inventada ("en el lateral de la culata hacia el lado de la caja") y un "anillo
dentado del árbol de escape" que ahí no existe.

Esto ya estaba anotado en la lista de descartes del área de VTC ("P0365, P0366 — el K24A4
monta un único sensor de árbol de levas. No hay sensor B"): la tabla en bruto se
contradecía a sí misma porque dos áreas se fusionaron sin cruzar sus descartes.

Consecuencia: **tampoco se añade P0366**, que un verificador pedía como hueco.

---

## 2. Duplicados colapsados (4 códigos aparecían dos veces)

La tabla en bruto tenía 102 filas pero solo 98 códigos únicos.

| Código | Qué pasaba | Qué se hizo |
|---|---|---|
| **P0340** | Dos filas ("sensor de admisión: sin señal" y "sin señal del sensor de levas") | Una sola fila. Se quita "de admisión" del título, porque insinuaba que hay un segundo sensor de leva, y no lo hay |
| **P0341** | Dos filas **con diagnósticos opuestos**: una culpaba al aceite/VTC, la otra a la cadena estirada | Una sola fila, reescrita (ver sección 3) |
| **P0344** | Dos filas **con gravedad contradictoria**: `grave` en una, `atencion` en la otra | Una sola fila, `atencion`. Era el duplicado más dañino: ese campo decide si el dueño sigue conduciendo |
| **P2279** | Dos filas, textos y causas distintos, y ambas con física equivocada | Una sola fila, reescrita (ver sección 3) |

---

## 3. Fichas que se quedan pero con el texto CORREGIDO

Estos códigos sí existen en este carro; lo que estaba mal era la explicación. Se listan
porque volver al texto anterior sería tan grave como reintroducir un código imposible.

**P0010 — era una ficha de aceite sobre un código eléctrico.** P0010 es el fallo eléctrico
del solenoide del VTC: el ECM no ve la respuesta eléctrica que espera. El aceite degradado
y la malla filtro obstruida no abren ni cierran un circuito; eso es P0011 y P1009. El texto
anterior mandaba al dueño a cambiar aceite para un fallo de cable. Además la resistencia
citada (6,75-8,25 ohmios) es la del K24Z de 2008-2012; el solenoide del K24A de 2003-2007
está en torno a 7 ohmios en frío, así que un solenoide de 6,8 se daba por bueno con el
rango antiguo. Ahora la ficha dice "del orden de 7 ohmios" y remite al valor exacto del
manual, en vez de fijar un número de otra generación.

**P0107 / P0108 — la misma causa estaba en los dos códigos opuestos.** "Conector
desenchufado" no puede ser la causa típica de la señal baja y de la señal alta a la vez. El
MAP desenchufado deja la línea de señal en alto, así que el conector suelto queda en
**P0108**, y P0107 se queda con lo que de verdad tira la señal abajo (alimentación de 5 V
cortada, señal derivada a masa, sensor en corto). También se ha corregido la descripción de
P0108: no describe "casi presión atmosférica", sino más voltaje del que el sensor puede dar
ni con el motor parado.

**P0125 — contaba la historia de P0128.** P0125 es "sensor de temperatura del refrigerante
con respuesta lenta": la prueba es sobre el sensor. Con "termostato pegado abierto, la causa
número uno" eran dos fichas seguidas contando lo mismo. Ahora P0125 es el sensor perezoso y
P0128 el termostato, y cada una remite a la otra.

**P0135 — contradecía cómo funciona una sonda de banda ancha.** El texto anterior daba a
entender que, al calentarse el escape, la sonda arrancaba sola y el problema se pasaba
("sobre todo en trayectos cortos y en frío"). Una LAF necesita calefacción controlada por la
computadora; con el calefactor quemado el motor se queda en mezcla fija **siempre**, no solo
en frío.

**P0137 / P0138 — describían el síntoma de P2270 / P2271.** "Marca siempre bajo y no se
mueve" es la definición literal de "signal stuck lean", que en la tabla ya era P2270. P0137 y
P0138 son fallos **eléctricos** del circuito de la sonda trasera (voltaje bajo o alto). Ahora
las cuatro fichas dicen cosas distintas: P0137/P0138 el circuito, P2270/P2271 la sonda
clavada.

**P0137 / P0138 / P0141 — ubicación no verificada.** Las tres afirmaban como hecho que el
conector de la sonda trasera "va dentro del carro, bajo el asiento del acompañante". No se
ha podido confirmar en ninguna fuente Honda. Repetido tres veces, ese dato manda al dueño a
levantar la alfombra buscando algo que quizá no esté ahí. Sustituido por lo que sí es
seguro: el conector va en los bajos, expuesto a agua y sal.

**P0341 — se estaba comiendo el contenido de P1009 y asustando de más.** La ficha anterior
hablaba de distribución desfasada, cadena que se salta dientes y "válvulas y pistones
destrozados". El desfase real de fase por falta de aceite ya es P0011 y P1009. P0341 es un
código de señal del sensor de leva contra la del cigüeñal, y **no dice en qué sentido** está
el desfase, así que afirmar "la leva va retrasada" empuja a diagnosticar cadena. Reescrito:
primero sensor, conector y aceite; la cadena, al final de la lista y avisando de que es lo
más caro. Gravedad bajada de `grave` a `atencion` por coherencia con lo que el código mide.

**P0420 — citaba un boletín sin número.** Afirmaba como hecho que "Honda emitió un boletín
para el Element 2003" de reprogramación del ECM. No se ha podido verificar el número. Si a
un dueño se le enseña un boletín, tiene que ser comprobable. Ahora la ficha dice lo único
accionable y honesto: antes de comprar catalizador, preguntar en el concesionario con el
número de bastidor si hay alguna actualización de software pendiente.

**P0457 — absorbe la fuga grande.** Como P0455 no está en el índice del Element (ver
sección 5), P0457 se explica explícitamente como *el* código de la fuga grande de vapores,
que es el papel que cumple en este carro.

**P0563 — llevaba colgada la ficha de otro código.** Los tres verificadores coinciden: Honda
define P0563 como "ECM/PCM Power Source Circuit Unexpected Voltage", no como "el alternador
está cargando de más". Se ha reescrito como voltaje anormal en la alimentación de la
computadora, conservando la sobrecarga del alternador como primera causa (que lo es) y el
aviso de gravedad, pero sin ponerle al código un nombre que no tiene. El código Honda de
sobrecarga como tal, P1549, **no se añade**: ver sección 5.

**P0730 / P0780 — distinción inventada, y ahora útil.** Honda describe los dos casi igual.
En vez de fingir dos averías distintas, las dos fichas dicen ahora que son avisos de conjunto
y **remiten a los códigos que sí nombran la marcha** (P0731-P0734) y a los de solenoide
(P0753/P0758). Además, la lista de causas de P0730 citaba "presostatos de 2ª y 3ª", que antes
no tenían número en la tabla; ahora sí (P1738/P1739/P1740).

**P1157 / P1172 — eran indistinguibles.** Cuatro causas casi calcadas y el mismo resumen. No
se elimina ninguno (los dos están en el índice del Element y un escáner puede mostrar
cualquiera), pero cada ficha dice ahora que es hermana de la otra, que suelen salir juntas y
que la reparación es la misma. Es más útil para el dueño que fingir dos averías separadas.

**P1297 / P1298 — el ELD estaba en el sitio equivocado.** El texto mandaba al dueño a
"la caja de fusibles de debajo del tablero". El ELD de Honda vive **dentro de la caja de
fusibles y relés del compartimento del motor**. Corregido en las dos fichas, que además ahora
coinciden entre sí. En P1298 se ha quitado la afirmación de que ese código sale cuando el
alternador se pasa de voltaje: una sobrecarga no sube la señal del ELD; P1298 es señal alta =
consumo leído cero (fusible del ELD fundido, alimentación cortada, ELD abierto).

**P2227 — la prueba descrita y las causas eran incompatibles.** Decía que la computadora
compara el barométrico con el MAP "con el motor parado", pero con el motor parado esos dos
valores coinciden por fuerza, pase lo que pase con la admisión. Todas las causas que listaba
(filtro saturado, nido en la toma de aire, sensor de aire flojo) solo alteran el MAP con el
motor girando. Ahora la ficha dice que la comparación se hace cuando los dos deberían
parecerse: con el contacto puesto antes de arrancar **y** pisando a fondo.

**P2279 — física de motor con MAF en un motor sin MAF.** Hablaba de "aire sin medir" y decía
que suele venir con mezcla pobre, mientras la ficha de P1129 explicaba correctamente que en
este motor una fuga por detrás de la mariposa **sí** la mide el MAP y hace ir al motor rico.
Las dos fichas se contradecían sobre el mismo fallo físico. Reescrita: fuga por detrás de la
mariposa, síntoma de ralentí alto e inestable, y remite a P1129 en vez de contradecirlo. Las
causas que estaban antes de la mariposa (tubo de admisión, sensor de aire salido de su goma)
se han movido a donde corresponden: P1128 y P2227.

**P2646 / P2648 / P2649 — banda de revoluciones equivocada.** Los tres decían que el motor
"se queda plano de 3.000 vueltas hacia arriba". El cambio de leva del K24A4 está en torno a
las **2.200 vueltas**. Corregido en los tres, y en el P1259 nuevo. También se ha quitado de
P2646 la frase "en algunos casos ni siquiera pasa de ahí": un fallo del presostato o del
solenoide del VTEC no impone corte de revoluciones.

**P2646 — mezclaba dos especificaciones de presión.** "Debe pasar de 50 psi a 3.000 vueltas"
es la especificación de presión de aceite del **motor**; el presostato del VTEC necesita más
presión que eso para conmutar. Tal y como estaba, el diagnóstico se cerraba en falso: el
motor cumple especificación y el presostato sigue sin cerrar. Ahora la ficha avisa
exactamente de eso, sin fijar un número que no se ha podido verificar.

**P2647 — boletín no verificable.** Citaba el "boletín Honda 13-021". No se ha podido
confirmar ese número. Lo que sí está documentado es la pieza actualizada del presostato,
**37250-PNE-G01**, así que se conserva la pieza y se quita el número de boletín.

**P0011 / P1009 / P0341 — tres fichas con la misma lista de causas en el mismo orden.**
Ahora P0010 es solo eléctrico, P0011 es "no obedece o se queda pasado de avance", P1009 es
"no consigue adelantar" (el clásico del Element, aceite primero) y P0341 es la señal que no
cuadra. Comparten piezas, como es lógico, pero ya no son la misma ficha escrita cuatro veces.

---

## 4. Huecos de los verificadores que SÍ se han añadido (7 códigos)

**P0731, P0732, P0733, P0734 — patinaje del embrague de 1ª, 2ª, 3ª y 4ª.**
Los tres verificadores los señalaron, la caja tiene exactamente esos cuatro embragues, y hay
un argumento interno decisivo: la lista de descartes original rechaza **P0735** por ser "la
relación incorrecta de 5ª" en una caja de 4 velocidades. Si P0735 se descarta *porque no hay
quinta*, P0731-P0734 son válidos *porque sí hay primera a cuarta*. Son además los únicos
códigos de esta caja que dicen QUÉ marcha está patinando; sin ellos, el dueño solo tenía
P0730 y P0780, que no orientan nada.

**P1740 — presostato del embrague de 4ª.**
La tabla traía el de 2ª (P1738) y el de 3ª (P1739), los presentaba como avería frecuente y
barata, y se dejaba fuera el tercero de la misma familia, que falla igual y por lo mismo
(aceite viejo, conector con ATF). Familia completada.

**P1773 — solenoide lineal de presión de embragues B.**
El solenoide lineal es un **conjunto doble** atornillado por fuera de la caja. La tabla solo
tenía uno de los dos números. El propio encargo del área nombra la familia como
"P1705-P1773", así que P1773 es el extremo declarado de esa familia y no una invención.

**P1259 — VTEC del Element de 2003.**
Este es el añadido más discutible y merece explicación. La lista de descartes original lo
rechazaba con un buen argumento: todos los hilos reales de P1259 son de CR-V, RSX, Insight y
Accord, y los del Element son siempre P2646-P2649. Pero un verificador aportó un dato nuevo
que reconcilia las dos observaciones: **Honda partió el antiguo P1259 en los cuatro códigos
P2646-P2649 a partir del año-modelo 2004**. Si eso es así, los dos hechos encajan: los
Element que se ven en los foros (2004 en adelante, que son la mayoría) dan P2646-P2649, y el
2003 daría P1259. Como la tabla cubre 2003-2006 explícitamente, dejarlo fuera significaba que
**un Element de 2003 no tenía ni una sola entrada de VTEC en toda la tabla**. Se añade con la
distinción de año escrita dentro de la propia ficha, para que nadie lo confunda con los
cuatro modernos.
*Punto abierto:* si alguien confirma en el manual de fábrica del Element 2003 que su ECM ya
usa P2646-P2649, esta fila sobra y hay que quitarla.

---

## 5. Huecos que los verificadores pidieron y que NO se han añadido

Estos son los que, aplicando la regla de la sección 0, no superan el listón. Cada uno lleva
su motivo, para que no vuelvan a entrar en la siguiente ronda.

### P0351, P0352, P0353, P0354 (circuito de las bobinas de encendido)
Dos verificadores lo llamaron "el hueco más grande de la tabla". Se deja fuera igualmente:

- Están en el boletín 03-020, y eso, por sí solo, no prueba nada (regla de la sección 0).
- Las bobinas del K24A4 son de tres cables: alimentación, masa y señal de mando. **No hay
  línea de confirmación de encendido de vuelta hacia el ECM**, así que el ECM no tiene por
  dónde enterarse de que una bobina está abierta. Sin esa vía física, no hay código.
- El manual del Element resuelve la bobina muerta como fallo de encendido: sale
  P0301-P0304, con la bobina como primera causa, que es exactamente lo que hacen esas fichas.
- El tercer verificador coincidió en que había que confirmarlo antes de publicarlo.

*Punto abierto:* si alguien tiene delante el índice de DTC del manual de fábrica y ve
P0351-P0354, son cuatro filas fáciles de escribir. Hasta entonces, fuera.

### P0455 (fuga grande de EVAP), P0441 (caudal de purga incorrecto)
Están en el boletín general, pero **no en el índice de DTC del manual del Element** de 2003
ni de 2006, que es evidencia de nivel 2. Esta generación reparte esas funciones:
la fuga grande sale como **P0457** (y así se ha escrito su ficha) y el caudal de purga como
**P0496** (alto) y **P0497** (bajo). Los tres están en la tabla.

### P1456 y P1457 (fugas EVAP, numeración Honda)
Trampa clásica: son códigos Honda auténticos, salen hasta en foros de Element, y aun así
están **ausentes del índice de DTC del manual de fábrica** de 2003 y 2006. Pertenecen a la
arquitectura EVAP de 1996-2002. En este carro el equivalente real es P0442 / P0456 / P0457
más P0498 / P0499 / P2422 para la válvula de venteo. Descartados por comprobación directa.

### P1162, P1163, P1164, P1165, P1149, P1166, P1167 (familia LAF de Honda)
Se pedían sobre todo como sustitutos del P0133 eliminado. Existen en el boletín pero
pertenecen a otras plataformas Honda con distinta arquitectura de sonda, y **ninguno aparece
en el índice del manual del Element**, que usa P1157 y P1172. El hueco funcional que dejaba
P0133 (sonda lenta y desviada) lo cubre P2A00, que sí está documentado para esta época: el
boletín 07-006 lo usa sobre modelos 2006-2007.

### P1158 y P1159 (líneas AFS+ / AFS- con numeración antigua)
Un verificador sostiene que los P22xx son la renumeración de la era CAN y que un Element de
ISO 9141 debería reportar P1158/P1159. Es una inferencia sobre la estructura del boletín. En
contra hay una comprobación directa del índice del Element, que usa **P2238** (línea AFS+) y
**P2252** (línea AFS-). Se mantiene lo verificado.

### P1106, P1107, P1108 (barométrico con numeración antigua)
Mismo caso. Son de la arquitectura vieja de Honda, cuando el barométrico era una pieza
aparte. En el Element 2003-2006 el barométrico va **dentro del PCM** y la familia es
P2227/P2228/P2229, con P2227 confirmado sobre un Element real y sobre Accord 2.4 de
2003-2004. Se mantiene lo confirmado en el mundo real.

### P1549 (sobrecarga del sistema de carga)
Dos verificadores lo querían, y es cierto que la avería que describe (alternador
sobrecargando) es real y peligrosa. Pero corresponde a modelos con control del alternador por
el circuito ALT-C, posteriores a este carro, y no está verificado para 2003-2006. La avería
no queda huérfana: **P0563**, reescrito, es el código que este ECM sí levanta cuando ve un
voltaje de alimentación fuera de rango, y su primera causa listada es precisamente el
regulador del alternador desmadrado.

### P0842 / P0843 (presostato 2ª), P0845 / P0847 / P0848 (3ª), P0872 / P0873 (4ª)
Es la numeración SAE de los presostatos de embrague, y **pertenece a la caja automática de
5 velocidades**. Un verificador citó como prueba que "hay hilos reales de dueños de Element
con P0847": muy probablemente son Element de 2007 en adelante, que es justo cuando el
Element cambia a la caja de 5 velocidades y a CAN. En esta caja de 4 velocidades la función
la cubren P1738, P1739 y P1740, los tres en la tabla.

### P0745-P0748 y P0775-P0778 (solenoides de presión, numeración SAE)
Mismo motivo. En este modelo el fallo del solenoide lineal de presión sale como **P1768** y
**P1773**, los dos en la tabla. No se ha podido confirmar que este ECM use además la
numeración SAE, y duplicar la misma avería con ocho números más solo sirve para que el dueño
no sepa cuál mirar.

### P0763 (solenoide de cambio C) y P0761 / P0762
Esta caja lleva los solenoides de cambio A y B montados juntos en un bloque externo (por eso
P0753 y P0758 salen tan a menudo a la vez). El solenoide C pertenece a otras cajas Honda. No
confirmado aquí; fuera.

### P0710 / P0711 / P0712 / P0713 / P0714 (temperatura del ATF)
Dos verificadores los pedían. No se ha podido confirmar que esta caja de 4 velocidades monte
un sensor de temperatura de aceite con circuito propio vigilado por el ECM. Es un hueco
honesto: **si la pieza existe, son cinco filas que faltan**; si no existe, meterlas sería
repetir el error del S2000. Se queda como punto abierto, no como fila inventada.

### P0741, P0743 (convertidor y su solenoide, numeración SAE) y P0717 / P0722
El bloqueo del convertidor está cubierto por **P0740** (comportamiento) y **P1753**
(circuito del solenoide). Los sensores de giro están cubiertos por P0715 y P0720. Las
variantes SAE no están confirmadas para este ECM y solo añadirían números redundantes.

### P1730-P1734, P1743-P1751, P1780-P1794 (válvulas de cambio pegadas)
Están en el boletín 03-020, pero describen válvulas de cambio D y E y combinaciones de
solenoides B y C que esta caja de 4 velocidades no tiene. El modo de fallo que representan
(corredera agarrotada por ATF nunca cambiado) sí es real en esta caja, y por eso se ha
añadido "corredera del cuerpo de válvulas agarrotada" como causa dentro de P0780, P1738,
P1739, P1768 y P1773, en vez de inventar filas con números de otra transmisión.

### P1705 / P1706 (interruptor de rango, numeración Honda antigua)
El fallo es real, pero en este carro el ECM lo reporta con la numeración moderna **P0705 /
P0706**, que sí están en la tabla. Poner los cuatro sería publicar la misma avería dos veces.

### P0603, P0606, P0602, P0630 (memoria, procesador y programación del ECM)
El fallo interno de computadora en este Honda se reporta como **P1607**, que está en la
tabla, y la pérdida de memoria por falta de alimentación permanente como **P0560**, que
también está. P0602 y P0630 ni siquiera son averías de conducción: solo aparecen tras una
reprogramación fallida o al montar una computadora sin codificar, y no le sirven de nada al
dueño del carro.

### P0685 (relé principal)
El relé principal de Honda es una avería clásica de esta época (no arranca en caliente,
arranca al enfriar), pero es un fallo que **no deja código** en este ECM: el carro
simplemente no arranca. No está confirmado en el índice del Element. Fuera.

### P1505 (fuga de aire por la PCV)
Pedido por dos verificadores con el argumento de que la manguera del PCV se cita como causa
en media docena de fichas. Es cierto que se cita mucho, pero no se ha podido confirmar que
ese número exista con ese significado en este ECM (la familia P150x de esta época es la del
control de ralentí). La fuga del PCV está cubierta como causa dentro de P0171, P0505, P0507,
P2195 y P2279, que es donde el dueño la va a encontrar.

### P0461 / P0462 / P0463 (aforador del tanque)
Argumento interesante: con el nivel de combustible inválido, el monitor de EVAP nunca corre
y el carro se queda en "not ready" para la inspección. Pero no está confirmado que este ECM
vigile el aforador con un DTC propio, y el aforador del Element se manifiesta normalmente
como una aguja de gasolina que miente, no como una luz de motor. Fuera, sin descartar
revisarlo si aparece documentación.

### P0121 (rango/rendimiento del TPS), P0336 (cigüeñal intermitente), P0725, P0366
- **P0121**: en esta generación Honda cubre esa comprobación con **P1121** y **P1122**, los
  dos en la tabla.
- **P0336**: el manual del Element define solo **P0335** (sin señal) y **P0339** (corte
  intermitente) para el sensor de cigüeñal.
- **P0725**: no confirmado en este ECM.
- **P0366**: es del sensor de leva B, que este motor no monta (sección 1).

---

## 6. Lo que ya estaba descartado y sigue descartado (resumen por familias)

No hace falta volver a discutir nada de esto:

- **MAF (P0100-P0104), P0105, P0109, P2280.** El K24A4 es *speed-density*: calcula el aire
  con MAP + IAT. La pieza no existe. Es el error exacto que hundió la tabla del S2000.
- **EGR (P0400-P0406, P1491, P1498).** No hay válvula EGR: Honda usa recirculación interna
  por solape de válvulas y afirma que "elimina la necesidad de una válvula EGR separada".
- **Acelerador electrónico (P2135, P1683, P1684, P1658, P1659) y P0068.** Acelerador de
  **cable** hasta 2006 y un solo sensor de mariposa. El drive-by-wire llega en 2007 con el
  K24A8.
- **Todo lo de banco 2 y cilindros 5-8** (P0305-P0308, P0150-P0167, P0174, P0175, P2197,
  P0430, P0431, P0345-P0349, P0330-P0334, P0020-P0024, P2247-P2255 de banco 2...). Motor de
  4 cilindros en línea: un solo banco, un solo catalizador, un solo sensor de detonación.
- **Arquitectura vieja de sensores del distribuidor** (P1361, P1362, P1366, P1367, P1381,
  P1382, P0320-P0322, P1399). El K24 no lleva distribuidor ni sensor TDC separado: usa
  CKP + CMP y la familia P033x/P034x.
- **Fase variable en el escape** (P0013, P0014, P0015) y **P0012**. El K24A4 solo lleva VTC
  en el árbol de admisión. P0012 no aparece ni una sola vez en el boletín 03-020: Honda no
  usa ese número en ningún modelo.
- **P0016, P0017, P0018.** Correlación cigüeñal-leva con numeración SAE de la era CAN. Aquí
  ese fallo sale como P0341.
- **VTEC de otras generaciones** (P1253) y **VCM de los V6** (P1286-P1289, P2653, P2654,
  P2658, P2659). El VCM es de Odyssey, Pilot y Accord V6.
- **Sistema de pausa de válvulas e híbridos IMA** (P1020-P1026, P128A, P128B, P2651, P2652,
  P1565-P1673 y familia P0Axx). El Element no es híbrido.
- **Caja de 5 velocidades** (P0735, P0784, P0766, P0767, P0771, P0773). El selector dice
  P R N D: cuatro velocidades. La de 5 llega al Element en 2007.
- **Caja manual y CVT** (P0704 interruptor de embrague, P0502, P1655). Este carro es
  automático con convertidor de par.
- **Módulos separados** (P1630 TCM, P1660 línea A/T-FI, P1656 VTM-4). Aquí la caja la
  gobierna el mismo PCM, y el Real Time 4WD del Element es una bomba dual mecánica sin
  unidad de control que hable con el PCM.
- **Familia U0xxx completa.** Este carro habla **ISO 9141-2 (línea K)**. No hay red CAN
  entre módulos hasta 2007. Estos códigos no pueden existir aquí.
- **IMRC / mariposas de admisión variable** (P1077, P1078, P2004, P2006). El Element lleva
  múltiple de aluminio de una sola etapa, referencia 17110-RAA-A00, sin válvula IMRC:
  verificado en el despiece de Honda, donde en ese grupo solo aparecen el sensor MAP y su
  O-ring. La IMRC es hardware del CR-V con K24A1 y del K20A del RSX.
- **Circuito de inyectores** (P0201-P0204, P0261-P0268) y **presión/temperatura de
  combustible** (P0087, P0088, P0181-P0183, P0190-P0193, P0230-P0232, P0627). Este ECM no
  vigila el circuito de cada inyector ni monta sensor de presión de combustible: el sistema
  es sin retorno con el regulador dentro del tanque. Un inyector muerto sale como fallo de
  encendido; la presión baja, como P0171 o como fallo de encendido.
- **P0170, P0173** (Fuel Trim Malfunction). Honda no los implementa: usa P0171 y P0172.
- **P2196, P2198** (A/F sensor stuck rich). No existen en Honda; solo hay stuck lean
  (P2195). El equivalente rico existe únicamente para la sonda trasera, y es P2271.
- **Tercera sonda** (P0143-P0147). Este carro tiene dos: la LAF delantera y la trasera.
- **P0115, P0119, P0114, P0126, P1486, P2181, P0217, P0480, P0481.** Ninguno figura en el
  índice del Element; sus funciones las cubren P0116, P0117, P0118, P0125 y P0128.
- **P219A** (desequilibrio entre cilindros) y **P145C**. Códigos de la era CAN, posteriores.
- **P1508, P1509** (válvula de ralentí, era D y B series 1996-2000) y **P0511**. Esta
  generación usa **P0505** y **P1519**.
- **P0130, P0131, P0132, P0136, P0140** (sonda delantera de banda estrecha). Este motor lleva
  sonda LAF de **banda ancha** aguas arriba (36531-PZD-A01).
- **P0521, P0522, P0523** (sensor de presión de aceite). El Element solo lleva un presostato
  que alimenta el testigo del tablero; el único presostato que el ECM vigila es el del VTEC
  (P2646/P2647).
- **P0421** (catalizador de arranque), **P0410 / P0411** (inyección de aire secundario),
  **P0444-P0449, P0459, P0446** (purga y venteo con numeración genérica), **P1458, P1459**.
  Ninguno en la lista de fábrica del Element.

Nota heredada que ya no aplica: la lista antigua decía que el equivalente de P1381/P1382 en
este motor era "el sensor de leva B (P0365/P0369)". Es incorrecto: **no hay sensor de leva B**
(sección 1). El equivalente real es el sensor de leva único, P0340 / P0341 / P0344.

---

## 7. Puntos abiertos, por orden de importancia

Si alguien consigue el índice de DTC del manual de fábrica del Element (2003 y 2006), estas
son las cinco preguntas que cierran la tabla:

1. **¿Aparecen P0351-P0354?** Si sí, son cuatro filas que faltan (bobina por cilindro).
2. **¿El ECM del Element 2003 usa P1259 o ya usa P2646-P2649?** Si usa los cuatro, sobra la
   fila de P1259.
3. **¿Existe sensor de temperatura de ATF con DTC propio (P0710-P0714)?** Si existe, faltan.
4. **¿Cómo define exactamente el manual P1738, P1739, P1740, P1753, P1768 y P1773?** Un
   verificador sostiene que en el boletín P1738 es "Shift Valves B and C Stuck" y P1753
   "Shift Valve E Stuck ON". La "válvula de cambio E" pertenece al cuerpo de válvulas de la
   caja de **5 velocidades**, así que lo más probable es que esa lectura venga de la sección
   del boletín correspondiente a otra transmisión: el boletín cubre todos los modelos y el
   mismo número P17xx significa cosas distintas según la caja. Se han mantenido las
   definiciones de esta caja de 4 velocidades (presostatos de embrague, solenoide de bloqueo
   y solenoides lineales de presión), y las causas de esas fichas incluyen además la corredera
   agarrotada, que es la lectura alternativa. Merece una comprobación contra el manual.
5. **¿P0133 y P2A00 aparecen o no en el índice?** P0133 se ha quitado por ser código de V6;
   P2A00 se ha mantenido porque el boletín 07-006 lo usa sobre modelos 2006-2007. Si el
   manual dice otra cosa, hay que ajustar el bloque de la sonda LAF.
