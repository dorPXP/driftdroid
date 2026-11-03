#!/bin/bash
# Builds the release APK WITH the game code inside (the libraries a DriftDroid Builder pack contains), plus the
# toolkit id, so a matching Builder pack still takes over. The development libraries are put aside and restored.
# usage: build-apk-with-code.sh VERSION TOOLKIT_JSON LIB_DIR OUT_APK
set -euo pipefail
VERSION="$1"; TOOLKIT_JSON="$2"; LIBS="$3"; OUT="$4"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JNI="$REPO/android/app/src/main/jniLibs/arm64-v8a"
ASIDE="${DRIFTDROID_ASIDE:-$HOME/.driftdroid-jnilibs-aside}"
ASSET="$REPO/android/app/src/main/assets/toolkit-id.txt"
ID="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['id'])" "$TOOLKIT_JSON")"
mkdir -p "$ASIDE"
restore() {
    for f in libWiiCompiled.so libGameCombined.so; do
        [ -f "$ASIDE/dev-$f" ] && mv -f "$ASIDE/dev-$f" "$JNI/$f"
    done
    for f in "$ASIDE"/orig-*; do [ -f "$f" ] && mv -f "$f" "$JNI/$(basename "$f" | sed 's/^orig-//')"; done
    rm -f "$ASSET"
}
trap restore EXIT
for f in libWiiCompiled.so libGameCombined.so; do
    cp "$JNI/$f" "$ASIDE/dev-$f"
    cp "$LIBS/$f" "$JNI/$f"
done
for f in "$JNI"/*.so; do
    case "$(basename "$f")" in libWiiCompiled.so|libGameCombined.so) continue;; esac
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
echo "[apk] toolkit id $ID, game code from $LIBS"
cd "$REPO/android"
./gradlew --offline -q :app:assembleRelease
APK="$(ls -t app/build/outputs/apk/release/*.apk | head -1)"
cp "$APK" "$OUT"
unzip -l "$OUT" | grep -E "lib/|toolkit-id"
ls -la "$OUT"
