#include "gx_internal.h"
#include "gx_stream_common.h"
#include "gx_cp_decode.h"
#include "isa/big_endian.h"

// Opcode constants and the stream helpers this file shares with gx_dl.cpp /
// gx_vertex.cpp; see gx_stream_common.h.
using namespace GxCmd;
using namespace GxStream;

HleGxState g_hleGxState;

namespace aurora::gx::fifo {
bool submit_raw_draw(GXPrimitive prim, GXVtxFmt fmt, const uint8_t* vertices, uint16_t vtxCount,
                     uint32_t vertexBytes);
bool submit_draw_in_stream(GXPrimitive prim, GXVtxFmt fmt, const uint8_t* vertices, uint16_t vtxCount,
                           uint32_t vertexBytes);
const uint8_t* get_buffer_data();
uint32_t get_buffer_size();
}

void HleGxState::ResetVertex() {
    currentAttr = NextEnabledAttr(GX_VA_PNMTXIDX - 1);
    currentComp = 0;
}

GXAttr HleGxState::NextEnabledAttr(int startAttr) {
    for (int i = startAttr + 1; i < 26; ++i) {
        if (vtxDesc[i] != GX_NONE) {
            return static_cast<GXAttr>(i);
        }
    }
    return GX_VA_NULL;
}

int HleGxState::GetExpectedCompCount(GXAttr attr, const VtxAttrFmt& fmt) {
    // Attributes with no component layout (matrix indices, XF arrays, GX_VA_NBT)
    // count as one component here: the incremental parser advances one element
    // per raw stream item for them.
    return static_cast<int>(AttrCompCount(attr, fmt, 1u));
}

static void ApplyAuroraVtxStateForRawBegin(GXVtxFmt fmt) {
    // KNOWN DIVERGENCE: this is the only publish site with includeNbt=true, so it also
    // publishes GX_VA_NBT after GX_VA_NRM, which makes aurora's SETVAT fall-through clobber the
    // NRM VAT with the default. Raw-FIFO geometry renders correctly with it; do not "fix" without
    // an in-race A/B run.
    // Nothing changed since the last primitive's publish here: see ImmediateVtxPublishMemo.
    bool checking = false;
    if (TrySkipImmediateVtxPublish(fmt, checking)) {
        return;
    }
    PublishAuroraVtxState(fmt, AuroraVtxPublishOptions{/*includeNbt=*/true,
                                                       /*fmtLoopFirst=*/0,
                                                       /*fmtLoopLast=*/25});
    if (checking) {
        FinishImmediateVtxPublishCheck(fmt);
    }
    NoteImmediateVtxPublished(fmt);
}

// There is deliberately no indexed-aurora path here. Immediate-mode indexed
// draws are parsed incrementally, and aurora's indexed-array upload has to
// precede the draw carrying the max-index bounds, which cannot be known without
// buffering the whole primitive. Indexed attributes are therefore expanded into
// the packed direct stream above (GX_INDEX8/16 -> GX_DIRECT).

static uint32_t GetDirectAttrByteSizeForFifo(GXAttr attr, const VtxAttrFmt& fmt) {
    return DirectAttrByteSize(attr, fmt, /*matrixAttrIsOneByte=*/true, /*fallbackComps=*/1u);
}

static bool TryGetRawDirectFifoVertexSize(GXVtxFmt fmt, uint32_t& vertexSize) {
    vertexSize = 0;
    if (fmt >= GX_MAX_VTXFMT) {
        return false;
    }

    for (int attr = 0; attr < 26; ++attr) {
        const GXAttrType type = g_hleGxState.vtxDesc[attr];
        if (type == GX_NONE) {
            continue;
        }
        if (type != GX_DIRECT) {
            return false;
        }

        const GXAttr gxAttr = static_cast<GXAttr>(attr);
        const uint32_t attrBytes =
            GetDirectAttrByteSizeForFifo(gxAttr, g_hleGxState.vtxAttrFmt[fmt][attr]);
        if (attrBytes == 0) {
            return false;
        }
        vertexSize += attrBytes;
    }

    return vertexSize != 0;
}

static bool TrySubmitRawDirectFifoDraw(const uint8_t* packet, uint32_t packetBytes, GXPrimitive prim,
                                       GXVtxFmt vtxFmt, uint16_t vtxCount) {
    if (packet == nullptr || packetBytes == 0 || vtxCount == 0) {
        return false;
    }

    EnsureAuroraFrameActive();

    g_hleGxState.currentVtxFmt = vtxFmt;
    g_hleGxState.currentPrim = prim;
    g_hleGxState.vertsRemaining = vtxCount;
    g_hleGxState.inBegin = false;
    g_hleGxState.auroraBeginCalled = false;
    g_hleGxState.ResetVertex();

    ApplyAuroraVtxStateForRawBegin(vtxFmt);
    EnsureDefaultGxAlphaCompare();

    if (!aurora::gx::fifo::submit_raw_draw(prim, vtxFmt, packet + 3, vtxCount, packetBytes - 3u)) {
        return false;
    }
    GXMarkFrameWork();
    return true;
}

