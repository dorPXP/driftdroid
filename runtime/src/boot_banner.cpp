// Wii disc banner player: unpacking, texture decoding and layout evaluation. See boot_banner.h.
//
// The layout and animation rules follow NW4R's own (nw4r::lyt Pane::CalculateMtx, Picture::DrawSelf,
// Material::SetupGX, GetHermiteCurveValue), so a banner looks and moves the way the Wii Menu showed
// it. Everything read from the file is bounds-checked: a damaged banner only means the plain splash.

#include "boot_banner.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <map>
#include <stdexcept>
#include <utility>

namespace BootBanner {
namespace {

using Bytes = std::vector<uint8_t>;

[[noreturn]] void Fail(const std::string& message) { throw std::runtime_error(message); }

// Big-endian reads with the bounds check every banner field goes through.
struct View {
    const uint8_t* data = nullptr;
    size_t size = 0;

    View() = default;
    explicit View(const Bytes& bytes) : data(bytes.data()), size(bytes.size()) {}
    View(const uint8_t* d, size_t s) : data(d), size(s) {}

    void Need(size_t offset, size_t count) const {
        if (offset > size || count > size - offset) {
            Fail("banner data is truncated");
        }
    }
    uint8_t U8(size_t offset) const {
        Need(offset, 1);
        return data[offset];
    }
    uint16_t U16(size_t offset) const {
        Need(offset, 2);
        return static_cast<uint16_t>(data[offset] << 8 | data[offset + 1]);
    }
    int16_t S16(size_t offset) const { return static_cast<int16_t>(U16(offset)); }
    uint32_t U32(size_t offset) const {
        Need(offset, 4);
        return static_cast<uint32_t>(data[offset]) << 24 | static_cast<uint32_t>(data[offset + 1]) << 16 |
               static_cast<uint32_t>(data[offset + 2]) << 8 | data[offset + 3];
    }
    float F32(size_t offset) const {
        const uint32_t bits = U32(offset);
        float value;
        std::memcpy(&value, &bits, sizeof(value));
        return std::isfinite(value) ? value : 0.0f;
    }
    // A NUL-padded name of at most `length` bytes.
    std::string Name(size_t offset, size_t length) const {
        Need(offset, length);
        const char* text = reinterpret_cast<const char*>(data + offset);
        return std::string(text, strnlen(text, length));
    }
    std::string CString(size_t offset) const {
        Need(offset, 1);
        const void* end = std::memchr(data + offset, 0, size - offset);
        if (end == nullptr) {
            Fail("banner string is not terminated");
        }
        return std::string(reinterpret_cast<const char*>(data + offset),
                           static_cast<const uint8_t*>(end) - (data + offset));
    }
    View Sub(size_t offset, size_t count) const {
        Need(offset, count);
        return View(data + offset, count);
    }
};

std::string Lower(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return text;
}

bool EndsWith(const std::string& text, const std::string& suffix) {
    return text.size() >= suffix.size() && text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

// --- Containers -------------------------------------------------------------------------------

// Nintendo LZ77 (types 0x10 and 0x11) behind a "LZ77" magic, as banner.bin uses.
Bytes DecompressLz77(View in) {
    constexpr size_t kMaxOutput = 16u << 20;
    if (in.size < 8) {
        Fail("LZ77 header is truncated");
    }
    const uint32_t header = static_cast<uint32_t>(in.U8(4)) | static_cast<uint32_t>(in.U8(5)) << 8 |
                            static_cast<uint32_t>(in.U8(6)) << 16 | static_cast<uint32_t>(in.U8(7)) << 24;
    const uint8_t type = header & 0xFF;
    const size_t outSize = header >> 8;
    if ((type != 0x10 && type != 0x11) || outSize > kMaxOutput) {
        Fail("unsupported LZ77 data");
    }
    Bytes out;
    out.reserve(outSize);
    size_t src = 8;
    while (out.size() < outSize) {
        const uint8_t flags = in.U8(src++);
        for (int bit = 0; bit < 8 && out.size() < outSize; ++bit) {
            if ((flags & (0x80 >> bit)) == 0) {
                out.push_back(in.U8(src++));
                continue;
            }
            const uint8_t b0 = in.U8(src);
            size_t length;
            size_t distance;
            if (type == 0x10) {
                length = (b0 >> 4) + 3;
                distance = ((b0 & 0xF) << 8 | in.U8(src + 1)) + 1;
                src += 2;
            } else if ((b0 >> 4) == 0) {
                length = ((b0 & 0xF) << 4 | in.U8(src + 1) >> 4) + 0x11;
                distance = ((in.U8(src + 1) & 0xF) << 8 | in.U8(src + 2)) + 1;
                src += 3;
            } else if ((b0 >> 4) == 1) {
                length = ((b0 & 0xF) << 12 | in.U8(src + 1) << 4 | in.U8(src + 2) >> 4) + 0x111;
                distance = ((in.U8(src + 2) & 0xF) << 8 | in.U8(src + 3)) + 1;
                src += 4;
            } else {
                length = (b0 >> 4) + 1;
                distance = ((b0 & 0xF) << 8 | in.U8(src + 1)) + 1;
                src += 2;
            }
            if (distance > out.size()) {
                Fail("LZ77 data refers before its start");
            }
            for (size_t i = 0; i < length && out.size() < outSize; ++i) {
                out.push_back(out[out.size() - distance]);
            }
        }
    }
    return out;
}

// banner.bin and icon.bin: an optional IMD5 header, then optionally LZ77.
Bytes Unwrap(View in) {
    if (in.size >= 0x20 && std::memcmp(in.data, "IMD5", 4) == 0) {
        in = in.Sub(0x20, in.size - 0x20);
    }
    if (in.size >= 4 && std::memcmp(in.data, "LZ77", 4) == 0) {
        return DecompressLz77(in);
    }
    return Bytes(in.data, in.data + in.size);
}

constexpr uint32_t kU8Magic = 0x55AA382D;

// A U8 archive's files by lower-case path ("arc/blyt/banner.brlyt").
std::map<std::string, View> ReadU8(View in) {
    if (in.U32(0) != kU8Magic) {
        Fail("not a U8 archive");
    }
    const uint32_t root = in.U32(4);
    const uint32_t count = in.U32(root + 8);
    if (count == 0 || count > 0x10000) {
        Fail("U8 archive has an implausible file count");
    }
    const size_t strings = root + static_cast<size_t>(count) * 12;
    std::map<std::string, View> files;
    std::vector<std::pair<std::string, uint32_t>> dirs;  // path, index past its last entry
    for (uint32_t i = 1; i < count; ++i) {
        while (!dirs.empty() && i >= dirs.back().second) {
            dirs.pop_back();
        }
        const size_t node = root + static_cast<size_t>(i) * 12;
        const bool isDir = in.U8(node) == 1;
        const std::string name = in.CString(strings + (in.U32(node) & 0xFFFFFF));
        const std::string path = dirs.empty() ? name : dirs.back().first + "/" + name;
        if (isDir) {
            dirs.emplace_back(path, in.U32(node + 8));
        } else {
            files[Lower(path)] = in.Sub(in.U32(node + 4), in.U32(node + 8));
        }
    }
    return files;
}

const View* FindFile(const std::map<std::string, View>& files, const std::string& suffix) {
    for (const auto& [path, view] : files) {
        if (EndsWith(path, suffix)) {
            return &view;
        }
    }
    return nullptr;
}

// --- Textures ---------------------------------------------------------------------------------

uint8_t Expand3(uint32_t v) { return static_cast<uint8_t>(v << 5 | v << 2 | v >> 1); }
uint8_t Expand4(uint32_t v) { return static_cast<uint8_t>(v * 0x11); }
uint8_t Expand5(uint32_t v) { return static_cast<uint8_t>(v << 3 | v >> 2); }
uint8_t Expand6(uint32_t v) { return static_cast<uint8_t>(v << 2 | v >> 4); }

void Rgb565(uint16_t c, uint8_t* out) {
    out[0] = Expand5(c >> 11);
    out[1] = Expand6((c >> 5) & 0x3F);
    out[2] = Expand5(c & 0x1F);
    out[3] = 255;
}

void Rgb5a3(uint16_t c, uint8_t* out) {
    if (c & 0x8000) {
        out[0] = Expand5((c >> 10) & 0x1F);
        out[1] = Expand5((c >> 5) & 0x1F);
        out[2] = Expand5(c & 0x1F);
        out[3] = 255;
    } else {
        out[0] = Expand4((c >> 8) & 0xF);
        out[1] = Expand4((c >> 4) & 0xF);
        out[2] = Expand4(c & 0xF);
        out[3] = Expand3((c >> 12) & 0x7);
    }
}

void Ia8(uint16_t c, uint8_t* out) {
    out[0] = out[1] = out[2] = c & 0xFF;
    out[3] = c >> 8;
}

// One TPL image (the first) decoded to RGBA8. GX texel formats are tiled in blocks; within a block
// texels run left to right, top to bottom.
Image DecodeTpl(View tpl) {
    if (tpl.U32(0) != 0x0020AF30 || tpl.U32(4) == 0) {
        Fail("not a TPL texture");
    }
    const uint32_t table = tpl.U32(8);
    const uint32_t header = tpl.U32(table);
    const uint32_t paletteHeader = tpl.U32(table + 4);
    const uint32_t height = tpl.U16(header);
    const uint32_t width = tpl.U16(header + 2);
    const uint32_t format = tpl.U32(header + 4);
    const uint32_t dataOffset = tpl.U32(header + 8);
    if (width == 0 || height == 0 || width > 1024 || height > 1024) {
        Fail("TPL texture has an implausible size");
    }

    // Colour-indexed formats look their texels up in the palette.
    std::vector<std::array<uint8_t, 4>> palette;
    if (format == 8 || format == 9 || format == 10) {
        if (paletteHeader == 0) {
            Fail("indexed TPL texture has no palette");
        }
        const uint32_t entries = tpl.U16(paletteHeader);
        const uint32_t paletteFormat = tpl.U32(paletteHeader + 4);
        const uint32_t paletteData = tpl.U32(paletteHeader + 8);
        palette.resize(entries);
        for (uint32_t i = 0; i < entries; ++i) {
            const uint16_t c = tpl.U16(paletteData + i * 2);
            if (paletteFormat == 0) {
                Ia8(c, palette[i].data());
            } else if (paletteFormat == 1) {
                Rgb565(c, palette[i].data());
            } else {
                Rgb5a3(c, palette[i].data());
            }
        }
    }
    const auto paletteEntry = [&](uint32_t index, uint8_t* out) {
        static constexpr std::array<uint8_t, 4> kMissing = {0, 0, 0, 0};
        const auto& c = index < palette.size() ? palette[index] : kMissing;
        std::memcpy(out, c.data(), 4);
    };

    uint32_t blockW;
    uint32_t blockH;
    uint32_t blockBytes;
    switch (format) {
    case 0: blockW = 8, blockH = 8, blockBytes = 32; break;   // I4
    case 1: blockW = 8, blockH = 4, blockBytes = 32; break;   // I8
    case 2: blockW = 8, blockH = 4, blockBytes = 32; break;   // IA4
    case 3: blockW = 4, blockH = 4, blockBytes = 32; break;   // IA8
    case 4: blockW = 4, blockH = 4, blockBytes = 32; break;   // RGB565
    case 5: blockW = 4, blockH = 4, blockBytes = 32; break;   // RGB5A3
    case 6: blockW = 4, blockH = 4, blockBytes = 64; break;   // RGBA8
    case 8: blockW = 8, blockH = 8, blockBytes = 32; break;   // C4
    case 9: blockW = 8, blockH = 4, blockBytes = 32; break;   // C8
    case 10: blockW = 4, blockH = 4, blockBytes = 32; break;  // C14X2
    case 14: blockW = 8, blockH = 8, blockBytes = 32; break;  // CMPR
    default: Fail("unsupported TPL texel format " + std::to_string(format));
    }
    const uint32_t blocksX = (width + blockW - 1) / blockW;
    const uint32_t blocksY = (height + blockH - 1) / blockH;
    const View texels = tpl.Sub(dataOffset, static_cast<size_t>(blocksX) * blocksY * blockBytes);

    Image image;
    image.width = width;
    image.height = height;
    image.rgba.assign(static_cast<size_t>(width) * height * 4, 0);
    const auto put = [&](uint32_t x, uint32_t y, const uint8_t* rgba) {
        if (x < width && y < height) {
            std::memcpy(&image.rgba[(static_cast<size_t>(y) * width + x) * 4], rgba, 4);
        }
    };

    for (uint32_t by = 0; by < blocksY; ++by) {
        for (uint32_t bx = 0; bx < blocksX; ++bx) {
            const size_t block = (static_cast<size_t>(by) * blocksX + bx) * blockBytes;
            const uint32_t x0 = bx * blockW;
            const uint32_t y0 = by * blockH;
            uint8_t px[4];
            if (format == 14) {
                // Four DXT1 sub-blocks, in reading order. The hardware blends the two in-between
                // colours 5/8 : 3/8 rather than DXT1's thirds.
                for (uint32_t sub = 0; sub < 4; ++sub) {
                    const size_t s = block + sub * 8;
                    const uint16_t c0 = texels.U16(s);
                    const uint16_t c1 = texels.U16(s + 2);
                    uint8_t colors[4][4];
                    Rgb565(c0, colors[0]);
                    Rgb565(c1, colors[1]);
                    for (int ch = 0; ch < 3; ++ch) {
                        if (c0 > c1) {
                            colors[2][ch] = static_cast<uint8_t>((colors[0][ch] * 5 + colors[1][ch] * 3) >> 3);
                            colors[3][ch] = static_cast<uint8_t>((colors[0][ch] * 3 + colors[1][ch] * 5) >> 3);
                        } else {
                            colors[2][ch] = colors[3][ch] = static_cast<uint8_t>((colors[0][ch] + colors[1][ch]) / 2);
                        }
                    }
                    colors[2][3] = 255;
                    colors[3][3] = c0 > c1 ? 255 : 0;
                    for (uint32_t row = 0; row < 4; ++row) {
                        const uint8_t bits = texels.U8(s + 4 + row);
                        for (uint32_t col = 0; col < 4; ++col) {
                            put(x0 + (sub & 1) * 4 + col, y0 + (sub >> 1) * 4 + row, colors[(bits >> (6 - col * 2)) & 3]);
                        }
                    }
                }
                continue;
            }
            for (uint32_t ty = 0; ty < blockH; ++ty) {
                for (uint32_t tx = 0; tx < blockW; ++tx) {
                    const uint32_t i = ty * blockW + tx;
                    switch (format) {
                    case 0: {
                        const uint8_t b = texels.U8(block + i / 2);
                        px[0] = px[1] = px[2] = px[3] = Expand4((i & 1) ? (b & 0xF) : (b >> 4));
                        break;
                    }
                    case 1: px[0] = px[1] = px[2] = px[3] = texels.U8(block + i); break;
                    case 2: {
                        const uint8_t b = texels.U8(block + i);
                        px[0] = px[1] = px[2] = Expand4(b & 0xF);
                        px[3] = Expand4(b >> 4);
                        break;
                    }
                    case 3: Ia8(texels.U16(block + i * 2), px); break;
                    case 4: Rgb565(texels.U16(block + i * 2), px); break;
                    case 5: Rgb5a3(texels.U16(block + i * 2), px); break;
                    case 6:
                        px[3] = texels.U8(block + i * 2);
                        px[0] = texels.U8(block + i * 2 + 1);
                        px[1] = texels.U8(block + 32 + i * 2);
                        px[2] = texels.U8(block + 32 + i * 2 + 1);
                        break;
                    case 8: {
                        const uint8_t b = texels.U8(block + i / 2);
                        paletteEntry((i & 1) ? (b & 0xF) : (b >> 4), px);
                        break;
                    }
                    case 9: paletteEntry(texels.U8(block + i), px); break;
                    default: paletteEntry(texels.U16(block + i * 2) & 0x3FFF, px); break;
                    }
                    put(x0 + tx, y0 + ty, px);
                }
            }
        }
    }
    return image;
}

// --- Maths ------------------------------------------------------------------------------------

struct Mtx34 {
    float m[3][4] = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}};
};

Mtx34 Mul(const Mtx34& a, const Mtx34& b) {
    Mtx34 r;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 4; ++j) {
            r.m[i][j] = a.m[i][0] * b.m[0][j] + a.m[i][1] * b.m[1][j] + a.m[i][2] * b.m[2][j] + (j == 3 ? a.m[i][3] : 0.0f);
        }
    }
    return r;
}

