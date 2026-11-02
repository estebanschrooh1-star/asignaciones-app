#!/usr/bin/env bash
# Arma la versión web (PWA para iPhone/iPad) en la carpeta _site/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/_site"
ASSETS="$ROOT/app/src/main/assets"

rm -rf "$OUT"; mkdir -p "$OUT"
cp "$ASSETS/index.html" "$OUT/"          # la MISMA pantalla que usa la app de Android
cp "$ROOT"/pwa/* "$OUT/"

if [ -s "$ASSETS/pdfjs/pdf.min.js" ] && [ -s "$ASSETS/pdfjs/pdf.worker.min.js" ]; then
  mkdir -p "$OUT/pdfjs"; cp "$ASSETS"/pdfjs/pdf*.js "$OUT/pdfjs/"
else
  bash "$ROOT/scripts/get-pdfjs.sh" "$OUT/pdfjs"
fi

VN="$(grep -oE 'versionName = "[^"]+"' "$ROOT/app/build.gradle.kts" | cut -d'"' -f2)"
SHA="${GITHUB_SHA:-local}"
sed -i "s/__VERSION__/${VN}-${SHA:0:7}/" "$OUT/sw.js"
touch "$OUT/.nojekyll"
echo "PWA lista en $OUT (versión ${VN}-${SHA:0:7})"
