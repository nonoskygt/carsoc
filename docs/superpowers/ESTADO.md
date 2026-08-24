# S2000 Dash — Estado y punto de retomada

**Última actualización:** 2026-08-24

Este archivo captura DÓNDE nos quedamos y, sobre todo, **qué ya se descartó con
evidencia**, para no repetir callejones sin salida.

---

## Resumen en una línea

La app está **terminada y desplegándose sola** en el radio. El único bloqueo es
físico: **el adaptador Steren no acepta ninguna conexión Bluetooth**. En cuanto
haya un adaptador que responda, el tablero lee el motor.

---

## Hardware (verificado en vivo, no supuesto)

| Cosa | Valor |
|---|---|
| Head unit | Android 11 / API 30, SoC rockchip **rk3326** |
| ABI | **armeabi-v7a** (32 bits, sin arm64). Irrelevante: la app es Kotlin puro, el APK es universal |
| Pantalla | **1280×480 físicos @160 dpi** — barra de 2.67:1, muy ancha y baja |
| Root | **No hay** |
| IP del radio | `192.168.2.57` |
| IP de la laptop | `192.168.2.20` — **cambia** (antes fue `.194`, al pasar de Wi-Fi a Ethernet) |
| Adaptador OBD | **Steren SCAN-010**, MAC `00:1D:A5:68:98:8B`, **tipo CLÁSICO** (no BLE) |
| Otros emparejados en el radio | `M11` (18:43:A7:1C:0E:65), `elperegrinocosmico` (DC:F0:90:58:E4:9E) |

---

## EL BLOQUEO ACTUAL

**El tablero no lee el motor.** El Steren aparece en el barrido pero **no acepta
ninguna conexión**.

### Lo que YA se descartó, con evidencia

| Hipótesis | Cómo se descartó |
|---|---|
| Es BLE, no clásico (riesgo R1 del diseño) | El barrido reporta `tipo=CLASICO`. **R1 cerrado.** |
| Falta teclear el PIN | El confirmador —que ve **todas** las ventanas y **también las notificaciones**— confirma que **nunca aparece diálogo ni notificación** de emparejamiento |
| El diálogo sale en un paquete no vigilado | Se le quitó el filtro `packageNames`; ve todo |
| El teléfono tenía tomado el adaptador | Se apagó su Bluetooth; sigue igual |
| Hace falta emparejar antes de conectar | Se probó **RFCOMM inseguro**, que no lo exige |

### El síntoma exacto

- `createBond()` devuelve `true`, entra en `BONDING` (11) y muere en `NONE` (10)
  **sin que el adaptador conteste nada**.
- Las **cuatro** vías de RFCOMM fallan idénticas:
  `read failed, socket might closed or timeout, read ret: -1`
  (inseguro-SPP, seguro-SPP, inseguro-canal1, seguro-canal1).

**Conclusión:** es el adaptador o la pila Bluetooth del radio. El software agotó
lo que puede hacer.

### Próximo paso EXACTO

Emparejar el **Steren con un teléfono** y abrirlo con una app OBD (Torque, Car
Scanner), con el switch en contacto.

- **Funciona en el teléfono** → el Bluetooth del radio está roto (frecuente en
  estas ROMs chinas). Salida: usar el **OBDLink MX+** que el usuario ya tiene
  emparejado en la laptop.
- **No funciona en el teléfono** → el Steren está muerto o es incompatible.

---

## Control remoto (esto ya funciona; usarlo)

El radio se controla **entero por HTTP** desde la laptop. Puerto `8099`:

| Ruta | Qué hace |
|---|---|
| `/state` | Estado del vehículo, del enlace, versión instalada, adaptador elegido, último error |
| `/shot.png` | El tablero dibujado tal cual se ve (la app se auto-fotografía) |
| `/log` | Bitácora de actualizaciones |
| `/update` | Busca e instala versión nueva |
| `/adaptadores` | Bluetooth ya emparejados |
| `/buscar` | Barre el aire (reporta tipo y UUIDs) |
| `/emparejar?mac=` | Empareja y elige |
| `/elegir?mac=` `/olvidar` | Elige / olvida adaptador |
| `/instalar-companero?url=&paquete=` | Instala el confirmador (verifica firma) |
| `/armar-pin?pin=` | Arma el confirmador para teclear el PIN |
| `/confirmador` | Qué ventanas está viendo el confirmador |

Con esto se desplegaron **12 versiones del tablero y 5 del confirmador sin tocar
el radio**.

### Publicar una versión

```bash
bash tools/publicar.sh          # detecta la IP sola
curl -s http://192.168.2.57:8099/update
```

`tools/anunciador.py` debe estar corriendo: difunde por UDP 8098 la URL base.
**La laptop anuncia y el radio escucha**, no al revés, porque el firewall de
Windows descarta el UDP entrante y abrirlo pide permisos de administrador.

---

## Acceso SSH al radio

```
ssh -i "<scratchpad>\radio_key2" -p 2222 -o BatchMode=yes user@192.168.2.57
```

Llave **sin passphrase**. Pública instalada:
`ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ7hM7QJe0eYv28jsYu1uhTxEZHDUvP8Ml9uVHUHe4JZ`

⚠️ **SimpleSSHD no arranca solo tras reiniciar el carro** salvo que esté activo
"Start on Boot" en sus ajustes.

### Lo que el shell del radio NO puede hacer (probado, no supuesto)

| Comando | Error |
|---|---|
| `pm install` | Llega a `install-create` pero muere en `install-write`: *Reverse mode only supported from shell or system* |
| `am start` / `am broadcast` | *package=com.android.shell does not belong to uid* |
| `monkey` | Bloqueado en silencio |
| `content query` | Requiere `ACCESS_CONTENT_PROVIDERS_EXTERNALLY` |
| `screencap`, `dumpsys`, `settings` | Sin permiso |

