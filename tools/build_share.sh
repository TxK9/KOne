#!/bin/bash
# Client edition: no personal photographs or home presets, with Tailscale off by default.
# Its separate package (cc.txwd.kone) preserves a personal installation. Reuse the authorized
# signing identity; activation requires an invitation from the console (see ActivationScreen).
#
# Usage: [KONE_CONSOLE=https://console.example] tools/build_share.sh [outdir]   (default outdir: dist/)
# With KONE_CONSOLE the console's address is built in and the start page asks for the key alone;
# without it the start page asks for the address too.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/dist}"
VERSION="$(sed -n 's/^KONE_VERSION=//p' "$ROOT/android/kone-version.properties")"
mkdir -p "$OUT"
python3 -I "$ROOT/tools/gradle_signed.py" "$OUT/build-share.log" :app:assembleOtherRelease -PkoneEdition=share ${KONE_CONSOLE:+-PkoneConsole=$KONE_CONSOLE}
rc=$?
if [ $rc -eq 0 ]; then
  cp "$ROOT/android/app/build/outputs/apk/other/release/KOne-share-$VERSION-arm64-v8a.apk" "$OUT/"
  echo "built $OUT/KOne-share-$VERSION-arm64-v8a.apk"
else
  echo "build failed, see $OUT/build-share.log"
fi
exit $rc
