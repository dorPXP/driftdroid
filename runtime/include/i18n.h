#pragma once

// Translations for the menus (in-game settings, Switch launcher). The English text of a string is its
// key: Tr("Vibration") returns the text in the chosen language, or the English text when that language
// has no entry. The tables come from i18n/<language>.json (tools/i18n/gen.py makes src/i18n_data.inc).
// The Android app reads the same JSON files from its assets, so one set of translations serves
// every part of the project.

#include <string>
#include <string_view>
#include <vector>

namespace i18n {

struct LanguageInfo {
    const char* code;        // "es-419"
    const char* nativeName;  // "Español (Latinoamérica)"
};

// Every language with a table (English first).
const std::vector<LanguageInfo>& Languages();

// Selects a language by code ("auto" or an unknown code = the system language, else English).
void SetLanguage(std::string_view code);
// The code in use ("en" when none was found).
const std::string& Language();
// The player's choice as saved ("auto" or a code), and saving a new one (takes effect at the next start).
std::string SavedChoice();
void SaveChoice(std::string_view code);
// The device language handed in by the Android app (its locale), used for "auto" there.
void SetDetectedLanguage(std::string_view code);
// The language of the device, as one of the codes above.
std::string DetectSystemLanguage();

// The translation of an English string; the same pointer when there is none. Safe to call every frame.
const char* Tr(const char* english);
std::string TrString(std::string_view english);
// A label with an ImGui ID suffix ("Player 1##Tilt"): translates the part before "##" and keeps the
// suffix as it was, so widget IDs do not change with the language.
std::string TrLabel(const char* label);

// The text in capital letters, including accented and Cyrillic letters (std::toupper only does ASCII).
std::string Upper(std::string_view text);

// Characters used by the chosen language's strings (UTF-8 code points), for building font atlases
// that only hold what is needed. Always includes printable ASCII.
std::vector<unsigned int> UsedCodepoints();

// Characters in the languages' own names (日本語, 한국어, ...): the Language list must always be readable.
std::vector<unsigned int> NameCodepoints();

// True for languages that need a CJK font (ja, ko, zh-Hans).
bool NeedsCjkFont();

}  // namespace i18n

#define TR(text) ::i18n::Tr(text)
// Marks a string for the extractor without translating it where it is written (static tables,
// translated later when drawn).
#define TRN(text) text
