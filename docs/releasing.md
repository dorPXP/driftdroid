# Making a release

For maintainers. Run every long step with `nice -n 19 ionice -c 3` (a full-priority build stutters the
machine). Switch builds use `-j4`: `-j8` froze the PC once.

## 1. Build the natives

```
rm -f android-native-build/libGameCombined.so          # Retro Rewind loads this one, the base game libWiiCompiled.so
ninja -C android-native-build -j6 WiiCompiled GameCombined
ninja -C switch-build-ninja  -j4 RetroRewind WiiCompiled
```

After **any** runtime C++ or JNI change both Android libraries must be rebuilt, or Retro Rewind fails with an
`UnsatisfiedLinkError`. If the shader text changed (anything in `aurora-main/lib/gx/shader*.cpp`), regenerate
the shader caches shipped with the app (the APK's `initial_pipeline_cache.db`, the Switch RomFS `Cache/`).

## 2. Toolkit and Builder

```
python3 tools/pc-builder/extract_plan.py --platform android --repo "$PWD" --build-dir android-native-build \
  --out <toolkit>/android --toolchain-root <ndk>/toolchains/llvm/prebuilt/linux-x86_64 \
  --pgo <profile.profdata> libWiiCompiled.so libGameCombined.so
python3 tools/pc-builder/extract_plan.py --platform switch --repo "$PWD" --build-dir switch-build-ninja \
  --out <toolkit>/switch --toolchain-root /opt/devkitpro WiiCompiled.elf RetroRewind.elf
SWITCH_NRO_ASSETS=<nro-assets> bash tools/pc-builder/make_toolkit.sh win-x64   <dist-win>   <toolkit>/android <toolkit>/switch
SWITCH_NRO_ASSETS=<nro-assets> bash tools/pc-builder/make_toolkit.sh linux-x64 <dist-linux> <toolkit>/android <toolkit>/switch
```

`make_toolkit.sh` prints the **toolkit id**. It changes whenever any toolkit file changes (native code, the
plan, the Switch `nro/` assets). The id in the APK, in the Builder and in a pack must match. The Builder
program itself (UI, README, the default Switch icon) is *not* part of the id.

## 3. Test the Builder end to end

Run the Linux build on a real disc: `DriftDroidBuilder --android --switch <disc>` (see
[pc-builder.md](pc-builder.md)). It makes `output/DriftDroid-GamePack-<version>.zip` and `output/switch/*.nro`.
Check a WBFS too with `--check-disc`.

## 4. The APK

`tools/release/` has two scripts. Both put the toolkit id into `assets/toolkit-id.txt`, scrub the build
machine's home path from the helper libraries with a same-length replacement, and restore the development
libraries afterwards.

- `build-apk-with-code.sh VERSION toolkit.json LIB_DIR OUT.apk` is what 1.4.0 ships: the game libraries come
  from a Builder-made pack (`LIB_DIR` holds the two `.so` files from the pack zip), so the APK runs exactly
  the code the Builder produces. Players do not need the Builder.
- `build-apk-code-free.sh VERSION toolkit.json [OUT.apk]` builds an APK without game code, which asks for a
  Builder pack. Use it only if a release must not carry game code.

Scan the result before uploading: no `/home/<you>`, user name or machine name in any file of the APK or the
Builder zips, and no disc files (`*.iso *.wbfs *.szs *.dol *.rel *.pul`).

## 5. Files and checksums

Release assets: `DriftDroid-v<version>.apk`, `DriftDroid-Builder-<version>-windows.zip`,
`DriftDroid-Builder-<version>-linux.zip`, `SHA256SUMS`. Zip each distribution folder as `DriftDroidBuilder/`
(use `cp -al` into a temp folder so no `output/`, `work/`, `downloads/` or `builder-log.txt` ends up inside).

**The app's own update check depends on these names.** DriftDroid 1.3.x asks GitHub for the repository's
*latest* release every hour, shows "Update available: <version>" and installs the asset named exactly
`DriftDroid-v<version>.apk` after checking it against `SHA256SUMS`. So:

- whatever the latest release's APK is, an older install can be moved onto it with one tap: it must run for
  someone with no PC, or a player will be broken by an update they were offered;
- marking a release as a pre-release hides it from that check;
- the APK must keep the same signing certificate as the previous release (debug key), or Android refuses to
  install it over the old one.

## 6. Publish and verify

```
gh release create vX.Y.Z -R <owner>/<repo> --target <branch> --title "..." --notes-file notes.md \
  DriftDroid-vX.Y.Z.apk DriftDroid-Builder-X.Y.Z-windows.zip DriftDroid-Builder-X.Y.Z-linux.zip SHA256SUMS
```

Push the commit first so the tag includes the final code. Afterwards download `SHA256SUMS` and the APK from
the release and run `sha256sum -c`. To change a published release use `gh release upload --clobber` and
`gh release edit --notes-file`; a tag can be moved with the Git refs API if the source must match the binary.

## 7. Devices

- **Phone:** wipe the app (`adb uninstall` removes its data), install the APK, import a disc image (a WBFS is
  smaller to copy), install Retro Rewind from the launcher, start both games. A phone with little free space
  cannot hold the disc image, the extracted data and the Retro Rewind pack at once.
- **Switch:** copy the `.nro` files over DBI/MTP, start DriftDroid **from its Home tile** (see
  [switch.md](switch.md), "Starting DriftDroid"). Remote testing needs sys-autopilot on and Frame Tap and
  SaltyNX off; put the player's normal boot flags back afterwards.