static void DecodeColorFromArray(uint32_t addr, GXCompType type, GXCompCnt cnt, GXColor& out) {
    // Gather exactly the bytes the shared decoder consumes. GX_RGBX8 is the one
    // format whose stream footprint (4 bytes) exceeds what is decoded (3), and
    // the pre-dedup code read only those 3 from the guest array - keep it that
    // way so a 3-byte tail at the end of an array cannot start faulting.
    const uint32_t byteCount =
        (type == GX_RGBX8) ? 3u : ColorByteSize(type, cnt);
    uint8_t bytes[4] = {0, 0, 0, 0};
    for (uint32_t i = 0; i < byteCount && i < 4u; ++i) {
        bytes[i] = Memory::Read8(addr + i);
    }
    DecodeColorBytes(bytes, type, cnt, out);
}

void SubmitIndexedAttribute(GXAttr attr, uint32_t index) {
    const auto& fmt = g_hleGxState.vtxAttrFmt[g_hleGxState.currentVtxFmt][attr];
    const auto& arr = g_hleGxState.vtxArray[attr];
    if (arr.base == 0 || arr.stride == 0) {
        float comps[9]{};
        switch (attr) {
        case GX_VA_CLR0:
        case GX_VA_CLR1:
            if (fmt.cnt == GX_CLR_RGB) {
                GXColor3u8(0, 0, 0);
            } else {
                GXColor4u8(0, 0, 0, 0xFF);
            }
            break;
        case GX_VA_POS:
        case GX_VA_NRM:
        case GX_VA_TEX0:
        case GX_VA_TEX1:
        case GX_VA_TEX2:
        case GX_VA_TEX3:
        case GX_VA_TEX4:
        case GX_VA_TEX5:
        case GX_VA_TEX6:
        case GX_VA_TEX7:
            SubmitAttribute(attr, comps, fmt);
            break;
        default:
            break;
        }
        return;
    }
    const uint32_t baseAddr = arr.base + index * arr.stride;
    float comps[9]{};
    u32 rawComps[9]{};
    switch (attr) {
    case GX_VA_POS: {
        const int count = static_cast<int>(AttrCompCount(GX_VA_POS, fmt, 0u));
        const int step = GetCompSizeBytes(fmt.type);
        uint32_t addr = baseAddr;
        for (int i = 0; i < count; ++i, addr += step) {
            comps[i] = ReadArrayComp(addr, fmt.type, fmt.frac);
            rawComps[i] = ReadArrayRawComp(addr, fmt.type);
        }
        SubmitAttribute(attr, comps, fmt, rawComps);
        break;
    }
    case GX_VA_NRM: {
        const int count = static_cast<int>(AttrCompCount(GX_VA_NRM, fmt, 0u));
        const int step = GetCompSizeBytes(fmt.type);
        uint32_t addr = baseAddr;
        for (int i = 0; i < count; ++i, addr += step) {
            comps[i] = ReadArrayComp(addr, fmt.type, fmt.frac);
            rawComps[i] = ReadArrayRawComp(addr, fmt.type);
        }
        SubmitAttribute(attr, comps, fmt, rawComps);
        break;
    }
    case GX_VA_CLR0:
    case GX_VA_CLR1: {
        GXColor color{};
        DecodeColorFromArray(baseAddr, fmt.type, fmt.cnt, color);
        if (fmt.cnt == GX_CLR_RGB) {
            GXColor3u8(color.r, color.g, color.b);
        } else {
            GXColor4u8(color.r, color.g, color.b, color.a);
        }
        break;
    }
    case GX_VA_TEX0: case GX_VA_TEX1: case GX_VA_TEX2: case GX_VA_TEX3:
    case GX_VA_TEX4: case GX_VA_TEX5: case GX_VA_TEX6: case GX_VA_TEX7: {
        // Every GX_VA_TEXn shares one component layout, so the constant here is
        // exact for whichever of them `attr` is.
        const int count = static_cast<int>(AttrCompCount(GX_VA_TEX0, fmt, 0u));
        const int step = GetCompSizeBytes(fmt.type);
        uint32_t addr = baseAddr;
        for (int i = 0; i < count; ++i, addr += step) {
            comps[i] = ReadArrayComp(addr, fmt.type, fmt.frac);
            rawComps[i] = ReadArrayRawComp(addr, fmt.type);
        }
        SubmitAttribute(attr, comps, fmt, rawComps);
        break;
    }
    default:
        break;
    }
}

// Mirrors the raw-integer fast paths in SubmitAttribute below: for those
// (attr, type) pairs the float component buffer is never read, so the direct
// FIFO path can skip building it.
static bool SubmitAttributeReadsFloatComps(GXAttr attr, const VtxAttrFmt& fmt) {
    switch (attr) {
    case GX_VA_POS:
    case GX_VA_NRM:
    case GX_VA_TEX0: case GX_VA_TEX1: case GX_VA_TEX2: case GX_VA_TEX3:
    case GX_VA_TEX4: case GX_VA_TEX5: case GX_VA_TEX6: case GX_VA_TEX7:
        break;
    default:
        return true;
    }
    switch (fmt.type) {
    case GX_U8:
    case GX_S8:
    case GX_U16:
    case GX_S16:
        return false;
    default:
        return true;
    }
}

