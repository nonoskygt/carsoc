#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SONDEAR — le pregunta al carro qué es capaz de contestar, y lo apunta.

Esto no es un tablero ni un diagnóstico: es el paso que va ANTES de los dos.
Contesta las preguntas que el proyecto responde hoy con "seguramente":

  · ¿Qué protocolo habla de verdad este carro?
  · ¿Qué PIDs tiene, y cuáles de los que pinta el tablero le faltan?
  · Si es CAN, ¿contesta en 11 bits, en 29, o en los dos?
  · ¿Cuántas lecturas por segundo da este enlace, de verdad?
  · ¿Quién contesta: solo el motor, o hay más módulos?

Y deja un informe fechado en `docs/`, para que dentro de un mes no haya que
fiarse de la memoria de nadie.

## ⚠️ SOLO LEE

No manda un solo comando que cambie nada. En particular NO manda el modo 04,
que además de borrar las averías **borra los monitores de emisiones** y deja
el carro sin poder pasar la revisión hasta completar un ciclo entero.

## LAS TRES RESPUESTAS QUE NO SE PUEDEN CONFUNDIR

Este fichero se reescribió entero por una razón. La primera versión, puesta
delante de un carro que no contestaba, publicaba **"0 PIDs de datos"** en
negrita y **"NO"** en las doce filas de lo que necesita el tablero. Es decir:
convertía "no lo sé" en "el carro no lo tiene", que es exactamente el pecado
que este proyecto persigue desde el primer commit, cometido por la
herramienta que existe para evitarlo.

Así que aquí todo dato vive en uno de estos estados, y **jamás se colapsan**:

    MEDIDO          el carro contestó y esto es lo que dijo
    SIN_DATOS       se preguntó y el carro dijo que no lo tiene
    NEGATIVA        el módulo contestó 7F: lo entendió y lo rechazó
    ERROR_ENLACE    fallo del adaptador o del bus, NO una respuesta del carro
    CORTADA         llegó a medias: no se puede creer ni descartar
    NO_PREGUNTADO   nunca se llegó a preguntar

Un `NO` en el informe solo puede salir de SIN_DATOS. Todo lo demás se pinta
como lo que es.

## Uso

    python tools/sondear.py --listar
    python tools/sondear.py --puerto COM3 --carro fit
    python tools/sondear.py --tcp 192.168.0.10:35000 --carro element
    python tools/sondear.py --prueba          # se comprueba a sí mismo, sin carro

