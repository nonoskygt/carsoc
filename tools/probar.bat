@echo off
setlocal EnableDelayedExpansion
REM ============================================================
REM  Probar un tablero en el emulador, de un doble clic.
REM
REM  Uso:  probar.bat element     o     probar.bat s2000
REM
REM  Cada carro tiene SU emulador, con la resolucion de su radio:
REM    element  ->  1024x600  (MediaTek AC8257 del Honda Element)
REM    s2000    ->  1280x480  (rk3326 del Honda S2000)
REM
REM  Probarlos en la resolucion equivocada no vale: es exactamente
REM  asi como se descubrio que el tablero del S2000 se rompia.
REM ============================================================

set "CARRO=%~1"
if "%CARRO%"=="" set "CARRO=element"

if /I "%CARRO%"=="element" (
  set "AVD=elementradio"
  set "TAREA=assembleElementRelease"
  set "APK=app\build\outputs\apk\element\release\app-element-release.apk"
  set "PAQUETE=com.nonosky.inmyelement"
  set "TITULO=In my element"
) else (
  set "AVD=s2000radio"
  set "TAREA=assembleS2000Release"
  set "APK=app\build\outputs\apk\s2000\release\app-s2000-release.apk"
  set "PAQUETE=com.nonosky.s2000dash"
  set "TITULO=S2000 Dash"
)

title Probando %TITULO%
cd /d "%~dp0.."

set "SDK=%USERPROFILE%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "GRADLE=%USERPROFILE%\tools\gradle-8.7\bin\gradle.bat"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"

echo.
echo   === %TITULO% ===
echo.

echo   [1/4] Compilando...
call "%GRADLE%" :app:%TAREA% --console=plain -q
if errorlevel 1 (
  echo.
  echo   NO COMPILA. Nada que probar.
  pause
  exit /b 1
)

echo   [2/4] Buscando emulador %AVD%...
"%ADB%" devices | findstr /C:"emulator-" >nul
if errorlevel 1 (
  echo         arrancando ^(tarda un par de minutos en frio^)...
  REM -no-metrics es OBLIGATORIO. Si el emulador se cayo alguna vez, al
  REM arrancar saca un dialogo de consentimiento que BLOQUEA el arranque
  REM esperando un clic. Con la ventana minimizada ese dialogo NO SE VE:
  REM el proceso vive, qemu come memoria, y adb no lo encuentra jamas.
  REM Costo dos horas encontrarlo, en una linea perdida entre trazas de
  REM GPU: "Showing crashdialog to get consent". Con esto arranca en 40 s.
  start "" /min "%EMU%" -avd %AVD% -no-boot-anim -no-metrics -gpu swiftshader_indirect
) else (
  echo         ya hay uno corriendo.
)

echo   [3/4] Esperando a que termine de arrancar...
"%ADB%" wait-for-device
:esperar
for /f "delims=" %%b in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set "LISTO=%%b"
echo %LISTO% | findstr "1" >nul || (
  timeout /t 3 /nobreak >nul
  goto esperar
)

echo   [4/4] Instalando y abriendo...
"%ADB%" install -r "%APK%"
REM Sin ubicacion, el barrido BLE devuelve CERO en silencio, sin error.
"%ADB%" shell pm grant %PAQUETE% android.permission.ACCESS_FINE_LOCATION 2>nul
"%ADB%" shell monkey -p %PAQUETE% -c android.intent.category.LAUNCHER 1 >nul 2>&1

echo.
echo   Listo. %TITULO% corriendo en el emulador.
echo   ^(No habra datos: no hay baterias ni adaptador que leer.^)
echo.
timeout /t 6 /nobreak >nul