void SubmitAttribute(GXAttr attr, float* comps, const VtxAttrFmt& fmt, const u32* rawComps) {
    auto r8 = [rawComps](int idx) { return static_cast<u8>(rawComps[idx]); };
    auto rs8 = [rawComps](int idx) { return static_cast<s8>(rawComps[idx]); };
    auto r16 = [rawComps](int idx) { return static_cast<u16>(rawComps[idx]); };
    auto rs16 = [rawComps](int idx) { return static_cast<s16>(rawComps[idx]); };

    switch (attr) {
    case GX_VA_POS:
        if (rawComps) {
            if (fmt.cnt == GX_POS_XY) {
                switch (fmt.type) {
                case GX_U8: GXPosition2u8(r8(0), r8(1)); return;
                case GX_S8: GXPosition2s8(rs8(0), rs8(1)); return;
                case GX_U16: GXPosition2u16(r16(0), r16(1)); return;
                case GX_S16: GXPosition2s16(rs16(0), rs16(1)); return;
                default: break;
                }
            } else {
                switch (fmt.type) {
                case GX_U8: GXPosition3u8(r8(0), r8(1), r8(2)); return;
                case GX_S8: GXPosition3s8(rs8(0), rs8(1), rs8(2)); return;
                case GX_U16: GXPosition3u16(r16(0), r16(1), r16(2)); return;
                case GX_S16: GXPosition3s16(rs16(0), rs16(1), rs16(2)); return;
                default: break;
                }
            }
        }
        if (fmt.cnt == GX_POS_XY) {
            GXPosition2f32(comps[0], comps[1]);
        } else {
            GXPosition3f32(comps[0], comps[1], comps[2]);
        }
        break;
    case GX_VA_NRM:
        if (rawComps) {
            const bool nbt = fmt.cnt == GX_NRM_NBT || fmt.cnt == GX_NRM_NBT3;
            const int groups = nbt ? 3 : 1;
            switch (fmt.type) {
            case GX_U8:
                for (int i = 0; i < groups; ++i) {
                    GXNormal3u8(r8(i * 3), r8(i * 3 + 1), r8(i * 3 + 2));
                }
                return;
            case GX_S8:
                for (int i = 0; i < groups; ++i) {
                    GXNormal3s8(rs8(i * 3), rs8(i * 3 + 1), rs8(i * 3 + 2));
                }
                return;
            case GX_U16:
                for (int i = 0; i < groups; ++i) {
                    GXNormal3u16(r16(i * 3), r16(i * 3 + 1), r16(i * 3 + 2));
                }
                return;
            case GX_S16:
                for (int i = 0; i < groups; ++i) {
                    GXNormal3s16(rs16(i * 3), rs16(i * 3 + 1), rs16(i * 3 + 2));
                }
                return;
            default: break;
            }
        }
        if (fmt.cnt == GX_NRM_NBT || fmt.cnt == GX_NRM_NBT3) {
            for (int i = 0; i < 3; ++i) {
                GXNormal3f32(comps[i * 3], comps[i * 3 + 1], comps[i * 3 + 2]);
            }
        } else {
            GXNormal3f32(comps[0], comps[1], comps[2]);
        }
        break;
    case GX_VA_CLR0:
    case GX_VA_CLR1:
        if (fmt.cnt == GX_CLR_RGB) {
            GXColor3u8(static_cast<u8>(comps[0]), static_cast<u8>(comps[1]),
                       static_cast<u8>(comps[2]));
        } else {
            GXColor4u8(static_cast<u8>(comps[0]), static_cast<u8>(comps[1]),
                       static_cast<u8>(comps[2]), static_cast<u8>(comps[3]));
        }
        break;
    case GX_VA_TEX0: case GX_VA_TEX1: case GX_VA_TEX2: case GX_VA_TEX3:
    case GX_VA_TEX4: case GX_VA_TEX5: case GX_VA_TEX6: case GX_VA_TEX7:
        if (rawComps) {
            if (fmt.cnt == GX_TEX_S) {
                switch (fmt.type) {
                case GX_U8: GXTexCoord1u8(r8(0)); return;
                case GX_S8: GXTexCoord1s8(rs8(0)); return;
                case GX_U16: GXTexCoord1u16(r16(0)); return;
                case GX_S16: GXTexCoord1s16(rs16(0)); return;
                default: break;
                }
            } else {
                switch (fmt.type) {
                case GX_U8: GXTexCoord2u8(r8(0), r8(1)); return;
                case GX_S8: GXTexCoord2s8(rs8(0), rs8(1)); return;
                case GX_U16: GXTexCoord2u16(r16(0), r16(1)); return;
                case GX_S16: GXTexCoord2s16(rs16(0), rs16(1)); return;
                default: break;
                }
            }
        }
        if (fmt.cnt == GX_TEX_S) {
            GXTexCoord1f32(comps[0]);
        } else {
            GXTexCoord2f32(comps[0], comps[1]);
        }
        break;
    default: break;
    }
}

