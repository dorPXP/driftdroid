// Draws the game's Wii Menu banner through ImGui for the Switch launcher's splash and the in-game
// startup cover. The banner itself (unpacking, decoding, layout) is boot_banner.cpp.

#include "boot_banner.h"

#include "i18n.h"
#include "runtime_config.h"
#include "runtime_log.h"

#include <aurora/imgui.h>

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iterator>

namespace BootBanner {
namespace {

using Clock = std::chrono::steady_clock;

enum class State { NotLoaded, Ready, Unavailable, Finished };

State g_state = State::NotLoaded;
Banner g_banner;
std::vector<ImTextureID> g_textures;  // one per banner image, uploaded the first time it shows
std::vector<Quad> g_quads;
double g_frame = 0.0;
Clock::time_point g_firstDraw{};
Clock::time_point g_lastDraw{};

// The banner's language group for the app's own language (Wii banners only carry the Wii Menu's).
std::string BannerLanguage() {
    const std::string& code = i18n::Language();
    if (code == "ja") return "JPN";
    if (code == "fr") return "FRA";
    if (code == "de") return "GER";
    if (code.rfind("es", 0) == 0) return "SPA";
    if (code == "it") return "ITA";
    if (code == "nl") return "NED";
    return "ENG";
}

void LoadOnce() {
    if (g_state != State::NotLoaded) {
        return;
    }
    g_state = State::Unavailable;
    const auto started = Clock::now();
    const std::filesystem::path root = RuntimeConfigFile::ResolvedDvdRoot();
    if (root.empty()) {
        return;
    }
    const std::filesystem::path path = root / "files" / "opening.bnr";
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        RT_LOG(RT_TAG_RUNTIME) << "boot banner: no " << path.string() << ", showing the plain splash" << std::endl;
        return;
    }
    const std::vector<uint8_t> file{std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
    std::string error;
    if (!g_banner.Load(file, error, BannerLanguage())) {
        RT_LOG(RT_TAG_RUNTIME) << "boot banner: " << path.string() << " could not be shown (" << error
                               << "), showing the plain splash" << std::endl;
        return;
    }
    for (const std::string& warning : g_banner.Warnings()) {
        RT_LOG(RT_TAG_RUNTIME) << "boot banner: " << warning << std::endl;
    }
    g_textures.assign(g_banner.Images().size(), ImTextureID{});
    g_state = State::Ready;
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count();
    RT_LOG(RT_TAG_RUNTIME) << "boot banner: loaded in " << ms << " ms (" << g_banner.Images().size()
                           << " images, intro " << g_banner.StartFrames() << " + loop " << g_banner.LoopFrames()
                           << " frames, language " << BannerLanguage() << ")" << std::endl;
}

}  // namespace

bool Draw(ImDrawList* drawList, float x, float y, float width, float height) {
    LoadOnce();
    if (g_state != State::Ready || drawList == nullptr || width <= 0.0f || height <= 0.0f) {
        return false;
    }
    // The clock runs on wall time, but moves at most a few frames per draw: when the game holds
    // the screen for a while (no frame presented while it loads), the animation picks up where it
    // stopped instead of jumping ahead.
    const auto now = Clock::now();
    if (g_lastDraw != Clock::time_point{}) {
        const double gap = std::chrono::duration<double>(now - g_lastDraw).count();
        if (gap > 0.25) {
            RT_LOG(RT_TAG_RUNTIME) << "boot banner: held still for " << static_cast<int>(gap * 1000.0)
                                   << " ms at frame " << static_cast<int>(g_frame) << ", "
                                   << std::chrono::duration_cast<std::chrono::milliseconds>(now - g_firstDraw).count()
                                   << " ms after it started" << std::endl;
        }
        g_frame += std::min(gap, 0.1) * 60.0;
    } else {
        g_firstDraw = now;
    }
    g_lastDraw = now;
    g_banner.Evaluate(g_frame, g_quads);

    // The part of the banner the Wii Menu left uncovered fills the height, centred; zoomed further
    // only if the screen is wider than the banner's background.
    const float scale = std::max(height / g_banner.FrameHeight(), width / g_banner.FrameWidth());
    const float centerX = x + width * 0.5f;
    const float centerY = y + height * 0.5f;
    const float frameCenterY = g_banner.FrameCenterY();
    const ImVec2 whitePixel = ImGui::GetFontTexUvWhitePixel();
    const ImTextureID fontTexture = ImGui::GetIO().Fonts->TexID;
    drawList->PushClipRect(ImVec2(x, y), ImVec2(x + width, y + height), true);
    drawList->AddRectFilled(ImVec2(x, y), ImVec2(x + width, y + height), IM_COL32(0, 0, 0, 255));
    for (const Quad& quad : g_quads) {
        ImTextureID texture = fontTexture;
        if (quad.image >= 0) {
            ImTextureID& uploaded = g_textures[quad.image];
            if (!uploaded) {
                const Image& image = g_banner.Images()[quad.image];
                uploaded = aurora_imgui_add_texture(image.width, image.height, image.rgba.data());
                g_banner.ReleasePixels(quad.image);
            }
            texture = uploaded;
        }
        drawList->PushTextureID(texture);
        drawList->PrimReserve(6, 4);
        const ImDrawIdx base = static_cast<ImDrawIdx>(drawList->_VtxCurrentIdx);
        for (const int corner : {0, 1, 2, 0, 2, 3}) {
            drawList->PrimWriteIdx(static_cast<ImDrawIdx>(base + corner));
        }
        for (int k = 0; k < 4; ++k) {
            const ImVec2 position(centerX + quad.x[k] * scale, centerY - (quad.y[k] - frameCenterY) * scale);
            const ImVec2 uv = quad.image >= 0 ? ImVec2(quad.u[k], quad.v[k]) : whitePixel;
            drawList->PrimWriteVtx(position, uv,
                                   IM_COL32(quad.rgba[k][0], quad.rgba[k][1], quad.rgba[k][2], quad.rgba[k][3]));
        }
        drawList->PopTextureID();
    }
    drawList->PopClipRect();
    return true;
}

void Finish() {
    if (g_state == State::Finished) {
        return;
    }
    if (g_state == State::Ready) {
        RT_LOG(RT_TAG_RUNTIME) << "boot banner: startup cover lifted at frame " << static_cast<int>(g_frame)
                               << std::endl;
    }
    // The textures stay with the renderer (aurora has no way to free an ImGui texture); only the
    // decoded pixels and layout are let go.
    g_state = State::Finished;
    g_banner = Banner{};
    std::vector<Quad>().swap(g_quads);
}

}  // namespace BootBanner
