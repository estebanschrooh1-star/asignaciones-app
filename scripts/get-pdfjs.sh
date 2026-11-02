#!/usr/bin/env bash
# Descarga pdf.js (visor de la planilla PDF) a la carpeta indicada. Uso: scripts/get-pdfjs.sh <carpeta>
set -euo pipefail
VER=3.11.174
DEST="$(mkdir -p "$1" && cd "$1" && pwd)"
if [ -s "$DEST/pdf.min.js" ] && [ -s "$DEST/pdf.worker.min.js" ]; then
  echo "pdf.js ya está en $DEST"; exit 0
fi
TMP="$(mktemp -d)"
( cd "$TMP" && npm pack "pdfjs-dist@$VER" --silent >/dev/null \
  && tar xzf "pdfjs-dist-$VER.tgz" package/build/pdf.min.js package/build/pdf.worker.min.js )
cp "$TMP/package/build/pdf.min.js" "$TMP/package/build/pdf.worker.min.js" "$DEST/"
test -s "$DEST/pdf.min.js" && test -s "$DEST/pdf.worker.min.js"
echo "pdf.js $VER copiado a $DEST"
