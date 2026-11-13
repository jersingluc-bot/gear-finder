#!/bin/bash
# Reads every shop from this Mac and rebuilds the catalogue. Takes hours the first time (Alpine Trek is large).
# Optional: ./crawl.sh --only cotswold,trekitt   or   ./crawl.sh --budget 5000
cd "$(dirname "$0")" || exit 1
# Kept outside Downloads so the app needs no folder permission to read it.
OUT="$HOME/Library/Application Support/Gear Finder/published"
./gradlew :collector:installDist -q || exit 1
collector/build/install/collector/bin/collector --out "$OUT" --cache "$PWD/data/cache" "$@"
