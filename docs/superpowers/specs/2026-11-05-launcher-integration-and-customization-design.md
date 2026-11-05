# Launcher integration and customization: design (2026-11-05)

Status: **draft for the owner's approval. Nothing here is released or pushed.** Public-safe.
Local work so far is on branch `aura-integration` (see "Where things stand").

## Intent

1. **Integrate the DriftDroid Aura launcher** (`edgHD/driftdroid-aura`, GPL-3.0, a public fork of this
   repo) into DriftDroid on Android: a new launcher with Home, Mods (GameBanana browser), Rooms (live
   Retro WFC rooms), Ranks (VR top 100), Profile, a Mii editor and Settings. The owner has not contacted
   the author yet; the integration goes ahead locally either way, with credit.
2. **More customization**, the owner's idea: a custom startup screen (the owner personally wants the
   "lagtrain" animation on Android), an imported video with audio on or off (also on Switch), custom
   backgrounds for the app, and custom colour gradients for the UI.
3. The owner plans to draw a new launcher design. The customization work should make a later re-skin
   cheap, so the theme engine comes before the redesign.

Non-goals: shipping anyone's media (the lagtrain file is the owner's own, never in the repo or the APK);
raising Switch clocks; remote code; anything that makes startup slower for players who use none of it.

## Where things stand (facts, checked 2026-11-05)

- The fork's three commits touch only the Android Kotlin app, resources, tests and README (about 8,800
  lines added, 77 files). No native, Switch or runtime code.
- It merges into `android-port` with **one conflict (README.md)**: our `android/` changed only a few
  i18n lines since its base (`4cba039b`).
- The fork rewrote `ModePickerActivity.kt` (our launcher), `ModManager.kt`, and deleted
  `MiiDatabaseEditor.kt`; it added `launcher/`, `mii/`, `mods/`, `net/`, `online/`, `profile/`, `ui/Lx.kt`
  (its look: "pit lane", slanted plates), `ui/Icons.kt`, `ui/RemoteImages.kt`, and four test files.
- It installs as `com.driftdroid.aura` (own app id), version `1.4.0-aura.1`, new icons. Its Kotlin classes
  keep `com.driftdroid.android` because the game's JNI functions bind by class name.
- Hosts it talks to: `gamebanana.com` (API v12), `rwfc.net` (player pages), `update.rwfc.net` and
  `cdn.update.rwfc.net` (Retro Rewind version and zip). Needs libarchive (BSD) behind an Apache-2.0
  wrapper for .zip/.7z/.rar mods.
- Its UI colours are compile-time constants in `Lx.kt` (`BG`, `SURFACE*`, `ACCENT`, ...).
- The startup screen today (Android and Switch) is one native ImGui window in
  `runtime/src/settings_overlay.cpp` (`DrawStartupScreen`): black, the word "DriftDroid", shown until the
  game's first frames; on Switch the disc banner draws over it (#24). The game starts after Play, so
  there is no Kotlin splash to restyle: custom startup media belongs in this one native place.

Done locally on `aura-integration` (not pushed): `3fed6dfe` merges the fork (README kept ours);
`fbf02c02` puts back the DriftDroid app id, version, name, icons, user agent, swaps the Aura mark for the
kart from our icon, and keeps a credit row ("Launcher based on DriftDroid Aura, by edgHD, GPL-3.0") in
Settings. Edgar stays the author of his commits in history. Compiling: see below.

## Part A: integration, phases

A1. **Compile and unit tests** (`./gradlew :app:compileDebugKotlin`, then the four fork test files).
A2. **Audit before anyone installs it** (read the code, it is AI-assisted and unreviewed by us):
   - What leaves the phone: each request, its headers, whether a friend code or any save data is sent to
     rwfc.net, the user agent, any telemetry. Nothing private may leave without the owner's say.
   - What it writes: the Retro Rewind `Patches` folder (they hard-link mods in, only removing their own
     files), generated `riivolution/config/RMCP.xml`, the Mii database and save edits.
   - **Mii editor**: it replaced our `MiiDatabaseEditor.kt`. Back up the database before any write, and
     compare behaviour with ours (the known corrupt-database cases, `docs` notes on RFL_DB).
   - Parity: everything our old launcher did (game pack import, Builder link, Retro Rewind install and
     update, graphics/GPU settings entries, language, icon picker, thermal) must still be reachable.
   - Mod installs run only on explicit user action, with a size cap and path-traversal checks on archives.
