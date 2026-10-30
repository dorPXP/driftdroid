DriftDroid Builder 1.4.0
========================

DriftDroid's downloads do not contain any of Mario Kart Wii's code. This program makes that code
on your own computer, from your own disc.

You need:
  - A 64-bit PC running Windows 10 or 11, or Linux (x86-64), about 25 GB of free disk space and an
    internet connection.
  - Your Mario Kart Wii disc as an .iso or .wbfs file. It must be the PAL (European) version,
    game ID RMCP01. Split .wbfs files (.wbfs + .wbf1) work too.
  - For Switch: devkitPro with "Switch Development" installed
    (https://devkitpro.org/wiki/Getting_Started). Android needs nothing extra.

How to use it:
  1. Unpack this whole folder somewhere with a short path, for example C:\DriftDroidBuilder or
     ~/DriftDroidBuilder. Keep the DriftDroidBuilder program and the "toolkit" folder together.
  2. Windows: double-click DriftDroidBuilder.exe.
     Linux: double-click DriftDroidBuilder, or run  ./DriftDroidBuilder  in a terminal.
  3. In the window: choose your disc image (or drag it onto the window), pick Android, Switch or
     both, and press Start. It takes between 30 minutes and a couple of hours, depending on your
     computer. The first time it also downloads the Android compiler (about 700 MB).
  4. When it is done, the window tells you what to copy where. The files are in the "output"
     folder:
       Android: DriftDroid-GamePack-1.4.0.zip
         Copy it to your phone, open DriftDroid and tap "Import game pack".
       Switch:  switch/DriftDroid.nro and switch/RetroRewind.nro
         Copy DriftDroid.nro to /switch/ and RetroRewind.nro to /switch/WiiCompiled/ on the SD card.
  5. Press "Free up space" at the end to delete the "work" folder and get the disk space back.

  No window? (For example on a Linux machine without a desktop.) Run it in a terminal with
  --android and/or --switch, and the disc image:  ./DriftDroidBuilder --android mkwii.iso

Retro Rewind updates:
  The builder always uses the newest Retro Rewind it finds on Retro Rewind's own update server.
  When Retro Rewind releases an update, run the builder again and import the new pack (or copy the
  new RetroRewind.nro). You do not need a new DriftDroid or a new builder for that.
  To build for an older Retro Rewind release, add  --retro-rewind 6.13.3  (the version you want).

A game pack only works with the DriftDroid version it was made for. When DriftDroid updates, get the
matching builder and make a new pack.

If something goes wrong, builder-log.txt in this folder has the details. Please attach it when you
report a problem.
