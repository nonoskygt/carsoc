# S2000 Dash — Estado y punto de retomada

**Última actualización:** 2026-08-23 (noche, sesión 2)

Este archivo captura DÓNDE nos quedamos, para no repetir trabajo ni perder lo verificado.

---

## Resumen en una línea

✅ **APP IMPLEMENTADA Y CONSTRUIDA.** El acceso al radio está resuelto (dos vías SSH sin
password), las specs del radio leídas, el toolchain instalado, y la app entera escrita
con 40 pruebas JVM en verde y APK de release listo y servido por HTTP.
Falta solo probarla en el carro encendido.

### Cómo conectarse ahora mismo

Llave (sin passphrase): `radio_key2` / `radio_key2.pub` en el scratchpad de la sesión.
Pública:
```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ7hM7QJe0eYv28jsYu1uhTxEZHDUvP8Ml9uVHUHe4JZ claude-radio-s2000-2
```

**Vía A — SimpleSSHD (puerto 2222):**
```
ssh -i "<scratchpad>\radio_key2" -p 2222 -o BatchMode=yes user@192.168.2.57
```
Instalada en `/data/user/0/org.galexander.sshd/files/authorized_keys` (ver bug de ruta abajo).

**Vía B — Termux (puerto 8022):**
```
ssh -i "<scratchpad>\radio_key2" -p 8022 -o BatchMode=yes root@192.168.2.57
```
Termux SSH acepta cualquier nombre de usuario en el login; el shell real siempre es
`u0_a83`. Instalada en `~/.ssh/authorized_keys` de Termux.

Nota: las dos apps corren en sandboxes Android separados (UIDs distintos, sin root) —
no se puede tocar los archivos de una desde una sesión SSH en la otra. Cada vía se
arregló por separado, entrando por su propia OTP.

### Historial del bloqueo con SimpleSSHD (ya resuelto, dejar como referencia)

1. Usuario correcto = `user` (confirmado).
2. La OTP se genera **por conexión entrante**, no por intervalo de tiempo — cada
   intento nuevo rota la password, así que hay que leerla y usarla en la MISMA
   conexión (no reconectar). Se resuelve con un script que abre el socket y espera
   a que el usuario lea la pantalla antes de mandar el password.
3. Bug real: la ruta de `authorized_keys` NO es `$HOME/.ssh/authorized_keys` sino
   literalmente `<SSH Path>/authorized_keys` (sin subcarpeta `.ssh`), donde
   `SSH Path` es un ajuste configurable en Settings → Dropbear → Paths, y por
   default vale `/data/user/0/org.galexander.sshd/files` (visible también en el
   menú "Copy App-private Path").
4. Una vez que SimpleSSHD detecta que existe `authorized_keys`, **deja de generar
   OTP para cualquier conexión**, incluidas las que solo intentan password. La
   primera llave generada (`radio_key`, sesión anterior) resultó tener passphrase
   desconocida y quedó inservible — solución: **Settings → "..." → Reset Keys**
   en la app (borra el `authorized_keys` viejo, vuelve a pedir OTP), y reinstalar
   con una llave nueva sin passphrase (`radio_key2`). Guardar esto para la próxima
   vez que haga falta reinstalar la llave ahí.

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

## Specs del radio (leídas por SSH, riesgo R2 del spec CERRADO)

| Cosa | Valor |
|---|---|
| Android | **11**, API level **30** |
| Marca/modelo/device | `rockchip` / `auto_rk_t11` / `auto_rk_t11` |
| SoC | `rk3326` (`ro.hardware=rk30board`) |
| ABI | **armeabi-v7a, armeabi** — es de 32 bits puro, **NO arm64-v8a**. Cualquier build/APK debe targetear `armeabi-v7a`. |
| Pantalla | **1280×480** físico, densidad **160** (ldpi/mdpi real, panel ancho tipo barra) |
| Root (`su`) | **NO tiene** — confirmado, "No su program found" |
| ADB por red (`persist.sys.usb.config`) | `none` — no está habilitado; sin root no se puede activar por sistema. |

