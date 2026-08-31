# ============================================================
#  Pruebas de extremo a extremo, en el emulador.
#
#  Compila los dos sabores, los instala, abre SUS DOS VARIANTES de
#  tablero —la de HTML y la de Canvas—, entra en cada pantalla y
#  comprueba que llego donde tenia que llegar. Deja las capturas para
#  poder MIRARLAS: una prueba de UI que solo dice "paso" y no ensena
#  nada no vale de mucho.
#
#  Uso:   powershell -ExecutionPolicy Bypass -File tools\e2e.ps1
#         ...\e2e.ps1 -rapido     (no recompila si el APK ya existe)
# ============================================================
$ErrorActionPreference = 'Continue'

$SDK    = "$env:USERPROFILE\Android\Sdk"
$ADB    = "$SDK\platform-tools\adb.exe"
$EMU    = "$SDK\emulator\emulator.exe"
$GRADLE = "$env:USERPROFILE\tools\gradle-8.7\bin\gradle.bat"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
$RAIZ   = Split-Path -Parent $PSScriptRoot
$SALIDA = Join-Path $RAIZ "build\e2e"
New-Item -ItemType Directory -Force -Path $SALIDA | Out-Null

$fallos = 0
function Comprobar($que, $ok, $detalle) {
  if ($ok) { Write-Host ("  OK    " + $que) -ForegroundColor Green }
  else {
    Write-Host ("  FALLO " + $que) -ForegroundColor Red
    if ($detalle) { Write-Host ("        " + $detalle) -ForegroundColor DarkGray }
    $script:fallos++
  }
}

# Vuelca la interfaz a fichero y devuelve el texto.
# A fichero y NO a /dev/tty: volcar la UI a la terminal se cuelga.
function VolcarUI($destino) {
  & $ADB shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
  & $ADB pull /sdcard/ui.xml $destino 2>&1 | Out-Null
  if (Test-Path $destino) { return (Get-Content $destino -Raw) }
  return ""
}

# ---------------------------------------------------------- emulador
Write-Host "`n[1] Emulador" -ForegroundColor Cyan
$hay = (& $ADB devices | Select-String "emulator-").Count -gt 0
if (-not $hay) {
  Write-Host "      arrancando elementradio sin ventana..."
  # -no-metrics es OBLIGATORIO: si el emulador se cayo alguna vez, al
  # arrancar saca un dialogo de consentimiento que BLOQUEA el arranque
  # esperando un clic que en una prueba automatica no llega nunca.
  Start-Process -FilePath $EMU -ArgumentList @(
    "-avd","elementradio","-no-window","-no-audio","-no-boot-anim",
    "-no-snapshot","-no-metrics","-gpu","swiftshader_indirect","-memory","3072"
  ) -WindowStyle Hidden
  & $ADB wait-for-device
}
$listo = $false
foreach ($i in 1..30) {
  $b = (& $ADB shell getprop sys.boot_completed 2>$null) -replace "\s",""
  if ($b -eq "1") { $listo = $true; break }
  Start-Sleep -Seconds 5
}
Comprobar "el emulador arranco" $listo "sys.boot_completed nunca llego a 1"
if (-not $listo) { exit 1 }

$res = (& $ADB shell wm size) -replace "\s",""
Write-Host ("      " + $res)

# Root para poder LEER las preferencias y comprobar el efecto real de la
# calibracion, no solo que se dibujo el modal. Sin esto la prueba diria
# "salio bonito" de algo que podria no estar guardando nada.
& $ADB root 2>&1 | Out-Null
Start-Sleep -Seconds 3

# ---------------------------------------------------------- sabores
#
# Las coordenadas van a mano y por sabor Y POR VARIANTE porque un WebView no
# aparece en el volcado de uiautomator: no hay forma de buscar sus botones
# por nombre. Las del Canvas si se podrian calcular, pero se dejan escritas
# igual para que las dos variantes se comprueben con el mismo rasero.
$sabores = @(
  @{ clave="element"; tarea="assembleElementRelease"; pkg="com.nonosky.inmyelement"
     apk="app\build\outputs\apk\element\release\app-element-release.apk"
     carro="Honda Element"; ajustesX=458; ajustesY=389
     variantes=@(
       @{ n="html";   rot="HTML sobre WebView"; llantaX=776; llantaY=160; masX=672; masY=272 },
       @{ n="lienzo"; rot="Canvas nativo";      llantaX=776; llantaY=165; masX=685; masY=290 }
     ) },
  @{ clave="s2000";   tarea="assembleS2000Release";   pkg="com.nonosky.s2000dash"
     apk="app\build\outputs\apk\s2000\release\app-s2000-release.apk"
     carro="Honda S2000"; ajustesX=962; ajustesY=37
     variantes=@(
       @{ n="html";   rot="HTML sobre WebView"; llantaX=770; llantaY=200; masX=672; masY=272 },
       @{ n="lienzo"; rot="Canvas nativo";      llantaX=776; llantaY=165; masX=685; masY=290 }
     ) }
)