A3. **Device test on the owner's phone** (only with the owner's OK for each action on the phone).
A4. **Fix findings**, then release. **Owner decision 2026-11-05: 1.4.1 is another big update**, so the
   launcher can go in it. The audit (A2) and a device test (A3) are the gate, not the version number; if
   they are not done in time, the launcher waits for the next release instead of shipping unchecked.

## Part B: customization

### B1. Theme engine (Android launcher, then the in-game overlay)

- One `Theme` object (accent, background, surface tones, gradient stops and angle, optional background
  image and its dim level). `Lx.kt` constants become reads from it; the activity recreates on change.
- Presets: the current "pit lane" dark, plus a few. A colour picker for accent and for 2 or 3 gradient
  stops. Settings has a Reset.
- Background image: chosen with the system picker, copied into the app's own storage, downscaled to the
  screen size (so a huge photo cannot use memory), with a dim slider so text stays readable.
- Switch and the in-game menus (ImGui): map the same accent and background colours onto `ImGuiStyle` in
  `settings_overlay.cpp` and `switch_launcher.cpp`; a setting file next to the existing `*.cfg` files.
- The owner's own redesign later plugs in as another preset plus new layout code, no re-plumbing.

### B2. Custom startup media (shared, native)

One renderer in the startup window, used on Android and Switch, replacing the "DriftDroid" text when set.

- **Formats, v1:** a single image (PNG/JPEG), an animated GIF, or a **frame pack**: a folder with numbered
  JPEG/PNG frames, a `startup.json` (fps, loop, fit = cover or contain) and an optional `audio.mp3`.
  The decoders are already in the app (stb_image for pictures and GIF, minimp3 for the music player), so
  no new codec is added. The owner's lagtrain becomes one ffmpeg command to frames plus audio.
- **Streaming**, not loading everything: decode the next frame on a worker thread; memory stays flat.
- **Audio** on or off, with volume. On Android it plays through the existing audio backend; on Switch
  through the music player's path. If the audio device is not up yet at that point, play it silently
  rather than delay the game.
- **When it shows:** from Play until the game's startup screen would end (`StartupScreenVisible`), never
  longer. Optional "let it finish" toggle, off by default. The Switch disc banner stays the default there.
- **Limits:** a size cap (for example 64 MB), a frame cap, a clear message for a bad pack, fallback to the
  normal screen. No network, nothing is uploaded.
- **Switch cost:** the game boot is CPU-bound, so decoding runs at a modest frame rate on a spare core and
  no clocks change. Measure boot time with and without it before shipping.
- **v2 (later):** direct video files on Android through the platform decoder (NDK `AMediaCodec`). On
  Switch no system codec exists, so the frame pack stays the format there (a PC converter can come later).

### B3. Where settings live

Android: the same SharedPreferences style as `GraphicsSettings.kt`. Switch: small files beside
`rumble.cfg` and `shake_trick.cfg` in `switch/WiiCompiled/`. Media and background images are copied into
the app's data folder, never referenced by path from outside.

## Order of work

1. A1 compile, tests (running now). 2. A2 audit, written findings. 3. B1 theme engine on Android.
4. B2 startup media on Android. 5. B2 and B1 on Switch. 6. The owner's redesign. 7. A3 device test,
release decision. The banner work from #24 (Android parts, logo swap) continues in parallel and is
the first Android use of the shared startup window.

## Risks

- A large unreviewed code import in a big release: mitigated by the audit and a device test as the gate.
- Licence: GPL-3.0 both ways; keep the fork's headers and notices, credit in Settings and the README
  credits, history keeps Edgar as author. Add the fork's libarchive wrapper to the third-party notices.
- The startup media must never slow down or break the start of the game: every failure falls back to the
  existing screen.
- Customization is easy to over-build. v1 is the five things above, nothing more.

## Open decisions (owner)

