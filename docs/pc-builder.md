# PC builder: how it works

DriftDroid Builder (`tools/pc-builder`) runs on a Windows or Linux PC: it reads the player's own PAL disc,
translates the game code and compiles it. It makes the **Switch** files (`DriftDroid.nro`,
`RetroRewind.nro`, which are never shipped) and, optionally, an Android **game pack** that carries the newest
Retro Rewind code. The Android APK of 1.4.0 already includes translated game code (see "The app side"), so
Android players do not need the Builder. The player-facing instructions are in
`tools/pc-builder/README.txt`; this page is for people maintaining it.

## Pieces

| File | Role |
|---|---|
| `extract_plan.py` | Turns a finished build folder into one platform's toolkit: everything not derived from the game (runtime, aurora, Dawn, SDL...) as prebuilt objects and archives, everything derived from the game as compile groups with the exact compiler arguments, plus the archive/link steps (`plan.json`). |
| `make_toolkit.sh` | Assembles the download: translator (self-contained publish), the repository files the translator and the game code need, the platform toolkits, the Android PGO profile, the builder program and README. Scrubs the build machine's home path. `RID` is `win-x64` or `linux-x64`. |
| `DriftDroidBuilder/` | The program players run (C#, .NET 8, window made with Avalonia). |

Placeholders in `plan.json`: `{REPO}` (the toolkit's repository copy), `{WORK}` (build folder on
the player's PC), `{TC}` (compiler root), `{PGO}` (profile folder), `{SRC}`/`{OBJ}` per file,
`@group:<id>` (every object of a compile group). Groups under `generated/build_shards/` compile
whatever files the translator wrote into that folder, so a different Retro Rewind release (with
different shard names) still builds.

## The window

Started without options (or with a disc image dropped on its icon) the builder opens a window
(`Gui/`), drawn to look like the Android and Switch launchers: the drifting checkerboard, the
logo, rounded grey panels, one blue button for the next step. Layout is in the Switch launcher's
1280x720 units, scaled to the window; colours are `switch_launcher.cpp`'s. Three screens: choose
the disc (checked straight away against the PAL hashes) and Android/Switch (Switch explains how to
get devkitPro when it is missing), a checklist of the build's stages with a progress bar and a
"Show details" log, and the result with what to copy where (or the error and the log).

- `Builder.cs` is the build itself, shared by the window and the terminal mode. It reports through
  `Log` (`Step(Stage, ...)`, `Progress(text, fraction)`, `Info`), which the window listens to.
- `Cancel` stops a build: running compilers and translator steps are killed.
- Any `--` option, or no desktop on Linux, runs the terminal mode instead (`--android`,
  `--switch`, `--console` to be asked in the terminal). The program is a window program on Windows
  (no console window); the terminal modes attach to the console they were started from.
- Under Wine the window draws on the CPU (`RunningUnderWine` in `Program.cs`).
- `DRIFTDROID_GUI_SELFTEST=<file.png>` opens the window, saves a picture of it drawn by the
  program itself and closes: a way to check the window starts (also under Wine) without taking a
  screenshot. Its picture can miss a line or two near the bottom edge; the window itself does not.

## What a run does

1. Reads `main.dol` and `rel/StaticR.rel` from the disc image (`.iso`, `.wbfs`, split `.wbf1`) and
   checks their SHA-256 against the PAL release (`Translation.cs`).
2. Fetches Retro Rewind's `Code.pul` (`RetroRewindSource.cs`): reads
   `update.rwfc.net/RetroRewind/RetroRewindVersion.txt`, walks back from the newest release to the
   newest update archive that contains `RetroRewind6/Binaries/Code.pul`, and downloads only that
   entry with HTTP range requests (zip directory, then the entry; CRC checked; zip64 handled).
   Cached per release in `downloads/`. `--retro-rewind <version>` builds for an older release.
3. Runs the five translator steps twice (the base translation depends on what the Retro Rewind
   step wrote on the previous pass; only the second pass matches a repository build).
4. Compiles the groups in parallel (jobs limited by RAM: about 2.5 GB per big file), replays the
   archive and link steps.
5. Android: strips the two libraries into `output/DriftDroid-GamePack-<version>.zip` with
   `driftdroid-pack.json` (`toolkit`, `version`, `retroRewind`, `retroRewindCodeSha256`).
   Switch: packs `DriftDroid.nro` and `RetroRewind.nro` with the release icon, title data and
   RomFS.

## Compilers

- **Android**: NDK r27c, the compiler the app is built with. Downloaded from Google on first use
  (Windows and Linux; SHA-256 pinned in `Toolchains.cs`, matching Google's published SHA-1), and
  only `toolchains/llvm/prebuilt/` is unpacked. The Linux archive has symbolic links (`clang++`)
  and executable bits, which the unpacker restores. `DRIFTDROID_NDK` points at an installed NDK
  instead.
- **Switch**: devkitPro's devkitA64 + libnx, from its own installer (devkitPro asks that its tools
  are not redistributed or auto-downloaded). `DEVKITPRO` or the default install path.

## The Switch icon

With Switch ticked the window shows a **Switch icon** button (`SwitchIcon.cs`, `ShowSwitchIconDialog` in
`Gui/MainWindow.cs`). The player picks any PNG/JPG/WebP/BMP; it is cropped to the centre square, scaled to
256x256 and saved as a JPEG in `%APPDATA%/DriftDroidBuilder/switch-icon.jpg` (Linux:
`~/.config/DriftDroidBuilder/`). `SwitchPackaging` passes it to `elf2nro --icon` for both `.nro` files, and the
image never leaves the computer. Without a custom icon the builder uses `Assets/switch-icon.jpg`, which is
**embedded in the builder program, not in the toolkit**, so changing it does not change the toolkit id. Terminal:
`--switch-icon <picture>` (saved for later builds) and `--default-switch-icon`. The footer shrinks its buttons
when this fourth button is shown so the note beside them does not overlap.

## Disk space

`Builder.NeededGb` is the figure the window and the README use: 8 GB per Android build, 6 GB for Switch, plus 2
(so about 10 GB for Android and 16 GB for both). Measured leftovers after a finished Android build: about
1.3 GB `work`, 2.2 GB of downloads (the compiler), 330 MB toolkit. The peak while compiling was not measured.

## The app side

The release APK carries both game libraries (`libWiiCompiled.so`, `libGameCombined.so`) **and**
`assets/toolkit-id.txt` (a hash of everything the builder links against):

- `GamePack.hasBundledCode()` looks for `lib/arm64-v8a/libWiiCompiled.so` inside the APK. With bundled code the
  game starts without a pack.
- A pack whose `toolkit` matches the id is imported and then **takes over** the bundled code
  (`GamePack.state() == Ready` makes `MainActivity` load the pack's libraries), for example for a newer Retro
  Rewind. A pack for another id is refused, so a pack always fits the app's own native glue.
- Without bundled code (a code-free build) the launcher asks for a pack instead.

The libraries inside the release APK are the ones a Builder pack contains (profile-optimised, stripped, build
paths already neutral), so the APK and a Builder pack run the same code. `tools/release/` has the scripts that
assemble the APK either way (see [releasing.md](releasing.md)).

## Testing

- Linux end to end: unpack the `linux-x64` build, then
  `DRIFTDROID_NDK=<ndk> ./DriftDroidBuilder <disc> --android` (add `--switch` with devkitPro
  installed). `DRIFTDROID_SKIP_SPACE_CHECK=1` and `DRIFTDROID_JOBS=<n>` help on small machines.
- Windows: run the `win-x64` folder under Wine. Single-file publishing does not start under Wine,
  so the Windows build ships as a folder. In terminal mode (`--android <disc>`) Wine builds the Android pack
  end to end; the Switch half needs a Windows devkitPro install, which Wine cannot provide, so check the Switch
  step with the Linux build.
- Looking at the window from a script: capture only its window (`import -window <id>` on X11), never the whole
  desktop. Scripted clicks reached the tiles but not the footer buttons (Help, Language, Switch icon), so open
  those by hand when checking a dialog.
- `--check-disc <image>` and `--check-retro-rewind [--retro-rewind <version>]` check the inputs
  without building.
- Screens in every state without running a build: a small program using `Avalonia.Headless` +
  `Avalonia.Skia` can create `MainWindow`, call its `Show...` methods by reflection and save
  `CaptureRenderedFrame()` for each.
- If Wine hangs on every program (even `--check-disc`), a Wine server is stuck: `wineserver -k`.
- A pack is right when its function symbols match a repository build of the same code.

## Known issues

- Switch builds made by the builder differ from `switch-build-ninja` in the sizes of about 22,100
  functions. The flags differ only in `-g`, the precompiled header (CMake's `cmake_pch.hxx` versus
  `-include mkw_pch.h`) and `-MD`; which one causes it is not known yet.

## Language

The builder's window is available in 14 languages. It follows the system language (`LANG`); the **Language** button
at the bottom of the window picks one. See [languages.md](languages.md).
