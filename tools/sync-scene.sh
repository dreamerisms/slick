#!/usr/bin/env bash
# Wraps the scene files (no <html>/<head>) into full pages for the app.
# web/slick.html becomes assets/index.html (the app's start page); other scenes keep their name.
# Usage: tools/sync-scene.sh            (syncs every web/*.html)
#        tools/sync-scene.sh web/x.html (syncs one)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
files=("$@"); [ ${#files[@]} -eq 0 ] && files=("$here"/web/*.html)
for src in "${files[@]}"; do
  name="$(basename "$src")"; [ "$name" = "slick.html" ] && name="index.html"
  out="$here/app/src/main/assets/$name"
  { printf '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><style>html,body{margin:0}[hidden]{display:none!important}</style></head><body>'
    cat "$src"; printf '</body></html>\n'; } > "$out"
  echo "wrote $out"
done
