# Changes ported from other projects

DriftDroid builds on the work of several open projects. This page lists the individual fixes we
took from them, who wrote each one, and where it came from. Some were adapted to fit this fork's
code. Where the adaptation matters, the note says how.

## patchzyy/WiiCompiled

The static recompilation project DriftDroid is built on.
[github.com/patchzyy/Wiicompiled](https://github.com/patchzyy/Wiicompiled)

| Change | Author | Source |
|---|---|---|
| Sky Station crash: unused constant-color selections read as zero instead of stopping the game | patchzyy | [#303](https://github.com/patchzyy/Wiicompiled/pull/303) |
| Wait for the GPU before releasing the screen surface (not on Switch, which has no surface release) | patchzyy | [#308](https://github.com/patchzyy/Wiicompiled/pull/308) |
| Only trust relocated module pointers when finding mod functions | patchzyy | [#321](https://github.com/patchzyy/Wiicompiled/pull/321) |
| Conditional calls (`beqlrl` and the rest) set the return address on both paths | patchzyy | [#297](https://github.com/patchzyy/Wiicompiled/pull/297) |
| `bcl` sets the return address before it branches | Michael G (DarthMDev) | [#258](https://github.com/patchzyy/Wiicompiled/pull/258), the translator part only |
| Settings changes no longer duplicate keys in Config.toml (kept: the Switch closes the file before rewriting it) | patchzyy | [#298](https://github.com/patchzyy/Wiicompiled/pull/298) |
| The game's clock starts at the real local date | patchzyy | [90589131](https://github.com/patchzyy/Wiicompiled/commit/90589131) |
| An all-zero save file counts as missing, not corrupt, so the game makes a fresh one | Wubbzee (JGM01) | [#168](https://github.com/patchzyy/Wiicompiled/pull/168) |
| Identical controllers keep their player ports (device path and slot) | patchzyy | [#305](https://github.com/patchzyy/Wiicompiled/pull/305) |
| Alarm rescheduling and Mii library interrupt context; offline Wii identity services; shader cache size checks; compiler worker wake-ups; three-byte vertex reads; split-screen frame interpolation; VI render mode lock; ImGui partial-startup cleanup | patchzyy, adapting KartPad fixes by Chris Sotraidis | [#244](https://github.com/patchzyy/Wiicompiled/pull/244), parts |

## KartPad

The iOS and Android sibling port by Chris Sotraidis.
[github.com/chrissotraidis/kartpad](https://github.com/chrissotraidis/kartpad), runtime at
[github.com/chrissotraidis/wiicompiled](https://github.com/chrissotraidis/wiicompiled)

| Change | Author | Source |
|---|---|---|
| Incomplete quads, triangles and strips draw what the console draws; lines and points are never merged | Chris Sotraidis | runtime [485f0caa](https://github.com/chrissotraidis/wiicompiled/commit/485f0caa) |
| GPU readback callbacks own their results instead of pointing into a finished stack frame | Chris Sotraidis | runtime [438421aa](https://github.com/chrissotraidis/wiicompiled/commit/438421aa) |
| Staging buffer map callbacks from a retired request are ignored | Chris Sotraidis | runtime [68ffb2dc](https://github.com/chrissotraidis/wiicompiled/commit/68ffb2dc) |
| Texture-copy blits match the destination format (here: RGBA plus the screen's format, with a fallback) | Chris Sotraidis | runtime [fd1bce18](https://github.com/chrissotraidis/wiicompiled/commit/fd1bce18) |
| Adreno vertex repack works one attribute at a time | Chris Sotraidis | runtime [ac118be0](https://github.com/chrissotraidis/wiicompiled/commit/ac118be0), the repack loop only |
| The phone vibrates for player one when their controller has no motor | Chris Sotraidis | runtime [12449f78](https://github.com/chrissotraidis/wiicompiled/commit/12449f78), app `KartPadDeviceRumble.kt` (KartPad 0.8.4) |
| Vibration strength, 0 to 200% | Chris Sotraidis | runtime [2316f7f6](https://github.com/chrissotraidis/wiicompiled/commit/2316f7f6) (KartPad 0.8.5) |

KartPad's vibration code goes through its Classic Controller path. Here it is rewritten for the
GameCube pad path DriftDroid uses (aurora `pad.cpp` and `DeviceRumble.kt`). The behaviour follows
KartPad's rule: touch-only play stays quiet, and a controller with its own motor keeps the phone
still.

## aurora

The GX graphics library under the whole renderer, by Luke Street and contributors.
[github.com/encounter/aurora](https://github.com/encounter/aurora)

| Change | Author | Source |
|---|---|---|
| Texture coordinates generated from a color use the lit channel color | Jasper St. Pierre | [e938637](https://github.com/encounter/aurora/commit/e938637) |
| Texture coordinates generated from an earlier texture coordinate (guarded so a bad setup cannot break the shader) | Jasper St. Pierre | [0857ad8](https://github.com/encounter/aurora/commit/0857ad8) |
| Color channels that only a texture coordinate reads are still computed | Roman Sandu (Mrkol) | [#291](https://github.com/encounter/aurora/pull/291) |
| Texture minification filter decoding and signed LOD bias encoding | Logan Shire (AttilaTheFun) | [#293](https://github.com/encounter/aurora/pull/293) |
| 1024-texel texture dimensions decode correctly | Luke Street | [a906fc2](https://github.com/encounter/aurora/commit/a906fc2) |
| `SET_REG_FIELD` masks the field it writes | Jasper St. Pierre | [afa388d](https://github.com/encounter/aurora/commit/afa388d) |
| `GXLoadPosMtxIndx` | Luke Street | [9381cc4](https://github.com/encounter/aurora/commit/9381cc4) |

### From patchzyy/aurora-wiicompiled (WiiCompiled 0.9's renderer)

[github.com/patchzyy/aurora-wiicompiled](https://github.com/patchzyy/aurora-wiicompiled)

| Change | Author | Source |
|---|---|---|
| GX matrix indices use only their low six bits | patchzyy | [4aa0141](https://github.com/patchzyy/aurora-wiicompiled/commit/4aa0141) |
| Invalid TEV constant-color and constant-alpha selections read as zero instead of stopping the game | patchzyy | [368c604](https://github.com/patchzyy/aurora-wiicompiled/commit/368c604) |
