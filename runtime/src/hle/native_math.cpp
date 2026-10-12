// Native versions of a few small, very hot maths routines of the game's libraries.
//
// As translated code each of these costs several hundred ARM64 instructions per call (a 3x4 matrix
// multiply executes 344, a 4x4 copy 291), almost all of it register-file traffic around a couple
// of dozen real operations. Written directly against guest memory they need a fraction of that.
//
// They replace the translated functions at link time (`-Wl,--wrap=func_ADDR`, see
// MKW_NATIVE_MATH_WRAPS in runtime/CMakeLists.txt): every call from another object file and the
// dispatch table reach `__wrap_func_ADDR` below, and the translated body stays linked as
// `__real_func_ADDR`. Nothing in generated/ changes, so no regeneration and no new profile run.
//
// Rules every routine here follows, so that the game cannot tell the difference:
//  - Same results bit for bit, including the Wii's handling of odd values: a signalling NaN is
//    quieted when a pair of floats is loaded, a stored float has NaNs quieted and denormals
//    flushed to a signed zero, and each multiply-add is one fused operation in the original's
//    operand order. Checked against the translated code in an ARM64 emulator on random, special
//    and overlapping inputs (~/devtools/native-math-harness).
//  - Same order of loads and stores wherever an output may overlap an input.
//  - Anything unusual (an output in the hardware register window, quantisation register 0 not set
//    to plain floats) goes to the translated original, which keeps its exact slow paths.
//  - Only memory and the registers a caller may read afterwards are written. The translated
//    originals also leave scratch values in volatile registers; those are not reproduced.
//
// `sdmc:/switch/WiiCompiled/no_native_math.flag` turns all of them off (for comparing speed), and
// `native_math_check.flag` runs both versions on every call and counts differences in the log.

#if defined(__aarch64__) && defined(MKW_NATIVE_MATH)

#include "abi_bridge.h"
#include "memory.h"
#include "ppc_runtime.h"
#include "runtime_log.h"

#include <arm_neon.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {

// The fast path of every entry point is one straight run of code with no call in it.
#define MKW_NATIVE_INLINE inline __attribute__((always_inline))

// 0 = native, 1 = always the translated original, 2 = run both and compare.
int g_mode = 0;
uint64_t g_checkCalls = 0;
uint64_t g_checkMismatches = 0;

// Whole range inside MEM1 or MEM2, where a guest access is plain host memory.
inline bool InRam(uint32_t addr, uint32_t length) {
    return (addr - 0x80000000u <= 0x01800000u - length) || (addr - 0x90000000u <= 0x04000000u - length);
}

// The translated code's own test for a store it can do directly: no wrap past the top of the
// address space and neither end inside the hardware register window (0xCC000000..0xCDFFFFFF).
// Its paired-float loads have no test at all, so inputs need none here either.
inline bool StoreIsPlain(uint32_t addr, uint32_t length) {
    const uint32_t last = addr + (length - 1u);
    return last >= addr && (addr & 0xFE000000u) != 0xCC000000u && (last & 0xFE000000u) != 0xCC000000u;
}

inline uint8_t* Host(uint32_t addr) { return MKW_FLAT_GUEST_BASE + addr; }

// Paired float load (psq_l, plain floats): big-endian, signalling NaNs quieted.
inline float32x4_t Load4(uint32_t addr) {
    const uint32x4_t bits = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(Host(addr))));
    const uint32x4_t isNan = vcgtq_u32(vandq_u32(bits, vdupq_n_u32(0x7FFFFFFFu)), vdupq_n_u32(0x7F800000u));
    return vreinterpretq_f32_u32(vorrq_u32(bits, vandq_u32(isNan, vdupq_n_u32(0x00400000u))));
}

inline float32x2_t Load2(uint32_t addr) {
    const uint32x2_t bits = vreinterpret_u32_u8(vrev32_u8(vld1_u8(Host(addr))));
    const uint32x2_t isNan = vcgt_u32(vand_u32(bits, vdup_n_u32(0x7FFFFFFFu)), vdup_n_u32(0x7F800000u));
    return vreinterpret_f32_u32(vorr_u32(bits, vand_u32(isNan, vdup_n_u32(0x00400000u))));
}

