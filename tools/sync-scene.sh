#!/usr/bin/env bash
# Wraps the artifact-style scene file (no <html>/<head>) into a full page for the app.
# Usage: tools/sync-scene.sh path/to/slick.html
set -euo pipefail
src="${1:?path to slick.html}"
out="$(dirname "$0")/../app/src/main/assets/index.html"
{ printf '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><style>html,body{margin:0}[hidden]{display:none!important}</style></head><body>'
  cat "$src"; printf '</body></html>\n'; } > "$out"
echo "wrote $out"