1. ~~Release split~~ Decided: 1.4.1 is a big update; the launcher is a candidate, gated by the audit.
2. Startup media on Switch: frame pack and mp3 (proposed) or wait for a video-file format?
3. Should the startup media and background be per product (Mario Kart Wii vs Retro Rewind) or one setting?
4. ~~Contact the Aura author~~ Done by the owner 2026-11-05, no reply yet. Integration goes ahead anyway (GPL-3.0, credit kept).
5. The kart mark: keep the launcher icon's kart as the Aura mark's stand-in until the redesign?

## Audit findings, first pass (2026-11-05)

Checked by reading the code on branch `aura-integration`; not yet run on a device.

Good:
- **Compiles; the fork's 25 unit tests pass** (Mii and friend codes, online and save formats, Retro
  Rewind patches, Riivolution choices).
- **Network:** one HTTP client (`net/Http.kt`): HTTPS only, every request names its allowed hosts and every
  redirect hop is checked against them, a hard byte limit per response, generic user agent (`DriftDroid/<version>`
  after our change). Hosts: `rwfc.net`, `update.rwfc.net`, `cdn.update.rwfc.net`, `gamebanana.com` and
  `*.gamebanana.com` (download mirrors), plus the Mii picture host. No analytics, ad id, device id or crash
  reporter found.
- **Friend codes** go only to rwfc.net's public leaderboard endpoints, when the player opens a Ranks/player page.
- **Mod archives** (`mods/ArchiveExtractor.kt`): each entry is checked to land inside the destination (no `..`,
  canonical-path check), at most 50,000 entries and 6 GB unpacked.
- **Mii database and save edits** write a temporary file, keep the previous file as `RFL_DB.dat.bak` /
  `rksys.dat.bak`, validate before swapping in.
- Everything else from the old launcher is still reachable (GPU driver, graphics, language, icon, private
  server, Retro Rewind install, game pack, diagnostics).

Found, to check before release:
1. ~~Regression: Mii file import dropped~~ **Wrong, corrected the same day.** The first look searched only
   `MiiEditorPage.kt`. The new Profile page (`launcher/ProfilePage.kt`, `importMii()` and `exportAll()`) already
   has both: a 74-byte Mii file (.rcd) is added to a free slot, and a whole RFL_DB.dat (779,968 bytes, "RNOD",
   CRC checked) replaces the database after a confirm dialog, with `RFL_DB.dat.bak` kept; "Export all" and
   "Export as .rcd" are there too. One difference: the new single-Mii import gives the Mii a fresh avatar id and
   this console's id (`MiiDatabase.adopt`) instead of inserting the record unchanged, which avoids id clashes.
2. Still to read: how mods are written into the Retro Rewind `Patches` folder (hard links; the fork removes
   only its own files) and the generated `riivolution/config/RMCP.xml`; the game-restart-on-mod-change logic in
   `MainActivity`; the Mii editor writing to `rksys.dat` while the game could be running.
3. Device test (needs the owner's OK for each action on the phone).

## Switch phase (planned 2026-11-05, not started)

The Switch launcher (`runtime/src/switch_launcher.cpp`, native ImGui) already draws the original drifting
checkerboard (`DrawBackground`, dark greys, 42-unit tiles at 0.1 tile/s). It keeps it as the default.
Android got the same checkerboard back as a built-in background (default for the original theme).

Order, smallest risk first. The Aura screens are Android views, so on Switch they are rebuilt in C++, reusing
the web calls and the file logic, not the code:

1. **Theming.** Replace the fixed colour constants and the few drawing functions (background, button, panel,
   list row) with values from a theme: the same presets, accent, backdrop colours and checkerboard; read
   `.driftdroid-theme.json` files from the SD card so a theme works on both. Looks (glass, Aero, skeuo) via
   ImGui's gradient vertex colouring. Backgrounds: still picture and GIF (stb_image); no video, the Switch has
   no video decoder (THP only). Needs the owner's console to judge it; nothing can be screenshotted here.
2. **Rooms and Ranks:** HTTPS GET to rwfc.net plus JSON. The runtime already does TLS for Retro Rewind downloads.
3. **Mods store:** GameBanana API, download, extract. Zip extraction exists (`zip_extract.cpp`); 7z and RAR
   need a library (check devkitPro portlibs) or are skipped with a clear message.
4. **Profile and Mii editor:** port of `profile/SaveFile.kt` and the `mii/` package (rksys.dat licences and
   friends, RFL_DB.dat); the largest piece, with backups before every write as on Android.
