
# DriftDroid

A native Android port of [WiiCompiled](https://github.com/patchzyy/Wiicompiled), a static
recompilation of Mario Kart Wii to native code. This fork adds everything the game needs to be a
phone app: touch controls, motion steering, a mobile settings menu and a launcher. The Android
APK includes the translated game code but none of the game's files (courses, music, textures): those
always come from your own disc. **DriftDroid Builder**, a Windows and Linux program, makes the Switch
version, and an optional Android game pack with the newest Retro Rewind code, from your own disc.

There's no emulator in the loop, no interpreter, no JIT, no PowerPC anywhere at runtime - the game
runs as translated native ARM64 code, same as the original desktop project.

> [!IMPORTANT]
> There is no Nintendo code, no assets and no game data anywhere in this project or its releases.
> You need your own legally dumped copy of the PAL version of the game. Nothing in this repo, in
> any release APK, or in the build process contains or downloads Nintendo IP.

---

## Status

DriftDroid is playable end to end: install the APK and pick your own disc image on the phone for
the game's data. No PC is needed on Android. (A PC with DriftDroid Builder is needed for Switch, and
optional on Android for the newest Retro Rewind code.)

- **Races, menus, audio, saves and online play work**, with touch controls or a
  physical controller.
- **Both Original Mario Kart Wii and Retro Rewind** are supported, chosen from
  the launcher screen. Retro Rewind 6.13.4 is the version this release runs.
- **The game code is in the APK.** DriftDroid Builder can also translate and compile it on your PC
  from your own disc into a game pack (one zip) that the app imports and prefers: that is how a
  newer Retro Rewind is supported before a new DriftDroid release.
- **The game data comes from your disc, on the phone**: pick your disc image
  and DriftDroid verifies it's a clean PAL `RMCP01` disc and extracts it.