// --- Whole-primitive path for immediate mode --------------------------------
//
// Between GXBegin and GXEnd the game writes a primitive to the FIFO one value at a time: three
// floats and a byte per particle vertex, each through HleFifoWrite. The incremental parser below
// buffers every value, works out which attribute and component it belongs to, converts it and
// hands it to aurora's own GXPosition/GXColor/GXTexCoord writers - which put the same bytes back
// into aurora's command stream. When every enabled attribute is direct data, the bytes the game
// writes ARE the draw packet's payload, so collect them and hand the finished primitive over in
// one piece (aurora::gx::fifo::submit_draw_in_stream: opcode, count, payload - what GXBegin, the
// attribute writers and GXEnd produce together).
//
// MKW_GX_PRIMITIVE_CHECK=1 (desktop test runs) keeps the incremental path and compares the bytes
// it left in aurora's stream with the bytes collected here, primitive by primitive.
namespace {
struct ImmediatePrimitive {
    bool collecting = false;  // bytes go to `bytes`, nothing reaches the incremental parser
    bool checking = false;    // incremental parser runs; `bytes` is kept for comparison
    GXPrimitive prim = GX_TRIANGLES;
    GXVtxFmt fmt = GX_VTXFMT0;
    uint16_t vertexCount = 0;
    uint32_t expectedBytes = 0;
    uint32_t auroraSizeAtBegin = 0;
    std::vector<uint8_t> bytes;
};
ImmediatePrimitive s_immediate;
constexpr uint32_t kImmediatePrimitiveMaxBytes = 256u * 1024u;

uint64_t s_immediateCheckSame = 0;
uint64_t s_immediateCheckDifferent = 0;
uint64_t s_immediateCheckUncheckable = 0;

bool ImmediatePrimitiveCheckEnabled() {
#if defined(__SWITCH__) || defined(__ANDROID__)
    return false;
#else
    static const bool enabled = std::getenv("MKW_GX_PRIMITIVE_CHECK") != nullptr;
    return enabled;
#endif
}

void FinishImmediatePrimitive() {
    ImmediatePrimitive& p = s_immediate;
    p.collecting = false;
    EnsureAuroraFrameActive();
    ApplyAuroraVtxStateForRawBegin(p.fmt);
    EnsureDefaultGxAlphaCompare();
    aurora::gx::fifo::submit_draw_in_stream(p.prim, p.fmt, p.bytes.data(), p.vertexCount, p.expectedBytes);
    GXMarkFrameWork();
    // Exactly the state the incremental parser leaves after the last vertex.
    g_hleGxState.vertsRemaining = 0;
    g_hleGxState.inBegin = false;
    g_hleGxState.auroraBeginCalled = false;
    g_hleGxState.ResetVertex();
    g_hleGxState.fifoReadOffset = 0;
    g_hleGxState.fifoByteCount = 0;
}

inline void AppendImmediateBytes(u32 val, uint32_t count) {
    uint8_t be[4] = {static_cast<uint8_t>(val >> 24), static_cast<uint8_t>(val >> 16),
                     static_cast<uint8_t>(val >> 8), static_cast<uint8_t>(val)};
    s_immediate.bytes.insert(s_immediate.bytes.end(), be + (4u - count), be + 4u);
}

// Called by the incremental parser in check mode, just before it ends the primitive.
void CheckImmediatePrimitive() {
    ImmediatePrimitive& p = s_immediate;
    if (!p.checking) {
        return;
    }
    p.checking = false;
    const uint32_t now = aurora::gx::fifo::get_buffer_size();
    const bool comparable = p.bytes.size() == p.expectedBytes && now >= p.auroraSizeAtBegin &&
                            now - p.auroraSizeAtBegin == p.expectedBytes;
    if (!comparable) {
        if (++s_immediateCheckUncheckable <= 10) {
            std::fprintf(stderr, "[gx-primitive-check] not comparable: prim=%d fmt=%d verts=%u expected=%u collected=%zu aurora=%d\n",
                         static_cast<int>(p.prim), static_cast<int>(p.fmt), p.vertexCount, p.expectedBytes, p.bytes.size(),
                         static_cast<int>(now) - static_cast<int>(p.auroraSizeAtBegin));
        }
    } else if (std::memcmp(aurora::gx::fifo::get_buffer_data() + p.auroraSizeAtBegin, p.bytes.data(), p.expectedBytes) == 0) {
        ++s_immediateCheckSame;
    } else if (++s_immediateCheckDifferent <= 10) {
        std::fprintf(stderr, "[gx-primitive-check] DIFFERENT bytes: prim=%d fmt=%d verts=%u bytes=%u\n",
                     static_cast<int>(p.prim), static_cast<int>(p.fmt), p.vertexCount, p.expectedBytes);
    }
    const uint64_t total = s_immediateCheckSame + s_immediateCheckDifferent + s_immediateCheckUncheckable;
    if ((total % 20000u) == 0) {
        std::fprintf(stderr, "[gx-primitive-check] %llu primitives: %llu identical, %llu different, %llu not comparable\n",
                     static_cast<unsigned long long>(total), static_cast<unsigned long long>(s_immediateCheckSame),
                     static_cast<unsigned long long>(s_immediateCheckDifferent),
                     static_cast<unsigned long long>(s_immediateCheckUncheckable));
    }
}
}  // namespace

// From GX__Begin, after it has reset the parser for a new primitive.
void GxFifoBeginImmediatePrimitive(GXPrimitive prim, GXVtxFmt fmt, uint32_t vertexCount) {
    ImmediatePrimitive& p = s_immediate;
    p.collecting = false;
    p.checking = false;
    uint32_t vertexBytes = 0;
    if (vertexCount == 0 || vertexCount > 0xFFFFu || !TryGetRawDirectFifoVertexSize(fmt, vertexBytes)) {
        return;
    }
    const uint32_t total = vertexCount * vertexBytes;
    if (total > kImmediatePrimitiveMaxBytes) {
        return;
    }
    p.prim = prim;
    p.fmt = fmt;
    p.vertexCount = static_cast<uint16_t>(vertexCount);
    p.expectedBytes = total;
    p.bytes.clear();
    if (ImmediatePrimitiveCheckEnabled()) {
        p.checking = true;
    } else {
        p.collecting = true;
    }
}