Mtx34 Rotation(int axis, float degrees) {
    const float rad = degrees * 3.14159265358979f / 180.0f;
    const float c = std::cos(rad);
    const float s = std::sin(rad);
    Mtx34 r;
    const int a = (axis + 1) % 3;
    const int b = (axis + 2) % 3;
    r.m[a][a] = c;
    r.m[a][b] = -s;
    r.m[b][a] = s;
    r.m[b][b] = c;
    return r;
}

uint8_t ToColor(float value) {
    value += 0.5f;
    return static_cast<uint8_t>(std::clamp(value, 0.0f, 255.0f));
}

uint8_t Clamp8(int value) { return static_cast<uint8_t>(std::clamp(value, 0, 255)); }

// GX texture wrap modes: clamp, repeat, mirror.
int32_t Wrap(int32_t i, int32_t n, uint8_t mode) {
    if (mode == 1) {
        return ((i % n) + n) % n;
    }
    if (mode == 2) {
        const int32_t period = n * 2;
        const int32_t m = ((i % period) + period) % period;
        return m < n ? m : period - 1 - m;
    }
    return std::clamp(i, 0, n - 1);
}

// GX enum values the banner data stores.
constexpr uint8_t kSrcReg = 0;           // GX_SRC_REG
constexpr uint8_t kSrcVtx = 1;           // GX_SRC_VTX
constexpr uint8_t kTexGenMtx2x4 = 1;     // GX_TG_MTX2x4
constexpr uint8_t kTexGenSrcTex0 = 4;    // GX_TG_TEX0
constexpr uint8_t kTexMtx0 = 30;         // GX_TEXMTX0, then every 3
constexpr uint8_t kIdentityMtx = 60;     // GX_IDENTITY

