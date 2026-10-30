# Languages

DriftDroid's own screens (the Android app, the Switch launcher, the in-game settings menu and the PC
builder) are available in 14 languages:

English, Español (Latinoamérica), Español (España), Português (Brasil), Français, Deutsch, Italiano,
日本語, 한국어, 简体中文, Русский, Türkçe, Nederlands, Polski.

This is the language of DriftDroid's menus only. The language of the game itself (Mario Kart Wii's own
menus and text) comes from the disc and is not changed by this setting.

## Choosing a language

By default DriftDroid follows the language of the device (Android system language, Switch system
language, or `LANG` on the PC). To pick one yourself:

- **Android:** the **Language...** button on the launcher screen.
- **Switch:** **Settings → Display → Language** in the in-game menu (open it with Minus, then
  **All settings**). It applies the next time the game starts.
- **PC builder:** the **Language** button at the bottom of the window.

The choice is stored in `language.cfg` in the app's data folder (on the Switch:
`/switch/WiiCompiled/language.cfg`, containing a language code such as `de` or `ja`).

## How it works

Every translatable text is an English sentence; the English text is its own key. A language file only
needs the sentences it has translated, anything missing is shown in English.

- `i18n/<code>.json` holds one language: `{"name": "...", "strings": {"English text": "translation"}}`.
- `tools/i18n/extract.py` scans the C++, Kotlin and C# sources for translatable texts and keeps
  `i18n/en.json` (the list of all keys) up to date. Texts that are built at runtime are listed by hand in
  `i18n/extra_keys.txt`.
- `tools/i18n/gen.py` turns the language files into what each program reads: a header compiled into the
  game (`runtime/src/i18n_data.inc`), the Android assets, and the PC builder resources.
- Texts with values in them use printf placeholders (`%s`, `%d`) that must appear in the translation
  too. `tools/i18n/put.py` checks that when translations are stored.

In code, C++ uses `TR("English text")`, Kotlin uses `T("English text")` and the PC builder uses
`I18n.T("English text")`. Widgets in the settings menu translate their labels automatically.

## Adding or fixing a translation

1. Edit the sentence in `i18n/<code>.json` (or add a new language file and add its code to `ORDER` in
   `tools/i18n/gen.py`; the language picker and the system-language detection in `runtime/src/i18n.cpp`
   may also need the new code).
2. Run `python3 tools/i18n/extract.py` and `python3 tools/i18n/gen.py`.
3. Rebuild.

## Fonts

- **Switch:** the console's own shared fonts are used, so no font files ship with the app. Latin,
  Cyrillic and Japanese come from the standard font; Korean and Simplified Chinese come from their own
  shared fonts, which are loaded for every language so the language list can show each language's name.
- **Android:** Roboto plus the phone's system CJK fonts.

## Long translations and layout

Translations are often longer than the English text, so every place that shows one has to cope:

- **Switch launcher buttons:** the label shrinks until it fits (down to 15 px), then is cut with "...".
  Keep button texts short anyway; the Dutch "Spel herinstalleren..." and "SD-kaart bekijken..." were shortened
  because the first versions did not fit.
- **Quick panel tiles (in-game menu):** label and status shrink to fit the tile, down to half size.
- **Sentences with values** (free space, "priority 1 of 2", versions) are written with printf placeholders and
  built with `Fmt("English %s")` in the launcher or `TR(...)` plus `snprintf` elsewhere. Never build a visible
  text with a plain `snprintf` of an English literal: it will not be translated. The Mods list line
  `"%s  -  priority %d of %d"` was missed this way once.
- The extractor does not see texts assembled at runtime; list them in `i18n/extra_keys.txt`.

## Checking a language on the Switch

The launcher and the in-game menu can be checked without touching the console:

1. Write the language code (for example `es`) into `/switch/WiiCompiled/language.cfg` on the card.
2. Start DriftDroid and look at the launcher, its Mods, Mii data and browse screens, the Retro Rewind
   download dialog and the reinstall screen.
3. Start a game, press Minus for the quick panel, then **All settings** for the drawer.
4. Delete `language.cfg` afterwards, or the console keeps using that language.

Checked so far (2026-10-28): launcher in ru, es, fr, it, nl, pt-BR (plus ja, ko, pl, tr, zh-Hans, de
earlier); Mods screen in es, ru, nl; reinstall screen and download dialog in es; quick panel and drawer
in es (and ja, de before). Not yet looked at in every language: Mii data and browse screens, and the quick
panel in de, ru, nl and pt-BR.

## Not done

- A language option for the game itself (the disc's own menus). It needs a replacement for the game's
  `SCGetLanguage` function and a full regeneration of the translated code.
- Community review of the machine-written first translations.