// GXEnd from the game with a primitive still open: nothing complete to draw.
void GxFifoAbandonImmediatePrimitive() {
    s_immediate.collecting = false;
    s_immediate.checking = false;
}

// `val` is a raw big-endian bit pattern: the FIFO stream is type-agnostic, and
// the float entry point converts before it gets here.
void HleFifoWrite(u32 val, uint32_t sizeBytes) {
    const bool recordOnly = IsDisplayListActive();
    if (recordOnly) {
        WriteDisplayListData(val, sizeBytes);
        return;
    }

    if (s_immediate.collecting) {
        const uint32_t count = (sizeBytes == 0 || sizeBytes > 4) ? 4u : sizeBytes;
        const uint32_t room = s_immediate.expectedBytes - static_cast<uint32_t>(s_immediate.bytes.size());
        if (count <= room) [[likely]] {
            AppendImmediateBytes(val, count);
            if (count == room) {
                FinishImmediatePrimitive();
            }
            return;
        }
        // A write that straddles the end of the primitive: its first bytes finish it, the rest
        // is whatever follows in the stream.
        const uint32_t rest = count - room;
        AppendImmediateBytes(val >> (8u * rest), room);
        FinishImmediatePrimitive();
        HleFifoWrite(rest == 4u ? val : (val & ((1u << (8u * rest)) - 1u)), rest);
        return;
    }
    if (s_immediate.checking) {
        const uint32_t count = (sizeBytes == 0 || sizeBytes > 4) ? 4u : sizeBytes;
        if (s_immediate.bytes.size() + count <= s_immediate.expectedBytes) {
            AppendImmediateBytes(val, count);
        } else {
            s_immediate.bytes.resize(s_immediate.expectedBytes + 1u);  // marks it not comparable
        }
    }

    auto resetFifoBuffer = [&]() {
        g_hleGxState.fifoReadOffset = 0;
        g_hleGxState.fifoByteCount = 0;
    };

    auto compactFifoBuffer = [&]() {
        if (g_hleGxState.fifoByteCount == 0) {
            g_hleGxState.fifoReadOffset = 0;
            return;
        }
        if (g_hleGxState.fifoReadOffset == 0) {
            return;
        }
        std::memmove(g_hleGxState.fifoBytes.data(),
                     g_hleGxState.fifoBytes.data() + g_hleGxState.fifoReadOffset,
                     g_hleGxState.fifoByteCount);
        g_hleGxState.fifoReadOffset = 0;
    };

    auto fifoData = [&]() -> uint8_t* {
        return g_hleGxState.fifoBytes.data() + g_hleGxState.fifoReadOffset;
    };

    auto pushBytes = [&](u32 value, uint32_t count) {
        if (count == 0) return;
        if (count > 4) count = 4;
        if (g_hleGxState.fifoReadOffset + g_hleGxState.fifoByteCount + count > g_hleGxState.fifoBytes.size()) {
            compactFifoBuffer();
            if (g_hleGxState.fifoReadOffset + g_hleGxState.fifoByteCount + count > g_hleGxState.fifoBytes.size()) {
                resetFifoBuffer();
            }
        }
        const size_t writeOffset = g_hleGxState.fifoReadOffset + g_hleGxState.fifoByteCount;
        switch (count) {
        case 4:
            BigEndian::Write32(g_hleGxState.fifoBytes.data() + writeOffset, value);
            break;
        case 2:
            BigEndian::Write16(g_hleGxState.fifoBytes.data() + writeOffset,
                               static_cast<uint16_t>(value));
            break;
        default:
            g_hleGxState.fifoBytes[writeOffset] = static_cast<uint8_t>(value & 0xFF);
            break;
        }
        g_hleGxState.fifoByteCount += count;
    };

    auto consumeBytes = [&](uint32_t count, u32& out) -> bool {
        if (count == 0 || g_hleGxState.fifoByteCount < count) return false;
        u32 value = 0;
        const uint32_t foldCount = (count > 4) ? 4 : count;
        uint8_t* data = fifoData();
        for (uint32_t i = 0; i < foldCount; ++i) {
            value = (value << 8) | data[i];
        }
        g_hleGxState.fifoReadOffset += count;
        g_hleGxState.fifoByteCount -= count;
        if (g_hleGxState.fifoByteCount == 0) {
            g_hleGxState.fifoReadOffset = 0;
        }
        out = value;
        return true;
    };

    pushBytes(val, sizeBytes == 0 ? 4 : sizeBytes);

    // Parse raw FIFO command packets written directly to the gather pipe
    // (e.g. NW4R/G3D paths that do not call the GXBegin wrapper function).
    while (!g_hleGxState.inBegin) {
        if (g_hleGxState.fifoByteCount < 1) {
            break;
        }

        uint8_t* data = fifoData();
        const uint8_t cmd = data[0];
        const uint8_t opcode = cmd & GX_OPCODE_MASK_CMD;
        u32 sink = 0;

        if (cmd == GX_NOP_CMD || opcode == GX_CMD_INVL_VC_CMD) {
            if (!consumeBytes(1, sink)) break;
            continue;
        }

        if (cmd == GX_LOAD_BP_REG_CMD) {
            if (g_hleGxState.fifoByteCount < 5) break;
            const uint32_t bpWord = ReadBE32(data + 1);
            GXApplyBPReg(static_cast<uint8_t>(bpWord >> 24), bpWord & 0x00FFFFFFu);
            if (!consumeBytes(5, sink)) break;
            continue;
        }

        if (opcode == GX_LOAD_CP_REG_CMD) {
            if (g_hleGxState.fifoByteCount < 6) break;
            const uint8_t reg = data[1];
            const uint32_t cpValue = ReadBE32(data + 2);
            GxCpDecode::ApplyCpRegWrite(reg, cpValue);
            if (!consumeBytes(6, sink)) break;
            continue;
        }

        if (opcode == GX_LOAD_XF_REG_CMD) {
            if (g_hleGxState.fifoByteCount < 5) break;
            const uint16_t countWords = ReadBE16(data + 1);
            const uint32_t packetBytes = 1u + 4u + (static_cast<uint32_t>(countWords) + 1u) * 4u;
            if (g_hleGxState.fifoByteCount < packetBytes) break;
            GXCallDisplayList(data, packetBytes);
            GXMarkFrameWork();
            if (!consumeBytes(packetBytes, sink)) break;
            continue;
        }

        if (opcode >= GX_LOAD_INDX_A_CMD && opcode <= GX_LOAD_INDX_D_CMD) {
            if (g_hleGxState.fifoByteCount < 5) break;
            const uint32_t xfValue = ReadBE32(data + 1);
            ApplyIndexedXfArrayForPacket(cmd, xfValue);
            GXCallDisplayList(data, 5);
            GXMarkFrameWork();
            if (!consumeBytes(5, sink)) break;
            continue;
        }

        if (opcode == GX_CMD_CALL_DL_CMD) {
            if (g_hleGxState.fifoByteCount < 9) break;
            const uint32_t listAddr = ReadBE32(data + 1);
            const uint32_t listSize = ReadBE32(data + 5);
            if (!consumeBytes(9, sink)) break;
            if (listAddr != 0 && listSize > 0) {
                GX__CallDisplayList_80172f64(listAddr, listSize);
            }
            continue;
        }

        if (IsDrawOpcode(opcode)) {
            if (g_hleGxState.fifoByteCount < 3) break;
            const uint16_t vtxCount = ReadBE16(data + 1);
            const GXVtxFmt vtxFmt = static_cast<GXVtxFmt>(cmd & GX_VAT_MASK_CMD);
            const GXPrimitive prim = OpcodeToGXPrimitive(cmd);
            uint32_t rawVertexSize = 0;
            if (TryGetRawDirectFifoVertexSize(vtxFmt, rawVertexSize)) {
                const uint32_t packetBytes = 3u + static_cast<uint32_t>(vtxCount) * rawVertexSize;
                if (g_hleGxState.fifoByteCount < packetBytes) {
                    break;
                }
                if (TrySubmitRawDirectFifoDraw(data, packetBytes, prim, vtxFmt, vtxCount)) {
                    if (!consumeBytes(packetBytes, sink)) break;
                    continue;
                }
            }
            if (!consumeBytes(3, sink)) break;

            g_hleGxState.currentVtxFmt = vtxFmt;
            g_hleGxState.currentPrim = prim;
            g_hleGxState.vertsRemaining = vtxCount;
            g_hleGxState.inBegin = true;
            g_hleGxState.auroraBeginCalled = false;
            g_hleGxState.ResetVertex();

            break;
        }

        // Unknown FIFO command byte outside a begin packet; discard it so stream parsing can recover.
        if (!consumeBytes(1, sink)) break;
    }

    if (!g_hleGxState.inBegin) {
        return;
    }

    if (!recordOnly && !g_hleGxState.auroraBeginCalled) {
        EnsureAuroraFrameActive();
        ApplyAuroraVtxStateForRawBegin(g_hleGxState.currentVtxFmt);
        EnsureDefaultGxAlphaCompare();
        GXBegin(g_hleGxState.currentPrim, g_hleGxState.currentVtxFmt, static_cast<u16>(g_hleGxState.vertsRemaining));
        s_immediate.auroraSizeAtBegin = aurora::gx::fifo::get_buffer_size();
        g_hleGxState.auroraBeginCalled = true;
        GXMarkFrameWork();
    }

    auto finishVertexIfNeeded = [&](GXAttr prevAttr) {
        g_hleGxState.currentAttr = g_hleGxState.NextEnabledAttr(prevAttr);
        g_hleGxState.currentComp = 0;
        if (g_hleGxState.currentAttr == GX_VA_NULL) {
            g_hleGxState.ResetVertex();
            if (g_hleGxState.vertsRemaining > 0) {
                g_hleGxState.vertsRemaining--;
                if (g_hleGxState.vertsRemaining == 0) {
                    g_hleGxState.inBegin = false;
                    g_hleGxState.auroraBeginCalled = false;
                    if (!recordOnly) {
                        CheckImmediatePrimitive();
                        GXEnd();
                    }
                }
            }
        }
    };

    while (true) {
        if (!g_hleGxState.inBegin) {
            break;
        }
        GXAttr attr = g_hleGxState.currentAttr;
        if (attr == GX_VA_NULL) {
            resetFifoBuffer();
            return;
        }

        GXAttrType inputType = g_hleGxState.vtxDesc[attr];
        const VtxAttrFmt& fmt = g_hleGxState.vtxAttrFmt[g_hleGxState.currentVtxFmt][attr];

        if (IsMatrixIndexAttr(attr)) {
            if (g_hleGxState.fifoByteCount < 1) break;
            u32 raw = 0;
            if (!consumeBytes(1, raw)) break;
            if (!recordOnly) {
                GXMatrixIndex1u8(attr, static_cast<u8>(raw));
            }
            finishVertexIfNeeded(attr);
            continue;
        }

        if (inputType == GX_DIRECT) {
            if (attr == GX_VA_CLR0 || attr == GX_VA_CLR1) {
                const uint32_t colorSize = ColorByteSize(fmt.type, fmt.cnt);

                if (g_hleGxState.fifoByteCount < colorSize) break;

                uint8_t colorBytes[4] = {0, 0, 0, 0};
                uint8_t* data = fifoData();
                for (uint32_t i = 0; i < colorSize && i < 4; ++i) {
                    colorBytes[i] = data[i];
                }
                g_hleGxState.fifoReadOffset += colorSize;
                g_hleGxState.fifoByteCount -= colorSize;
                if (g_hleGxState.fifoByteCount == 0) {
                    g_hleGxState.fifoReadOffset = 0;
                }

                GXColor color{};
                DecodeColorBytes(colorBytes, fmt.type, fmt.cnt, color);

                if (!recordOnly) {
                    if (fmt.cnt == GX_CLR_RGB) {
                        GXColor3u8(color.r, color.g, color.b);
                    } else {
                        GXColor4u8(color.r, color.g, color.b, color.a);
                    }
                }
                finishVertexIfNeeded(attr);
                continue;
            }
            
            const int compSize = GetCompSizeBytes(fmt.type);
            if (compSize <= 0 || g_hleGxState.fifoByteCount < static_cast<size_t>(compSize)) break;
            u32 raw = 0;
            if (!consumeBytes(static_cast<uint32_t>(compSize), raw)) break;
            constexpr int kMaxComps = 9;
            if (g_hleGxState.currentComp < kMaxComps) {
                if (SubmitAttributeReadsFloatComps(attr, fmt)) {
                    g_hleGxState.compBuffer[g_hleGxState.currentComp] =
                        ConvertCompToFloat(raw, fmt.type, fmt.frac);
                }
                g_hleGxState.rawCompBuffer[g_hleGxState.currentComp] = raw;
            }
            g_hleGxState.currentComp++;
            const int expected = g_hleGxState.GetExpectedCompCount(attr, fmt);
            if (g_hleGxState.currentComp >= expected) {
                if (!recordOnly) {
                    SubmitAttribute(attr, g_hleGxState.compBuffer, fmt, g_hleGxState.rawCompBuffer);
                }
                finishVertexIfNeeded(attr);
            }
            continue;
        }

        if (inputType == GX_INDEX8 || inputType == GX_INDEX16) {
            const uint32_t idxSize = (inputType == GX_INDEX8) ? 1u : 2u;
            const uint32_t indexCount = (attr == GX_VA_NRM) ? NormalIndexCount(fmt) : 1u;
            if (g_hleGxState.fifoByteCount < idxSize * indexCount) break;
            u32 raw[3]{};
            for (uint32_t i = 0; i < indexCount; ++i) {
                if (!consumeBytes(idxSize, raw[i])) break;
            }
            if (!recordOnly) {
                if (attr == GX_VA_NRM && indexCount == 3u) {
                    SubmitIndexedNormalNBT3(raw, fmt);
                } else {
                    SubmitIndexedAttribute(attr, raw[0]);
                }
            }
            finishVertexIfNeeded(attr);
            continue;
        }

        g_hleGxState.currentAttr = g_hleGxState.NextEnabledAttr(attr);
        g_hleGxState.currentComp = 0;
        if (g_hleGxState.currentAttr == GX_VA_NULL) {
            g_hleGxState.ResetVertex();
        }
        break;
    }
}