using Files = std::map<std::string, View>;

}  // namespace

float CurveValue(float frame, const std::vector<CurveKey>& keys, bool step) {
    const size_t n = keys.size();
    if (n == 0) {
        return 0.0f;
    }
    if (n == 1 || frame <= keys[0].frame) {
        return keys[0].value;
    }
    if (frame >= keys[n - 1].frame) {
        return keys[n - 1].value;
    }
    size_t left = 0;
    size_t right = n - 1;
    while (left != right - 1 && left != right) {
        const size_t center = (left + right) / 2;
        if (step ? frame < keys[center].frame : frame <= keys[center].frame) {
            right = center;
        } else {
            left = center;
        }
    }
    const CurveKey& l = keys[left];
    const CurveKey& r = keys[right];
    if (std::fabs(frame - r.frame) <= 0.001f) {
        if (!step && right < n - 1 && r.frame == keys[right + 1].frame) {
            return keys[right + 1].value;
        }
        return r.value;
    }
    if (step) {
        return l.value;
    }
    if (r.frame <= l.frame) {
        return r.value;
    }
    const float t1 = frame - l.frame;
    const float t2 = 1.0f / (r.frame - l.frame);
    const float t1t1t2 = t1 * t1 * t2;
    const float t1t1t2t2 = t1t1t2 * t2;
    const float t1t1t1t2t2 = t1 * t1t1t2t2;
    const float t1t1t1t2t2t2 = t1t1t1t2t2 * t2;
    return l.value * (2.0f * t1t1t1t2t2t2 - 3.0f * t1t1t2t2 + 1.0f) +
           r.value * (-2.0f * t1t1t1t2t2t2 + 3.0f * t1t1t2t2) +
           l.slope * (t1t1t1t2t2 - 2.0f * t1t1t2 + t1) + r.slope * (t1t1t1t2t2 - t1t1t2);
}

