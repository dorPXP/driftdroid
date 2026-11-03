# Switch: files, controls and troubleshooting

Notes for players and for people working on the Switch build.

## Files on the SD card

| Path | What it is |
|---|---|
| `/switch/DriftDroid.nro` | Mario Kart Wii |
| `/switch/WiiCompiled/RetroRewind.nro` | Retro Rewind, started from DriftDroid's "Retro Rewind" button |
| `/switch/WiiCompiled/` | Everything else: settings (`Config.toml`), the game image, logs |
| `/switch/WiiCompiled/RetroRewind6/` | The Retro Rewind pack (`version.txt` says which release) |
| `/switch/WiiCompiled/log.txt`, `stderr.txt` | The log of the last run |

### Saves

Each game has one save file, `rksys.dat`. It holds the licences, unlocks, records, saved ghosts and
the friend roster; the `banner.bin` beside it is only the picture for the Wii's data screen.

- Mario Kart Wii: `/switch/WiiCompiled/NAND/title/00010004/524d4350/data/rksys.dat`
- Retro Rewind: `/switch/WiiCompiled/riivolution/save/RetroWFC/RMCP/rksys.dat`

To move a save, copy the file with the game closed. The file is the same one a Wii, Dolphin or the
Android app uses. A save made on a Wii of another region (for example a US console) carries that
region in a stamp the game checks, and the game would then only offer to create new save data
("Your Country setting has been changed..."). DriftDroid runs the PAL game, so on load it
restamps such a save as PAL and fixes its checksum, once, keeping the untouched file as
`rksys.dat.region-backup`.

### Retro Rewind updates

The Retro Rewind program downloads the pack itself. A fresh install takes the full archive
(about 1.9 GB); an installed pack gets only the update archives after its own release (for example
6.13.1 -> 6.13.3 -> 6.13.4, about 40 MB). Every archive is checked against pinned sizes and hashes
in `runtime/src/retro_rewind_download.cpp`, and the result must have the `Code.pul` the program was
built for. See "Supporting a new Retro Rewind release" in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Controls

- **Settings menu**: click both sticks together (there is no F10 key). It slides in from the right
  with the race visible behind it: L / R change page, the D-pad or stick moves, A changes a
  setting, B or + closes. Changes are saved straight away.
- **Players 1-4** (top of the Controls page): each connected controller is its own player, numbered
  like the lights on the controller, with the player's colour and the controller type. Press a
  button and your row lights up. "Change controllers" opens the console's own controller screen
  (the one games show before multiplayer) to add players, swap who is who or change the grip. The
  game always follows the console's order.
- **Tilt to steer** (Controls page): a switch per player. The roll of the console or that player's
  controller replaces the left stick's left/right, and a bar shows the tilt live. It only steers
  while you are driving a race: in menus, in the pause menu and while the settings menu is open the
  stick is yours alone, so a controller held slightly tilted does not move cursors. With two
  separate Joy-Cons the right one is read, or the left if the right gives nothing. A single Joy-Con
  held sideways has no motion support yet.
- **Shake to trick** (Controls page): a switch per player. While driving, a shake of that player's
  controller presses the D-pad for a moment in the direction of the shake: any shake on the ground
  is a wheelie, and in the air up, down, left or right picks the trick. Nothing is pressed in menus.
  It applies right away, with no restart.
- **Presets**: "GameCube" and "Classic Controller Pro" give L = ZL or L and R = ZR or R, with A, B, X
  and Y on the matching Switch buttons.
- **Vibration** (top of the Controller page, every platform): on or off, saved in `rumble.cfg`. On
  Switch both vibration bands play at about 150 Hz with the game's strength, the signal SDL sends
  the same controllers on PC.

## Starting DriftDroid

Start it from its own Home tile (a forwarder or title override that takes over a game's process), not from
the Album or an applet-mode hbmenu. Opened as an applet the program gets far too little memory and the game
aborts while starting (the log ends at `[selftest] begin`, and Atmosphere writes a `User Break` crash report
with result `2359-0003` for `hbloader`, followed by a `qlaunch` crash). The launcher checks this
(`InAppletMode()` in `switch_launcher.cpp`, using `appletGetAppletType()`): in applet mode it shows a red
status line, "Opened from the Album: too little memory for the game. Start DriftDroid from its Home tile.",
and refuses to start a game instead of crashing. The Home tile icon is separate from the `.nro` icon: it
belongs to the forwarder, which the player makes themselves.

The icon the `.nro` shows in the homebrew menu is chosen when DriftDroid Builder packs it (see
[pc-builder.md](pc-builder.md), "The Switch icon"): DriftDroid's own, or any picture the player picks.

## Optional flags

Empty files in `/switch/WiiCompiled/`:

| File | Effect |
|---|---|
| `sound_core.flag` | Runs the game's sound engine on its own CPU core (about 2 ms less per frame in a race). Experimental and off by default: Retro Rewind online crashes with it (rooms, race starts). `no_sound_core.flag` overrides it. |
| `profile.flag` | Writes CPU profile windows for performance work (`profile_windows/`). Costs some speed. |

## Troubleshooting with the log

- `[sound_core] still waiting after N ms: ...` - a wait between the game and the sound thread is
  taking long. It names the wait and the place where the game last turned interrupts off
  (`SoundCore::Enabled +offset`: add the offset to that function's address in the `.elf`). Waits no
  longer give up: older builds printed `gave up waiting` and went on unprotected, which let both
  threads change the same data at once. `[sound_core] ignored an unlock of the sound mutex by a
  thread that does not hold it` means the game released that lock without holding it (on Switch
  that used to jam the lock and freeze the Retro WFC login; fixed). Retro Rewind online still
  crashes with the sound core on, so it stays off by default.
- `[nand] SaveRegion: save from region N restamped as PAL` - an imported save was converted, see
  Saves above.
- A crash writes `[switch-crash]` lines with the program counter and link register relative to the
  module base; symbolize them against the `.elf` of the same build.

## Language

The launcher and the in-game menus follow the console's system language by default. To pick one, open the
in-game menu (Minus, then **All settings**) and use **Display → Language**; it applies the next time the game
starts. The choice is stored in `/switch/WiiCompiled/language.cfg` (a language code such as `de` or `ja`; delete the
file to follow the system language again). See [languages.md](languages.md).
