# ============================================================
#  Pruebas de extremo a extremo, en el emulador.
#
#  Compila los dos sabores, los instala, los abre, entra en cada
#  pantalla y comprueba que llego donde tenia que llegar. Deja las
#  capturas para poder MIRARLAS: una prueba de UI que solo dice
#  "paso" y no ensena nada no vale de mucho.
#
#  Uso:   powershell -ExecutionPolicy Bypass -File tools\e2e.ps1
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

# ---------------------------------------------------------- sabores
$sabores = @(
  @{ clave="element"; tarea="assembleElementRelease"; pkg="com.nonosky.inmyelement"
     apk="app\build\outputs\apk\element\release\app-element-release.apk"
     papeles=4; carro="Honda Element"; ajustesX=458; ajustesY=389 },
  @{ clave="s2000";   tarea="assembleS2000Release";   pkg="com.nonosky.s2000dash"
     apk="app\build\outputs\apk\s2000\release\app-s2000-release.apk"
     papeles=2; carro="Honda S2000"; ajustesX=962; ajustesY=37 }
)

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

  & $ADB shell monkey -p $s.pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
  Start-Sleep -Seconds 12
  $foco = (& $ADB shell "dumpsys window | grep mCurrentFocus") -join " "
  Comprobar "abre el tablero" ($foco -match "TableroActivity") $foco

  & $ADB shell screencap -p /data/local/tmp/e2e.png 2>$null
  & $ADB pull /data/local/tmp/e2e.png "$SALIDA\$($s.clave)-tablero.png" 2>&1 | Out-Null
  Comprobar "captura el tablero" (Test-Path "$SALIDA\$($s.clave)-tablero.png")

  # El menu NO esta exportado a proposito, asi que se llega tocando el
  # boton — que ademas es como lo usa el dueño. Y por eso esta comprobacion
  # vale la pena: cuando el tablero del S2000 se rediseño, se quedo SIN
  # boton de ajustes y el menu de emparejamiento se volvio inalcanzable en
  # ese carro. Lo unico que lo delato fue este toque fallando.
  #
  # Las coordenadas van por sabor porque los dos tableros ponen el boton en
  # sitios distintos: el del Element vive en el rotulo de la tarjeta de
  # motor y el del S2000 en la cabecera. Un WebView no aparece en el volcado
  # de uiautomator, asi que no hay forma de buscarlo por nombre.
  & $ADB shell input tap $($s.ajustesX) $($s.ajustesY)
  Start-Sleep -Seconds 5
  $foco2 = (& $ADB shell "dumpsys window | grep mCurrentFocus") -join " "
  Comprobar "abre la configuracion" ($foco2 -match "ConfiguracionActivity") `
    ("toque en $($s.ajustesX),$($s.ajustesY) -> " + $foco2)

  & $ADB shell screencap -p /data/local/tmp/e2e2.png 2>$null
  & $ADB pull /data/local/tmp/e2e2.png "$SALIDA\$($s.clave)-config.png" 2>&1 | Out-Null

  # Cada carro ofrece SOLO sus papeles: el S2000 no tiene banco de
  # vivienda ni nevera, y su menu no debe ofrecerlos.
  # A fichero y no a /dev/tty: volcar la UI a la terminal se cuelga.
  & $ADB shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
  & $ADB pull /sdcard/ui.xml "$SALIDA\ui-$($s.clave).xml" 2>&1 | Out-Null
  $texto = ""
  if (Test-Path "$SALIDA\ui-$($s.clave).xml") {
    $texto = Get-Content "$SALIDA\ui-$($s.clave).xml" -Raw
  }
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
    Write-Host "  --    no se pudo volcar la UI; los papeles se revisan en la captura" -ForegroundColor DarkGray
  }

  & $ADB shell input keyevent KEYCODE_BACK 2>$null
  Start-Sleep -Seconds 2
  & $ADB shell am force-stop $s.pkg 2>$null
}

Write-Host ""
if ($fallos -eq 0) {
  Write-Host "TODO EN VERDE. Capturas en build\e2e\" -ForegroundColor Green
  exit 0
} else {
  Write-Host ("$fallos COMPROBACIONES FALLARON. Mira build\e2e\") -ForegroundColor Red
  exit 1
}
