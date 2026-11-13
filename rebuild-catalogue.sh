#!/bin/bash
# Rebuilds the catalogue from the pages already saved in data/cache, without contacting any shop.
# The Mac app picks the new catalogue up next time it starts (its catalogue source points at the catalogue).
cd "$(dirname "$0")" || exit 1
# Kept outside Downloads so the app needs no folder permission to read it.
OUT="$HOME/Library/Application Support/Gear Finder/published"
./gradlew :collector:installDist -q || exit 1
collector/build/install/collector/bin/collector --merge-only --out "$OUT" --cache "$PWD/data/cache"
