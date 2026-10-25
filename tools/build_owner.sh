#!/bin/bash
# Build the self-use edition and restore tracked placeholders on exit.
# Git's exclude file covers private image staging in normal checkouts and worktrees.
#
# Usage: tools/build_owner.sh [outdir]       (default outdir: dist/)
# Environment: KONE_PHOTOS must identify the external eight-image resource folder.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PHOTOS="${KONE_PHOTOS:?Set KONE_PHOTOS to the external image resource folder}"
OUT="${1:-$ROOT/dist}"
RES="$ROOT/android/app/src/main/res"
NAMES="owner_wallpaper_1 owner_wallpaper_2 owner_wallpaper_3 owner_wallpaper_4 owner_wallpaper_5 owner_wallpaper_6 portrait_backdrop sunset_backdrop"
EXCL="$(git -C "$ROOT" rev-parse --git-path info/exclude)" || exit 1
case "$EXCL" in /*) ;; *) EXCL="$ROOT/$EXCL" ;; esac
[ -f "$EXCL" ] || { echo "Git exclude file is unavailable"; exit 1; }
# An explicit template: macOS mktemp would otherwise ignore TMPDIR, which a sandbox relies on.
HOLD="$(mktemp -d "${TMPDIR:-/tmp}/kone-photos.XXXXXX")" || { echo "no temporary folder for the placeholders"; exit 1; }
VERSION="$(sed -n 's/^KONE_VERSION=//p' "$ROOT/android/kone-version.properties")"

restore() {
  for n in $NAMES; do
    [ -f "$HOLD/$n.xml" ] && mv "$HOLD/$n.xml" "$RES/drawable/$n.xml"
    rm -f "$RES/drawable-nodpi/$n".*
  done
  for n in $NAMES; do grep -v -x -F "/android/app/src/main/res/drawable-nodpi/$n.*" "$EXCL" > "$EXCL.tmp" && mv "$EXCL.tmp" "$EXCL"; done
  rmdir "$HOLD" 2>/dev/null
  echo "placeholders restored"
}
trap restore EXIT

for n in $NAMES; do
  ls "$PHOTOS/$n".* >/dev/null 2>&1 || { echo "missing photo $n in $PHOTOS"; exit 1; }
done
for n in $NAMES; do
  echo "/android/app/src/main/res/drawable-nodpi/$n.*" >> "$EXCL"
  mv "$RES/drawable/$n.xml" "$HOLD/$n.xml" || { echo "could not set aside the placeholder $n"; exit 1; }
  cp "$PHOTOS/$n".* "$RES/drawable-nodpi/" || { echo "could not copy the photo $n"; exit 1; }
done

mkdir -p "$OUT"
python3 -I "$ROOT/tools/gradle_signed.py" "$OUT/build-owner.log" :app:assembleOtherRelease
rc=$?
if [ $rc -eq 0 ]; then
  cp "$ROOT/android/app/build/outputs/apk/other/release/KOne-$VERSION-arm64-v8a.apk" "$OUT/"
  echo "built $OUT/KOne-$VERSION-arm64-v8a.apk"
else
  echo "build failed, see $OUT/build-owner.log"
fi
exit $rc