// The BRLYT / BRLAN readers. A friend of Banner so the file layouts stay out of the header.
struct Loader {
    static Banner::Material ReadMaterial(Banner& b, View sec, size_t at) {
        Banner::Material m;
        const std::string name = sec.Name(at, 20);
        for (int c = 0; c < 4; ++c) {
            m.black[c] = sec.S16(at + 20 + c * 2);
            m.white[c] = sec.S16(at + 28 + c * 2);
        }
        const uint32_t flags = sec.U32(at + 60);
        const uint32_t texMaps = flags & 0xF;
        const uint32_t texSrts = (flags >> 4) & 0xF;
        const uint32_t texGens = (flags >> 8) & 0xF;
        const bool swapTable = (flags >> 12) & 1;
        const uint32_t indSrts = (flags >> 13) & 0x3;
        const uint32_t indStages = (flags >> 15) & 0x7;
        const uint32_t tevStages = (flags >> 18) & 0x1F;
        const bool alphaCompare = (flags >> 23) & 1;
        const bool blendMode = (flags >> 24) & 1;
        const bool chanCtrl = (flags >> 25) & 1;
        const bool matColor = (flags >> 27) & 1;

        size_t p = at + 64;
        if (texMaps > 0) {
            m.texture = sec.U16(p);
            m.wrapS = sec.U8(p + 2);
            m.wrapT = sec.U8(p + 3);
        }
        if (texMaps > 1) {
            b.Warn(name + ": only the first of its " + std::to_string(texMaps) + " textures is drawn");
        }
        p += 4 * texMaps;

        std::vector<std::array<float, 5>> srts;  // translate x/y, rotate, scale x/y
        for (uint32_t i = 0; i < texSrts; ++i, p += 20) {
            srts.push_back({sec.F32(p), sec.F32(p + 4), sec.F32(p + 8), sec.F32(p + 12), sec.F32(p + 16)});
        }
        for (uint32_t i = 0; i < texGens; ++i, p += 4) {
            if (i != 0) {
                continue;  // the default setup only samples texture coordinate 0
            }
            const uint8_t type = sec.U8(p);
            const uint8_t src = sec.U8(p + 1);
            const uint8_t mtx = sec.U8(p + 2);
            if (src >= kTexGenSrcTex0 && src < kTexGenSrcTex0 + 8) {
                m.texCoordSet = src - kTexGenSrcTex0;
            } else {
                b.Warn(name + ": texture coordinates generated from source " + std::to_string(src));
            }
            const size_t srt = mtx >= kTexMtx0 ? (mtx - kTexMtx0) / 3 : SIZE_MAX;
            if (type == kTexGenMtx2x4 && mtx != kIdentityMtx && srt < srts.size()) {
                // CalcTextureMtx: scale and rotate about the texture's centre, then translate.
                const auto& s = srts[srt];
                const float rad = s[2] * 3.14159265358979f / 180.0f;
                const float cr = std::cos(rad);
                const float sr = std::sin(rad);
                float a0 = cr * s[3];
                float a1 = -sr * s[4];
                m.texMatrix[0][0] = a0;
                m.texMatrix[0][1] = a1;
                m.texMatrix[0][2] = s[0] + 0.5f - a0 * 0.5f - a1 * 0.5f;
                a0 = sr * s[3];
                a1 = cr * s[4];
                m.texMatrix[1][0] = a0;
                m.texMatrix[1][1] = a1;
                m.texMatrix[1][2] = s[1] + 0.5f - a0 * 0.5f - a1 * 0.5f;
                m.hasTexMatrix = true;
            }
        }
        if (chanCtrl) {
            m.hasChanCtrl = true;
            m.colorSrc = sec.U8(p);
            m.alphaSrc = sec.U8(p + 1);
            p += 4;
        }
        if (matColor) {
            for (int c = 0; c < 4; ++c) {
                m.matColor[c] = sec.U8(p + c);
            }
            p += 4;
        }
        if (swapTable) {
            p += 4;
        }
        if (indSrts > 0 || indStages > 0) {
            b.Warn(name + ": indirect texturing is not drawn");
        }
        p += 20 * indSrts + 4 * indStages;
        if (tevStages > 0) {
            b.Warn(name + ": its own TEV stages are drawn with the default setup");
        }
        p += 16 * tevStages;
        if (alphaCompare) {
            p += 4;
        }
        if (blendMode) {
            // GX_BM_BLEND with GX_BL_SRCALPHA / GX_BL_INVSRCALPHA is plain alpha blending.
            if (sec.U8(p) != 1 || sec.U8(p + 1) != 4 || sec.U8(p + 2) != 5) {
                b.Warn(name + ": its blend mode is drawn as plain alpha blending");
            }
        }
        return m;
    }