// One float loaded through psq_l: the bits as they are.
inline float Load1Raw(uint32_t addr) {
    uint32_t bits;
    std::memcpy(&bits, Host(addr), sizeof(bits));
    bits = __builtin_bswap32(bits);
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

// What a float store (psq_st, plain floats) writes: NaNs quieted, denormals flushed to a signed zero.
inline uint32x4_t StoreBits4(float32x4_t value) {
    const uint32x4_t bits = vreinterpretq_u32_f32(value);
    const uint32x4_t exponent = vdupq_n_u32(0x7F800000u);
    const uint32x4_t isNan = vcgtq_u32(vandq_u32(bits, vdupq_n_u32(0x7FFFFFFFu)), exponent);
    const uint32x4_t isSubnormal = vceqq_u32(vandq_u32(bits, exponent), vdupq_n_u32(0));
    const uint32x4_t quieted = vorrq_u32(bits, vandq_u32(isNan, vdupq_n_u32(0x00400000u)));
    return vbslq_u32(isSubnormal, vandq_u32(bits, vdupq_n_u32(0x80000000u)), quieted);
}

inline void Store4(uint32_t addr, float32x4_t value) {
    vst1q_u8(Host(addr), vrev32q_u8(vreinterpretq_u8_u32(StoreBits4(value))));
}

inline void Store2(uint32_t addr, float32x2_t value) {
    vst1_u8(Host(addr), vrev32_u8(vreinterpret_u8_u32(vget_low_u32(StoreBits4(vcombine_f32(value, value))))));
}

inline void Store1(uint32_t addr, float value) {
    const uint32_t bits = __builtin_bswap32(vgetq_lane_u32(StoreBits4(vdupq_n_f32(value)), 0));
    std::memcpy(Host(addr), &bits, sizeof(bits));
}

// Quantisation register 0 as the SDK always leaves it: plain floats for loads and stores.
inline bool PlainFloatGqr(const CpuContext* ctx) { return ctx->gqr[0] == 0; }

// --- running both versions and comparing (native_math_check.flag) ------------------------------

struct Range {
    uint32_t addr;
    uint32_t length;
};

constexpr uint32_t kCheckBytes = 256;

void CheckedCall(CpuContext* ctx, uint32_t guestAddress, void (*real)(CpuContext*), void (*native)(CpuContext*),
                 const Range* touched, int touchedCount, const Range& output, bool compareR3) {
    uint8_t before[4][kCheckBytes];
    uint8_t expected[kCheckBytes];
    for (int i = 0; i < touchedCount; ++i) {
        if (touched[i].length > kCheckBytes || !InRam(touched[i].addr, touched[i].length)) {
            real(ctx);
            return;
        }
        std::memcpy(before[i], Host(touched[i].addr), touched[i].length);
    }
    CpuContext copy = *ctx;
    real(&copy);
    std::memcpy(expected, Host(output.addr), output.length);
    for (int i = 0; i < touchedCount; ++i) {
        std::memcpy(Host(touched[i].addr), before[i], touched[i].length);
    }
    native(ctx);
    ++g_checkCalls;
    const bool same = std::memcmp(expected, Host(output.addr), output.length) == 0 &&
                      (!compareR3 || copy.gpr[3] == ctx->gpr[3]);
    if (!same) {
        if (++g_checkMismatches <= 20) {
            RT_LOGF(RT_TAG_RUNTIME, "native maths differs at %08X (call %llu of this run, lr %08X)\n", guestAddress,
                    static_cast<unsigned long long>(g_checkCalls), ctx->lr);
        }
        // Keep the game on the original's answer.
        std::memcpy(Host(output.addr), expected, output.length);
        ctx->gpr[3] = copy.gpr[3];
    }
    if ((g_checkCalls & 0xFFFFFu) == 0) {
        RT_LOGF(RT_TAG_RUNTIME, "native maths check: %llu calls, %llu differences\n",
                static_cast<unsigned long long>(g_checkCalls), static_cast<unsigned long long>(g_checkMismatches));
    }
}

// --- MTX: PSMTXConcat (0x80199D64) -------------------------------------------------------------
// r3 = a, r4 = b, r5 = ab; all 3x4 float matrices, ab may be a or b.

constexpr uint32_t kUnit01Pair = 0x80385A08u;  // the routine's own { 0.0f, 1.0f } constant

inline float32x4_t ConcatRow(float32x4_t a, float32x4_t b0, float32x4_t b1, float32x4_t b2, float32x2_t unit01) {
    float32x4_t row = vmulq_laneq_f32(b0, a, 0);
    row = vfmaq_laneq_f32(row, b1, a, 1);
    row = vfmaq_laneq_f32(row, b2, a, 2);
    // Only the last two columns take the translation term, as in the original.
    return vcombine_f32(vget_low_f32(row), vfma_laneq_f32(vget_high_f32(row), unit01, a, 3));
}

MKW_NATIVE_INLINE void NativePSMTXConcat(CpuContext* ctx) {
    const uint32_t a = ctx->gpr[3];
    const uint32_t b = ctx->gpr[4];
    const uint32_t ab = ctx->gpr[5];
    const float32x4_t a0 = Load4(a);
    const float32x4_t b0 = Load4(b);
    const float32x4_t b1 = Load4(b + 16);
    const float32x4_t a1 = Load4(a + 16);
    const float32x2_t unit01 = Load2(kUnit01Pair);
    const float32x4_t b2 = Load4(b + 32);
    const float32x4_t a2 = Load4(a + 32);
    Store4(ab, ConcatRow(a0, b0, b1, b2, unit01));
    Store4(ab + 16, ConcatRow(a1, b0, b1, b2, unit01));
    Store4(ab + 32, ConcatRow(a2, b0, b1, b2, unit01));
}

MKW_NATIVE_INLINE bool ConcatIsPlain(const CpuContext* ctx) {
    return PlainFloatGqr(ctx) && StoreIsPlain(ctx->gpr[5], 48);
}

// --- MTX: PSMTXMultVec (0x8019A91C) ------------------------------------------------------------
// r3 = m (3x4), r4 = src, r5 = dst (3 floats each); dst may be src.

inline float MultVecRow(float32x4_t row, float32x2_t xy, float32x2_t zOne) {
    const float32x2_t sum = vfma_f32(vmul_f32(vget_low_f32(row), xy), vget_high_f32(row), zOne);
    return PpcForceSingleValueInline(static_cast<double>(vget_lane_f32(sum, 0)) + vget_lane_f32(sum, 1));
}

MKW_NATIVE_INLINE void NativePSMTXMultVec(CpuContext* ctx) {
    const uint32_t m = ctx->gpr[3];
    const uint32_t src = ctx->gpr[4];
    const uint32_t dst = ctx->gpr[5];
    const float32x2_t xy = Load2(src);
    const float32x4_t row0 = Load4(m);
    const float32x2_t zOne = vset_lane_f32(1.0f, vdup_n_f32(Load1Raw(src + 8)), 1);
    const float32x4_t row1 = Load4(m + 16);
    // The original stores x before it loads the third row, and y before nothing else is read.
    Store1(dst, MultVecRow(row0, xy, zOne));
    const float32x4_t row2 = Load4(m + 32);
    Store1(dst + 4, MultVecRow(row1, xy, zOne));
    Store1(dst + 8, MultVecRow(row2, xy, zOne));
}

MKW_NATIVE_INLINE bool MultVecIsPlain(const CpuContext* ctx) {
    // The single floats (z in, x/y/z out) go through the page tables in the original: keep those
    // two pointers to main memory, where that is the same thing as a direct access.
    return PlainFloatGqr(ctx) && InRam(ctx->gpr[4], 12) && InRam(ctx->gpr[5], 12);
}

// --- nw4r::math::MTX44Copy (0x80085B80) --------------------------------------------------------
// r3 = dst, r4 = src; 16 floats, all loaded before any is stored.

MKW_NATIVE_INLINE void NativeMTX44Copy(CpuContext* ctx) {
    const uint32_t dst = ctx->gpr[3];
    const uint32_t src = ctx->gpr[4];
    const float32x4_t r0 = Load4(src);
    const float32x4_t r1 = Load4(src + 16);
    const float32x4_t r2 = Load4(src + 32);
    const float32x4_t r3 = Load4(src + 48);
    Store4(dst, r0);
    Store4(dst + 16, r1);
    Store4(dst + 32, r2);
    Store4(dst + 48, r3);
}

MKW_NATIVE_INLINE bool Mtx44CopyIsPlain(const CpuContext* ctx) {
    return PlainFloatGqr(ctx) && StoreIsPlain(ctx->gpr[3], 64);
}

// --- memcpy (0x80005F34) -----------------------------------------------------------------------
// r3 = dst, r4 = src, r5 = count. The game's memcpy is a byte-at-a-time memmove: forwards when
// src >= dst, backwards otherwise. The registers it leaves behind are reproduced too (r0 = last
// byte moved, r4/r6 = where the two cursors stopped, r5 = 0, cr0 = "equal", carry set).

MKW_NATIVE_INLINE void NativeMemcpy(CpuContext* ctx) {
    const uint32_t dst = ctx->gpr[3];
    const uint32_t src = ctx->gpr[4];
    const uint32_t count = ctx->gpr[5];
    if (count != 0) {
        const bool forward = src >= dst;
        // The last byte the original loop reads: read it before the move in case the ranges overlap
        // (it is still untouched at that point in the original, which is why its value is this one).
        const uint8_t last = forward ? *Host(src + count - 1) : *Host(src);
        std::memmove(Host(dst), Host(src), count);
        ctx->gpr[0] = last;
        ctx->gpr[4] = forward ? src + count - 1 : src;
        ctx->gpr[6] = forward ? dst + count - 1 : dst;
    } else {
        const bool forward = src >= dst;
        ctx->gpr[4] = forward ? src - 1 : src;
        ctx->gpr[6] = forward ? dst - 1 : dst;
    }
    ctx->gpr[5] = 0;
    ctx->xer |= 0x20000000u;                                              // carry out of the last count - 1
    ctx->cr = (ctx->cr & 0x0FFFFFFFu) | 0x20000000u | ((ctx->xer >> 3) & 0x10000000u);  // cr0: equal, plus SO
}

MKW_NATIVE_INLINE bool MemcpyIsPlain(const CpuContext* ctx) {
    const uint32_t count = ctx->gpr[5];
    // An empty copy only moves registers. A real one must be a plain store with no address wrap.
    return count == 0 || (count <= 0x01000000u && StoreIsPlain(ctx->gpr[3], count) && ctx->gpr[4] <= UINT32_MAX - count);
}

}  // namespace

