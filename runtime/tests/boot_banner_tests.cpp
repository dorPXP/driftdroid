// PC checks for the Wii banner player (boot_banner.cpp). Needs a game's opening.bnr, which is not
// redistributable, so the banner tests take its path; the curve tests run without one.
//
//   g++ -std=c++20 -O2 -Iruntime/include runtime/tests/boot_banner_tests.cpp runtime/src/boot_banner.cpp
//       -o boot_banner_tests
//   ./boot_banner_tests [DATA/files/opening.bnr [frames-dir]]
//
// With frames-dir it also software-renders a few 1280x720 frames there as .ppm, the way the
// Switch draws them (filtered, clamped sampling and plain alpha blending), to look at.

#include "boot_banner.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

static void Require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

static bool Near(float a, float b) { return std::fabs(a - b) < 1e-4f; }

static void TestCurves() {
    using BootBanner::CurveKey;
    using BootBanner::CurveValue;
    const std::vector<CurveKey> ramp = {{0, 0, 0}, {10, 100, 0}};
    Require(Near(CurveValue(-5, ramp, false), 0), "value holds before the first key");
    Require(Near(CurveValue(15, ramp, false), 100), "value holds after the last key");
    Require(Near(CurveValue(5, ramp, false), 50), "flat-slope Hermite is symmetric at its midpoint");
    Require(CurveValue(2.5f, ramp, false) < 25, "flat-slope Hermite eases in");
    const std::vector<CurveKey> linear = {{0, 0, 10}, {10, 100, 10}};
    Require(Near(CurveValue(2.5f, linear, false), 25), "slopes matching the chord give a straight line");
    const std::vector<CurveKey> jump = {{0, 0, 0}, {10, 0, 0}, {10, 255, 0}, {20, 255, 0}};
    Require(Near(CurveValue(10, jump, false), 255), "two keys on one frame jump to the later value");
    Require(Near(CurveValue(9.9f, jump, false), 0), "before a jump the earlier value holds");
    const std::vector<CurveKey> steps = {{0, 1, 0}, {30, 0, 0}, {60, 1, 0}};
    Require(Near(CurveValue(29, steps, true), 1), "step keys hold until the next key");
    Require(Near(CurveValue(30, steps, true), 0), "step keys switch on their frame");
    Require(Near(CurveValue(45, steps, true), 0), "step keys do not interpolate");
}

struct Canvas {
    int width = 1280;
    int height = 720;
    std::vector<float> rgb = std::vector<float>(static_cast<size_t>(1280) * 720 * 3, 0.0f);
};

