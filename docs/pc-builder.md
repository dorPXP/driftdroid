# PC builder: how it works

From 1.4.0 the downloads (Android APK, Switch NROs) contain no Mario Kart Wii code. Players run
DriftDroid Builder (`tools/pc-builder`) on a Windows or Linux PC: it reads their own PAL disc,
translates the game code and compiles it. The player-facing instructions are in
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

## The app side

The release APK is built without `libWiiCompiled.so`/`libGameCombined.so` and with
`assets/toolkit-id.txt` (a hash of everything the builder links against). The app imports a pack
only when its `toolkit` matches, so a pack always fits the app's own native glue: a new app release
needs a new pack.

## Testing

- Linux end to end: unpack the `linux-x64` build, then
  `DRIFTDROID_NDK=<ndk> ./DriftDroidBuilder <disc> --android` (add `--switch` with devkitPro
  installed). `DRIFTDROID_SKIP_SPACE_CHECK=1` and `DRIFTDROID_JOBS=<n>` help on small machines.
- Windows: run the `win-x64` folder under Wine. Single-file publishing does not start under Wine,
  so the Windows build ships as a folder.
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