Implicación directa para el spec: sin root, **no hay `scrcpy` ni control remoto de
pantalla**, y hay que compilar el APK para `armeabi-v7a` (32-bit) — revisar que el
toolchain de Kotlin/Android genere ese ABI y no solo arm64.

---

## Toolchain de build (✅ INSTALADO)

| Pieza | Dónde |
|---|---|
| JDK 17 (Temurin) | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` |
| Android SDK | `C:\Users\Usuario\Android\Sdk` — platform 34, build-tools 34.0.0, platform-tools |
| Gradle 8.7 | `C:\Users\Usuario\tools\gradle-8.7` (sin wrapper: se invoca directo) |

Comando de build (pruebas + APK):

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
$env:ANDROID_HOME="$env:USERPROFILE\Android\Sdk"
& "$env:USERPROFILE\tools\gradle-8.7\bin\gradle.bat" -p C:\Users\Usuario\s2000 testDebugUnitTest assembleRelease
```

`local.properties` usa barras normales (`C:/Users/...`): con backslashes Java se
come los escapes y Gradle falla con "sintaxis de la etiqueta del volumen no es correcta".

---

## Estado de la app (✅ IMPLEMENTADA)

Todo el diseño está implementado y commiteado. **40 pruebas JVM en verde**, APK de
release construido (1.7 MB).

| Unidad | Archivo |
|---|---|
| Constantes del F20C | `app/src/main/kotlin/.../EngineConstants.kt` |
| Estado del carro | `.../VehicleState.kt` |
| Decodificador de PIDs | `.../obd/PidDecoder.kt` |
| Diálogo AT | `.../obd/Elm327Session.kt` |
| Round-robin de sondeo | `.../obd/PollScheduler.kt` |
| Transporte RFCOMM | `.../obd/SppTransport.kt` |
| Tablero en Canvas | `.../ui/DashView.kt` |
| Pantalla | `.../DashActivity.kt` |

Detalle que costó encontrar: el reparto de PIDs **no** puede hacerse con módulos
encadenados (`cycle % 3`, `cycle % 20`...). "Cada 3" y "cada 20" coinciden una de cada
tres veces y el de mayor prioridad le roba el turno al otro, dejando agua, aire y carga
por debajo de la frecuencia de §5. Está resuelto con una tabla explícita de 60 ciclos
(mcm de 3, 10 y 20) con slots disjuntos por construcción.

Nota sobre el ABI: la app es Kotlin puro, sin código nativo, así que el APK sirve para
cualquier arquitectura — el `armeabi-v7a` de 32 bits del radio incluido. La restricción
de ABI que preocupaba resultó no aplicar.

### Instalación en el radio

`pm install` por SSH **no** funciona: sin root, Termux (`u0_a83`) no tiene permiso, y
`/sdcard` tampoco es escribible desde ahí. La vía que sí funciona es la ya probada por
HTTP, con el servidor de la laptop:

- Página de instalación: **192.168.2.194:8000/radio.html** (botón de un toque)
- APK directo: **192.168.2.194:8000/dash.apk**

Los archivos servidos viven en el scratchpad de la sesión anterior, en `serve/`.

### Lo único que falta

Validación visual del tacómetro en el radio con el carro encendido (§11 dice
explícitamente que `DashView` no lleva pruebas automatizadas), y confirmar los riesgos
abiertos R1 (¿el Steren es SPP o BLE?) y R5 (¿el AP1 habla ISO 9141-2?) — ambos se
responden solos al primer arranque: el badge de arriba muestra el protocolo que negoció.

---

## Hardware OBD (recordatorio)

- Adaptador OBD del carro: **Steren Bluetooth SPP/ELM327** (se asume clásico, no BLE — riesgo R1).
- Protocolo esperado del AP1: **ISO 9141-2 (K-line)**, lento, ~9 lecturas/seg — confirmar con `ATDP`.
- La laptop también tiene emparejado un **OBDLink MX+** (COM3) que estaba sin corriente (carro apagado).
