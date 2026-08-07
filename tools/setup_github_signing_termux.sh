#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="${1:-piotreksmaga-art/rpg-os-android}"
SIGN_DIR="$HOME/rpgos-signing"
KEYSTORE="$SIGN_DIR/rpgos-release.jks"
BACKUP="$SIGN_DIR/SIGNING_BACKUP.txt"

command -v gh >/dev/null || { echo "Brak gh."; exit 1; }
command -v keytool >/dev/null || { echo "Brak keytool. Zainstaluj OpenJDK."; exit 1; }
command -v openssl >/dev/null || { echo "Brak openssl."; exit 1; }

mkdir -p "$SIGN_DIR"
chmod 700 "$SIGN_DIR"

if [ -e "$KEYSTORE" ]; then
  echo "Keystore już istnieje: $KEYSTORE"
  echo "Nie nadpisuję go, aby nie zepsuć przyszłych aktualizacji."
  exit 1
fi

STORE_PASS="$(openssl rand -hex 24)"
KEY_PASS="$(openssl rand -hex 24)"

keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -alias rpgos \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=RPG OS, OU=ALPHA, O=RPG OS, L=Local, ST=Local, C=PL"

chmod 600 "$KEYSTORE"

KEYSTORE_B64="$(base64 -w 0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')"

printf '%s' "$KEYSTORE_B64" | gh secret set RPGOS_KEYSTORE_B64 -R "$REPO"
printf '%s' "$STORE_PASS" | gh secret set RPGOS_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$KEY_PASS" | gh secret set RPGOS_KEY_PASSWORD -R "$REPO"

cat > "$BACKUP" <<EOF
RPG OS SIGNING BACKUP
Repository: $REPO
Keystore: $KEYSTORE
Alias: rpgos
Store password: $STORE_PASS
Key password: $KEY_PASS

UWAGA:
Ten keystore i hasła są potrzebne do WSZYSTKICH przyszłych aktualizacji.
Nie publikuj ich i zrób prywatną kopię zapasową.
EOF
chmod 600 "$BACKUP"

echo
echo "OK — stały podpis RPG OS został utworzony."
echo "GitHub Secrets ustawione dla: $REPO"
echo "Prywatny backup danych podpisu: $BACKUP"
echo "Keystore: $KEYSTORE"
