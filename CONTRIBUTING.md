# Contributing to DriftDroid

Thanks for wanting to help! A few ground rules.

## Where a change belongs

DriftDroid is the Android fork of
[WiiCompiled](https://github.com/patchzyy/Wiicompiled). Sending a change to the
right repository saves everyone a round trip:

- **Here**: the Android app shell (`android/`), touch controls, the settings
  sidebar, the launcher, thermal and renderer work specific to phones, and
  anything under `aurora-main/` that only matters on Android or OpenGL ES.
- **Upstream WiiCompiled**: the recompiler itself, game behavior, HLE, and
  anything portable. Portable fixes are welcome here too, but they belong
  upstream first so every port gets them.

If you aren't sure, open an issue and ask before writing the code.

## The short version

- Code is judged on quality, not where it came from.
- You must understand and be able to explain every line you submit.
- PR descriptions and responses must be written by you, **not** generated.
- Accuracy is the bar for anything touching game behavior.

## Code quality

We don't care how your code came into existence. What we care about is whether
it meets or improves the project's patterns and standards, and the only way we
measure that is by reading it.

Low-quality code won't be merged, regardless of origin. AI slop and human slop
get the same treatment.

## If you use AI tools

That's ok, but rules apply:

1. **You must be able to explain your changes.** If a reviewer asks why a line
   exists or what a function does and you can't answer, the PR will be closed.
   "The AI wrote it" is not an explanation.
2. **Write your own PR description.** The description exists so reviewers know
   what you changed and why, in your words. Generated descriptions tend to
   describe everything and explain nothing, and they will get your PR closed.

## Pull requests

- Keep PRs focused. Try to keep it at 1 change per PR. Small PRs get reviewed
  fast.
- Explain **what** and **why**. Reference the issue if there is one.
- For anything affecting game behavior: identical behavior to real hardware is
  the goal. Be prepared to show your change doesn't diverge from the original
  game (hardware comparison, logs, whatever fits).
- Review feedback is about the code, not about you ;).

## Testing on a device

This is a phone port, so "it builds" is not the same as "it works". Anything
touching input, rendering, audio, or the lifecycle needs a run on real
hardware before it's submitted. Say which device and Android version you tested
on, and note whether it was a debug or release build - debug builds carry
Android's own runtime checks and are noticeably slower, so they're no basis for
a performance claim.

Useful while testing:

```bash
# the runtime's own log (debug builds only - release isn't debuggable)
adb shell run-as com.driftdroid.android cat files/WiiCompiled/android_runtime_attempt.log
```

Performance claims should come with numbers and the conditions behind them. A
phone that has been running for ten minutes is thermally throttled and will
score differently from a cold one, and some devices have a boost or performance
mode that silently changes the answer.

## Supporting a new Retro Rewind release

Retro Rewind ships faster than this app does, and most of the time nothing needs
doing: the app reads the official release list from `update.rwfc.net` at runtime
and decides what it can run from the SHA-256 of `Binaries/Code.pul`, not from a
version number. A release that only changes tracks, characters or other assets
is picked up on its own, with no code change and no app update.

A release that changes `Code.pul` is different. That file is the mod's PowerPC
game logic, and because everything here is compiled to native code ahead of time
rather than emulated, it has to be translated on a desktop and compiled in - a
phone or a Switch cannot pick it up at runtime. The app detects this case from
the release's small update archive before downloading anything large, refuses
it, and leaves the player's working install alone.

### With the PC builder

`tools/pc-builder` (see [docs/pc-builder.md](docs/pc-builder.md)) fetches the
newest `Code.pul` from the update server by itself every time it runs and
records the version and hash it used in the game pack
(`driftdroid-pack.json`: `retroRewind`, `retroRewindCodeSha256`). A player
re-runs the builder after a Retro Rewind update. `--retro-rewind 6.13.3` builds
for an older release; `--check-retro-rewind` prints what a build would use.

### For a build made in this repository