It is still an active, personal fork rather than a finished product. Expect
rough edges, and expect performance to vary a lot between devices - see
[Performance](#performance).

## What it does

**Native rendering via aurora.** The graphics layer is built on
[aurora](https://github.com/encounter/aurora), a source-level GameCube & Wii
compatibility layer, running on Vulkan through Google's Dawn.

**An OpenGL ES fallback.** Some phones ship a Vulkan driver too broken to start
the game at all. The launcher's **Settings > Graphics** can switch the renderer
to OpenGL ES for those devices. It is slower than Vulkan by nature - OpenGL only
lets one thread talk to the GPU, so shaders compile on the render thread and the
multithreaded graphics path is unavailable - so leave it on Automatic unless
Vulkan doesn't work for you.

**Touch controls, tuned for Mario Kart Wii specifically.** Drag-anywhere
steering joystick, GameCube-diamond-style face buttons, D-Pad (needed for bike
wheelies), and L/R for items and drift. In the layout editor (Settings >
Controls > Edit touch layout) you drag buttons to move them, pinch to resize,
and tap one to hide it, fade it or recolour it (including how solid the colour
is). Layouts can be saved, swapped, exported and imported; vertical and
horizontal mode each keep their own layout and their own saved list.
Double-tapping A can hold acceleration for you.

**Motion controls.** Settings > Controls > Motion controls, "Steer with":
- **Tilt**: tilt the phone like a wheel, with sensitivity, flip and "set
  straight ahead".
- **Wii Wheel**: the phone is handed to the game as a Wii Remote in a Wii Wheel,
  so the game's own wheel code steers and shaking the phone does real tricks and
  wheelies. The phone's own vibration is filtered out so it can't steer or trick.
- **Stick** with **Shake for tricks**: steer with the stick, shake for tricks.

In every mode, pushing the stick sideways takes over the steering. With a real
controller connected the phone's motion is paused and the controller plays like a
normal controller (its D-pad does tricks). Wii Wheel and shake changes need a
restart, which the menu does for you.

**Vertical mode (experimental).** Play upright like Mario Kart Tour: the race
fills the tall screen, the race HUD moves to the corners and the touch controls
get their own layout. Launcher > Settings > Graphics > Vertical mode.

**Save import and export.** Bring your profile over from a Wii NAND backup or
Dolphin (`rksys.dat`), or copy it out, from the launcher's **Settings > Save data**.

**A launcher built around the game.** Home, Mods, Rooms, Ranks, Profile and
Settings pages, upright with a tab bar on a phone and sideways with a navigation
rail on tablets and handhelds. **Settings > Launcher orientation** picks Automatic,
Portrait or Landscape. Long jobs (installing Retro Rewind, downloading a mod,
importing a game pack) show their progress in a task bar that follows you from
page to page.

**Mods from inside the app.** Browse GameBanana's Mario Kart Wii mods and install
one with a tap (`.zip`, `.7z` and `.rar`, including RAR5), or import an archive you
already have. Turn mods on and off and drag them into priority order: the higher
mod wins when two change the same file. Turned-on mods load when you press Play (a
game that's already running restarts so the change takes) and apply to both Mario
Kart Wii and Retro Rewind. For Retro Rewind they're also laid out in its own
`Patches` folder the way the Wheel Wizard PC tool does it, so mods built on its
loose archive overrides (files like `Name.race.szs`) work too.

**Rooms and Ranks.** Every public Retro WFC room, live: the track being raced,
who's in it with their Miis and VR, the room's average VR and whether it's open.
Ranks has the top 100 players by VR, and each player has a page with their rank and
a 7-day VR chart. Tap anyone to copy their friend code or add them as a friend.
Joining a room still happens inside the game.

**Profile and Mii editor.** Licenses from your saves (Mii, friend code, VR and BR,
races and wins, friends), and an editor for every part of every Mii where each shape
is picked from pictures of your own Mii wearing it. Make, duplicate and delete Miis,
and import or export single Miis (`.rcd`) or the whole Mii file. Every change is
checked, written with the correct checksum, and the previous file is kept next to it
as a `.bak`. The pictures come from Nintendo's online Mii renderer; **Settings > Mii
pictures** turns that off and draws simple offline ones instead.

**Picture settings.** Brightness, contrast and saturation in the Display
settings.

**Real controller support.** Controllers are fed to the game as a GameCube-style
pad. Mappings are positional (`south`, `east`, `west`, `north`) rather than
Xbox-labelled, so one config makes sense across Xbox, PlayStation, Nintendo and
generic controllers.

**Unlocked framerate with interpolation.** The original game is hard-locked to
60 fps. The runtime can generate interpolated frames in between for a smoother
feel on high-refresh displays.

> [!WARNING]
> Interpolation is experimental and will show artifacts in specific scenarios.

**A 30 FPS mode for phones that can't hold 60.** With **30 FPS races** turned
on, every race uses the game's own 30 FPS mode - the one it uses for three- and
four-player split-screen - so it plays at normal speed while drawing half as
many frames, instead of running in slow motion. It works in the original game
and in Retro Rewind, offline and online. Menus stay at 60, and Retro Rewind's
time trials and ghost races stay at 60 because the mod requires it there.

**It behaves like a phone app.** The game pauses when you background it instead
of fast-forwarding through everything it missed, it can drop its render
resolution when the device gets hot rather than sliding into a slideshow, and it
mutes its own music while you play something else - without stealing audio
focus from your music app.

**An in-game settings menu**, reachable from the gear button or the Back
button. It slides in from the side (in vertical mode it comes up from the bottom),
the race stays visible behind it, and every change is saved the moment you make
it. Pages Display, Controls and Sound:
- Internal resolution, display mode, and FPS counter
- Frame interpolation, copy filter, and shader-compilation behavior
- 30 FPS races, and simpler computer karts for lighter races
- Thermal resolution scaling and experimental multithreaded graphics
- Touch control visibility and double-tap auto-hold
- Controller assignment (pick a player and a controller from dropdowns) and full
  per-button mapping, with presets
- Vibration and its strength (the phone vibrates for a controller without a motor)
- Volume sliders and instant mute

Everything you change is saved to `Config.toml` on the device and restored on
the next launch.

## Performance

This is native ARM64 code, not emulation, but Mario Kart Wii still asks a lot of
a phone, and the honest summary is:

- A cold phone holds 60 fps on the devices this has been tested on. A hot one
  does not - sustained play throttles the SoC, and the port is CPU-bound, so
  lowering the resolution only helps so much.
- **Thermal resolution scaling** (on by default) trades pixels for frame rate
  automatically as the device heats up.
- **30 FPS races** (off by default) is the setting for a phone that can't hold
  60: the race stays at normal speed and the phone has half as many frames to
  draw.
- **Simpler computer karts** (off by default) gives computer players the
  lighter kart models the game uses in split-screen.
- The first few minutes after installing are the worst, because shaders are
  still compiling. It settles down. After an update the shaders are rebuilt in
  the background on a single low-priority thread, so the phone stays cool while
  it happens.
- The display is held at the game's frame rate, so a 120 Hz phone does not
  redraw twice per game frame.
- Release builds can be compiled with a recorded profile of real play
  (profile-guided optimization), which cuts the work per frame. See
  [`docs/android-performance.md`](docs/android-performance.md) for how to
  record one and how to measure a release build.
- Debug builds are much slower than release builds, so don't judge the port by
  one.

## Requirements

- **Android 10 (API 29) or newer**, on a 64-bit ARM (`arm64-v8a`) chip. There is no 32-bit or
  x86 build.
- A working Vulkan driver for the best experience. Devices without one can fall back to OpenGL ES
  from the launcher's **Settings > Graphics**.
- Enough free storage for the extracted game data (a few GB) plus headroom during the one-time
  extraction step.
- A clean, unmodified **PAL `RMCP01`** disc image of Mario Kart Wii (.iso or .wbfs), dumped by you.
  Only the clean PAL revision is supported; other regions or modified executables are rejected.
- **Optional, and needed for Switch:** a Windows 10/11 or Linux (x86-64) PC, once, to run DriftDroid Builder (about 10 GB of free space while it works for Android, 16 GB if you also build for Switch). There is no macOS version of the Builder yet.

> [!NOTE]
> Nobody here will tell you where to get the game. Dumping your own disc is on you, and links to
> game files won't be provided or tolerated.

## Installing

From this repo's [Releases](https://github.com/dorPXP/driftdroid/releases) page, get the APK (and
**DriftDroid Builder**, a zip of the same version, if you want the Switch version or a game pack).
The app isn't on the Play Store.

1. Install the APK on your phone.
2. Open it, tap **Install with ISO/WBFS** and pick your disc image. The first time, DriftDroid
   extracts the game's data on the phone.
3. For Retro Rewind, tap **Install** on its card.

To update, install the new APK over the old one - saves, the extracted game data and Retro Rewind
all stay.

**Optional: a game pack.** On your PC, unpack DriftDroid Builder, open `DriftDroidBuilder.exe`
(Linux: `DriftDroidBuilder`), choose your disc image, pick Android and press **Start**. It takes 30
minutes to a couple of hours and makes `output/DriftDroid-GamePack-<version>.zip`. Copy it to the
phone and open **Settings > Import game pack**: the app then uses that game code, for example a newer Retro
Rewind. A game pack fits one app version, so make a new one with the matching builder.

DriftDroid Builder also makes the **Switch** version (`DriftDroid.nro` and `RetroRewind.nro`); that
needs devkitPro's "Switch Development" tools installed on the PC.

**Installing on the Switch.** Copy the files to the SD card one by one; don't drag the whole `switch`
output folder over, or the files end up in the wrong place.

1. Put `DriftDroid.nro` directly in `/switch/`, not inside `WiiCompiled`.
2. Create a folder named exactly `WiiCompiled` inside `/switch/` and put your PAL disc image (.iso or
   .wbfs) in it. For Retro Rewind, put `RetroRewind.nro` in that same folder.
3. Start DriftDroid from its Home tile, or from hbmenu opened by holding R while launching a game.
   Don't start it from the Album: it gets too little memory there and can crash Atmosphere.

The settings menu opens by clicking both sticks together. More in [`docs/switch.md`](docs/switch.md).

> [!CAUTION]
> Only take builds from this repository's own Releases page. Don't install an APK someone shared
> through Discord or a random download site.

## Building from source

Owning the game is still required even if you compile everything yourself. You'll need:

- Android Studio / the Android SDK and NDK (this project targets `arm64-v8a`)
- CMake and Ninja
- A JDK compatible with the Gradle version in `android/`

Build the native runtime and the app:

```bash
# native runtime (produces libWiiCompiled.so / libGameCombined.so)
cmake --build <your-android-build-dir>

# copy the built .so files into android/app/src/main/jniLibs/arm64-v8a/, then:
cd android && ./gradlew assembleRelease
```

A build made this way carries the game code inside the APK. Release APKs do too, and also carry a
toolkit id (`assets/toolkit-id.txt`), so a game pack made by DriftDroid Builder with the same id takes
over the code in the APK. DriftDroid Builder is made from a finished build by `tools/pc-builder/extract_plan.py`
and `tools/pc-builder/make_toolkit.sh`; how it works is in [`docs/pc-builder.md`](docs/pc-builder.md).
Switch files, controls and log messages are in [`docs/switch.md`](docs/switch.md).

Build `assembleRelease` for anything you intend to measure. Debug builds enable Android's runtime
checks and are substantially slower.

Launch both the original game and Retro Rewind once before calling a build good - they use
different libraries. Profile-guided builds, measuring and the build traps that have bitten this
project are written up in [`docs/android-performance.md`](docs/android-performance.md).

This project also still carries the original desktop build (Windows, via the .NET translator and
Launcher under `translator/` and `Launcher/`) from upstream WiiCompiled - see
[`translator/README.md`](translator/README.md) if you're interested in that side instead.

## A note on related projects

WiiCompiled, KartPad, Wheel Wizard, Retro Rewind, and other related projects are developed
**independently** and each has its own contribution rules. What applies here does not
automatically apply there, and vice versa.

## Retro Rewind

[Retro Rewind](https://wiki.tockdom.com/wiki/Retro_Rewind) is a separate, optional community mod -
nothing in this repo or any release bundles it, the same way nothing here includes the base game.
The app's first screen lets you choose Original Mario Kart Wii or Retro Rewind; picking Retro
Rewind before it's installed walks you through getting it set up, three ways:

1. **Download it** - the app asks the mod's own official update service
   (`update.rwfc.net`, the same one Wheel Wizard and the Retro Rewind updater use) which release
   is newest, and fetches it straight from there: the newest full download, then any small
   updates released on top of it. Nothing is mirrored or re-hosted here, and the game code in
   every download is checked against this build before it's installed.
2. **Select the `.zip` file** you already downloaded - the app extracts it for you, no separate
   unzip step needed.
3. **Select a folder**, if you've already extracted it yourself. Use this one if you're bringing
   an install over from Dolphin with your own mods in it - your files are copied as they are.

Either way the pack lands in place (~2GB, so it takes a few minutes) and you're done: no manual
file management, no computer required. Downloads resume where they left off if your connection
drops, and the copy keeps running while you're in another app.

### Retro Rewind versions

Retro Rewind releases more often than this app does, so the app checks what's actually out there
rather than knowing about one hardcoded release, and **"Check for updates"** in the Retro Rewind
menu applies the mod's own small per-version update archives - a few megabytes instead of
re-downloading the whole pack.

There is one real limit worth understanding. Retro Rewind's *game logic* lives in
`Binaries/Code.pul`, and because DriftDroid compiles everything to native code ahead of time
rather than emulating a Wii, that file has to be compiled into the app itself - it can't be picked
up on your phone at runtime. So:

- A Retro Rewind update that changes **tracks, characters or other assets** works immediately, with
  no app update at all.
- A Retro Rewind update that changes **game code** needs new game code: either a new DriftDroid
  build, or a game pack made on your PC with DriftDroid Builder, which always takes the newest
  Retro Rewind. The app detects this before downloading anything large, tells you, and leaves your
  working install alone rather than replacing it with something that would crash.
- After DriftDroid itself has been updated for a newer Retro Rewind, an install still on the old
  one is brought up to date when you tap Retro Rewind, before the game starts.

Mods that replace Retro Rewind's own menu or race files are tied to the Retro Rewind version they
were made from. One made for 6.12.8 can crash on 6.13 (for example when opening Retro WFC) until
it is rebuilt.

This is also why importing a Retro Rewind copy from somewhere else can be refused: it's checked
against the code this build was compiled for. Character/track mods in an otherwise-supported
install are fine - those are assets, and they're preserved.

Both the Riivolution-style file-overlay system this depends on, and a network stack built
specifically to talk to the community Retro-WFC service, are shared with upstream WiiCompiled's
runtime and already wired up on Android.

## FAQ

**Is this an emulator?**
No. Everything is compiled to native code before you ever press play. At runtime there's nothing
emulating a Wii CPU or GPU.

**Do you provide the game?**
No. Don't ask. Nothing in this repo or any release contains Nintendo code or assets.

**Which game version works?**
Clean PAL `RMCP01`. Other regions and modified executables are rejected outright.

**Can I use the newest Retro Rewind version?**
If it only changed assets, yes - the app picks it up on its own. If it changed game code, it needs
a DriftDroid update first; the app will say so instead of installing something broken. See
[Retro Rewind versions](#retro-rewind-versions).

**How do I update DriftDroid?**
On Android, install the new APK over the old one. Saves, settings and Retro Rewind stay, and no
game pack is needed (it only matters for a newer Retro Rewind than the APK includes). On Switch,
make new `.nro` files with the matching DriftDroid Builder and replace the old ones; back up your
`switch/WiiCompiled` folder first, since it holds your saves.

**Is there gonna be a Switch version?**
Yes. DriftDroid for Nintendo Switch is built on your PC with DriftDroid Builder from your own disc
(see "Installing on the Switch" above). The old Beta 1 download was removed because it contained game
code. It runs the original game and Retro Rewind; Retro Rewind's online play still has issues.
There is no macOS version of the Builder yet.

**Is it done?**
No - this is an active, personal work-in-progress fork. It's playable start to finish, but
performance work, device compatibility and bug fixing are ongoing.

**It won't start / the screen stays black.**
Most often a Vulkan driver the device can't actually deliver on. Open the launcher, go to
**Settings > Graphics**, choose **OpenGL ES**, and try again. Report it either way - which renderer worked
is exactly the information that's useful.

**It runs at 60 fps and then gets slower.**
That's thermal throttling, not a bug in the build. See [Performance](#performance). Leaving
"Lower resolution when the phone gets hot" enabled keeps the frame rate steadier as the device
heats up.

**It never reaches 60, and everything looks like slow motion.**
Turn on **30 FPS races** in the settings menu. The race then runs at normal speed with half
the frames. It applies from the next race.

**Retro Rewind crashes when I open Retro WFC or a menu after updating.**
Most likely a mod that replaces Retro Rewind's own files and was made for an older Retro Rewind.
Turn your mods off on the **Mods** page and try again; if that fixes it, the mod needs a version made
for the Retro Rewind you have now.

**Characters or menu icons are stretched across the screen.**
A driver bug on Snapdragon 8 Elite class GPUs (Adreno 8xx). The fix turns itself on for those
GPUs; if you still see it, set **Fix stretched graphics (Snapdragon)** in the settings menu to
**Everything**, or try a different GPU driver (next question).

**Can I use a different GPU driver?**
On Snapdragon phones, yes: **Settings > Graphics** -> **GPU driver...** installs an AdrenoTools driver
package such as Turnip. DriftDroid does not ship drivers; where to get them and how to check which
one is active is in [`docs/android-gpu-compatibility.md`](docs/android-gpu-compatibility.md).

**I picked Vulkan but it says OpenGL ES.**
The game shows a notice at start when the renderer you chose could not start and another one was
used. Please report the device and GPU - that is exactly the list that needs fixing.

**Some characters or objects are invisible.**
A few Adreno GPUs miscompile one of the shaders. Enable **Fix missing characters (some Adreno
GPUs)** in the settings menu. It turns itself on for the Adreno 750 (Galaxy S24 class).

**The game crashed / stopped with an error.**
Open an issue with the crash template. If you're on a debug build you can pull the run log with:
```
adb shell run-as com.driftdroid.android cat files/WiiCompiled/android_runtime_attempt.log
```
(Release builds aren't debuggable, so `run-as` won't work on them.)

**How do I open the settings if I hid the gear button?**
Press the phone's Back button.

**Why doesn't my Switch Pro Controller rumble?**
Confirmed at the kernel level (`adb shell getevent -pl`): stock Android's Bluetooth HID gamepad
driver doesn't expose any force-feedback capability for this controller at all - Nintendo's
rumble motor uses its own proprietary HID protocol, not the generic one Android supports. This
isn't something the app can fix from user space; it would need a from-scratch low-level HID
output-report driver specifically for Nintendo controllers. Controllers with real Android-exposed
vibrator support (most Xbox/PlayStation-style pads) should rumble correctly.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Short version: changes to the Android app, touch controls,
launcher and phone-specific rendering belong here; changes to the recompiler itself or to game
behavior belong upstream in WiiCompiled. Anything touching input, rendering or audio needs a test
on a real device.

## AI usage
AI coding tools were used extensively during development of this fork, including for debugging,
implementation, and this README. Physics accuracy inherited from upstream WiiCompiled is proven
synced across Wii, Dolphin, and WiiCompiled via matching ghost ​replays (input-based, not
position-tracked).

## Credits

- **[patchzyy/WiiCompiled](https://github.com/patchzyy/Wiicompiled)** - the static recompilation
  project this Android port is built on top of.
- **[aurora](https://github.com/encounter/aurora)** by Luke Street and contributors - the GX
  rendering/windowing backend this project's whole graphics layer sits on. MIT licensed.
- **[Dawn](https://dawn.googlesource.com/dawn)** - Google's WebGPU implementation, powering
  aurora's Vulkan and OpenGL ES backends on Android.
- **[Dolphin Emulator](https://github.com/dolphin-emu/dolphin)** - an invaluable reference for Wii
  hardware behavior, plus the source of the free DSP coefficient ROM and the unmodified default
  WiiConnect24 bootstrap tree bundled with the runtime.
- **[Retro Rewind](https://wiki.tockdom.com/wiki/Retro_Rewind)** by ZPL and team - the mod
  distribution the underlying runtime supports.
- **[KartPad](https://github.com/chrissotraidis/kartpad)** by Chris Sotraidis - the iOS/Android
  sibling port this fork's settings sidebar layout, touch control styling, and motion (tilt)
  steering calibration curve were directly modeled on, and the source of several GX command
  processor fixes, the in-app updater, and the phone vibration fallback and vibration strength
  setting this fork carries.
- **[Wheel Wizard](https://github.com/TeamWheelWizard/WheelWizard)** by Team Wheel Wizard - the PC
  tool whose features the launcher brings to Android, and the reference for its Retro WFC and
  GameBanana API use, the Mii renderer encoding, the save-file and Mii-file layouts, and friend-code
  math.
- **Retro WFC**, whose public API powers Rooms and Ranks, and
  **[GameBanana](https://gamebanana.com/games/5896)** and the mod authors whose work the Mods page
  lists.
- **[libarchive](https://www.libarchive.org/)** (BSD) through
  **[libarchive-android](https://github.com/zhanghai/libarchive-android)** (Apache 2.0) - unpacking
  `.7z` and `.rar` mods.
- **Material Design icons** by Google (Apache 2.0) in the launcher.
- **App icon**: a 3D render made for this project in Blender. The kart is
  **"Kart" by [absence_art](https://sketchfab.com/absence_art)** on Sketchfab
  (<https://sketchfab.com/3d-models/kart-1eef98f88e804e3481d8b1bfb311ee06>), licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); it was repainted, posed and rendered
  with the Android robot and the effects. The Android robot is reproduced or modified from work
  created and shared by Google and used according to terms described in the
  [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
- Every fix taken from these projects, with its author and source, is listed in
  [`docs/ported-changes.md`](docs/ported-changes.md).
- Everyone in the static recompilation community.

Bundled third-party components and their licenses live in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## License

WiiCompiled is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License, version 3](LICENSE) as published by the Free Software Foundation.

WiiCompiled is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

Any mkwii distribution making use of WiiCompiled must be licensed under GPL v3.0.

Not affiliated with, endorsed by, or associated with Nintendo. Mario Kart Wii is a trademark of
Nintendo. No Nintendo intellectual property is contained in, distributed with, or obtainable
through this project.
