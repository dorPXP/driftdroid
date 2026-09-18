
# DriftDroid

A native Android port of [WiiCompiled](https://github.com/patchzyy/Wiicompiled), a static
recompilation of Mario Kart Wii to native code. This fork adds everything the game needs to be a
phone app: touch controls, motion steering, a mobile settings sidebar, a launcher, and an in-app
way to turn your own game disc into a playable install, with no PC step in between.

There's no emulator in the loop, no interpreter, no JIT, no PowerPC anywhere at runtime - the game
runs as translated native ARM64 code, same as the original desktop project.

> [!IMPORTANT]
> There is no Nintendo code, no assets and no game data anywhere in this project or its releases.
> You need your own legally dumped copy of the PAL version of the game. Nothing in this repo, in
> any release APK, or in the build process contains or downloads Nintendo IP.

---

## Status

DriftDroid is playable end to end: install the app, point it at your own disc
image once, and it builds a working game on the device. No PC step, no manual
file staging.

- **Races, menus, audio, saves and online play work**, with touch controls or a
  physical controller.
- **Both Original Mario Kart Wii and Retro Rewind** are supported, chosen from
  the launcher screen.
- **Getting the game onto the device is done in-app**: pick your disc image and
  DriftDroid verifies it's a clean PAL `RMCP01` disc and extracts it for you.

It is still an active, personal fork rather than a finished product. Expect
rough edges, and expect performance to vary a lot between devices - see
[Performance](#performance).

## What it does

**Native rendering via aurora.** The graphics layer is built on
[aurora](https://github.com/encounter/aurora), a source-level GameCube & Wii
compatibility layer, running on Vulkan through Google's Dawn.

**An OpenGL ES fallback.** Some phones ship a Vulkan driver too broken to start
the game at all. The launcher's **Graphics...** screen can switch the renderer
to OpenGL ES for those devices. It is slower than Vulkan by nature - OpenGL only
lets one thread talk to the GPU, so shaders compile on the render thread and the
multithreaded graphics path is unavailable - so leave it on Automatic unless
Vulkan doesn't work for you.

**Touch controls, tuned for Mario Kart Wii specifically.** Drag-anywhere
steering joystick, GameCube-diamond-style face buttons, D-Pad (needed for bike
wheelies), and L/R for items and drift. Every control can be repositioned,
resized, or hidden from a long-press edit mode, and double-tapping A can hold
acceleration for you.

**Motion steering.** Tilt-to-steer, with its own calibration and sensitivity,
for anyone who played the original with a Wii Wheel.

**Real controller support.** Controllers are fed to the game as a GameCube-style
pad. Mappings are positional (`south`, `east`, `west`, `north`) rather than
Xbox-labelled, so one config makes sense across Xbox, PlayStation, Nintendo and
generic controllers.

**Unlocked framerate with interpolation.** The original game is hard-locked to
60 fps. The runtime can generate interpolated frames in between for a smoother
feel on high-refresh displays.

> [!WARNING]
> Interpolation is experimental and will show artifacts in specific scenarios.

**It behaves like a phone app.** The game pauses when you background it instead
of fast-forwarding through everything it missed, it can drop its render
resolution when the device gets hot rather than sliding into a slideshow, and it
mutes its own music while you play something else - without stealing audio
focus from your music app.

**An in-game settings sidebar**, reachable from the gear button or the Back
button:
- Internal resolution, display mode, and FPS counter
- Frame interpolation, copy filter, and shader-compilation behavior
- Thermal resolution scaling and experimental multithreaded graphics
- Touch control visibility and double-tap auto-hold
- Controller assignment and full per-button mapping, with presets
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
- The first few minutes after installing are the worst, because shaders are
  still compiling. It settles down.
- Debug builds are much slower than release builds, so don't judge the port by
  one.

## Requirements

- **Android 10 (API 29) or newer**, on a 64-bit ARM (`arm64-v8a`) chip. There is no 32-bit or
  x86 build.
- A working Vulkan driver for the best experience. Devices without one can fall back to OpenGL ES
  from the launcher's **Graphics...** screen.
- Enough free storage for the extracted game data (a few GB) plus headroom during the one-time
  extraction step.
- A clean, unmodified **PAL `RMCP01`** disc image of Mario Kart Wii, dumped by you. Only the clean
  PAL revision is supported; other regions or modified executables are rejected.

> [!NOTE]
> Nobody here will tell you where to get the game. Dumping your own disc is on you, and links to
> game files won't be provided or tolerated.

## Installing

Grab the latest APK from this repo's [Releases](https://github.com/dorPXP/driftdroid/releases)
page and sideload it - it isn't on the Play Store. On first launch the app asks you to pick your
Mario Kart Wii disc image, checks it really is a clean PAL disc, and extracts it on the device.
That's the whole setup.

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

Build `assembleRelease` for anything you intend to measure. Debug builds enable Android's runtime
checks and are substantially slower.

This project also still carries the original desktop build (Windows, via the .NET translator and
Launcher under `translator/` and `Launcher/`) from upstream WiiCompiled - see
[`translator/README.md`](translator/README.md) if you're interested in that side instead.

## A note on related projects

WiiCompiled, KartPad, Wheel Wizard, Retro Rewind, and other related projects are developed
**independently** and each has its own contribution rules. What applies here does not
automatically apply there, and vice versa.

## Retro Rewind

[Retro Rewind](https://wiki.tockdom.com/wiki/Retro_Rewind) is a separate, optional community mod -
this app doesn't include, bundle, or download it, the same way it doesn't include the base game.
The app's first screen lets you choose Original Mario Kart Wii or Retro Rewind; picking Retro
Rewind before it's installed walks you through getting it set up:

1. Get your own copy of Retro Rewind first (search "Retro Rewind Mario Kart Wii", or use the
   separate Wheel Wizard tool most PC players already use for this).
2. Come back to the app and tap "Install Retro Rewind...", then either:
   - **Select the `.zip` file** exactly as you downloaded it - the app extracts it for you, no
     separate unzip step needed, or
   - **Select a folder**, if you've already extracted it yourself.
3. The app copies the pack into place (~2GB, so this takes a minute) and you're done - no manual
   file management, no computer required.

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

**Is there gonna be a Switch version?**
Im considering this and im going to see if this is possible

**Is it done?**
No - this is an active, personal work-in-progress fork. It's playable start to finish, but
performance work, device compatibility and bug fixing are ongoing.

**It won't start / the screen stays black.**
Most often a Vulkan driver the device can't actually deliver on. Open the launcher, tap
**Graphics...**, choose **OpenGL ES**, and try again. Report it either way - which renderer worked
is exactly the information that's useful.

**It runs at 60 fps and then gets slower.**
That's thermal throttling, not a bug in the build. See [Performance](#performance). Leaving
"Lower resolution when the phone gets hot" enabled keeps the frame rate steadier as the device
heats up.

**Some characters or objects are invisible.**
A few Adreno GPUs miscompile one of the shaders. Enable **Fix missing characters (some Adreno
GPUs)** in the settings sidebar.

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
- **[aurora](https://github.com/encounter/aurora)** - the GX rendering/windowing backend this
  project's whole graphics layer sits on. MIT licensed.
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
  processor fixes this fork carries.
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
