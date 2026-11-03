#!/bin/bash
# Builds the release APK without game code: the game libraries leave jniLibs for the duration of
# the build, the APK gets the builder toolkit's id, the remaining libraries get build paths
# scrubbed. Restores the development setup afterwards.
# usage: build-apk-code-free.sh VERSION TOOLKIT_JSON [OUT_APK]
set -euo pipefail
VERSION="$1"; TOOLKIT_JSON="$2"; OUT="${3:-DriftDroid-v$VERSION.apk}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JNI="$REPO/android/app/src/main/jniLibs/arm64-v8a"
ASIDE="${DRIFTDROID_ASIDE:-$HOME/.driftdroid-jnilibs-aside}"
ASSET="$REPO/android/app/src/main/assets/toolkit-id.txt"
ID="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['id'])" "$TOOLKIT_JSON")"

mkdir -p "$ASIDE"
restore() {
    for f in libWiiCompiled.so libGameCombined.so; do
        [ -f "$ASIDE/$f" ] && mv "$ASIDE/$f" "$JNI/$f"
    done
    for f in "$ASIDE"/orig-*; do [ -f "$f" ] && mv "$f" "$JNI/$(basename "$f" | sed 's/^orig-//')"; done
    rm -f "$ASSET"
}
trap restore EXIT

for f in libWiiCompiled.so libGameCombined.so; do
    [ -f "$JNI/$f" ] && mv "$JNI/$f" "$ASIDE/$f"
done
# Same-length path scrub on the libraries that stay in the APK.
for f in "$JNI"/*.so; do
    cp "$f" "$ASIDE/orig-$(basename "$f")"
    python3 - "$f" "$HOME" <<'PY'
import sys
path, home = sys.argv[1], sys.argv[2].encode() + b"/"
neutral = b"/home/" + b"builder"[: len(home) - 7].ljust(len(home) - 7, b"_") + b"/"
data = open(path, "rb").read()
open(path, "wb").write(data.replace(home, neutral))
PY
done
echo "$ID" > "$ASSET"
echo "[apk] toolkit id $ID"

cd "$REPO/android"
./gradlew --offline -q :app:assembleRelease
APK="$(ls -t app/build/outputs/apk/release/*.apk | head -1)"
cp "$APK" "$OUT"
unzip -l "$OUT" | grep -E "lib/|toolkit-id"
ls -la "$OUT"