// Must behave exactly like GX_HLE_FIFO_Write32/16/8 run byte by byte. The ring is not
// bulk-appended ahead of a single parse because the parser can re-enter GX HLE (nested display
// lists, XF/draw calls), which would misorder that work relative to the stream.
static void HleFifoWriteBurstChunked(const uint8_t* data, uint32_t sizeBytes) {
    uint32_t offset = 0;
    for (; offset + 4u <= sizeBytes; offset += 4u) {
        HleFifoWrite(ReadBE32(data + offset), 4);
    }
    if (offset + 2u <= sizeBytes) {
        HleFifoWrite(static_cast<u32>(ReadBE16(data + offset)), 2);
        offset += 2u;
    }
    if (offset < sizeBytes) {
        HleFifoWrite(static_cast<u32>(data[offset]), 1);
    }
}

// Applies complete register-load packets straight to the parser's own entry points, skipping the
// serialize/ring/re-parse round trip; returns bytes consumed, caller hands the rest to
// HleFifoWriteBurstChunked. Safe (per gd_fifo_hle.cpp's GdCanApplyDirect) only on a packet
// boundary with nothing buffered, outside a GXBegin packet and outside display-list recording;
// any other opcode or a truncated packet just stops the walk.
static uint32_t ApplyFifoPacketsDirect(const uint8_t* data, uint32_t sizeBytes) {
    uint32_t offset = 0;

    while (offset < sizeBytes) {
        // Re-tested per packet, not once per burst: nothing currently re-enters GX HLE mid-walk,
        // but if it ever does, breaking here just hands the remainder to the ring.
        if (IsDisplayListActive() || g_hleGxState.inBegin || g_hleGxState.fifoByteCount != 0) {
            break;
        }

        const uint8_t* packet = data + offset;
        const uint32_t avail = sizeBytes - offset;
        const uint8_t cmd = packet[0];
        const uint8_t opcode = cmd & GX_OPCODE_MASK_CMD;

        if (cmd == GX_NOP_CMD) {
            offset += 1u;
            continue;
        }

        if (cmd == GX_LOAD_BP_REG_CMD) {
            if (avail < 5u) break;
            const uint32_t bpWord = ReadBE32(packet + 1);
            GXApplyBPReg(static_cast<uint8_t>(bpWord >> 24), bpWord & 0x00FFFFFFu);
            offset += 5u;
            continue;
        }

        if (opcode == GX_LOAD_CP_REG_CMD) {
            if (avail < 6u) break;
            const uint8_t reg = packet[1];
            const uint32_t cpValue = ReadBE32(packet + 2);
            // Same function the parser calls, so the CP registers it does not
            // decode (0x30/0x40 among them) are dropped here identically.
            GxCpDecode::ApplyCpRegWrite(reg, cpValue);
            offset += 6u;
            continue;
        }

        if (opcode == GX_LOAD_XF_REG_CMD) {
            if (avail < 5u) break;
            const uint16_t countWords = ReadBE16(packet + 1);
            const uint32_t packetBytes = 1u + 4u + (static_cast<uint32_t>(countWords) + 1u) * 4u;
            if (avail < packetBytes) break;
            // Aurora parses consecutive XF loads in one pass. Keep the batch inside this burst so
            // CP/BP writes and any later HLE call retain their original order. (KartPad b435655.)
            uint32_t batchBytes = packetBytes;
            while (avail - batchBytes >= 5u) {
                const uint8_t* next = packet + batchBytes;
                if ((next[0] & GX_OPCODE_MASK_CMD) != GX_LOAD_XF_REG_CMD) break;
                const uint32_t nextBytes = 5u + (static_cast<uint32_t>(ReadBE16(next + 1)) + 1u) * 4u;
                if (avail - batchBytes < nextBytes) break;
                batchBytes += nextBytes;
            }
            GXCallDisplayList(packet, batchBytes);
            GXMarkFrameWork();
            offset += batchBytes;
            continue;
        }

        break;
    }

    return offset;
}