# Deja el tablero en la variante pedida, por el mismo camino que el dueño:
# el menu de ajustes. La fila se busca POR SU TEXTO en el volcado —es una
# vista nativa, ahi si sale— en vez de por una coordenada que se pudra.
function LeerVariante($ui) {
  # El detalle de la fila dice "<la puesta>  ·  toca para usar <la otra>".
  # Se recorta: el separador viene con espacios a los dos lados y sin el
  # `Trim()` la comparacion falla SIEMPRE por un espacio suelto — y al fallar
  # toca la fila, con lo que la prueba conmuta la variante que venia a fijar.
  # Asi es como el sabor s2000 acabo probando dos veces el mismo tablero.
  if ($ui -match 'text="([^"]*)\s+toca para usar') {
    return ($Matches[1] -replace '\s*(&#183;|·)\s*$', '').Trim()
  }
  return ""
}

function PonerVariante($s, $v) {
  & $ADB shell am force-stop $($s.pkg) 2>$null
  # Con root se puede abrir aunque NO este exportada, que es como debe estar.
  & $ADB shell am start -n "$($s.pkg)/com.nonosky.s2000dash.config.ConfiguracionActivity" 2>&1 | Out-Null
  Start-Sleep -Seconds 4
  foreach ($intento in 1..3) {
    & $ADB shell input swipe 512 500 512 120 300 2>$null
    Start-Sleep -Milliseconds 700
  }
  $ui = VolcarUI "$SALIDA\ui-variante.xml"
  $puesta = LeerVariante $ui
  if ($puesta -ne $v.rot -and
      $ui -match 'text="Tablero"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $ADB shell input tap $x $y 2>$null
    Start-Sleep -Seconds 2
    $puesta = LeerVariante (VolcarUI "$SALIDA\ui-variante.xml")
  }

  # Se COMPRUEBA que quedo puesta. Sin esto, todo lo que viene despues
  # probaria un tablero que no es el que dice el rotulo, y lo haria en verde.
  Comprobar "[$($v.n)] deja puesta la variante" ($puesta -eq $v.rot) `
    ("la fila dice '" + $puesta + "' y se pedia '" + $v.rot + "'")

  & $ADB shell input keyevent KEYCODE_BACK 2>$null
  Start-Sleep -Seconds 2
  & $ADB shell am force-stop $($s.pkg) 2>$null
}

foreach ($s in $sabores) {
  Write-Host ("`n[2] " + $s.clave) -ForegroundColor Cyan

  $apk = Join-Path $RAIZ $s.apk
  # Con -rapido se salta la compilacion si el APK ya existe. Util cuando se
  # itera sobre la prueba y no sobre el codigo.
  if (-not ($args -contains "-rapido") -or -not (Test-Path $apk)) {
    & $GRADLE ":app:$($s.tarea)" --console=plain -q 2>&1 | Out-Null
  }
  Comprobar "compila" (Test-Path $apk)
  if (-not (Test-Path $apk)) { continue }

  $inst = & $ADB install -r $apk 2>&1 | Out-String
  Comprobar "instala" ($inst -match "Success") $inst
  & $ADB shell pm grant $s.pkg android.permission.ACCESS_FINE_LOCATION 2>$null

  $prefsCal = "/data/data/$($s.pkg)/shared_prefs/calibracion_llantas.xml"

  foreach ($v in $s.variantes) {
    Write-Host ("      variante: " + $v.rot) -ForegroundColor DarkCyan
    PonerVariante $s $v

    & $ADB shell monkey -p $s.pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
    Start-Sleep -Seconds 12
    $foco = (& $ADB shell "dumpsys window | grep mCurrentFocus") -join " "
    Comprobar "[$($v.n)] abre el tablero" ($foco -match "TableroActivity") $foco

    $cap = "$SALIDA\$($s.clave)-$($v.n)-tablero.png"
    & $ADB shell screencap -p /data/local/tmp/e2e.png 2>$null
    & $ADB pull /data/local/tmp/e2e.png $cap 2>&1 | Out-Null
    Comprobar "[$($v.n)] captura el tablero" (Test-Path $cap)

    # -------------------------------------------------- calibrar una llanta
    #
    # Se comprueba EL EFECTO, no la pantalla: que el dedo sostenido acabe
    # escribiendo en las preferencias. Una captura del modal solo probaria
    # que se dibujo bien; lo que importa es que la correccion se guarde,
    # porque es la que despues cambia el numero por el que suena la alarma
    # de presion baja.
    #
    # Y se comprueba lo contrario tambien: que un TOQUE SUELTO no abra nada.
    # En un carro que se mueve se toca la pantalla sin querer.
    & $ADB shell rm -f $prefsCal 2>$null

    & $ADB shell input tap $($v.llantaX) $($v.llantaY) 2>$null
    Start-Sleep -Seconds 2
    $tras = (& $ADB shell cat $prefsCal 2>&1) -join " "
    Comprobar "[$($v.n)] un toque suelto en la llanta NO calibra" `
      ($tras -notmatch "ajuste_") $tras

    # 900 ms: por encima de los 600 del sostenido, con margen.
    & $ADB shell input swipe $($v.llantaX) $($v.llantaY) $($v.llantaX) $($v.llantaY) 900 2>$null
    Start-Sleep -Seconds 2
    $modal = "$SALIDA\$($s.clave)-$($v.n)-calibrar.png"
    & $ADB shell screencap -p /data/local/tmp/cal.png 2>$null
    & $ADB pull /data/local/tmp/cal.png $modal 2>&1 | Out-Null

    & $ADB shell input tap $($v.masX) $($v.masY) 2>$null
    Start-Sleep -Seconds 2
    $xml = (& $ADB shell cat $prefsCal 2>&1) -join " "
    Comprobar "[$($v.n)] el dedo sostenido calibra la llanta" `
      ($xml -match 'ajuste_0" value="0\.5') $xml
    # La casilla viene marcada de fabrica, asi que un toque mueve las cuatro.
    Comprobar "[$($v.n)] y por omision mueve las cuatro" `
      ($xml -match 'ajuste_3" value="0\.5') $xml

    & $ADB shell input keyevent KEYCODE_BACK 2>$null
    Start-Sleep -Seconds 2

    # ------------------------------------------- el menu de emparejamiento
    #
    # Solo por la variante HTML: el boton del Canvas vive en la cabecera y
    # ya se toca al abrir la variante. Lo que hay que vigilar aqui es que el
    # tablero HTML TENGA la puerta — cuando el del S2000 se rediseño se
    # quedo sin boton de ajustes y el menu se volvio inalcanzable en ese
    # carro. Lo unico que lo delato fue este toque fallando.
    if ($v.n -eq "html") {
      & $ADB shell input tap $($s.ajustesX) $($s.ajustesY) 2>$null
      Start-Sleep -Seconds 5
      $foco2 = (& $ADB shell "dumpsys window | grep mCurrentFocus") -join " "
      Comprobar "abre la configuracion" ($foco2 -match "ConfiguracionActivity") `
        ("toque en $($s.ajustesX),$($s.ajustesY) -> " + $foco2)

      & $ADB shell screencap -p /data/local/tmp/e2e2.png 2>$null
      & $ADB pull /data/local/tmp/e2e2.png "$SALIDA\$($s.clave)-config.png" 2>&1 | Out-Null

      # Cada carro ofrece SOLO sus papeles: el S2000 no tiene banco de
      # vivienda ni nevera, y su menu no debe ofrecerlos.
      $texto = VolcarUI "$SALIDA\ui-$($s.clave).xml"
      if ($texto -match "Bater") {
        $tieneVivienda = $texto -match "vivienda"
        $tieneNevera   = $texto -match "efrigerador"
        if ($s.clave -eq "element") {
          Comprobar "ofrece bateria de vivienda" $tieneVivienda
          Comprobar "ofrece refrigeradora" $tieneNevera
        } else {
          Comprobar "NO ofrece bateria de vivienda" (-not $tieneVivienda)
          Comprobar "NO ofrece refrigeradora" (-not $tieneNevera)
        }
      } else {
        Write-Host "  --    no se pudo volcar la UI; los papeles se ven en la captura" -ForegroundColor DarkGray
      }
      & $ADB shell input keyevent KEYCODE_BACK 2>$null
      Start-Sleep -Seconds 2
    }

    & $ADB shell am force-stop $s.pkg 2>$null
  }

  # Se deja el carro como estaba: la variante de omision es la de HTML, y
  # una prueba que cambia los ajustes del aparato y no los devuelve es una
  # prueba que ensucia lo siguiente que se ejecute.
  & $ADB shell rm -f "/data/data/$($s.pkg)/shared_prefs/tablero.xml" 2>$null
  & $ADB shell rm -f $prefsCal 2>$null
}

Write-Host ""
if ($fallos -eq 0) {
  Write-Host "TODO EN VERDE. Capturas en build\e2e\" -ForegroundColor Green
  exit 0
} else {
  Write-Host ("$fallos COMPROBACIONES FALLARON. Mira build\e2e\") -ForegroundColor Red
  exit 1
}