    static Banner::Pane ReadPane(Banner& b, View sec, const std::string& magic) {
        Banner::Pane p;
        const uint8_t flags = sec.U8(8);
        p.visible = (flags & 1) != 0;
        p.influencedAlpha = (flags & 2) != 0;
        p.basePosition = sec.U8(9);
        p.alpha = sec.U8(10);
        p.name = sec.Name(12, 16);
        for (int i = 0; i < 3; ++i) {
            p.translate[i] = sec.F32(36 + i * 4);
            p.rotate[i] = sec.F32(48 + i * 4);
        }
        for (int i = 0; i < 2; ++i) {
            p.scale[i] = sec.F32(60 + i * 4);
            p.size[i] = sec.F32(68 + i * 4);
        }
        if (magic == "pic1") {
            p.picture = true;
            for (int i = 0; i < 4; ++i) {
                for (int c = 0; c < 4; ++c) {
                    p.vertexColors[i][c] = sec.U8(76 + i * 4 + c);
                }
            }
            p.material = sec.U16(92);
            const uint8_t sets = sec.U8(94);
            for (size_t i = 0; i < static_cast<size_t>(sets) * 8; ++i) {
                p.texCoords.push_back(sec.F32(96 + i * 4));
            }
        } else if (magic == "txt1") {
            b.Warn("text pane " + p.name + " is not drawn");
        } else if (magic == "wnd1") {
            b.Warn("window pane " + p.name + " is not drawn");
        }
        return p;
    }

    static void ReadLayout(Banner& b, View d, const Files& files) {
        if (d.Name(0, 4) != "RLYT") {
            Fail("not a BRLYT layout");
        }
        const uint16_t sections = d.U16(0xE);
        size_t off = d.U16(0xC);
        std::vector<int> parents;
        int last = -1;
        for (uint16_t s = 0; s < sections; ++s) {
            const std::string magic = d.Name(off, 4);
            const uint32_t size = d.U32(off + 4);
            if (size < 8) {
                Fail("BRLYT section is truncated");
            }
            const View sec = d.Sub(off, size);
            if (magic == "lyt1") {
                if (sec.U8(8) == 0) {
                    b.Warn("the layout's origin is its corner, drawn as if centred");
                }
                b.m_width = sec.F32(12);
                b.m_height = sec.F32(16);
            } else if (magic == "txl1") {
                const uint16_t count = sec.U16(8);
                for (uint16_t i = 0; i < count; ++i) {
                    Banner::Texture texture;
                    texture.name = sec.CString(12 + sec.U32(12 + i * 8));
                    const View* file = FindFile(files, "timg/" + Lower(texture.name));
                    if (file == nullptr) {
                        b.Warn("texture " + texture.name + " is missing");
                    } else {
                        try {
                            texture.image = DecodeTpl(*file);
                        } catch (const std::exception& ex) {
                            b.Warn(texture.name + ": " + ex.what());
                        }
                    }
                    b.m_textures.push_back(std::move(texture));
                }
            } else if (magic == "mat1") {
                const uint16_t count = sec.U16(8);
                for (uint16_t i = 0; i < count; ++i) {
                    b.m_materials.push_back(ReadMaterial(b, sec, sec.U32(12 + i * 4)));
                }
            } else if (magic == "pan1" || magic == "pic1" || magic == "txt1" || magic == "wnd1" || magic == "bnd1") {
                Banner::Pane pane = ReadPane(b, sec, magic);
                pane.parent = parents.empty() ? -1 : parents.back();
                last = static_cast<int>(b.m_panes.size());
                if (pane.parent >= 0) {
                    b.m_panes[pane.parent].children.push_back(last);
                }
                b.m_panes.push_back(std::move(pane));
            } else if (magic == "pas1") {
                if (last < 0) {
                    Fail("BRLYT opens a child list before any pane");
                }
                parents.push_back(last);
            } else if (magic == "pae1") {
                if (parents.empty()) {
                    Fail("BRLYT closes more child lists than it opened");
                }
                last = parents.back();
                parents.pop_back();
            } else if (magic == "grp1") {
                static constexpr const char* kLanguages[] = {"JPN", "ENG", "GER", "FRA", "SPA", "ITA", "NED"};
                const std::string name = sec.Name(8, 16);
                if (std::find(std::begin(kLanguages), std::end(kLanguages), name) != std::end(kLanguages)) {
                    std::vector<std::string> members;
                    const uint16_t count = sec.U16(24);
                    for (uint16_t i = 0; i < count; ++i) {
                        members.push_back(sec.Name(28 + i * 16, 16));
                    }
                    b.m_languageGroups.emplace_back(name, std::move(members));
                }
            }
            // fnl1, grs1, gre1 and usd1 hold nothing to draw.
            off += size;
        }
        if (b.m_panes.empty() || !(b.m_width > 0) || !(b.m_height > 0)) {
            Fail("the banner layout is empty");
        }
    }

    static void ReadAnimation(Banner& b, View d, Banner::Animation& out) {
        if (d.Name(0, 4) != "RLAN") {
            Fail("not a BRLAN animation");
        }
        const uint16_t sections = d.U16(0xE);
        size_t off = d.U16(0xC);
        for (uint16_t s = 0; s < sections; ++s) {
            const std::string magic = d.Name(off, 4);
            const uint32_t size = d.U32(off + 4);
            if (size < 8) {
                Fail("BRLAN section is truncated");
            }
            if (magic == "pai1") {
                const View sec = d.Sub(off, size);
                out.frames = sec.U16(8);
                const uint16_t entries = sec.U16(14);
                const uint32_t table = sec.U32(16);
                for (uint16_t i = 0; i < entries; ++i) {
                    const size_t entry = sec.U32(table + i * 4);
                    const std::string name = sec.Name(entry, 20);
                    const uint8_t kinds = sec.U8(entry + 20);
                    if (sec.U8(entry + 21) != 0) {
                        b.Warn("material animation of " + name + " is not played");
                        continue;
                    }
                    int pane = -1;
                    for (size_t p = 0; p < b.m_panes.size(); ++p) {
                        if (b.m_panes[p].name == name) {
                            pane = static_cast<int>(p);
                            break;
                        }
                    }
                    if (pane < 0) {
                        continue;
                    }
                    for (uint8_t k = 0; k < kinds; ++k) {
                        const size_t info = entry + sec.U32(entry + 24 + k * 4);
                        const std::string kind = sec.Name(info, 4);
                        const char code = kind == "RLPA" ? 'S' : kind == "RLVC" ? 'C' : kind == "RLVI" ? 'V' : 0;
                        if (code == 0) {
                            b.Warn(kind + " animation of " + name + " is not played");
                            continue;
                        }
                        const uint8_t targets = sec.U8(info + 4);
                        for (uint8_t t = 0; t < targets; ++t) {
                            const size_t target = info + sec.U32(info + 8 + t * 4);
                            Banner::Curve curve;
                            curve.pane = pane;
                            curve.kind = code;
                            curve.target = sec.U8(target + 1);
                            curve.step = code == 'V';  // visibility uses step keys, the rest Hermite keys
                            const uint16_t keyCount = sec.U16(target + 4);
                            const size_t keys = target + sec.U32(target + 8);
                            for (uint16_t key = 0; key < keyCount; ++key) {
                                if (curve.step) {
                                    curve.keys.push_back({sec.F32(keys + key * 8),
                                                          static_cast<float>(sec.U16(keys + key * 8 + 4)), 0.0f});
                                } else {
                                    const size_t k12 = keys + key * 12;
                                    curve.keys.push_back({sec.F32(k12), sec.F32(k12 + 4), sec.F32(k12 + 8)});
                                }
                            }
                            if (!curve.keys.empty()) {
                                out.curves.push_back(std::move(curve));
                            }
                        }
                    }
                }
            }
            off += size;
        }
    }
};

