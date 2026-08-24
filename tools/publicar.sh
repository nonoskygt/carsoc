#!/usr/bin/env bash
# Publica el APK de release y el manifiesto que la app consulta.
# La IP se detecta sola: ya nos mordio una vez tenerla clavada.
set -euo pipefail

REPO="/c/Users/Usuario/s2000"
SERVE="/c/Users/Usuario/AppData/Local/Temp/claude/C--Users-Usuario-s2000/d22f7a3d-646d-4186-b49f-67e48d5f0a1d/scratchpad/serve"
PUERTO=8000

APK="$REPO/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "No hay APK: $APK"; exit 1; }

IP=$(ipconfig | grep -A6 -i "Ethernet:" | grep -i "IPv4" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+')
[ -n "$IP" ] || IP=$(ipconfig | grep -i "IPv4" | grep -oE '192\.168\.[0-9]+\.[0-9]+' | head -1)

CODE=$(grep -oE 'versionCode = [0-9]+' "$REPO/app/build.gradle.kts" | grep -oE '[0-9]+')
NAME=$(grep -oE 'versionName = "[^"]+"' "$REPO/app/build.gradle.kts" | cut -d'"' -f2)
SIZE=$(stat -c %s "$APK")

# Nombre unico por version: un navegador o un proxy cacheando el APK viejo
# ya nos hizo perder un despliegue.
FILE="dash-v${CODE}.apk"
cp "$APK" "$SERVE/$FILE"
cp "$APK" "$SERVE/dash.apk"

cat > "$SERVE/version.json" <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "file": "$FILE",
  "url": "http://$IP:$PUERTO/$FILE",
  "size": $SIZE
}
JSON

echo "Publicado v$NAME (code $CODE, $SIZE bytes) en $IP:$PUERTO"
echo "  http://$IP:$PUERTO/version.json"
echo "  http://$IP:$PUERTO/$FILE"