// The uncommon cases live in their own function so the entry point itself needs no stack frame.
#define MKW_NATIVE_MATH_ENTRY(address, native, isPlain, ...)                                          \
    extern "C" void __real_func_##address(CpuContext* ctx);                                           \
    namespace {                                                                                       \
    __attribute__((noinline, cold)) void Uncommon_##address(CpuContext* ctx) {                        \
        if (g_mode == 2 && isPlain(ctx)) {                                                            \
            __VA_ARGS__;                                                                              \
            return;                                                                                   \
        }                                                                                             \
        __real_func_##address(ctx);                                                                   \
    }                                                                                                 \
    }                                                                                                 \
    extern "C" void __wrap_func_##address(CpuContext* ctx) {                                          \
        if (g_mode == 0 && isPlain(ctx)) [[likely]] {                                                 \
            native(ctx);                                                                              \
            return;                                                                                   \
        }                                                                                             \
        Uncommon_##address(ctx);                                                                      \
    }

MKW_NATIVE_MATH_ENTRY(80199D64, NativePSMTXConcat, ConcatIsPlain, {
    const Range touched[] = {{ctx->gpr[3], 48}, {ctx->gpr[4], 48}, {ctx->gpr[5], 48}};
    CheckedCall(ctx, 0x80199D64u, __real_func_80199D64, [](CpuContext* c) { NativePSMTXConcat(c); }, touched, 3, touched[2], false);
})

