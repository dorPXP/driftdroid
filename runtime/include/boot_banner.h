#pragma once

// The game disc's own Wii Menu banner (opening.bnr), played while the game starts in place of the
// plain "DriftDroid" splash. This is the animation the Wii's Disc Channel shows for the game: the
// banner's intro once, then its loop.
//
// Two halves. This header and boot_banner.cpp are plain C++ with no renderer or platform
// dependencies: they unpack the banner, decode its textures and evaluate the layout into
// screen-ready quads, so runtime/tests/boot_banner_tests.cpp can check them on a PC.
// boot_banner_draw.cpp loads the banner from the game data and draws those quads through ImGui.
//
// Only what Wii banners use is supported: picture panes whose materials use NW4R's default
// texture setup (one texture, interpolated between the material's black and white colors, then
// tinted by the vertex colors), pane movement / scale / rotation / size, vertex colors, pane alpha
// and visibility animations. Anything else is listed in Banner::warnings and drawn as close as
// that allows.

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

struct ImDrawList;

namespace BootBanner {

// A decoded texture, already combined with its material's colors and, for repeating or mirrored
// textures, tiled out over the range the pane shows.
struct Image {
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> rgba;
};

// One animation key. Hermite curves use all three; step curves (visibility) only frame and value.
struct CurveKey {
    float frame;
    float value;
    float slope;
};

// NW4R's curve evaluation (GetHermiteCurveValue / GetStepCurveValue): the first and last values
// hold outside the keys, and two keys on one frame make a jump that takes the later value there.
float CurveValue(float frame, const std::vector<CurveKey>& keys, bool step);

// One pane of the banner as two triangles, corners in the order top-left, top-right,
// bottom-right, bottom-left. Positions are layout units with y up and the layout's centre at 0.
struct Quad {
    int image = -1;  // index into Banner::Images(), or -1 for a flat-coloured quad
    float x[4];
    float y[4];
    float u[4];
    float v[4];
    uint8_t rgba[4][4];  // per-corner tint, alpha included
};

class Banner {
public:
    // Unpacks an opening.bnr (or the banner.bin inside one). False with `error` set when the file
    // is not a banner this player can show.
    //
    // Banners keep their per-language panes (a logo with or without katakana, say) in layout groups
    // named after the language, "ENG", "JPN", "GER", "FRA", "SPA", "ITA" or "NED", and the Wii
    // Menu shows only the console language's group. `language` is that code; a banner without it
    // falls back to "ENG", then to its first language group.
    bool Load(const std::vector<uint8_t>& file, std::string& error, const std::string& language = "ENG");

    float Width() const { return m_width; }
    float Height() const { return m_height; }

    // The part of the layout to fill the screen with, in layout units. The Wii Menu covers the
    // bottom of a channel's screen with its buttons, so banners keep their artwork above that and
    // leave the strip underneath bare; shown whole, that bare strip read as the banner being cut
    // off. The frame runs from the lowest artwork to the top of the full-height background.
    // FrameWidth is the background's width: the view must not get wider than that.
    float FrameCenterY() const { return m_frameCenterY; }
    float FrameHeight() const { return m_frameHeight; }
    float FrameWidth() const { return m_frameWidth; }

    // Frames of the intro (played once) and of the loop that follows; 0 when absent.
    float StartFrames() const { return m_start.frames; }
    float LoopFrames() const { return m_loop.frames; }

    // The quads to draw for `frame` (60 per second) of the intro-then-loop timeline, back to front.
    // Fully transparent panes are left out.
    void Evaluate(double frame, std::vector<Quad>& out) const;

    const std::vector<Image>& Images() const { return m_images; }
    // The drawing side uploads each image once and then frees its pixels here.
    void ReleasePixels(int image);

    // Unsupported features met while loading, for the log.
    const std::vector<std::string>& Warnings() const { return m_warnings; }

private:
    struct Material {
        int16_t black[4] = {0, 0, 0, 0};          // TEV colour register 0
        int16_t white[4] = {255, 255, 255, 255};  // TEV colour register 1
        int texture = -1;                         // index into m_textures
        uint8_t wrapS = 0;
        uint8_t wrapT = 0;
        bool hasTexMatrix = false;
        float texMatrix[2][3] = {{1, 0, 0}, {0, 1, 0}};
        uint8_t texCoordSet = 0;  // which of the pane's UV sets feeds the texture
        bool hasChanCtrl = false;
        uint8_t colorSrc = 1;  // GX_SRC_VTX
        uint8_t alphaSrc = 1;
        uint8_t matColor[4] = {255, 255, 255, 255};
    };

    struct Pane {
        std::string name;
        int parent = -1;
        std::vector<int> children;
        bool visible = true;
        bool otherLanguage = false;  // in another language's group: never drawn
        bool influencedAlpha = false;
        uint8_t basePosition = 4;
        uint8_t alpha = 255;
        float translate[3] = {0, 0, 0};
        float rotate[3] = {0, 0, 0};
        float scale[2] = {1, 1};
        float size[2] = {0, 0};
        // Picture panes only.
        bool picture = false;
        int material = -1;
        uint8_t vertexColors[4][4] = {
            {255, 255, 255, 255}, {255, 255, 255, 255}, {255, 255, 255, 255}, {255, 255, 255, 255}};  // LT, RT, LB, RB as stored
        std::vector<float> texCoords;  // 8 floats per set: LT, RT, LB, RB
        int image = -1;                // baked image for this pane; -1 flat colour, -2 not drawable
        float imageUv[4][2] = {};      // LT, RT, RB, LB into that image
    };

    struct Curve {
        int pane = -1;
        char kind = 0;  // 'S' SRT, 'C' colour/alpha, 'V' visibility
        uint8_t target = 0;
        bool step = false;
        std::vector<CurveKey> keys;  // step keys keep their value in `value`
    };

    struct Animation {
        float frames = 0;
        std::vector<Curve> curves;
    };

    struct Texture {
        std::string name;
        Image image;  // undecorated texels
    };

    friend struct Loader;  // the file parsers, in boot_banner.cpp
    void BakeImages();
    void ComputeFrame();
    void Apply(const Animation& anim, float frame, std::vector<Pane>& panes) const;
    void Emit(const std::vector<Pane>& panes, std::vector<Quad>& out) const;
    void Warn(std::string message);

    float m_width = 0;
    float m_height = 0;
    float m_frameCenterY = 0;
    float m_frameHeight = 0;
    float m_frameWidth = 0;
    std::vector<std::pair<std::string, std::vector<std::string>>> m_languageGroups;  // code, pane names
    std::vector<Material> m_materials;
    std::vector<Pane> m_panes;  // tree order: a parent always comes before its children
    std::vector<Texture> m_textures;
    std::vector<Image> m_images;
    Animation m_start;
    Animation m_loop;
    std::vector<std::string> m_warnings;
};

// --- Drawing (boot_banner_draw.cpp) ---------------------------------------------------------------

// Draws the game's banner over the rectangle (x, y, width, height), scaled to fill its height and
// centred. The first call loads it from the game data, which takes a moment; later calls only
// draw. The animation clock starts at the first call and runs on wall time, so the launcher's
// splash and the in-game startup cover show one continuous animation. False when there is no
// banner to show (no game data yet, a missing or unreadable opening.bnr), and the caller draws
// its own splash instead.
bool Draw(ImDrawList* drawList, float x, float y, float width, float height);

// The startup cover is gone for good: frees the banner's memory. Draw() returns false afterwards.
void Finish();

}  // namespace BootBanner
