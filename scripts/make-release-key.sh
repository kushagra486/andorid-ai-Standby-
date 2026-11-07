#!/usr/bin/env bash
# Creates YOUR private release signing key (Stage 10/15). Run on your own computer, never in CI.
# Back up the .jks file and passwords safely — losing them prevents future in-place updates.
set -euo pipefail
OUT="${1:-nova-release.jks}"
read -rsp "Choose a keystore password: " PASS; echo
keytool -genkeypair -v -keystore "$OUT" -alias nova -keyalg RSA -keysize 3072 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=NOVA Owner"
echo
echo "To let GitHub build signed releases, add these repository secrets (Settings → Secrets → Actions):"
echo "  NOVA_KEYSTORE_BASE64 = $(command -v base64 >/dev/null && echo '<output of: base64 -w0 '"$OUT"'>')"
echo "  NOVA_KEYSTORE_PASSWORD = <the password you chose>"
