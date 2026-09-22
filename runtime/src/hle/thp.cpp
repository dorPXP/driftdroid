// Native THP video/audio decode, replacing the guest's own decoder.
//
// THP is essentially MJPEG, and the guest decoder is the single most expensive thing in the game
// outside a race: profiles put the Select Cup screen at 9 FPS with >55% of the frame inside
// __THPInverseDCTNoYPos / __THPInverseDCTY8 / __THPHuffDecodeDCTComp*, and the attract-mode demo
// at ~72% THP decode. Running it as translated PPC costs roughly six ARM64 instructions per guest
// instruction, so a native decoder is worth far more here than anywhere else in the game.
//
// The decoder itself is aurora's (upstream commit 3251f4e2, "Add aurora::thp implementation"),
// which upstream verified bit-exact against Dolphin for both THPVideoDecode and THPAudioDecode.
// This file is only the guest-ABI shim: guest addresses in, host pointers out.

#include "hle_stubs.h"
#include "memory_access.h"
#include "runtime_log.h"

#include <dolphin/thp.h>

#include <cstdint>
#include <cstdio>
#include <algorithm>
#include <cstdlib>

namespace {

// The guest hands us MEM1/MEM2 addresses. Sizes are not known up front (the frame header carries
// them), so map a single byte and rely on the decoder staying inside the buffer the game sized -
// the same contract the translated code had.
void* GuestPtr(uint32_t addr) {
    if (addr == 0) {
        return nullptr;
    }
    try {
        return Memory::GetPointer(addr, 1);
    } catch (const Memory::AccessViolation& e) {
        LogMemoryError(RT_TAG_RUNTIME, "THP guest pointer", e);
        return nullptr;
    }
}

} // namespace

extern "C" uint32_t THP__VideoDecode_801b3bac(uint32_t fileAddr, uint32_t tileYAddr, uint32_t tileUAddr,
                                              uint32_t tileVAddr, uint32_t workAddr) {
    // Chroma came out neutral grey on hardware while luma was perfect, which means the U/V writes
    // are not landing where the game reads them. Log the first few calls so the guest-side buffer
    // addresses and the decoder's verdict are visible instead of guessed at.
    // Sample well into the movie, not the opening frames: those are a white title card, where
    // neutral 0x80 chroma is the *correct* answer and proves nothing.
    static int s_calls = 0;
    const int callIndex = s_calls++;
    const bool logThis = (callIndex % 60) == 0 && callIndex < 1800;
    // Pass nulls straight through rather than short-circuiting: aurora returns kNoInput for a null
    // file and kNoOutput for a null tile, whereas returning 0 here would tell the game the frame
    // decoded fine and leave it presenting stale tile memory.
    // `work` is unused by aurora's decoder, but pass it through so the signature keeps matching.
    const uint32_t result = static_cast<uint32_t>(THPVideoDecode(GuestPtr(fileAddr), GuestPtr(tileYAddr),
                                                                 GuestPtr(tileUAddr), GuestPtr(tileVAddr),
                                                                 GuestPtr(workAddr)));
    if (logThis) {
        // RT_LOGF output never reached the SD card on Switch (console.log held only its header),
        // so write straight to a file the way the sampling profiler does - reopened and closed each
        // time, because a held-open file reports size 0 over the SD card.
        if (FILE* out = std::fopen("sdmc:/switch/WiiCompiled/thp_debug.txt", "a")) {
            const auto* u = static_cast<const unsigned char*>(GuestPtr(tileUAddr));
            const auto* v = static_cast<const unsigned char*>(GuestPtr(tileVAddr));
            const auto* y = static_cast<const unsigned char*>(GuestPtr(tileYAddr));
            // Spread over the whole plane, not just byte 0: report how far chroma strays from
            // neutral 0x80. A genuinely colourful frame must show a non-trivial deviation.
            long uDev = 0, vDev = 0, yMin = 255, yMax = 0;
            const int kSamples = 16384;
            for (int i = 0; i < kSamples; ++i) {
                if (u) uDev += std::abs(static_cast<int>(u[i]) - 128);
                if (v) vDev += std::abs(static_cast<int>(v[i]) - 128);
                if (y) { yMin = std::min<long>(yMin, y[i]); yMax = std::max<long>(yMax, y[i]); }
            }
            std::fprintf(out, "call=%d y=%08X u=%08X v=%08X -> %d  uDev=%ld vDev=%ld  yRange=%ld..%ld\n",
                         callIndex, tileYAddr, tileUAddr, tileVAddr, static_cast<int>(result),
                         uDev / kSamples, vDev / kSamples, yMin, yMax);
            std::fclose(out);
        }
    }
    return result;
}
PPC_NATIVE_OVERRIDE(801B3BAC, THP__VideoDecode_801b3bac, uint32_t,
                    (uint32_t fileAddr, uint32_t tileYAddr, uint32_t tileUAddr, uint32_t tileVAddr,
                     uint32_t workAddr),
                    (fileAddr, tileYAddr, tileUAddr, tileVAddr, workAddr));

// NOT overriding THPInit (0x801b6ee0). aurora's THPInit() is just `return TRUE`, because its
// decoder needs no setup - but the guest's is 39 real instructions, and the game's *own* THP code
// (THP::AudioDecoder and the player at 0x805508e8 onwards) still runs as translated PPC and may
// depend on whatever state that sets up. Replacing it with a stub left that code running against
// uninitialised state. Only the pure leaf decode function is safe to take over.
