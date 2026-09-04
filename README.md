
# WiiCompiled Android

A native Android port of [WiiCompiled](https://github.com/patchzyy/Wiicompiled), a static
recompilation of Mario Kart Wii to native code. This fork adds the Android application shell:
touch controls, an in-app settings screen, and (in progress) an in-app way to turn your own game
disc into a playable install, with no PC step in between.

There's no emulator in the loop, no interpreter, no JIT, no PowerPC anywhere at runtime - the game
runs as translated native ARM64 code, same as the original desktop project.

> [!IMPORTANT]
> There is no Nintendo code, no assets and no game data anywhere in this project or its releases.
> You need your own legally dumped copy of the PAL version of the game. Nothing in this repo, in
> any release APK, or in the build process contains or downloads Nintendo IP.

---

## Status

This is a personal, in-progress fork, not an official release. Current state:

- **Runs and is playable** on Android (tested on a real arm64-v8a phone) with a physical
  controller, including races, menus, and audio.
- **Touch controls** are implemented: an on-screen joystick, face buttons, D-Pad, L/R, and
  Start, all backed by a virtual SDL gamepad so they go through the exact same input/mapping
  pipeline a real controller uses. Layout is drag-to-move, pinch-to-resize, and tap-to-hide via a
  long-press edit mode.
- **In-game settings** (resolution, frame interpolation, bloom, audio levels, controller mapping)
  are reachable from a gear-icon button, reusing the same settings overlay the desktop build has
  behind F10.
- **Getting your own game onto the device is not finished yet.** A first-run screen exists that
  lets you pick a disc image file and confirms it's really a PAL `RMCP01` Mario Kart Wii disc, but
  it does not yet extract the game data out of that file - that's the next piece of work. Until
  it lands, game data has to be staged onto the device manually (not something an end user should
  need to do).

The goal is the same experience the desktop version already gives Windows users: install the app,
point it at your own disc image once, and it builds a working game from that image on your own
device - nothing pre-built, nothing uploaded.

## What it does

**Native rendering via aurora.** The graphics layer is built on
[aurora](https://github.com/encounter/aurora), a source-level GameCube & Wii compatibility layer,
running through Vulkan on Android.

**Unlocked framerate with interpolation.** The original game is hard-locked to 60 fps. The
runtime can generate interpolated frames in between for a smoother feel on high-refresh displays.

> [!WARNING]
> Interpolation is experimental and will show artifacts in specific scenarios.

**Touch controls, tuned for Mario Kart Wii specifically.** Drag-anywhere steering joystick, GameCube-diamond-style
face buttons, D-Pad (needed for bike wheelies), and L/R for items/drift. Every control can be
repositioned, resized, or hidden without touching a config file.

**Real controller support.** Controllers are fed to the game as a GameCube-style pad. Mappings
are positional (`south`, `east`, `west`, `north`) rather than Xbox-labelled, so the same config
makes sense across Xbox, PlayStation, Nintendo, and generic controllers.

**An in-game settings screen**, reachable via a gear button:
- Internal resolution and display mode (defaults to borderless fullscreen)
- FPS counter and frame interpolation
- Controller assignment and full per-button mapping, with a Classic Controller Pro preset
- Volume sliders and instant mute

Everything you change is saved to `Config.toml` on the device and restored on the next launch.

## Requirements

- An Android device with a 64-bit ARM (`arm64-v8a`) chip. There is no 32-bit or x86 build.
- Enough free storage for the extracted game data (a few GB) plus headroom during the one-time
  build step.
- A clean, unmodified **PAL `RMCP01`** disc image of Mario Kart Wii, dumped by you. Only the clean
  PAL revision is supported; other regions or modified executables are rejected.

> [!NOTE]
> Nobody here will tell you where to get the game. Dumping your own disc is on you, and links to
> game files won't be provided or tolerated.

## Installing

Grab the latest APK from this repo's [Releases](https://github.com/dorPXP/wiicompiled-android/releases)
page (once one exists) and sideload it - it isn't on the Play Store. On first launch, the app
will ask you to pick your Mario Kart Wii disc image; once the in-app extraction step is finished,
picking a valid file there is the only setup you'll need to do.

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
cmake --build <your-android-build-dir> --target WiiCompiled
cd android && ./gradlew assembleDebug
```

This project also still carries the original desktop build (Windows, via the .NET translator and
Launcher under `translator/` and `Launcher/`) from upstream WiiCompiled - see
[`translator/README.md`](translator/README.md) if you're interested in that side instead.

## A note on related projects

WiiCompiled, Wheel Wizard, Retro Rewind, and other related projects are developed
**independently** and each has its own contribution rules. What applies here does not
automatically apply there, and vice versa.

## Retro Rewind

The underlying runtime already carries real, working support for
[Retro Rewind](https://wiki.tockdom.com/wiki/Retro_Rewind) - a Riivolution-style file-overlay
system and a network stack built specifically to talk to the community Retro-WFC service - since
this fork shares that runtime with upstream WiiCompiled. None of that is wired up to an Android
import flow yet; it's a natural next step once basic disc import is finished.

## FAQ

**Is this an emulator?**
No. Everything is compiled to native code before you ever press play. At runtime there's nothing
emulating a Wii CPU or GPU.

**Do you provide the game?**
No. Don't ask. Nothing in this repo or any release contains Nintendo code or assets.

**Which game version works?**
Clean PAL `RMCP01`. Other regions and modified executables are rejected outright.

**Is it done?**
No - this is an active, personal work-in-progress fork. Touch controls, settings, and gameplay
itself work on a real device; getting your own disc image turned into a playable install
end-to-end, without any manual file staging, is the current focus.

**The game crashed / stopped with an error.**
Pull the run log from the device with:
```
adb shell run-as com.wiicompiled.android cat files/WiiCompiled/android_runtime_attempt.log
```

## AI usage
AI coding tools were used extensively during development of this fork, including for debugging,
implementation, and this README. Physics accuracy inherited from upstream WiiCompiled is proven
synced across Wii, Dolphin, and WiiCompiled via matching ghost ​replays (input-based, not
position-tracked).

## Credits

- **[patchzyy/WiiCompiled](https://github.com/patchzyy/Wiicompiled)** - the static recompilation
  project this Android port is built on top of.
- **[aurora](https://github.com/encounter/aurora)** - the GX rendering/windowing backend this
  project's whole graphics layer sits on. MIT licensed.
- **[Dawn](https://dawn.googlesource.com/dawn)** - Google's WebGPU implementation, powering
  aurora's Vulkan backend on Android.
- **[Dolphin Emulator](https://github.com/dolphin-emu/dolphin)** - an invaluable reference for Wii
  hardware behavior, plus the source of the free DSP coefficient ROM and the unmodified default
  WiiConnect24 bootstrap tree bundled with the runtime.
- **[Retro Rewind](https://wiki.tockdom.com/wiki/Retro_Rewind)** by ZPL and team - the mod
  distribution the underlying runtime supports.
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