bool Banner::Load(const std::vector<uint8_t>& file, std::string& error, const std::string& language) {
    *this = Banner{};
    try {
        const View in(file);
        // A disc's opening.bnr has 0x40 zero bytes, then the IMET header; a channel's has 0x40
        // more in front. The archive holding meta/banner.bin follows that 0x600-byte header.
        size_t archive = SIZE_MAX;
        for (const size_t imet : {size_t{0x40}, size_t{0x80}}) {
            if (in.size >= imet + 4 && std::memcmp(in.data + imet, "IMET", 4) == 0) {
                archive = imet + 0x5C0;
                break;
            }
        }
        Bytes bannerBin;
        if (archive != SIZE_MAX) {
            const Files outer = ReadU8(in.Sub(archive, in.size - archive));
            const View* bin = FindFile(outer, "meta/banner.bin");
            if (bin == nullptr) {
                Fail("the banner has no meta/banner.bin");
            }
            bannerBin = Unwrap(*bin);
        } else {
            bannerBin = Unwrap(in);
        }
        const Files files = ReadU8(View(bannerBin));
        const View* layout = FindFile(files, "blyt/banner.brlyt");
        if (layout == nullptr) {
            layout = FindFile(files, ".brlyt");
        }
        if (layout == nullptr) {
            Fail("the banner has no layout");
        }
        Loader::ReadLayout(*this, *layout, files);
        if (!m_languageGroups.empty()) {
            const auto find = [&](const std::string& code) {
                return std::find_if(m_languageGroups.begin(), m_languageGroups.end(),
                                    [&](const auto& group) { return group.first == code; });
            };
            auto chosen = find(language);
            if (chosen == m_languageGroups.end()) {
                chosen = find("ENG");
            }
            if (chosen == m_languageGroups.end()) {
                chosen = m_languageGroups.begin();
            }
            for (Pane& pane : m_panes) {
                const auto in = [&](const auto& group) {
                    return std::find(group.second.begin(), group.second.end(), pane.name) != group.second.end();
                };
                if (!in(*chosen)) {
                    pane.otherLanguage = std::any_of(m_languageGroups.begin(), m_languageGroups.end(), in);
                }
            }
        }
        const View* start = FindFile(files, "banner_start.brlan");
        const View* loop = FindFile(files, "banner_loop.brlan");
        if (loop == nullptr) {
            loop = FindFile(files, "banner.brlan");
        }
        if (start != nullptr) {
            Loader::ReadAnimation(*this, *start, m_start);
        }
        if (loop != nullptr) {
            Loader::ReadAnimation(*this, *loop, m_loop);
        }
        BakeImages();
        ComputeFrame();
        m_textures.clear();
        m_textures.shrink_to_fit();
    } catch (const std::exception& ex) {
        error = ex.what();
        *this = Banner{};
        return false;
    }
    return true;
}

void Banner::Warn(std::string message) {
    if (std::find(m_warnings.begin(), m_warnings.end(), message) == m_warnings.end()) {
        m_warnings.push_back(std::move(message));
    }
}

void Banner::ReleasePixels(int image) {
    if (image >= 0 && static_cast<size_t>(image) < m_images.size()) {
        std::vector<uint8_t>().swap(m_images[image].rgba);
    }
}