static void Sample(const BootBanner::Image& image, float u, float v, float out[4]) {
    if (image.rgba.empty()) {
        out[0] = out[1] = out[2] = out[3] = 1.0f;
        return;
    }
    const float x = std::clamp(u * image.width - 0.5f, 0.0f, image.width - 1.0f);
    const float y = std::clamp(v * image.height - 0.5f, 0.0f, image.height - 1.0f);
    const int x0 = static_cast<int>(x);
    const int y0 = static_cast<int>(y);
    const int x1 = std::min<int>(x0 + 1, image.width - 1);
    const int y1 = std::min<int>(y0 + 1, image.height - 1);
    const float fx = x - x0;
    const float fy = y - y0;
    for (int c = 0; c < 4; ++c) {
        const auto at = [&](int px, int py) { return image.rgba[(static_cast<size_t>(py) * image.width + px) * 4 + c] / 255.0f; };
        out[c] = (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy;
    }
}

static void Render(const BootBanner::Banner& banner, double frame, Canvas& canvas) {
    std::fill(canvas.rgb.begin(), canvas.rgb.end(), 0.0f);
    std::vector<BootBanner::Quad> quads;
    banner.Evaluate(frame, quads);
    // Same framing as boot_banner_draw.cpp.
    const float scale = std::max(canvas.height / banner.FrameHeight(), canvas.width / banner.FrameWidth());
    for (const BootBanner::Quad& q : quads) {
        static const BootBanner::Image kWhite{};
        const BootBanner::Image& image = q.image >= 0 ? banner.Images()[q.image] : kWhite;
        float sx[4];
        float sy[4];
        for (int k = 0; k < 4; ++k) {
            sx[k] = canvas.width / 2.0f + q.x[k] * scale;
            sy[k] = canvas.height / 2.0f - (q.y[k] - banner.FrameCenterY()) * scale;
        }
        for (const auto& tri : {std::array<int, 3>{0, 1, 2}, std::array<int, 3>{0, 2, 3}}) {
            const int a = tri[0], b = tri[1], c = tri[2];
            const float area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
            if (std::fabs(area) < 1e-6f) continue;
            const int minX = std::max(0, static_cast<int>(std::floor(std::min({sx[a], sx[b], sx[c]}))));
            const int maxX = std::min(canvas.width - 1, static_cast<int>(std::ceil(std::max({sx[a], sx[b], sx[c]}))));
            const int minY = std::max(0, static_cast<int>(std::floor(std::min({sy[a], sy[b], sy[c]}))));
            const int maxY = std::min(canvas.height - 1, static_cast<int>(std::ceil(std::max({sy[a], sy[b], sy[c]}))));
            for (int y = minY; y <= maxY; ++y) {
                for (int x = minX; x <= maxX; ++x) {
                    const float px = x + 0.5f, py = y + 0.5f;
                    const float wa = ((sx[b] - px) * (sy[c] - py) - (sx[c] - px) * (sy[b] - py)) / area;
                    const float wb = ((sx[c] - px) * (sy[a] - py) - (sx[a] - px) * (sy[c] - py)) / area;
                    const float wc = 1.0f - wa - wb;
                    if (wa < 0 || wb < 0 || wc < 0) continue;
                    float texel[4];
                    Sample(image, wa * q.u[a] + wb * q.u[b] + wc * q.u[c], wa * q.v[a] + wb * q.v[b] + wc * q.v[c], texel);
                    float color[4];
                    for (int ch = 0; ch < 4; ++ch) {
                        color[ch] = texel[ch] * (wa * q.rgba[a][ch] + wb * q.rgba[b][ch] + wc * q.rgba[c][ch]) / 255.0f;
                    }
                    float* dst = &canvas.rgb[(static_cast<size_t>(y) * canvas.width + x) * 3];
                    for (int ch = 0; ch < 3; ++ch) dst[ch] = color[ch] * color[3] + dst[ch] * (1 - color[3]);
                }
            }
        }
    }
}

static void WritePpm(const Canvas& canvas, const std::string& path) {
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << canvas.width << " " << canvas.height << "\n255\n";
    for (const float v : canvas.rgb) {
        const int byte = static_cast<int>(std::clamp(v * 255.0f + 0.5f, 0.0f, 255.0f));
        out.put(static_cast<char>(static_cast<unsigned char>(byte)));
    }
    Require(out.good(), "could not write " + path);
}

static void TestBanner(const std::string& path, const std::string& framesDir) {
    std::ifstream in(path, std::ios::binary);
    Require(in.good(), "cannot open " + path);
    const std::vector<uint8_t> file{std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};

    BootBanner::Banner banner;
    std::string error;
    Require(banner.Load(file, error), "load failed: " + error);
    for (const std::string& warning : banner.Warnings()) std::cout << "warning: " << warning << "\n";
    std::cout << "layout " << banner.Width() << "x" << banner.Height() << ", intro " << banner.StartFrames()
              << " frames, loop " << banner.LoopFrames() << " frames, " << banner.Images().size() << " images\n";
    std::cout << "frame: " << banner.FrameHeight() << " tall around y " << banner.FrameCenterY() << ", "
              << banner.FrameWidth() << " wide\n";
    Require(banner.Width() > 0 && banner.Height() > 0, "layout has a size");
    Require(banner.FrameHeight() >= banner.Height() * 0.5f && banner.FrameHeight() <= banner.Height(),
            "the frame is within the layout's height");
    Require(banner.FrameCenterY() - banner.FrameHeight() / 2 >= -banner.Height() / 2 - 0.01f &&
                banner.FrameCenterY() + banner.FrameHeight() / 2 <= banner.Height() / 2 + 0.01f,
            "the frame stays inside the background");
    Require(!banner.Images().empty(), "banner has images");
    for (const BootBanner::Image& image : banner.Images()) {
        Require(image.rgba.size() == static_cast<size_t>(image.width) * image.height * 4, "image has all its pixels");
    }

    std::vector<BootBanner::Quad> quads;
    const double total = banner.StartFrames() + banner.LoopFrames() * 2;
    for (double frame = 0; frame <= total; frame += 7.5) {
        banner.Evaluate(frame, quads);
        Require(!quads.empty(), "every frame draws something (frame " + std::to_string(frame) + ")");
        for (const BootBanner::Quad& q : quads) {
            Require(q.image < static_cast<int>(banner.Images().size()), "quad image index is valid");
            for (int k = 0; k < 4; ++k) {
                Require(std::isfinite(q.x[k]) && std::isfinite(q.y[k]) && std::isfinite(q.u[k]) && std::isfinite(q.v[k]),
                        "quad corners are finite");
            }
        }
    }
    if (banner.LoopFrames() > 0) {
        // The loop wraps: one loop length later the picture is the same.
        std::vector<BootBanner::Quad> later;
        const double frame = banner.StartFrames() + 123;
        banner.Evaluate(frame, quads);
        banner.Evaluate(frame + banner.LoopFrames(), later);
        Require(quads.size() == later.size(), "loop repeats the same panes");
        for (size_t i = 0; i < quads.size(); ++i) {
            Require(Near(quads[i].x[0], later[i].x[0]) && quads[i].rgba[0][3] == later[i].rgba[0][3], "loop repeats exactly");
        }
    }

    {
        // A language the banner has no group for shows the English panes, exactly as "ENG" does.
        BootBanner::Banner korean;
        Require(korean.Load(file, error, "KOR"), "load with an unknown language failed: " + error);
        std::vector<BootBanner::Quad> english;
        std::vector<BootBanner::Quad> fallback;
        banner.Evaluate(300, english);
        korean.Evaluate(300, fallback);
        Require(english.size() == fallback.size() && korean.Images().size() == banner.Images().size(),
                "an unknown language falls back to English");
    }

    if (!framesDir.empty()) {
        Canvas canvas;
        for (const double frame : {0.0, 20.0, 40.0, 69.0, 100.0, 250.0, 450.0, 700.0}) {
            Render(banner, frame, canvas);
            const std::string out = framesDir + "/frame_" + std::to_string(static_cast<int>(frame)) + ".ppm";
            WritePpm(canvas, out);
            banner.Evaluate(frame, quads);
            std::cout << "wrote " << out << " (" << quads.size() << " quads)\n";
        }
    }
}

int main(int argc, char** argv) {
    try {
        TestCurves();
        if (argc > 1) TestBanner(argv[1], argc > 2 ? argv[2] : "");
        std::cout << "boot banner tests passed\n";
        return 0;
    } catch (const std::exception& ex) {
        std::cerr << "FAILED: " << ex.what() << "\n";
        return 1;
    }
}