1. Put the new `Code.pul` in
   `PulsarPacks/completed/RetroRewind/RetroRewind6/Binaries/`, back up
   `generated/`, `build/mods/` and `build/base/`, and run the five translator
   steps (`translate-recursive`, `emit-base-manifest`, `translate-mod`,
   `generate-data-init`, `emit-build-shards`) **twice**: the base translation
   takes into account what the Retro Rewind step wrote on the previous pass, so
   only the second pass is final (it reports nothing changed). Shard files are
   named by content: run `cmake .` and `ninja -t cleandead` in each build folder
   before building.
2. Android, `android/app/src/main/kotlin/com/driftdroid/android/RetroRewindRelease.kt`:
   replace the hash in `SUPPORTED_CODE_PUL_SHA256` (one build carries the
   translated code of one `Code.pul`), add the release to
   `KNOWN_CODE_PUL_SHA256`, and move the offline fallback (`VERSION`,
   `ARCHIVE_URL`, `ARCHIVE_BYTES`, `ARCHIVE_SHA256`, `CODE_PUL_SHA256`) to the
   new full archive. Not every release has one (6.13.1 only exists as an update
   on top of 6.13.0).
3. Switch, `runtime/src/retro_rewind_download.cpp`: `kRelease` is the full
   archive for a fresh install; `kUpdates` lists the update archives oldest
   first, and `kUpdateFrom` says which installed pack each one applies to. An
   installed pack gets only the updates after its own game code. Each entry
   pins the archive size and its zip directory (offset, size, SHA-256), so a
   file changed on the server fails cleanly. The values come from the end of
   the archive with HTTP range requests: find the end-of-central-directory
   record (`PK 05 06`) in the last 64 KiB, read the directory size and offset
   from it (zip64: follow the `PK 06 07` locator), and hash
   `[offset, offset + size)`.
4. Check hand-written code that depends on the mod's own code addresses
   (`runtime/src/hle/low_detail_karts.cpp`).
5. Test: a Retro Rewind race on PC, a fresh install and "Check for updates" on
   Android, and the Switch downloader on PC. The downloader has a test entry
   point:

   ```sh
   clang++ -std=c++20 -O1 -DCRYPTOPP_DISABLE_ASM -DCRYPTOPP_NO_GLOBAL_BYTE -DNOMINMAX \
       -DRR_DOWNLOAD_TEST_MAIN -Iruntime/include -Iruntime/third_party \
       runtime/src/retro_rewind_download.cpp linux-native-build/libmkw_cryptopp.a \
       -lmbedtls -lmbedx509 -lmbedcrypto -lpthread -o rrdl
   ./rrdl /path/to/RetroRewind6 runtime/assets/certs/cacert.pem
   ```

   The Crypto++ defines must match the ones the library was built with, or
   SHA-256 crashes. To test an upgrade, point it at a pack whose `Code.pul` and
   `version.txt` are those of an older release; it updates in place.

Never relax the hash check to make a release install. It exists because an
unsupported `Code.pul` desyncs from the recompiled logic at runtime instead of
failing cleanly, which is far worse for the player than being told to wait.

## Bug reports

Use the issue templates, and see the FAQ in the [README](README.md). The most
useful report names the device, the Android version, the DriftDroid version,
whether it's Original or Retro Rewind, and includes the runtime log if you can
get it.

## A note on related projects

WiiCompiled, KartPad, Wheel Wizard, and other projects in this ecosystem are
developed independently and each has its **own** contribution rules, including
their own rules around AI usage. What applies here does not automatically apply
there, and vice versa. Check each project's own CONTRIBUTING file.

## Legal

- Never!!! include Nintendo code, assets, or game data in a PR, an issue, or
  anywhere else in this project. No exceptions. That includes logs or
  screenshots that embed game data, and links to game files.
- By contributing, you agree your contributions are licensed under
  [GPL v3.0](LICENSE), like the rest of the project.