// Each picture pane gets its own image: the texture run through the material's default TEV stage
// (lerp from the black colour to the white colour by the texel), laid out over exactly the texel
// range the pane shows. Repeating and mirrored textures are tiled out here, so drawing needs no
// sampler wrap modes and no per-pixel colour maths.
void Banner::BakeImages() {
    constexpr int64_t kMaxSide = 2048;
    for (Pane& pane : m_panes) {
        if (!pane.picture) {
            continue;
        }
        if (pane.otherLanguage) {
            pane.image = -2;
            continue;
        }
        if (pane.material < 0 || static_cast<size_t>(pane.material) >= m_materials.size()) {
            Warn("picture pane " + pane.name + " has no material");
            pane.image = -2;
            continue;
        }
        const Material& m = m_materials[pane.material];
        if (m.texture < 0) {
            pane.image = -1;
            continue;
        }
        if (static_cast<size_t>(m.texture) >= m_textures.size() || m_textures[m.texture].image.rgba.empty()) {
            pane.image = -2;
            continue;
        }
        const Image& tex = m_textures[m.texture].image;

        float uv[4][2];  // LT, RT, LB, RB
        const size_t set = m.texCoordSet;
        for (int i = 0; i < 4; ++i) {
            float u = static_cast<float>(i & 1);
            float v = static_cast<float>(i >> 1);
            if (pane.texCoords.size() >= (set + 1) * 8) {
                u = pane.texCoords[set * 8 + i * 2];
                v = pane.texCoords[set * 8 + i * 2 + 1];
            }
            if (m.hasTexMatrix) {
                uv[i][0] = m.texMatrix[0][0] * u + m.texMatrix[0][1] * v + m.texMatrix[0][2];
                uv[i][1] = m.texMatrix[1][0] * u + m.texMatrix[1][1] * v + m.texMatrix[1][2];
            } else {
                uv[i][0] = u;
                uv[i][1] = v;
            }
        }
        float uMin = uv[0][0], uMax = uv[0][0], vMin = uv[0][1], vMax = uv[0][1];
        for (int i = 1; i < 4; ++i) {
            uMin = std::min(uMin, uv[i][0]);
            uMax = std::max(uMax, uv[i][0]);
            vMin = std::min(vMin, uv[i][1]);
            vMax = std::max(vMax, uv[i][1]);
        }
        const int64_t texW = tex.width;
        const int64_t texH = tex.height;
        int64_t x0 = static_cast<int64_t>(std::floor(uMin * texW));
        int64_t x1 = static_cast<int64_t>(std::ceil(uMax * texW));
        int64_t y0 = static_cast<int64_t>(std::floor(vMin * texH));
        int64_t y1 = static_cast<int64_t>(std::ceil(vMax * texH));
        // Past a clamped edge the hardware repeats the edge texel, which a clamped sampler on the
        // baked image does too, so only the texture itself needs baking on that axis.
        if (m.wrapS == 0) {
            x0 = std::clamp<int64_t>(x0, 0, texW - 1);
            x1 = std::clamp<int64_t>(x1, x0 + 1, texW);
        }
        if (m.wrapT == 0) {
            y0 = std::clamp<int64_t>(y0, 0, texH - 1);
            y1 = std::clamp<int64_t>(y1, y0 + 1, texH);
        }
        if (x1 <= x0) {
            x1 = x0 + 1;
        }
        if (y1 <= y0) {
            y1 = y0 + 1;
        }
        if (x1 - x0 > kMaxSide || y1 - y0 > kMaxSide) {
            Warn(pane.name + ": its texture repeats too often and is cut short");
            x1 = std::min(x1, x0 + kMaxSide);
            y1 = std::min(y1, y0 + kMaxSide);
        }

        Image baked;
        baked.width = static_cast<uint32_t>(x1 - x0);
        baked.height = static_cast<uint32_t>(y1 - y0);
        baked.rgba.resize(static_cast<size_t>(baked.width) * baked.height * 4);
        for (uint32_t y = 0; y < baked.height; ++y) {
            const int32_t sy = Wrap(static_cast<int32_t>(y0 + y), static_cast<int32_t>(texH), m.wrapT);
            for (uint32_t x = 0; x < baked.width; ++x) {
                const int32_t sx = Wrap(static_cast<int32_t>(x0 + x), static_cast<int32_t>(texW), m.wrapS);
                const uint8_t* src = &tex.rgba[(static_cast<size_t>(sy) * texW + sx) * 4];
                uint8_t* dst = &baked.rgba[(static_cast<size_t>(y) * baked.width + x) * 4];
                for (int c = 0; c < 4; ++c) {
                    dst[c] = Clamp8((m.black[c] * (255 - src[c]) + m.white[c] * src[c] + 127) / 255);
                }
            }
        }
        static constexpr int kQuadOrder[4] = {0, 1, 3, 2};  // LT RT LB RB -> LT RT RB LB
        for (int k = 0; k < 4; ++k) {
            const float* c = uv[kQuadOrder[k]];
            pane.imageUv[k][0] = (c[0] * texW - x0) / static_cast<float>(baked.width);
            pane.imageUv[k][1] = (c[1] * texH - y0) / static_cast<float>(baked.height);
        }
        pane.image = static_cast<int>(m_images.size());
        m_images.push_back(std::move(baked));
    }
}

// Worked out from the layout as it stands before any animation. A picture as tall as the whole
// layout is the background; everything else is artwork. The frame keeps the artwork's vertical
// extent, cut to the background so nothing outside it shows. A layout with no clear artwork band
// (less than half its height) is shown whole, as the Wii Menu would.
void Banner::ComputeFrame() {
    m_frameCenterY = 0.0f;
    m_frameHeight = m_height;
    m_frameWidth = m_width;
    std::vector<Quad> quads;
    Emit(m_panes, quads);
    float backgroundBottom = m_height;
    float backgroundTop = -m_height;
    float artworkBottom = m_height;
    float artworkTop = -m_height;
    bool haveBackground = false;
    for (const Quad& q : quads) {
        const float bottom = std::min({q.y[0], q.y[1], q.y[2], q.y[3]});
        const float top = std::max({q.y[0], q.y[1], q.y[2], q.y[3]});
        if (top - bottom >= m_height * 0.99f) {
            const float left = std::min({q.x[0], q.x[1], q.x[2], q.x[3]});
            const float right = std::max({q.x[0], q.x[1], q.x[2], q.x[3]});
            backgroundBottom = std::min(backgroundBottom, bottom);
            backgroundTop = std::max(backgroundTop, top);
            m_frameWidth = haveBackground ? std::min(m_frameWidth, right - left) : right - left;
            haveBackground = true;
        } else {
            artworkBottom = std::min(artworkBottom, bottom);
            artworkTop = std::max(artworkTop, top);
        }
    }
    if (!haveBackground) {
        backgroundBottom = -m_height / 2;
        backgroundTop = m_height / 2;
    }
    const float bottom = std::max(artworkBottom, backgroundBottom);
    const float top = std::min(artworkTop, backgroundTop);
    if (top - bottom >= m_height * 0.5f) {
        m_frameCenterY = (top + bottom) / 2;
        m_frameHeight = top - bottom;
    }
}

void Banner::Apply(const Animation& anim, float frame, std::vector<Pane>& panes) const {
    for (const Curve& curve : anim.curves) {
        Pane& pane = panes[curve.pane];
        const float value = CurveValue(frame, curve.keys, curve.step);
        if (curve.kind == 'S') {
            if (curve.target < 3) {
                pane.translate[curve.target] = value;
            } else if (curve.target < 6) {
                pane.rotate[curve.target - 3] = value;
            } else if (curve.target < 8) {
                pane.scale[curve.target - 6] = value;
            } else if (curve.target < 10) {
                pane.size[curve.target - 8] = value;
            }
        } else if (curve.kind == 'C') {
            if (curve.target == 16) {
                pane.alpha = ToColor(value);
            } else if (curve.target < 16) {
                pane.vertexColors[curve.target / 4][curve.target % 4] = ToColor(value);
            }
        } else if (curve.kind == 'V') {
            pane.visible = value != 0.0f;
        }
    }
}