Con el contacto en la posición II ya contesta casi todo. Con el motor en
marcha además salen valores vivos, que es lo que hace falta para medir las
lecturas por segundo de verdad.
"""

import argparse
import datetime
import os
import re
import socket
import sys
import time

# ============================================================ estados

MEDIDO = "MEDIDO"
SIN_DATOS = "SIN_DATOS"
NEGATIVA = "NEGATIVA"
ERROR_ENLACE = "ERROR_ENLACE"
CORTADA = "CORTADA"
NO_PREGUNTADO = "NO_PREGUNTADO"

COMO_SE_LEE = {
    MEDIDO: "contestó",
    SIN_DATOS: "no lo tiene",
    NEGATIVA: "lo rechazó (7F)",
    ERROR_ENLACE: "fallo del enlace",
    CORTADA: "respuesta cortada",
    NO_PREGUNTADO: "no se preguntó",
}

# El catálogo de mensajes de error del ELM327. Está aquí entero y no como un
# `"ERROR" in resp` porque la primera versión hacía justamente eso y contaba
# como lectura buena a SEARCHING, BUFFER FULL, ERR94 y a las tramas cortadas.
# Un cronómetro que cuenta errores como aciertos publica una velocidad de
# enlace inventada, y esa cifra iba a gobernar el reparto de turnos.
#
# Sale de la hoja de datos del ELM327 (Elm Electronics), sección "Error and
# Alert Messages", más los prefijos ERRxx del firmware.
ERRORES_ADAPTADOR = (
    "UNABLE TO CONNECT",   # no encontró protocolo
    "BUS INIT: ERROR",
    "BUS INIT: ...ERROR",
    "BUS ERROR",
    "BUS BUSY",
    "CAN ERROR",
    "DATA ERROR",
    "<DATA ERROR",
    "BUFFER FULL",
    "RX ERROR",
    "<RX ERROR",
    "FB ERROR",            # el bus no repite lo que el ELM mandó
    "LV RESET",            # se le fue la tensión
    "LP ALERT",
    "ACT ALERT",
    "STOPPED",
    "UNABLE",
    "NO DATA",             # se trata aparte, pero se lista para el catálogo
)


def clasificar(crudo, hubo_indicador, orden=""):
    """Qué significa de verdad lo que contestó el adaptador.

    Distinguir esto bien es la mitad del valor de esta herramienta. Las cuatro
    cosas que más se confunden:

    - `SEARCHING...` NO es una respuesta: es el adaptador diciendo "espera".
      Si sale sola, la petición se quedó a medias.
    - `NO DATA` es del CARRO: se preguntó y no contestó. Es un dato.
    - `CAN ERROR`, `BUFFER FULL`, `ERR94` son del ADAPTADOR o del bus. No
      dicen nada del carro, y contarlos como "no lo tiene" es inventar.
    - `7F xx yy` es el módulo diciendo "te entendí y me niego". Eso es una
      respuesta, y significa lo contrario de "soportado".
    """
    if not hubo_indicador:
        return CORTADA
    t = (crudo or "").upper().strip()
    if not t:
        return CORTADA

    sin_espacios = t.replace(" ", "").replace("\n", "")

    # ERRxx del firmware: ERR94, ERR91...
    if re.search(r"\bERR\d{2}\b", t):
        return ERROR_ENLACE

    if t.startswith("?"):
        # El adaptador no entendió la ORDEN. Es un fallo nuestro, no del carro.
        return ERROR_ENLACE

    for e in ERRORES_ADAPTADOR:
        if e == "NO DATA":
            continue
        if e in t:
            return ERROR_ENLACE

    if "NO DATA" in t:
        return SIN_DATOS

    # Respuesta negativa de diagnóstico: `<cabecera> [pci] 7F <servicio> <motivo>`.
    #
    # Se comprueba la LÍNEA ENTERA y no "7F suelto en algún sitio": con ATS0
    # los bytes llegan pegados y no hay separador que anclar, así que buscar
    # un 7F precedido de no-hex no casa nunca — que es lo que hacía la
    # primera versión. El resultado era que el modo 06 y el servicio 22F190
    # salían como soportados en un carro que los había RECHAZADO.
    if any(NEGATIVA_TRAMA.match(l.strip().upper().replace(" ", ""))
           for l in (crudo or "").splitlines()):
        return NEGATIVA

    # "SEARCHING..." sin nada detrás: se quedó buscando.
    limpio = t.replace("SEARCHING...", "").replace("SEARCHING", "").strip()
    if not limpio:
        return CORTADA

    if limpio == "OK":
        return MEDIDO

    if re.search(r"[0-9A-F]{4}", limpio.replace(" ", "")):
        return MEDIDO

    return CORTADA


class Respuesta:
    """Una pregunta al carro y lo que salió de ella."""

    def __init__(self, orden, crudo="", estado=NO_PREGUNTADO):
        self.orden = orden
        self.crudo = crudo
        self.estado = estado

    @property
    def hay(self):
        return self.estado == MEDIDO

    def resumen(self):
        return COMO_SE_LEE.get(self.estado, self.estado)

    def __repr__(self):
        return f"<{self.orden} {self.estado}>"


# `COMO_SE_LEE` sirve para una casilla de tabla ("no lo tiene"), pero metido
# dentro de una frase se lee al revés de lo que dice: «el carro no contestó a
# 0100 (no lo tiene)» suena a que al carro le falta el 0100, cuando lo que
# pasó es que no contestó nada. Para prosa se usa esta otra.
EN_UNA_FRASE = {
    MEDIDO: "contestó",
    SIN_DATOS: "el adaptador devolvió NO DATA",
    NEGATIVA: "el módulo lo rechazó con un 7F",
    ERROR_ENLACE: "hubo un fallo de enlace o de bus",
    CORTADA: "la respuesta llegó cortada",
    NO_PREGUNTADO: "no se llegó a preguntar",
}


def en_una_frase(resp):
    return EN_UNA_FRASE.get(getattr(resp, "estado", None), "no se sabe")


VACIA = Respuesta("", "", NO_PREGUNTADO)


# ============================================================ transporte


class EnlaceCaido(Exception):
    pass


class Enlace:
    """Lo mínimo para hablar con un ELM327: mandar una línea y leer hasta '>'.

    El ELM327 termina SIEMPRE con el indicador `>`. Leer "hasta que deje de
    llegar nada" es la forma equivocada y es como se cuelan las tramas
    partidas: en K-line una respuesta tarda decenas de milisegundos y un
    tiempo de espera corto la corta justo por en medio.

    Y cuando el indicador NO llega, eso se APUNTA. La versión anterior no
    distinguía una respuesta completa de una cortada por el reloj, así que a
    partir del primer corte todo lo siguiente leía la cola de lo anterior y
    el informe entero salía desplazado una pregunta, sin avisar.
    """

    def __init__(self, escribir, leer, cerrar, purgar, nombre):
        self._escribir = escribir
        self._leer = leer
        self._cerrar = cerrar
        self._purgar = purgar
        self.nombre = nombre
        self.eco = []
        self.cortes = 0

    def cmd(self, orden, espera=5.0):
        # Se tira lo que hubiera quedado de la orden anterior. Sin esto, un
        # corte contamina TODAS las respuestas siguientes.
        try:
            self._purgar()
        except Exception:
            pass

        self._escribir((orden + "\r").encode("ascii"))
        crudo = b""
        indicador = False
        limite = time.time() + espera
        while time.time() < limite:
            trozo = self._leer()
            if trozo:
                crudo += trozo
                if b">" in crudo:
                    indicador = True
                    break
            else:
                time.sleep(0.01)

        texto = crudo.decode("ascii", errors="replace")
        limpio = texto.replace(">", "").strip()
        lineas = [l.strip() for l in re.split(r"[\r\n]+", limpio) if l.strip()]
        # El eco de la propia orden, por si ATE0 no llegó a aplicarse.
        if lineas:
            primera = re.sub(r"[^0-9A-Za-z]", "", lineas[0]).upper()
            if primera == re.sub(r"[^0-9A-Za-z]", "", orden).upper():
                lineas = lineas[1:]
        resp = "\n".join(lineas)

        if not indicador:
            self.cortes += 1
            # Se intenta recuperar el sincronismo antes de la siguiente orden.
            fin = time.time() + 1.5
            while time.time() < fin:
                if not self._leer():
                    break
                time.sleep(0.02)

        r = Respuesta(orden, resp, clasificar(resp, indicador, orden))
        self.eco.append(r)
        return r

    def cerrar(self):
        try:
            self._cerrar()
        except Exception:
            pass


def por_serie(puerto, baudios=38400):
    import serial

    s = serial.Serial(puerto, baudios, timeout=0)
    time.sleep(0.4)
    s.reset_input_buffer()

    def leer():
        try:
            return s.read(4096)
        except Exception as e:
            raise EnlaceCaido(str(e))

    return Enlace(s.write, leer, s.close, s.reset_input_buffer, puerto)


def por_tcp(destino):
    host, _, puerto = destino.partition(":")
    s = socket.create_connection((host, int(puerto or 35000)), timeout=8)
    s.setblocking(False)

    def leer():
        try:
            b = s.recv(4096)
        except BlockingIOError:
            return b""
        except OSError as e:
            raise EnlaceCaido(str(e))
        # recv devolviendo vacío en un socket NO bloqueante significa que el
        # otro extremo cerró. La versión anterior lo trataba como "todavía no
        # ha llegado nada" y producía un informe completo y coherente de un
        # carro que no contestaba nada — con el cable desenchufado.
        if b == b"":
            raise EnlaceCaido("el adaptador cerró la conexión")
        return b

    def purgar():
        try:
            while s.recv(4096):
                pass
        except Exception:
            pass

    return Enlace(lambda b: s.sendall(b), leer, s.close, purgar, destino)


def listar_puertos():
    try:
        from serial.tools import list_ports
    except ImportError:
        print("Falta pyserial:  pip install pyserial")
        return 1
    puertos = list(list_ports.comports())
    if not puertos:
        print("No hay puertos serie. Empareja el adaptador por Bluetooth primero.")
        return 1
    print(f"{len(puertos)} puerto(s):\n")
    for p in puertos:
        print(f"  {p.device:8s}  {p.description}")
    print("\nEn Windows el emparejado crea DOS puertos COM. El bueno suele ser")
    print("el saliente; si uno no contesta a ATZ, prueba el otro.")
    return 0


# ============================================================ OBD-II

PROTOCOLOS = {
    "1": "SAE J1850 PWM (41,6 kbaud)",
    "2": "SAE J1850 VPW (10,4 kbaud)",
    "3": "ISO 9141-2 (K-line, arranque a 5 baudios)",
    "4": "ISO 14230-4 KWP (arranque a 5 baudios)",
    "5": "ISO 14230-4 KWP (arranque rápido)",
    "6": "ISO 15765-4 CAN, 11 bits, 500 kbaud",
    "7": "ISO 15765-4 CAN, 29 bits, 500 kbaud",
    "8": "ISO 15765-4 CAN, 11 bits, 250 kbaud",
    "9": "ISO 15765-4 CAN, 29 bits, 250 kbaud",
    "A": "SAE J1939 CAN",
}
CAN = ("6", "7", "8", "9", "A")

# Con ATH1 cada línea trae su trama entera. En CAN, entre la cabecera y el
# eco del modo va el byte PCI de longitud; en K-line y J1850 no. La primera
# versión exigía el `41` pegado a la cabecera, así que en un carro CAN NO
# CASABA NI UNA y el informe decía "no se pudo separar ninguna cabecera"
# aunque contestaran tres módulos. Justo el carro para el que se escribió.
# Una respuesta negativa: cabecera (+ PCI en CAN) + 7F + servicio + motivo.
NEGATIVA_TRAMA = re.compile(
    r"^(?:[0-9A-F]{3}|[0-9A-F]{8}|[0-9A-F]{6})(?:[0-9A-F]{2})?7F[0-9A-F]{4}$"
)

TRAMA = re.compile(
    r"^(?P<cab>[0-9A-F]{3}|[0-9A-F]{8}|[0-9A-F]{6})(?P<pci>[0-9A-F]{2})?(?P<sid>4[0-9A-F])(?P<resto>[0-9A-F]*)$"
)


def tramas(resp_crudo, es_can):
    """Parte una respuesta en tramas, UNA POR MÓDULO.

    Esto importa mucho más de lo que parece. Cuando contestan varias ECU, la
    respuesta a `0100` trae una línea por módulo, cada una con SU mapa de
    PIDs. La versión anterior pegaba todas las líneas y se quedaba con el
    primer `4100`: publicaba el mapa de UN módulo como si fuera el del carro.

    El propio proyecto ya se había tropezado con esto en Kotlin y lo dejó
    escrito en `PidSoportadosTest`: "el día que se encienda ATH1 o entre otro
    módulo, el mapa saldría recortado y sin avisar, que es el peor modo de
    fallar". Aquí ATH1 está encendido siempre.
    """
    fuera = []
    for linea in (resp_crudo or "").split("\n"):
        l = linea.strip().upper().replace(" ", "")
        if not l or ":" in l:
            continue
        # La cabecera de longitud de una multitrama del CAN ("014") es hex
        # válido y colaría como trama.
        if len(l) <= 3:
            continue
        m = TRAMA.match(l)
        if not m:
            continue
        # En K-line la cabecera son 3 bytes (6 hex) y NO lleva PCI. El patrón
        # lo resuelve solo: la rama de 3 no encuentra el `4x` donde toca y
        # retrocede hasta la de 6 sin grupo opcional. Comprobado en la
        # autoprueba con una trama real de cada protocolo.
        cab = m.group("cab")
        pci = m.group("pci") or ""
        fuera.append({
            "cabecera": cab,
            "pci": pci,
            "sid": m.group("sid"),
            "datos": m.group("resto"),
            "linea": l,
        })
    return fuera


def mapa_de_una_trama(t, base):
    """Saca los PIDs de UNA trama de respuesta a `01<base>`.

    La trama es `41 <base> AA BB CC DD`. El bit más significativo de AA es el
    PID base+1; el menos significativo de DD, el base+0x20.
    """
    if t["sid"] != "41":
        return None, None
    d = t["datos"]
    esperado = "%02X" % base
    if not d.startswith(esperado):
        return None, None
    datos = d[2:10]
    if len(datos) < 8:
        return None, None
    valor = int(datos, 16)
    pids = [base + n + 1 for n in range(32) if valor & (1 << (31 - n))]
    return pids, datos


NOMBRES = {
    0x01: "Monitores / MIL", 0x03: "Estado del combustible",
    0x04: "Carga calculada", 0x05: "Temperatura del refrigerante",
    0x06: "Ajuste corto banco 1", 0x07: "Ajuste largo banco 1",
    0x08: "Ajuste corto banco 2", 0x09: "Ajuste largo banco 2",
    0x0A: "Presión de combustible", 0x0B: "Presión del colector (MAP)",
    0x0C: "Revoluciones", 0x0D: "Velocidad", 0x0E: "Avance del encendido",
    0x0F: "Temperatura del aire admitido", 0x10: "Caudal de aire (MAF)",
    0x11: "Posición de la mariposa", 0x12: "Aire secundario",
    0x13: "Sondas presentes (2 bancos)", 0x1C: "Norma OBD que declara",
    0x1D: "Sondas presentes (4 bancos)", 0x1F: "Tiempo desde el arranque",
    0x21: "Distancia con la MIL encendida", 0x22: "Presión de riel (relativa)",
    0x23: "Presión de riel (directa)", 0x2C: "EGR mandada",
    0x2D: "Error de EGR", 0x2E: "Purga del canister",
    0x2F: "Nivel de combustible", 0x30: "Calentamientos desde el borrado",
    0x31: "Distancia desde el borrado", 0x32: "Presión de vapores",
    0x33: "Presión barométrica", 0x3C: "Temperatura del catalizador B1S1",
    0x3D: "Temperatura del catalizador B2S1", 0x41: "Monitores de este ciclo",
    0x42: "Tensión del módulo", 0x43: "Carga absoluta",
    0x44: "Lambda mandada", 0x45: "Posición relativa de la mariposa",
    0x46: "Temperatura ambiente", 0x47: "Mariposa absoluta B",
    0x49: "Pedal del acelerador D", 0x4A: "Pedal del acelerador E",
    0x4C: "Mariposa mandada", 0x51: "Tipo de combustible",
    0x5C: "Temperatura del aceite",
}


def nombre_de_sonda(pid, con_1d):
    """Los PIDs 0x14-0x1B cambian de significado según 0x13 o 0x1D.

    Con 0x13 el carro tiene dos bancos y 0x14..0x1B son B1S1..B2S4. Con 0x1D
    tiene cuatro bancos y los mismos números son B1S1..B4S2. Poner siempre
    una sola lectura es nombrar mal la mitad de las sondas de un V6.
    """
    i = pid - 0x14
    if not 0 <= i <= 7:
        return None
    if con_1d:
        banco, sensor = i // 2 + 1, i % 2 + 1
        return f"Sonda B{banco}S{sensor} (4 bancos)"
    banco, sensor = i // 4 + 1, i % 4 + 1
    return f"Sonda B{banco}S{sensor}"


def nombre_pid(pid, con_1d):
    n = nombre_de_sonda(pid, con_1d)
    if n:
        return n
    return NOMBRES.get(pid, "(sin nombre en la tabla del proyecto)")


LO_QUE_PINTA_EL_TABLERO = {
    0x04: "carga", 0x05: "agua", 0x06: "ajuste corto", 0x07: "ajuste largo",
    0x0B: "colector", 0x0C: "rpm", 0x0D: "velocidad", 0x0E: "avance",
    0x0F: "aire", 0x11: "mariposa", 0x24: "mezcla (banda ancha)",
    0x42: "tensión",
}


def descifrar_vin(resp_crudo):
    """Saca el VIN de una respuesta al modo 09 PID 02.

    El VIN es la única identificación del carro que sale DEL CARRO. Sin él,
    el informe se identifica solo con la etiqueta que tecleó un humano — y
    un informe titulado "fit" que en realidad se midió en el Element es peor
    que no tener informe.
    """
    hexes = []
    for linea in (resp_crudo or "").split("\n"):
        l = linea.strip().upper().replace(" ", "")
        if ":" in l:
            l = l.split(":", 1)[1]
        if re.fullmatch(r"[0-9A-F]+", l or ""):
            hexes.append(l)
    h = "".join(hexes)
    i = h.find("4902")
    if i < 0:
        return None
    cuerpo = h[i + 4:]
    # Algunos contestan con un byte de cuenta (01) delante.
    if cuerpo.startswith("01"):
        cuerpo = cuerpo[2:]
    letras = ""
    for j in range(0, len(cuerpo) - 1, 2):
        v = int(cuerpo[j:j + 2], 16)
        if 0x20 <= v < 0x7F:
            letras += chr(v)
    letras = re.sub(r"[^A-HJ-NPR-Z0-9]", "", letras)
    return letras[:17] if len(letras) >= 17 else (letras or None)


# ============================================================ sondeo


class Sondeo:
    """Todo lo que se llegó a medir, y lo que no.

    Cada fase apunta si salió, si se abortó y por qué. La versión anterior
    perdía todo el trabajo ante cualquier excepción, y el fallo más probable
    de todos —el Bluetooth flojo, agachado en un carro— era justo el que lo
    destruía.
    """

    def __init__(self):
        self.fases = {}
        self.ident = None
        self.protocolo = None
        self.once = None
        self.modulos = None
        self.pids = None
        self.mapas = []
        self.con_1d = False
        self.modos = []
        self.ritmo = None
        self.vin = None
        self.calid = None

    def fase(self, nombre, ok, nota=""):
        self.fases[nombre] = {"ok": ok, "nota": nota}


class Sonda:
    def __init__(self, enlace, verboso=False):
        self.e = enlace
        self.v = verboso
        self.s = Sondeo()

    def cmd(self, orden, espera=5.0):
        r = self.e.cmd(orden, espera)
        if self.v:
            print(f"    >{orden}  [{r.estado}]  {r.crudo!r}")
        return r

    # -- preparar ------------------------------------------------------------

    def despertar(self):
        print("· Despertando el adaptador...")
        ident = self.cmd("ATZ", 6.0)
        if ident.estado == CORTADA and not ident.crudo.strip():
            self.s.fase("adaptador", False, "no contesta ni a ATZ")
            return False
        self.s.ident = ident.crudo.replace("\n", " ").strip()
        self._ajustes()
        self.s.fase("adaptador", True)
        print(f"    {self.s.ident}")
        return True

    def _ajustes(self):
        """Los ajustes que este sondeo da por puestos.

        Se agrupan aparte porque hay que volver a ponerlos: un `ERR94` o un
        reinicio espontáneo del adaptador devuelve ATH0 y ATE1, y a partir de
        ahí las cabeceras dejan de llegar SIN QUE NADIE SE ENTERE. El informe
        diría "solo contesta un módulo" cuando lo que pasa es que ya no se ven.
        """
        self.cmd("ATE0", 2.0)   # sin eco
        self.cmd("ATL0", 2.0)   # sin saltos de más
        self.cmd("ATS0", 2.0)   # sin espacios
        self.cmd("ATH1", 2.0)   # CON cabeceras: son media herramienta

    def _cabeceras_puestas(self):
        r = self.cmd("ATH1", 2.0)
        return r.estado in (MEDIDO, SIN_DATOS)

    # -- protocolo -----------------------------------------------------------

    def negociar(self):
        """Negocia y luego COMPRUEBA que hubo conversación de verdad.

        ⚠️ `ATDPN` devuelve lo que el adaptador tiene PUESTO, no lo que el
        carro habla. Con el conector al aire, `ATSP0` + `ATDPN` devuelve un
        número igual, y la primera versión lo publicaba como el protocolo del
        carro y remataba con "Es K-line". Un ajuste no es una medida.

        Aquí el protocolo solo se da por medido si además hubo una respuesta
        de verdad a una petición de verdad.
        """
        print("· Negociando protocolo (puede tardar ~15 s)...")
        self.cmd("ATSP0", 2.0)
        primera = self.cmd("0100", 20.0)
        num = self.cmd("ATDPN", 3.0)
        nom = self.cmd("ATDP", 3.0)

        crudo_num = (num.crudo or "").strip().upper()
        auto = crudo_num.startswith("A")
        clave = crudo_num[1:2] if auto else crudo_num[:1]

        confirmado = primera.estado == MEDIDO
        self.s.protocolo = {
            "confirmado": confirmado,
            "atdpn": crudo_num or "(sin respuesta)",
            "clave": clave,
            "automatico": auto,
            "nombre_elm": (nom.crudo or "").strip(),
            "nombre": PROTOCOLOS.get(clave, "no reconocido"),
            "es_can": confirmado and clave in CAN,
            "primera": primera,
        }
        self.s.fase("protocolo", confirmado,
                    "" if confirmado else f"al pedir `0100`, {en_una_frase(primera)}")
        etiqueta = self.s.protocolo["nombre"] if confirmado else "SIN CONFIRMAR"
        print(f"    ATDPN={crudo_num or '—'}  →  {etiqueta}")
        return confirmado

    def once_y_veintinueve(self):
        p = self.s.protocolo
        if not p or not p["es_can"]:
            self.s.fase("11vs29", False, "solo aplica a CAN")
            return
        print("· Probando 11 bits contra 29 bits...")
        salida = {}
        for clave, etiqueta in (("6", "11 bits / 500k"), ("7", "29 bits / 500k")):
            self.cmd("ATSP" + clave, 2.0)
            r = self.cmd("0100", 10.0)
            salida[etiqueta] = {"estado": r.estado, "crudo": r.crudo}
        # Se devuelve el adaptador a lo que el carro habla, y se COMPRUEBA
        # que volvió a conectar. Forzar un protocolo que el carro no habla
        # deja el enlace muerto, y todo lo que viniera después saldría como
        # "el carro no lo tiene".
        self.cmd("ATSP" + (p["clave"] or "0"), 2.0)
        vuelta = self.cmd("0100", 12.0)
        if vuelta.estado != MEDIDO:
            self.cmd("ATSP0", 2.0)
            vuelta = self.cmd("0100", 20.0)
        self._ajustes()
        self.s.once = salida
        self.s.fase("11vs29", vuelta.estado == MEDIDO,
                    "" if vuelta.estado == MEDIDO else "no reconectó tras forzar protocolos")

    # -- quién contesta ------------------------------------------------------

    def quien_contesta(self):
        print("· Viendo qué módulos contestan...")
        p = self.s.protocolo or {}
        r = self.cmd("0100", 10.0)
        if r.estado != MEDIDO:
            self.s.modulos = {"estado": r.estado, "crudo": r.crudo, "cabeceras": None}
            self.s.fase("modulos", False, en_una_frase(r))
            return
        ts = tramas(r.crudo, p.get("es_can", False))
        cabs = sorted({t["cabecera"] for t in ts})
        self.s.modulos = {"estado": MEDIDO, "crudo": r.crudo, "cabeceras": cabs}
        # Cero cabeceras con respuesta buena significa que el parseo falló, y
        # eso es distinto de que no conteste nadie. Se dice cuál de las dos es.
        self.s.fase("modulos", True,
                    "" if cabs else "contestó, pero no se pudo separar ninguna cabecera")
        print(f"    {len(cabs)} módulo(s): {', '.join(cabs) if cabs else '—'}")

    # -- PIDs ----------------------------------------------------------------

    def mapa_de_pids(self):
        """Recorre los bloques 0100, 0120... uniendo lo que dice CADA módulo.

        El mapa del carro es el O lógico de todos, no el del primero que
        conteste. Es el mismo criterio que ya exige la prueba de Kotlin.
        """
        print("· Preguntando qué PIDs tiene...")
        p = self.s.protocolo or {}
        es_can = p.get("es_can", False)
        pids, base = set(), 0x00
        alguno = False
        while base <= 0xC0:
            r = self.cmd("01%02X" % base, 8.0)
            entrada = {"consulta": "01%02X" % base, "estado": r.estado,
                       "crudo": r.crudo, "por_modulo": []}
            if r.estado != MEDIDO:
                self.s.mapas.append(entrada)
                break
            hubo = False
            for t in tramas(r.crudo, es_can):
                trozo, datos = mapa_de_una_trama(t, base)
                if trozo is None:
                    continue
                hubo = alguno = True
                pids |= set(trozo)
                entrada["por_modulo"].append({"cabecera": t["cabecera"], "bytes": datos,
                                              "pids": sorted(trozo)})
            self.s.mapas.append(entrada)
            if not hubo:
                break
            # Solo se sigue si ALGÚN módulo dice que hay más bloque.
            if (base + 0x20) not in pids:
                break
            base += 0x20

        if not alguno:
            # ⚠️ None, no lista vacía. Una lista vacía diría "el carro no tiene
            # ningún PID", que es una afirmación. None dice "no se sabe".
            self.s.pids = None
            self.s.fase("pids", False, "ningún módulo devolvió un mapa legible")
            print("    no se pudo leer el mapa")
            return
        self.s.con_1d = 0x1D in pids
        self.s.pids = sorted(p for p in pids if p % 0x20 != 0)
        self.s.fase("pids", True)
        print(f"    {len(self.s.pids)} PIDs")

    # -- ritmo ---------------------------------------------------------------

    def medir_ritmo(self, vueltas=20):
        """Cuántas lecturas por segundo da ESTE enlace.

        Se elige un PID que el carro haya dicho que tiene. Cronometrar 010C
        en un carro que no lo soporta mide la velocidad a la que el adaptador
        contesta NO DATA, que no es lo mismo y sale más rápido.
        """
        if not self.s.pids:
            self.s.fase("ritmo", False, "no se sabe qué PIDs acepta el carro")
            return
        for cand in (0x0C, 0x05, 0x0D, 0x04):
            if cand in self.s.pids:
                pid = "01%02X" % cand
                break
        else:
            pid = "01%02X" % self.s.pids[0]

        print(f"· Cronometrando {vueltas} lecturas de {pid}...")
        buenas = errores = mudas = 0
        t0 = time.time()
        for _ in range(vueltas):
            r = self.cmd(pid, 3.0)
            if r.estado == MEDIDO:
                buenas += 1
            elif r.estado == SIN_DATOS:
                mudas += 1
            else:
                errores += 1
        seg = time.time() - t0
        self.s.ritmo = {
            "pid": pid, "intentos": vueltas, "buenas": buenas,
            "mudas": mudas, "errores": errores, "segundos": round(seg, 2),
            # Sin ni una lectura buena NO hay velocidad que publicar. La
            # versión anterior escribía "0.0 lecturas por segundo" como cifra
            # rectora del reparto de turnos.
            "por_segundo": round(buenas / seg, 2) if (buenas and seg > 0) else None,
        }
        self.s.fase("ritmo", buenas > 0,
                    "" if buenas else "ninguna lectura buena: no hay velocidad que medir")
        if buenas:
            print(f"    {self.s.ritmo['por_segundo']} lecturas/s")

    # -- otros modos ---------------------------------------------------------

    def otros_modos(self):
        print("· Probando modos 03, 06, 07, 09, 0A y el 0x22...")
        pruebas = [
            ("03", "Averías guardadas", 8.0),
            ("07", "Averías pendientes", 8.0),
            ("0A", "Averías permanentes", 8.0),
            ("0600", "Modo 06 — qué monitores hay", 8.0),
            ("0900", "Modo 09 — qué información hay", 8.0),
            ("0902", "Modo 09 — VIN", 10.0),
            ("0904", "Modo 09 — CALID (versión de software)", 10.0),
            ("0906", "Modo 09 — CVN", 10.0),
            # Servicio 0x22 de UDS (ReadDataByIdentifier). Es de LECTURA.
            ("22F190", "Servicio 0x22 (UDS) — DID del VIN", 5.0),
        ]
        for orden, que, espera in pruebas:
            r = self.cmd(orden, espera)
            self.s.modos.append({"orden": orden, "que": que,
                                 "estado": r.estado, "crudo": r.crudo})
            if orden == "0902" and r.estado == MEDIDO:
                self.s.vin = descifrar_vin(r.crudo)
                if self.s.vin:
                    print(f"    VIN: {self.s.vin}")
            if orden == "0904" and r.estado == MEDIDO:
                self.s.calid = descifrar_vin(r.crudo.replace("4904", "4902"))
        self.s.fase("modos", True)


# ============================================================ informe


def _celda(t):
    """Deja un crudo dentro de una celda de tabla markdown sin romperla."""
    s = (t or "—").replace("|", "\\|").replace("\n", " ⏎ ")
    return s[:70] if s.strip() else "—"


def informe(carro, s, enlace_nombre, cortes):
    ahora = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    L = []
    A = L.append
    A(f"# Sondeo del OBD-II — {carro}")
    A("")
    A(f"_{ahora} · lo escribe `tools/sondear.py`_")
    A("")
    A("Todo lo que hay aquí salió del carro. Lo que el carro no contestó")
    A("aparece como que no contestó, y lo que no se llegó a preguntar aparece")
    A("como que no se preguntó. **No son lo mismo y nunca se mezclan.**")
    A("")

    # --- identidad
    A("## Qué carro es este")
    A("")
    A(f"- Etiqueta que se tecleó: `{carro}`")
    if s.vin:
        A(f"- **VIN leído del carro: `{s.vin}`**")
        if len(s.vin) >= 6:
            A(f"  - Posiciones 4-6 (modelo/carrocería): `{s.vin[3:6]}`")
    else:
        A("- VIN: **no se pudo leer** (modo 09 PID 02). La etiqueta de arriba")
        A("  la escribió un humano y no la respalda nada.")
    if s.calid:
        A(f"- CALID (versión de software de la centralita): `{s.calid}`")
    A("")

    # --- qué se llegó a hacer
    A("## Qué se llegó a medir")
    A("")
    A("| Fase | ¿Salió? | Nota |")
    A("|---|---|---|")
    nombres = {"adaptador": "Hablar con el adaptador", "protocolo": "Determinar el protocolo",
               "11vs29": "11 bits contra 29", "modulos": "Ver qué módulos contestan",
               "pids": "Leer el mapa de PIDs", "modos": "Probar los otros modos",
               "ritmo": "Cronometrar el enlace"}
    for k, n in nombres.items():
        f = s.fases.get(k)
        if f is None:
            A(f"| {n} | **no se llegó** | — |")
        else:
            A(f"| {n} | {'sí' if f['ok'] else '**no**'} | {f['nota'] or '—'} |")
    A("")
    A(f"- Adaptador: `{s.ident or 'no contestó'}`")
    A(f"- Enlace: `{enlace_nombre}`")
    if cortes:
        A(f"- ⚠️ **{cortes} respuesta(s) llegaron cortadas** (sin indicador `>`).")
        A("  Lo marcado como cortado no se puede creer ni descartar.")
    A("")

    # --- protocolo
    A("## Protocolo")
    A("")
    p = s.protocolo
    if not p:
        A("**No se llegó a preguntar.**")
    elif not p["confirmado"]:
        A(f"- El adaptador tenía puesto `{p['atdpn']}` — que sería *{p['nombre']}*.")
        A("")
        A("> ⚠️ **Esto NO es el protocolo del carro.** `ATDPN` dice lo que el")
        A("> adaptador tiene configurado, no lo que hay al otro lado: con el")
        A("> conector al aire devuelve un número igual. El carro no contestó a")
        A(f"> Al pedirle `0100`, {en_una_frase(p['primera'])}.")
        A(">")
        A("> **El protocolo de este carro queda sin determinar.**")
    else:
        A(f"- `ATDPN` → **`{p['atdpn']}`**, y el carro contestó de verdad por ahí.")
        A(f"- Es decir: **{p['nombre']}**")
        A(f"- `ATDP` → `{p['nombre_elm']}`")
        A(f"- Negociado automáticamente: {'sí' if p['automatico'] else 'no'}")
        A("")
        if p["es_can"]:
            A("> Es **CAN**. El reparto de turnos pensado para los ~9 lect/s de")
            A("> la K-line no aplica tal cual aquí.")
        else:
            A("> Es **K-line / J1850**, como los otros dos carros del proyecto.")
    A("")

    if s.once:
        A("### 11 bits contra 29 bits")
        A("")
        A("Importa porque una librería que da por hecho `7DF`/`7E8` contra un")
        A("carro que solo habla 29 bits **no da error: da NO DATA**. Y NO DATA")
        A("se parece demasiado a «el motor está apagado».")
        A("")
        A("| Modo | Resultado |")
        A("|---|---|")
        for k, v in s.once.items():
            A(f"| {k} | {COMO_SE_LEE.get(v['estado'], v['estado'])} |")
        A("")

    # --- módulos
    A("## Quién contesta")
    A("")
    if s.modulos is None:
        A("**No se llegó a preguntar.**")
    elif s.modulos["estado"] != MEDIDO:
        A(f"**El carro no contestó** ({COMO_SE_LEE.get(s.modulos['estado'])}).")
        A("Esto no dice cuántos módulos hay: dice que no hubo respuesta.")
    elif not s.modulos["cabeceras"]:
        A("Hubo respuesta, **pero no se pudo separar ninguna cabecera**. Eso es")
        A("un fallo de lectura de esta herramienta, no una propiedad del carro.")
    else:
        A(f"**{len(s.modulos['cabeceras'])} módulo(s)** contestaron:")
        A("")
        for c in s.modulos["cabeceras"]:
            A(f"- `{c}`")
    A("")
    if s.modulos and s.modulos.get("crudo"):
        A("```")
        A(s.modulos["crudo"])
        A("```")
        A("")

    # --- PIDs
    A("## PIDs del modo 01")
    A("")
    if s.pids is None:
        A("> ⚠️ **NO SE PUDO LEER EL MAPA DE PIDs.**")
        A(">")
        A("> Esto no significa que el carro no tenga PIDs: significa que no se")
        A("> sabe cuáles tiene. La tabla de lo que necesita el tablero se omite")
        A("> a propósito — rellenarla de «NO» sería afirmar que le faltan doce")
        A("> cosas cuando lo único cierto es que no se pudo preguntar.")
        A("")
        if s.mapas:
            A("Lo que se intentó:")
            A("")
            A("| Consulta | Resultado | Crudo |")
            A("|---|---|---|")
            for m in s.mapas:
                A(f"| `{m['consulta']}` | {COMO_SE_LEE.get(m['estado'], m['estado'])} | `{_celda(m['crudo'])}` |")
            A("")
    else:
        A("| Consulta | Módulo | Bytes | Resultado |")
        A("|---|---|---|---|")
        for m in s.mapas:
            if m["por_modulo"]:
                for pm in m["por_modulo"]:
                    A(f"| `{m['consulta']}` | `{pm['cabecera']}` | `{pm['bytes']}` | contestó |")
            else:
                A(f"| `{m['consulta']}` | — | — | {COMO_SE_LEE.get(m['estado'], m['estado'])} |")
        A("")
        A(f"**{len(s.pids)} PIDs de datos**, uniendo lo que dijo cada módulo.")
        A("")
        A("| PID | Qué es |")
        A("|---|---|")
        for pid in s.pids:
            A(f"| `01{pid:02X}` | {nombre_pid(pid, s.con_1d)} |")
        A("")
        A("### Lo que el tablero necesita")
        A("")
        A("| PID | Para | ¿Está? |")
        A("|---|---|---|")
        tiene = set(s.pids)
        faltan = []
        for pid, para in sorted(LO_QUE_PINTA_EL_TABLERO.items()):
            hay = pid in tiene
            A(f"| `01{pid:02X}` | {para} | {'sí' if hay else '**no lo tiene**'} |")
            if not hay:
                faltan.append(f"`01{pid:02X}` ({para})")
        A("")
        if faltan:
            A("> ⚠️ **Le faltan: " + ", ".join(faltan) + ".**")
            A("> Esos huecos van en blanco en el tablero, o derivados y marcados")
            A("> como derivados. Nunca en cero.")
        else:
            A("> Están los doce.")
        A("")

    # --- otros modos
    A("## Otros modos")
    A("")
    if not s.modos:
        A("**No se llegaron a probar.**")
    else:
        A("Ojo con leer esta tabla: «no lo tiene» quiere decir que el carro")
        A("contestó NO DATA. En los modos de averías (03, 07, 0A) eso puede")
        A("significar simplemente **que no hay averías guardadas**, no que el")
        A("modo no exista.")
        A("")
        A("| Orden | Qué es | Resultado | Crudo |")
        A("|---|---|---|---|")
        for m in s.modos:
            A(f"| `{m['orden']}` | {m['que']} | {COMO_SE_LEE.get(m['estado'], m['estado'])} | `{_celda(m['crudo'])}` |")
        A("")

    # --- ritmo
    A("## Ritmo real del enlace")
    A("")
    if not s.ritmo:
        A("**No se llegó a medir.**")
    elif s.ritmo["por_segundo"] is None:
        A(f"- Se intentaron {s.ritmo['intentos']} lecturas de `{s.ritmo['pid']}`.")
        A(f"- Buenas: **{s.ritmo['buenas']}** · sin datos: {s.ritmo['mudas']} · errores: {s.ritmo['errores']}")
        A("")
        A("> **No hay velocidad que publicar.** Cero lecturas buenas no son")
        A("> «cero lecturas por segundo»: son ninguna medición.")
    else:
        A(f"- PID cronometrado: `{s.ritmo['pid']}`")
        A(f"- {s.ritmo['buenas']} buenas de {s.ritmo['intentos']} en {s.ritmo['segundos']} s")
        A(f"  (sin datos: {s.ritmo['mudas']} · errores: {s.ritmo['errores']})")
        A(f"- **{s.ritmo['por_segundo']} lecturas por segundo**")
        A("")
        A("> De ESTE carro con ESTE adaptador. Es la cifra que debe gobernar el")
        A("> reparto de turnos, no la de la hoja de datos.")
    A("")
    return "\n".join(L)


def conversacion(eco):
    L = ["", "## Conversación completa", "",
         "<details><summary>Todo lo que se mandó y se recibió</summary>", "", "```"]
    for r in eco:
        L.append(f"> {r.orden}    [{r.estado}]")
        for l in (r.crudo or "(nada)").split("\n"):
            L.append(f"  {l}")
    L += ["```", "", "</details>", ""]
    return "\n".join(L)


# ============================================================ autoprueba


def autoprueba():
    """Se comprueba a sí mismo, sin carro y sin adaptador.

    Existe porque la primera versión de este fichero se llevó 24 defectos
    confirmados en una revisión, y 21 eran de la clase «publica como medido
    algo que no midió». Eso no se ve leyendo: se ve ejecutando.
    """
    fallos = []

    def check(que, cond):
        print(("  OK   " if cond else "  FALLO ") + que)
        if not cond:
            fallos.append(que)

    print("\n  AUTOPRUEBA — la lógica pura, sin carro\n")

    print("  Clasificar respuestas del ELM327:")
    check("NO DATA es del carro", clasificar("NO DATA", True) == SIN_DATOS)
    check("CAN ERROR es del enlace", clasificar("CAN ERROR", True) == ERROR_ENLACE)
    check("BUFFER FULL es del enlace", clasificar("BUFFER FULL", True) == ERROR_ENLACE)
    check("ERR94 es del enlace", clasificar("ERR94", True) == ERROR_ENLACE)
    check("UNABLE TO CONNECT es del enlace", clasificar("UNABLE TO CONNECT", True) == ERROR_ENLACE)
    check("STOPPED es del enlace", clasificar("STOPPED", True) == ERROR_ENLACE)
    check("'?' es orden no entendida", clasificar("?", True) == ERROR_ENLACE)
    check("SEARCHING a secas queda cortada", clasificar("SEARCHING...", True) == CORTADA)
    check("sin indicador queda cortada", clasificar("7E8064100BE3EA813", False) == CORTADA)
    check("7F 01 12 es negativa (CAN)", clasificar("7E8037F0112", True) == NEGATIVA)
    check("7F en K-line también", clasificar("486B107F0112", True) == NEGATIVA)
    check("un 7F dentro de datos NO es negativa",
          clasificar("7E8064100BE3E7F01", True) == MEDIDO)
    check("una trama buena es medida", clasificar("7E8064100BE3EA813", True) == MEDIDO)
    check("OK es medida", clasificar("OK", True) == MEDIDO)

    print("\n  Separar tramas con ATH1:")
    can11 = tramas("7E8064100BE3EA813\n7E9064100801FE800", True)
    check("CAN 11 bits: dos módulos", [t["cabecera"] for t in can11] == ["7E8", "7E9"])
    can29 = tramas("18DAF110064100BE3EA813", True)
    check("CAN 29 bits", [t["cabecera"] for t in can29] == ["18DAF110"])
    kl = tramas("486B104100BE3EA813C4", False)
    check("K-line", [t["cabecera"] for t in kl] == ["486B10"])
    check("la cabecera de longitud '014' se ignora",
          tramas("014\n0:490201314847", True) == [])

    print("\n  Decodificar el mapa de PIDs:")
    t = tramas("7E8064100BE3EA813", True)[0]
    pids, datos = mapa_de_una_trama(t, 0x00)
    check("los bytes son BE3EA813", datos == "BE3EA813")
    check("0100 da 17 bits (16 datos + el 0x20)", len(pids) == 17)
    check("tiene 010C (rpm)", 0x0C in pids)
    check("NO tiene 0110 (MAF)", 0x10 not in pids)

    todos = set()
    for base, dat in ((0x00, "BE3EA813"), (0x20, "801FF011"), (0x40, "FAD00000")):
        tr = tramas("7E806" + "41%02X" % base + dat, True)[0]
        p, _ = mapa_de_una_trama(tr, base)
        todos |= set(p)
    datos_reales = sorted(x for x in todos if x % 0x20)
    check("el volcado del Fit da 36 PIDs de datos", len(datos_reales) == 36)
    check("techo en 014C", max(datos_reales) == 0x4C)
    check("no hay bloque 0160", 0x60 not in todos)
    check("NO tiene 0124 (banda ancha)", 0x24 not in datos_reales)

    print("\n  Unir el mapa de VARIOS módulos:")
    # El segundo módulo declara 0x10 (MAF), que el primero NO tiene. Si la
    # unión no crece con esto, es que se está publicando el mapa de UN módulo
    # como si fuera el del carro — que es lo que hacía la primera versión.
    dos = tramas("7E8064100BE3EA813\n7E906410080010011", True)
    union = set()
    for tr in dos:
        p, _ = mapa_de_una_trama(tr, 0x00)
        if p:
            union |= set(p)
    uno, _ = mapa_de_una_trama(dos[0], 0x00)
    check("la unión es mayor que la del primer módulo", len(union) > len(uno))

    print("\n  Nombrar sondas según 0x13 o 0x1D:")
    check("con 0x13, 0x14 es B1S1", nombre_pid(0x14, False) == "Sonda B1S1")
    check("con 0x13, 0x18 es B2S1", nombre_pid(0x18, False) == "Sonda B2S1")
    check("con 0x1D, 0x18 es B3S1", nombre_pid(0x18, True) == "Sonda B3S1 (4 bancos)")

    print("\n  VIN:")
    vin = "1HGCM82633A004352"
    cuerpo = "".join("%02X" % ord(c) for c in vin)
    check("se descifra del modo 09", descifrar_vin("014\n0:4902" + cuerpo) == vin)
    check("sin 4902 devuelve None", descifrar_vin("NO DATA") is None)

    print("\n  El informe con un carro que NO contesta:")
    s = Sondeo()
    s.fase("adaptador", True)
    s.protocolo = {"confirmado": False, "atdpn": "A3", "clave": "3", "automatico": True,
                   "nombre_elm": "AUTO, ISO 9141-2", "nombre": PROTOCOLOS["3"],
                   "es_can": False, "primera": Respuesta("0100", "NO DATA", SIN_DATOS)}
    s.fase("protocolo", False, "el carro no contestó")
    s.pids = None
    s.fase("pids", False, "ningún módulo devolvió un mapa")
    texto = informe("fantasma", s, "COM9", 0)
    check("NO dice '0 PIDs'", "0 PIDs" not in texto)
    check("NO afirma que es K-line", "Es **K-line" not in texto)
    check("dice que el protocolo está sin determinar", "sin determinar" in texto)
    check("NO pinta la tabla del tablero", "Lo que el tablero necesita" not in texto)
    check("avisa de que no se pudo leer el mapa", "NO SE PUDO LEER EL MAPA" in texto)
    check("dice que el VIN no se pudo leer", "no se pudo leer" in texto)
    check("la frase del 0100 se lee bien", "devolvió NO DATA" in texto)
    check("NO dice el absurdo 'no contestó (no lo tiene)'",
          "(no lo tiene)" not in texto)

    print("\n  Celdas de tabla:")
    check("una barra vertical no rompe la tabla", "\\|" in _celda("A|B"))

    print()
    if fallos:
        print(f"  {len(fallos)} FALLO(S)\n")
        return 1
    print("  Todo en verde.\n")
    return 0


# ============================================================ main


def main():
    p = argparse.ArgumentParser(description="Sondea qué es capaz de contestar un carro por OBD-II.")
    p.add_argument("--puerto", help="Puerto serie del adaptador, p.ej. COM3")
    p.add_argument("--tcp", help="Adaptador por WiFi, p.ej. 192.168.0.10:35000")
    p.add_argument("--baudios", type=int, default=38400)
    p.add_argument("--carro", default="carro", help="Etiqueta para el informe")
    p.add_argument("--salida", help="Dónde dejar el informe (por omisión, docs/)")
    p.add_argument("--listar", action="store_true", help="Enseña los puertos y sale")
    p.add_argument("--prueba", action="store_true", help="Se comprueba a sí mismo, sin carro")
    p.add_argument("--sin-ritmo", action="store_true")
    p.add_argument("-v", "--verboso", action="store_true")
    a = p.parse_args()

    if a.prueba:
        return autoprueba()
    if a.listar:
        return listar_puertos()
    if not a.puerto and not a.tcp:
        p.error("hace falta --puerto o --tcp (o --listar para ver qué hay)")

    print(f"\n  SONDEANDO — {a.carro}")
    print("  " + "-" * 46)
    print("  Solo lee. No borra averías ni cambia nada del carro.\n")

    try:
        enlace = por_tcp(a.tcp) if a.tcp else por_serie(a.puerto, a.baudios)
    except Exception as e:
        print(f"  No se pudo abrir el enlace: {e}")
        print("  En Windows el emparejado Bluetooth crea DOS puertos COM.")
        print("  Si este no contesta, prueba el otro.")
        return 1

    sonda = Sonda(enlace, a.verboso)

    # ⚠️ CADA FASE EN SU PROPIO try. El fallo más probable de todos —el
    # Bluetooth flojo, agachado en un carro— era el que en la versión
    # anterior destruía dos minutos de mediciones sin escribir nada.
    def intentar(nombre, fn):
        try:
            return fn()
        except EnlaceCaido as e:
            sonda.s.fase(nombre, False, f"se cayó el enlace: {e}")
            print(f"    ⚠️ se cayó el enlace durante «{nombre}»: {e}")
            raise
        except KeyboardInterrupt:
            sonda.s.fase(nombre, False, "interrumpido a mano")
            print(f"\n    interrumpido durante «{nombre}»; se guarda lo medido")
            raise
        except Exception as e:
            sonda.s.fase(nombre, False, f"{type(e).__name__}: {e}")
            print(f"    ⚠️ falló «{nombre}»: {e}")
            return None

    try:
        if intentar("adaptador", sonda.despertar):
            if intentar("protocolo", sonda.negociar):
                intentar("11vs29", sonda.once_y_veintinueve)
                intentar("modulos", sonda.quien_contesta)
                intentar("pids", sonda.mapa_de_pids)
                intentar("modos", sonda.otros_modos)
                if not a.sin_ritmo:
                    intentar("ritmo", sonda.medir_ritmo)
            else:
                print("\n  El adaptador vive, pero el carro no contesta.")
                print("  Casi siempre es una de estas dos:")
                print("  · El contacto no está en la posición II.")
                print("  · El adaptador no hace contacto en el conector.")
                print("  Se guarda el informe igual: un «no contesta» es un dato.")
        else:
            print("\n  El adaptador no contesta ni a ATZ.")
            print("  · ¿Es el puerto COM saliente? Prueba el otro.")
            print("  · ¿Está enchufado y con el contacto puesto?")
    except (EnlaceCaido, KeyboardInterrupt):
        pass
    finally:
        try:
            texto = informe(a.carro, sonda.s, enlace.nombre, enlace.cortes)
            texto += conversacion(enlace.eco)
        except Exception as e:
            texto = f"# Sondeo — {a.carro}\n\nEl informe no se pudo componer: `{e}`\n"
        enlace.cerrar()

    destino = a.salida or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "docs", f"sondeo-{a.carro}-{datetime.date.today()}.md",
    )
    # `dirname` de un nombre suelto es "", y os.makedirs("") revienta: la
    # versión anterior perdía el informe entero en el último paso.
    carpeta = os.path.dirname(os.path.abspath(destino))
    os.makedirs(carpeta, exist_ok=True)
    with open(destino, "w", encoding="utf-8") as f:
        f.write(texto)

    print(f"\n  Informe: {destino}\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n  cortado.\n")
        sys.exit(130)
