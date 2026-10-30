// TEMP-SNDAUDIT (desktop test builds only, linked with -Wl,--wrap for the functions below): logs
// the game's heap calls made by the sound host thread, and whether the emulation thread was
// stopped at the time. On a Wii the sound thread never runs at the same time as the game; here it
// does, so a heap call from it with the game running can corrupt the heap lists.
#if defined(MKW_SOUND_AUDIT)

#include "abi_bridge.h"
#include "sound_core.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <set>
#include <utility>

namespace SoundCore {
bool SoundThreadHasWorldStopped(); // sound_core.cpp
}

namespace {

std::mutex g_seenMutex;
std::set<std::pair<uint32_t, uint32_t>> g_seen;
std::atomic<uint64_t> g_unprotected{0};

void Audit(const char* name, uint32_t address, CpuContext* ctx)
{
    if (!SoundCore::OnSoundThread()) {
        return;
    }
    const bool stopped = SoundCore::SoundThreadHasWorldStopped();
    const bool section = !SoundCore::SoundThreadInterruptsEnabled();
    if (!stopped) {
        g_unprotected.fetch_add(1, std::memory_order_relaxed);
    }
    std::lock_guard<std::mutex> lock(g_seenMutex);
    if (g_seen.insert({address, ctx->lr}).second || (!stopped && g_unprotected.load() % 100 == 1)) {
        std::printf("[snd-audit] %s from lr %08X r3 %08X r4 %08X: world %s, sound section %s (unprotected so far %llu)\n",
                    name, ctx->lr, ctx->gpr[3], ctx->gpr[4], stopped ? "stopped" : "RUNNING", section ? "yes" : "no",
                    static_cast<unsigned long long>(g_unprotected.load()));
        std::fflush(stdout);
    }
}

} // namespace

#define MKW_AUDIT_WRAP(ADDR, NAME)                                    \
    extern "C" void __real_func_##ADDR(CpuContext* ctx);              \
    extern "C" void __wrap_func_##ADDR(CpuContext* ctx)               \
    {                                                                 \
        Audit(NAME, 0x##ADDR##u, ctx);                                \
        __real_func_##ADDR(ctx);                                      \
    }

MKW_AUDIT_WRAP(80229814, "EGG::Heap::Alloc")
MKW_AUDIT_WRAP(80229B84, "EGG::Heap::Free")
MKW_AUDIT_WRAP(80226C04, "EGG::ExpHeap::Alloc")
MKW_AUDIT_WRAP(80226C78, "EGG::ExpHeap::free")

#endif
