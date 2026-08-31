# -*- coding: utf-8 -*-
"""ELM327 de mentira. Dos personalidades para probar la sonda sin carro."""
import socket, sys, threading

MUDO = "mudo"        # el adaptador vive, el carro no contesta NADA
CAN  = "can"         # un Fit imaginario: CAN 11 bits, 2 modulos

def responder(orden, quien):
    o = orden.upper().replace(" ", "")
    if o.startswith("AT"):
        if o == "ATZ":   return "ELM327 v1.5"
        if o == "ATDPN": return "A3" if quien == MUDO else "A6"
        if o == "ATDP":  return "AUTO, ISO 9141-2" if quien == MUDO else "AUTO, ISO 15765-4 (CAN 11/500)"
        return "OK"
    if quien == MUDO:
        return "NO DATA"
    # --- personalidad CAN ---------------------------------------------------
    if o == "0100": return "7E8064100BE3EA813\n7E9064100 8001 0011".replace(" ","")
    if o == "0120": return "7E806412 0801FF011".replace(" ","")
    if o == "0140": return "7E8064140FAD00000"
    if o == "0160": return "NO DATA"
    if o == "010C": return "7E8041 0C1AF8".replace(" ","")
    if o == "0902":
        vin = "1HGGD38477S012345"
        h = "".join("%02X" % ord(c) for c in vin)
        return "014\n0:490201" + h[:10] + "\n1:" + h[10:24] + "\n2:" + h[24:]
    if o == "0600": return "7E8037F0612"      # el modulo lo RECHAZA
    if o == "22F190": return "7E8037F2211"    # tambien
    if o in ("03","07","0A"): return "7E8024300" if o=="03" else "NO DATA"
    return "NO DATA"

def servir(puerto, quien):
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", puerto)); s.listen(1)
    c, _ = s.accept()
    buf = b""
    try:
        while True:
            d = c.recv(1024)
            if not d: break
            buf += d
            while b"\r" in buf:
                linea, _, buf = buf.partition(b"\r")
                orden = linea.decode("ascii", "replace").strip()
                if not orden: continue
                r = responder(orden, quien)
                c.sendall((r + "\r\r>").encode("ascii"))
    except Exception: pass
    finally:
        c.close(); s.close()

if __name__ == "__main__":
    servir(int(sys.argv[1]), sys.argv[2])
