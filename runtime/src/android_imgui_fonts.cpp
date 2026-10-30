// The phone's in-game menus: a real font instead of ImGui's built-in one (which has no accents,
// Cyrillic or CJK), taken from the phone's own system fonts so nothing needs bundling. The characters
// are only the ones the chosen language uses (i18n::UsedCodepoints) plus the language names.
#if defined(__ANDROID__)

#include <imgui.h>

#include <cstdio>
#include <string>
#include <sys/stat.h>

#include "aurora/aurora.h"
#include "i18n.h"
#include "runtime_log.h"

namespace {

bool FileExists(const char* path) {
    struct stat info {};
    return ::stat(path, &info) == 0 && info.st_size > 0;
}

const char* FirstExisting(const char* const* candidates, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        if (FileExists(candidates[i])) {
            return candidates[i];
        }
    }
    return nullptr;
}

}  // namespace

void AndroidInitFonts(const AuroraWindowSize*) {
    ImGuiIO& io = ImGui::GetIO();
    i18n::SetLanguage(i18n::SavedChoice());

    static ImVector<ImWchar> glyphRanges;  // everything the chosen language needs
    static ImVector<ImWchar> nameRanges;   // only the characters of the languages' own names
    {
        ImFontGlyphRangesBuilder builder;
        builder.AddRanges(io.Fonts->GetGlyphRangesDefault());
        for (const unsigned int codepoint : i18n::UsedCodepoints()) {
            builder.AddChar(static_cast<ImWchar>(codepoint));
        }
        for (const unsigned int codepoint : i18n::NameCodepoints()) {
            builder.AddChar(static_cast<ImWchar>(codepoint));
        }
        builder.BuildRanges(&glyphRanges);
        ImFontGlyphRangesBuilder names;
        for (const unsigned int codepoint : i18n::NameCodepoints()) {
            names.AddChar(static_cast<ImWchar>(codepoint));
        }
        names.BuildRanges(&nameRanges);
    }

    // The menu was laid out for ImGui's 13 px font scaled by 2.4 (aurora imgui.cpp); a TTF baked at that
    // size and drawn at scale 1 keeps every measurement the same but renders sharp.
    constexpr float kBakePx = 13.0f * 2.4f;
    static const char* const kLatin[] = {
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
    };
    const char* base = FirstExisting(kLatin, sizeof(kLatin) / sizeof(kLatin[0]));
    if (base == nullptr) {
        RT_LOG(RT_TAG_RUNTIME) << "no system font found for the menus; keeping ImGui's built-in font" << std::endl;
        return;
    }
    ImFontConfig config;
    config.OversampleH = 2;
    config.OversampleV = 1;
    ImFont* font = io.Fonts->AddFontFromFileTTF(base, kBakePx, &config, glyphRanges.Data);
    if (font == nullptr) {
        return;
    }

    // CJK: a font per script. A TTC holds several (Noto Sans CJK: Japanese 0, Korean 1, Simplified
    // Chinese 2); the language's own entry is merged in for its full text, and for the other two
    // scripts only the characters of the language names are asked for.
    struct Cjk {
        const char* language;
        int ttcIndex;
        const char* const* singles;
        size_t singleCount;
    };
    static const char* const kJapanese[] = {"/system/fonts/NotoSansJP-Regular.otf", "/system/fonts/NotoSansCJKjp-Regular.otf",
                                            "/system/fonts/SourceHanSansJP-Regular.otf"};
    static const char* const kKorean[] = {"/system/fonts/NotoSansKR-Regular.otf", "/system/fonts/NotoSansCJKkr-Regular.otf",
                                          "/system/fonts/SourceHanSansKR-Regular.otf"};
    static const char* const kChinese[] = {"/system/fonts/NotoSansSC-Regular.otf", "/system/fonts/NotoSansCJKsc-Regular.otf",
                                           "/system/fonts/SourceHanSansCN-Regular.otf", "/system/fonts/HwChinese-Medium.ttf",
                                           "/system/fonts/DroidSansFallbackBBK.ttf", "/system/fonts/DroidSansFallback.ttf",
                                           "/system/fonts/DroidSansFallbackFull.ttf"};
    const Cjk scripts[] = {
        {"ja", 0, kJapanese, sizeof(kJapanese) / sizeof(kJapanese[0])},
        {"ko", 1, kKorean, sizeof(kKorean) / sizeof(kKorean[0])},
        {"zh-Hans", 2, kChinese, sizeof(kChinese) / sizeof(kChinese[0])},
    };
    static const char* const kCollection[] = {"/system/fonts/NotoSansCJK-Regular.ttc", "/system/fonts/NotoSerifCJK-Regular.ttc"};
    const char* collection = FirstExisting(kCollection, sizeof(kCollection) / sizeof(kCollection[0]));
    const std::string& language = i18n::Language();
    // The chosen language's script first, so its glyph forms win where scripts share characters.
    for (int pass = 0; pass < 2; ++pass) {
        for (const Cjk& script : scripts) {
            const bool active = language == script.language;
            if (active != (pass == 0)) {
                continue;
            }
            const char* file = collection != nullptr ? collection : FirstExisting(script.singles, script.singleCount);
            if (file == nullptr) {
                continue;
            }
            ImFontConfig merge;
            merge.MergeMode = true;
            merge.OversampleH = 1;
            merge.OversampleV = 1;
            merge.FontNo = collection != nullptr ? script.ttcIndex : 0;
            io.Fonts->AddFontFromFileTTF(file, kBakePx, &merge, active ? glyphRanges.Data : nameRanges.Data);
        }
    }
    io.FontGlobalScale = 1.0f;
}

#endif  // __ANDROID__