MKW_NATIVE_MATH_ENTRY(8019A91C, NativePSMTXMultVec, MultVecIsPlain, {
    const Range touched[] = {{ctx->gpr[3], 48}, {ctx->gpr[4], 12}, {ctx->gpr[5], 12}};
    CheckedCall(ctx, 0x8019A91Cu, __real_func_8019A91C, [](CpuContext* c) { NativePSMTXMultVec(c); }, touched, 3, touched[2], false);
})

MKW_NATIVE_MATH_ENTRY(80085B80, NativeMTX44Copy, Mtx44CopyIsPlain, {
    const Range touched[] = {{ctx->gpr[3], 64}, {ctx->gpr[4], 64}};
    CheckedCall(ctx, 0x80085B80u, __real_func_80085B80, [](CpuContext* c) { NativeMTX44Copy(c); }, touched, 2, touched[0], false);
})

MKW_NATIVE_MATH_ENTRY(80005F34, NativeMemcpy, MemcpyIsPlain, {
    const Range touched[] = {{ctx->gpr[3], ctx->gpr[5]}, {ctx->gpr[4], ctx->gpr[5]}};
    CheckedCall(ctx, 0x80005F34u, __real_func_80005F34, [](CpuContext* c) { NativeMemcpy(c); }, touched, 2, touched[0], true);
})

// Called once from main() after the SD card is reachable.
extern "C" void NativeMath_ReadFlags() {
#if defined(__SWITCH__)
    if (FILE* off = std::fopen("sdmc:/switch/WiiCompiled/no_native_math.flag", "r")) {
        std::fclose(off);
        g_mode = 1;
        RT_LOGF(RT_TAG_RUNTIME, "native maths routines turned off by no_native_math.flag\n");
    } else if (FILE* check = std::fopen("sdmc:/switch/WiiCompiled/native_math_check.flag", "r")) {
        std::fclose(check);
        g_mode = 2;
        RT_LOGF(RT_TAG_RUNTIME, "native maths routines: comparing every call with the translated code\n");
    }
#endif
}

#else

extern "C" void NativeMath_ReadFlags() {}

#endif  // __aarch64__ && MKW_NATIVE_MATH