void Banner::Evaluate(double frame, std::vector<Quad>& out) const {
    out.clear();
    if (m_panes.empty()) {
        return;
    }
    std::vector<Pane> panes = m_panes;
    if (m_start.frames > 0 && (frame < m_start.frames || m_loop.frames <= 0)) {
        Apply(m_start, static_cast<float>(std::min<double>(std::max(frame, 0.0), m_start.frames)), panes);
    } else if (m_loop.frames > 0) {
        // The loop takes over from where the intro ended, for anything the loop leaves alone.
        double loopFrame = frame;
        if (m_start.frames > 0) {
            Apply(m_start, m_start.frames, panes);
            loopFrame -= m_start.frames;
        }
        loopFrame = std::fmod(std::max(loopFrame, 0.0), static_cast<double>(m_loop.frames));
        Apply(m_loop, static_cast<float>(loopFrame), panes);
    }
    Emit(panes, out);
}

void Banner::Emit(const std::vector<Pane>& panes, std::vector<Quad>& out) const {
    // Pane::CalculateMtx and Picture::DrawSelf: a pane draws itself, then its children. A pane
    // marked "influenced alpha" passes its alpha down to everything below it.
    const auto visit = [&](const auto& self, int index, const Mtx34& parentMtx, bool hasParent,
                           float globalAlpha, bool influenced) -> void {
        const Pane& pane = panes[index];
        if (!pane.visible || pane.otherLanguage) {
            return;
        }
        Mtx34 local;
        local.m[0][0] = pane.scale[0];
        local.m[1][1] = pane.scale[1];
        local = Mul(Rotation(0, pane.rotate[0]), local);
        local = Mul(Rotation(1, pane.rotate[1]), local);
        local = Mul(Rotation(2, pane.rotate[2]), local);
        for (int i = 0; i < 3; ++i) {
            local.m[i][3] += pane.translate[i];
        }
        const Mtx34 global = hasParent ? Mul(parentMtx, local) : local;
        const uint8_t alpha = influenced && hasParent ? static_cast<uint8_t>(pane.alpha * globalAlpha) : pane.alpha;

        if (pane.picture && pane.image != -2) {
            const Material& m = m_materials[pane.material];
            bool whiteVertices = true;
            for (const auto& c : pane.vertexColors) {
                whiteVertices = whiteVertices && c[0] == 255 && c[1] == 255 && c[2] == 255 && c[3] == 255;
            }
            const bool modulate = alpha != 255 || !whiteVertices;
            const bool useVtx = !m.hasChanCtrl || m.colorSrc == kSrcVtx || m.alphaSrc == kSrcVtx;
            const bool useMat = m.hasChanCtrl && (m.colorSrc == kSrcReg || m.alphaSrc == kSrcReg);
            uint8_t matColor[4] = {m.matColor[0], m.matColor[1], m.matColor[2], m.matColor[3]};
            if (alpha != 255) {
                matColor[3] = static_cast<uint8_t>(matColor[3] * alpha / 255);
            }
            const bool matIsWhite = matColor[0] == 255 && matColor[1] == 255 && matColor[2] == 255 && matColor[3] == 255;
            // Without a texture the default stage is the white colour times the rasterised colour.
            const bool useRas = (useVtx && modulate) || (useMat && !matIsWhite) || pane.image == -1;
            const bool sendVtx = useRas && useVtx;

            Quad quad;
            quad.image = pane.image;
            const float w = pane.size[0];
            const float h = pane.size[1];
            const int horizontal = pane.basePosition % 3;
            const int vertical = pane.basePosition / 3;
            const float left = horizontal == 1 ? -w / 2 : horizontal == 2 ? -w : 0.0f;
            const float top = vertical == 1 ? h / 2 : vertical == 2 ? h : 0.0f;
            const float cornersX[4] = {left, left + w, left + w, left};
            const float cornersY[4] = {top, top, top - h, top - h};
            static constexpr int kStoredCorner[4] = {0, 1, 3, 2};  // quad LT RT RB LB -> stored LT RT LB RB
            bool anyVisible = false;
            for (int k = 0; k < 4; ++k) {
                quad.x[k] = global.m[0][0] * cornersX[k] + global.m[0][1] * cornersY[k] + global.m[0][3];
                quad.y[k] = global.m[1][0] * cornersX[k] + global.m[1][1] * cornersY[k] + global.m[1][3];
                quad.u[k] = pane.imageUv[k][0];
                quad.v[k] = pane.imageUv[k][1];

                const uint8_t* vtx = pane.vertexColors[kStoredCorner[k]];
                const uint8_t vtxAlpha = alpha != 255 ? static_cast<uint8_t>(vtx[3] * alpha / 255) : vtx[3];
                uint8_t ras[4] = {255, 255, 255, 255};
                if (useRas) {
                    const bool colorFromMat = m.hasChanCtrl && m.colorSrc == kSrcReg;
                    const bool alphaFromMat = m.hasChanCtrl && m.alphaSrc == kSrcReg;
                    for (int c = 0; c < 3; ++c) {
                        ras[c] = colorFromMat ? matColor[c] : sendVtx ? vtx[c] : 255;
                    }
                    ras[3] = alphaFromMat ? matColor[3] : sendVtx ? vtxAlpha : 255;
                }
                if (pane.image == -1) {
                    for (int c = 0; c < 4; ++c) {
                        ras[c] = static_cast<uint8_t>(ras[c] * Clamp8(m.white[c]) / 255);
                    }
                }
                std::memcpy(quad.rgba[k], ras, 4);
                anyVisible = anyVisible || ras[3] != 0;
            }
            if (anyVisible) {
                out.push_back(quad);
            }
        }

        float childAlpha = globalAlpha;
        bool childInfluenced = influenced;
        if (pane.influencedAlpha && pane.alpha != 255) {
            childAlpha = globalAlpha * pane.alpha * (1.0f / 255.0f);
            childInfluenced = true;
        }
        for (const int child : pane.children) {
            self(self, child, global, true, childAlpha, childInfluenced);
        }
    };
    for (size_t i = 0; i < panes.size(); ++i) {
        if (panes[i].parent < 0) {
            visit(visit, static_cast<int>(i), Mtx34{}, false, 1.0f, false);
        }
    }
}

}  // namespace BootBanner
