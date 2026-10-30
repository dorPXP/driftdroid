#!/bin/bash
# Assembles the DriftDroid Builder download: the builder program plus its toolkit (translator,
# the repository files the translator and the game code need, and per platform the plan and the
# prebuilt non-game parts written by extract_plan.py).
#
# usage: make_toolkit.sh RID OUT [PLATFORM_DIR...]
#   RID           win-x64 or linux-x64 (both are released)
#   OUT           folder to create (replaced if it exists)
#   PLATFORM_DIR  extract_plan.py outputs, e.g. /path/to/ddb-toolkit/android
#
# Nothing copied here is derived from the game: generated/ and build/mods/ are not shipped except
# for the frozen shard map (function address ranges) and Retro Rewind's own Retro WFC payload.
set -euo pipefail
RID="$1"; OUT="$2"; shift 2
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# SWITCH_NRO_ASSETS: folder with icon.jpg, app.nacp, app-retrorewind.nacp and romfs/ (Switch only).
NDK_BIN="${NDK_BIN:-$HOME/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin}"
VERSION="1.4.0"

rm -rf "$OUT"
TK="$OUT/toolkit"
mkdir -p "$TK/repo"

echo "[toolkit] repository files"
copy() { mkdir -p "$TK/repo/$(dirname "$1")"; cp -a "$REPO/$1" "$TK/repo/$1"; }
copy projects/mkwii/recomp.yml
copy projects/mkwii/MAP.txt
# The translator scans the runtime's sources for native overrides; the game code includes its headers.
mkdir -p "$TK/repo/runtime"
rsync -a --include='*/' --include='*.cpp' --include='*.h' --include='*.hpp' --include='*.inc' --exclude='*' \
    "$REPO/runtime/src/" "$TK/repo/runtime/src/"
rsync -a "$REPO/runtime/include/" "$TK/repo/runtime/include/"
copy runtime/third_party/sse2neon/sse2neon.h
copy runtime/tools/symbol_order.txt
copy runtime/tools/switch_section_order.ld
copy generated/build_shards/base_common_shard_map.json
copy build/mods/retro_rewind_full_cpp/retro_wfc_payload.bin
copy build/mods/retro_rewind_full_cpp/retro_wfc_payload.bin.source

echo "[toolkit] translator ($RID)"
dotnet publish "$REPO/translator/src/Translator.Cli/Translator.Cli.csproj" -c Release -r "$RID" --self-contained \
    -p:PublishSingleFile=false -p:DebugType=none -p:PathMap="$REPO=/src" -o "$TK/translator" -v quiet -nologo

for PLATFORM_DIR in "$@"; do
    NAME="$(basename "$PLATFORM_DIR")"
    echo "[toolkit] $NAME plan and prebuilt parts"
    cp -a "$PLATFORM_DIR" "$TK/$NAME"
    # Debug information only makes the download bigger; the game is never debugged from it.
    find "$TK/$NAME/prebuilt" \( -name '*.o' -o -name '*.a' \) -print0 | xargs -0 -n 64 "$NDK_BIN/llvm-strip" --strip-debug
    if [ "$NAME" = switch ]; then
        # Icon, title data and the RomFS files of the .nro.
        cp -a "${SWITCH_NRO_ASSETS:?set SWITCH_NRO_ASSETS for a Switch toolkit}" "$TK/$NAME/nro"
    fi
done

# Privacy: compilers bake this machine's source paths into strings (asserts, logs). Swap the home
# folder for a neutral one of the same length, as the Switch release packaging does.
python3 - "$TK" "$HOME" <<'PY'
import os, sys
root, home = sys.argv[1], sys.argv[2].encode() + b"/"
neutral = b"/home/" + b"builder"[: len(home) - 7].ljust(len(home) - 7, b"_") + b"/"
assert len(neutral) == len(home)
count = 0
for d, _, files in os.walk(root):
    for f in files:
        p = os.path.join(d, f)
        data = open(p, "rb").read()
        # With and without the trailing slash: debug info stores the directory and the file name apart
        # (libpng16.so had "/home/<user>" on its own).
        fixed = data.replace(home, neutral).replace(home[:-1], neutral[:-1])
        if fixed != data:
            count += data.count(home[:-1])
            open(p, "wb").write(fixed)
print(f"[toolkit] scrubbed {count} build paths")
PY

mkdir -p "$TK/pgo"
# The profile names static functions after their source path: rewrite it through the text form so
# the index is rebuilt for the scrubbed names (only translated, extern "C" functions matter here).
NEUTRAL_HOME="$(python3 -c "import sys; h=sys.argv[1]+'/'; print('/home/' + 'builder'[:len(h)-7].ljust(len(h)-7, '_') + '/')" "$HOME")"
"$NDK_BIN/llvm-profdata" merge --text "$HOME/android-pgo/android-2026-10-01.profdata" -o "$TK/pgo/profile.txt"
sed -i "s#$HOME/#$NEUTRAL_HOME#g" "$TK/pgo/profile.txt"
"$NDK_BIN/llvm-profdata" merge "$TK/pgo/profile.txt" -o "$TK/pgo/android-2026-10-01.profdata"
rm "$TK/pgo/profile.txt"

# The pack an Android app accepts must come from the same toolkit as the app's own JNI glue: the
# id is a hash of everything the builder links against.
PLATFORMS="$(cd "$TK" && for d in android switch; do [ -d "$d" ] && echo "$d"; done; true)"
ID="$(cd "$TK" && find $PLATFORMS -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -c1-16)"
printf '{"id": "%s", "version": "%s"}\n' "$ID" "$VERSION" > "$TK/toolkit.json"
echo "[toolkit] id $ID"

echo "[toolkit] builder ($RID)"
dotnet publish "$REPO/tools/pc-builder/DriftDroidBuilder/DriftDroidBuilder.csproj" -c Release -r "$RID" --self-contained \
    -p:PublishSingleFile=false -o "$OUT" -v quiet -nologo
rm -f "$OUT"/*.pdb
cp "$REPO/tools/pc-builder/README.txt" "$OUT/README.txt"
du -sh "$OUT"
