#pragma once

#include <atomic>
#include <cstdint>

#define MKW_RESTRICT __restrict

#if defined(__aarch64__)
// The ISA headers (ppc_isa_float.h, ppc_isa_quantized.h) are written against the SSE/SSE2
// intrinsic surface directly - sse2neon.h supplies that same __m128/__m128i/__m128d API backed
// by NEON, so those files need NO changes at all on arm64 (verified compiling both unchanged
// through this header for arm64-v8a). Vendored at runtime/third_party/sse2neon (MIT, upstream
// github.com/DLTcollab/sse2neon) rather than hand-porting every intrinsic call site individually
// - a sibling ARM64 static-recompilation port of this same upstream project (for Apple platforms)
// took the identical approach for the identical reason.
#include "../../third_party/sse2neon/sse2neon.h"

// sse2neon deliberately stops at SSE4.2 (see its own header comment); the translated PPC helpers
// use these two FMA3 intrinsics (introduced with AVX2/FMA, i.e. after SSE4.2) to get a single
// rounding step for paired-single arithmetic. Both map to one NEON fused multiply-add/subtract
// instruction, so they're exact per-lane single roundings, matching what the x86 FMA3 path does.
inline __m128 _mm_fmadd_ps(__m128 a, __m128 b, __m128 c)
{
    return vfmaq_f32(c, a, b);
}

inline __m128 _mm_fmsub_ps(__m128 a, __m128 b, __m128 c)
{
    return vfmaq_f32(vnegq_f32(c), a, b);
}
#else
#include <immintrin.h>
#endif

inline constexpr bool MkwStateFreeAbiEnabled(uint32_t) noexcept
{
    return true;
}

#if defined(_WIN32)
#define MKW_PPC_FORCE_INLINE __forceinline
#define MKW_PPC_NO_INLINE __declspec(noinline)
#define MKW_PPC_INTERNAL_CALL __regcall
#else
// __forceinline/__declspec are MS-extension keywords Clang only recognizes when targeting
// Windows (MSVC or mingw); native Linux/Android Clang needs the GNU-attribute spellings
// instead. __regcall has no portable non-Windows equivalent worth chasing here (and the code
// generator's StateFreeCallingConvention() always emits an empty string anyway — see
// CxxLinearCodeGenerator.StateFreeAbi.cs:145; __regcall was abandoned there because a
// state-free callee using host r12 as scratch once collided with it) — plain calls are fine.
#define MKW_PPC_FORCE_INLINE __attribute__((always_inline)) inline
#define MKW_PPC_NO_INLINE __attribute__((noinline))
#define MKW_PPC_INTERNAL_CALL
#endif
#define MKW_PPC_ALWAYS_INLINE_BODY __attribute__((always_inline))
#define MKW_PPC_COLD __attribute__((cold))


// ext_vector_type is a portable Clang vector extension (not x86-specific), so the same
// definition works unchanged on arm64.
using MkwStateFreeResult2 = uint64_t __attribute__((ext_vector_type(2)));