Sí funcionan: `scp`, `pm list/path`, `stat`, `logcat` (solo del propio UID).

---

## Arquitectura

**Dos APK, misma firma** (SHA-256 `97cdb6b0…`) — de eso depende el permiso de
nivel `signature` entre ellos.

1. **`com.nonosky.s2000dash`** — el tablero. Se auto-actualiza.
2. **`com.nonosky.s2000dash.confirmador`** — APK aparte con el
   `AccessibilityService` que pulsa "Instalar" y rellena el PIN.

### Por qué el confirmador va aparte

**Android desactiva el servicio de accesibilidad de una app en cuanto esa app se
actualiza.** Es una protección deliberada. Se verificó en vivo: el proceso seguía
corriendo y respondiendo, y aun así dejó de confirmar. Con el confirmador dentro
del tablero, la cadena de auto-actualización servía **exactamente una vez**.

El tablero puede actualizar al confirmador con `/instalar-companero`: el
confirmador viejo auto-confirma la instalación del nuevo.

### Componentes

```
obd/      PidDecoder, Elm327Session, PollScheduler, SppTransport, ObdTransport
ui/       DashView (Canvas 60 fps, 3 columnas)
bt/       ObdPairing
selfupdate/ UpdateChecker, AutoInstaller, ApkVerifier, ServerDiscovery,
            InstallStatusReceiver, UpdateState
debug/    DebugServer
          DashService (foreground, START_STICKY), BootReceiver, EstadoActual
```

---

## Toolchain

| Pieza | Dónde |
|---|---|
| JDK 17 Temurin | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` |
| Android SDK | `C:\Users\Usuario\Android\Sdk` (platform 34, build-tools 34.0.0) |
| Gradle 8.7 | `C:\Users\Usuario\tools\gradle-8.7` (sin wrapper) |

`local.properties` usa **barras normales** (`C:/Users/...`): con backslashes Java
se come los escapes y Gradle falla con "sintaxis de la etiqueta del volumen".

---

## Defectos graves encontrados por auditoría adversarial

Se corrieron **3 workflows** de auditoría (39–52 agentes cada uno, ~140 hallazgos,
20 confirmados tras verificación adversarial). Los que importan:

1. **CRÍTICO** — Todo el I/O de OBD corría en `lifecycleScope`, que despacha en el
   **hilo principal**. ANR garantizado al conectar el adaptador. Ahora
   `Dispatchers.IO`, con prueba de regresión.
2. **ALTO** — `PidDecoder` tomaba `BUS INIT` y `SEARCHING` por errores, pero son
   **banners que preceden a la trama buena**, y en ISO 9141-2 la primera petición
   de cada conexión **siempre** trae `BUS INIT`. `probeBus()` fallaba siempre y
   **no se habría leído un solo dato** con el carro andando. Ahora se parsea línea
   por línea.
3. **CRÍTICO (autoinfligido)** — El anuncio UDP sin autenticar + un confirmador que
   se fiaba del `android:label` = **ejecución de código arbitrario desde la Wi-Fi**.
   Cerrado con `ApkVerifier` (exige firma idéntica a la propia) y sesión armada en
   vez de texto de pantalla.
4. **ALTO** — Una excepción en el hilo de petición del `DebugServer` **mataba el
   proceso entero**: un simple escaneo de puertos dejaba sin tablero a mitad de
   camino.
5. **ALTO** — `ObdPairing` guardaba como adaptador OBD **cualquier** dispositivo
   emparejado con el radio (bastaba emparejar un teléfono).
6. **ALTO** — `startDash` se rendía **para siempre** si el Bluetooth estaba apagado
   al arrancar — el caso normal tras reiniciar el carro.
7. **Bucle de instalación** — Se revisaba actualización en cada `onStart`, y cerrar
   el diálogo devolvía el foco → otra instalación. El radio quedaba inservible.
8. **Reparto de PIDs** — No puede hacerse con módulos encadenados: "cada 3" y
   "cada 20" coinciden una de cada tres veces y el prioritario le roba el turno al
   otro. Tabla explícita de 60 ciclos (mcm de 3, 10 y 20).

---

## Aprendizajes operativos (caros de redescubrir)

- **Play Protect bloquea** apps de fuera de la tienda que declaran
  `AccessibilityService`. Hubo que apagarlo en el radio.
- Los **Ajustes de este radio no listan Accesibilidad**. Se llega por intent desde
  una actividad propia del confirmador (`AbrirAccesibilidadActivity`).
- El puente y el actualizador **no pueden vivir en la Activity**: mueren con ella
  y el radio queda incomunicado. Van en `DashService`.
- La IP de la laptop **cambia**. Por eso el descubrimiento va por UDP.
- Cachés de navegador sirven APKs viejos: **publicar con nombre único por versión**.

---

## Estado de pruebas

**54+ pruebas JVM en verde** entre los dos módulos. Cubren decodificación de PIDs
(incluida toda la basura del ELM327), el diálogo AT, el reparto del presupuesto de
K-line, los valores derivados del F20C y las reglas de seguridad del confirmador.

`DashView` no lleva pruebas automatizadas por decisión de diseño (§11): se valida a
ojo — y ya se validó con capturas reales por `/shot.png`.

---

## Hardware OBD (recordatorio)

- **Steren SCAN-010** — el que no conecta.
- **OBDLink MX+** — emparejado en la laptop (COM3). **Es la alternativa a probar.**
- Protocolo esperado del AP1: **ISO 9141-2 (K-line)**, ~9 lecturas/seg. Sin
  confirmar todavía: hace falta un enlace vivo para que `ATDP` lo diga.