// Display-list recording just copies bytes into the guest list buffer and advances the shadow
// cursor, so a burst is one logical append as long as it doesn't cross the list end; a burst that
// would wrap falls back to the per-write path instead. Returns false to signal that fallback.
static bool WriteDisplayListBurst(const uint8_t* data, uint32_t sizeBytes) {
    GxDisplayListState& dl = g_dlRecordState;
    if (!dl.active || dl.base == 0 || dl.size == 0 || dl.writePtr == 0) {
        // Same guard WriteDisplayListData applies; let the per-write path
        // reproduce its (silent) drop.
        return false;
    }

    const uint32_t end = dl.base + dl.size;
    if (dl.writePtr > end || (end - dl.writePtr) < sizeBytes) {
        return false;
    }

    // Guest-visible bytes are written through the ordinary store path, so
    // unaligned cursors, MMIO policy and executable-write guards all behave as
    // they do for a single store. The cursor and count advance per element so a
    // failed write in the middle leaves exactly the completed prefix recorded.
    try {
        uint32_t offset = 0;
        for (; offset + 4u <= sizeBytes; offset += 4u) {
            Memory::Write32(dl.writePtr, ReadBE32(data + offset));
            dl.writePtr += 4u;
            dl.count += 4u;
        }
        if (offset + 2u <= sizeBytes) {
            Memory::Write16(dl.writePtr, ReadBE16(data + offset));
            dl.writePtr += 2u;
            dl.count += 2u;
            offset += 2u;
        }
        if (offset < sizeBytes) {
            Memory::Write8(dl.writePtr, data[offset]);
            dl.writePtr += 1u;
            dl.count += 1u;
        }
    } catch (const Memory::AccessViolation&) {
    }
    return true;
}

extern "C" void GX_HLE_FIFO_WriteBurst(const uint8_t* data, uint32_t sizeBytes) {
    if (data == nullptr || sizeBytes == 0) {
        return;
    }

    if (IsDisplayListActive() && WriteDisplayListBurst(data, sizeBytes)) {
        return;
    }

    const uint32_t applied = ApplyFifoPacketsDirect(data, sizeBytes);
    if (applied < sizeBytes) {
        HleFifoWriteBurstChunked(data + applied, sizeBytes - applied);
    }
}
