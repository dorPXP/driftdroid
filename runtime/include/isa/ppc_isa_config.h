#pragma once

#include <atomic>
#include <cstdint>

#define MKW_RESTRICT __restrict

#if defined(__aarch64__)
#include <arm_neon.h>
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
