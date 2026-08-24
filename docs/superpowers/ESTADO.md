# S2000 Dash — Estado y punto de retomada

**Última actualización:** 2026-08-23

Este archivo captura DÓNDE nos quedamos, para no repetir trabajo ni perder lo verificado.

---

## Resumen en una línea

El diseño de la app está aprobado y escrito. Estamos trabados en **conseguir acceso
al radio (head unit RK3326)** para leer sus specs e instalar la app. El servidor SSH
del radio ya responde; falta autenticarse con el **usuario correcto**.

---

## Lo que YA funciona (verificado en vivo)

| Cosa | Estado | Detalle |
|---|---|---|
| Spec de diseño | ✅ escrito y commiteado | `docs/superpowers/specs/2026-08-23-s2000-dash-design.md` |
| IP del radio | ✅ `192.168.2.57` | responde a ping (~250 ms), en Wi-Fi `Nonosky.com` |
| SSH en el radio | ✅ escuchando | puerto **2222**, Dropbear 2020.81 (SimpleSSHD), host key ed25519 `SHA256:Cgf/zaeMb+K5KiBpXBIhApRG9VRN4fcOWWKOxivbcHc` |
| Servidor HTTP para instalar APKs | ✅ arriba | laptop `192.168.2.194`, puerto **80** y **8000**, sirve `s.apk` (SimpleSSHD) y `t.apk` (Termux). Firewall abierto (reglas `S2000-APK-80` y `S2000-APK-8000`). |
| El radio descargó Termux por HTTP | ✅ confirmado en log | `192.168.2.57 GET /t.apk 200` |
| Memoria USB | ✅ reformateada 16 GB FAT32 | letra `E:`, etiqueta RADIO, clúster 8 KB, offset 1 MB. Trae `1-SimpleSSHD.apk`, `2-Termux.apk`, `LEEME.txt` |
| Apps instaladas en el radio | ✅ SimpleSSHD y Termux | por el usuario |
| Llave SSH para el radio | ✅ generada | `scratchpad/radio_key` (privada) y `radio_key.pub` (pública). Pública abajo. |
| Cliente SSH en laptop | ✅ | OpenSSH 9.5p2 + paramiko 5.0.0 |

---

## EL BLOQUEO ACTUAL

SimpleSSHD usa **contraseña de un solo uso** (cambia en cada intento) y estábamos
mandando el **usuario equivocado**. La pantalla de SimpleSSHD mostró
**"attempt from user"**, lo que indica que el nombre de usuario correcto es
literalmente **`user`** — NO `root`, `admin`, `any` ni `u0_aXXX`.

Se quemaron 4 contraseñas OTP probando con usuario equivocado.

### Próximo paso EXACTO al retomar

1. El usuario abre SimpleSSHD y lee la contraseña **actual** en pantalla.
2. Correr (una sola vez, dispara al instante para que no rote):
   ```
   C:\Users\Usuario\AppData\Local\Programs\Python\Python311\python.exe \
     "<scratchpad>\go.py"  <PASSWORD_FRESCA>
   ```
   - `go.py` ya está escrito. Usuario fijo = **`user`**. Una sola autenticación.
   - Si entra: instala la llave pública automáticamente y vuelca las specs del radio.
3. Al instalar la llave, **se acaba la dependencia de la OTP** — de ahí en adelante:
   ```
   ssh -i "<scratchpad>\radio_key" -p 2222 user@192.168.2.57
   ```

`<scratchpad>` = `C:\Users\Usuario\AppData\Local\Temp\claude\C--Users-Usuario-s2000\d22f7a3d-646d-4186-b49f-67e48d5f0a1d\scratchpad`

### Si el usuario `user` tampoco entra

- Leer la línea EXACTA que muestra SimpleSSHD (formato `usuario@ip:puerto`) y usar ese usuario.
- Plan B: usar **Termux** (ya instalado) con contraseña FIJA — más estable que la OTP:
  en Termux teclear `pkg i openssh -y`, `passwd` (fijar una), `sshd`, `whoami`.
  Luego `ssh -p 8022 <whoami>@192.168.2.57`.

---

## Llave pública a instalar en el radio

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINpc7Ubdb2lfGe4MZgTHaJgCbRboGvWXs+grmw9Kdk/8 claude-radio-s2000
```

---

## Lo que sigue pendiente de saber del radio (cierra riesgo R2 del spec)

Nada de esto se ha podido leer aún — requiere entrar por SSH:
- Versión de Android y API level
- Resolución exacta y densidad de pantalla
- ABI del RK3326 (esperado arm — confirmar arm64-v8a vs armeabi-v7a)
- Si tiene **root** (`su`). Si lo tiene → activar adb por red y usar `scrcpy` (control total de pantalla) + `logcat`.

---

## Toolchain de build (NO instalado todavía)

Para compilar el APK del tablero falta: **JDK + Android SDK**. Solo está `platform-tools`
(adb) en `~/Android/Sdk`. Es descarga de varios GB. No se ha empezado a petición de
enfocarnos primero en el acceso al radio.

Decisiones de build ya tomadas (ver spec): Kotlin nativo, `DashView` con Canvas (no Compose),
minSdk 21, métrico puro (km/h/°C), sin modo demo, interpolación de aguja a 60 fps.

---

## Hardware OBD (recordatorio)

- Adaptador OBD del carro: **Steren Bluetooth SPP/ELM327** (se asume clásico, no BLE — riesgo R1).
- Protocolo esperado del AP1: **ISO 9141-2 (K-line)**, lento, ~9 lecturas/seg — confirmar con `ATDP`.
- La laptop también tiene emparejado un **OBDLink MX+** (COM3) que estaba sin corriente (carro apagado).
