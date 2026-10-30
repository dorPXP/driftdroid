#include "sound_core.h"

#include "abi_bridge.h"
#include "hle_stubs.h"
#include "memory.h"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>

namespace OsHleInternal {
extern std::atomic<bool> g_interrupts_enabled;
}

// Switch: core placement and priority for the sound host thread (main.cpp).
extern "C" void SwitchConfigureSoundThread() __attribute__((weak));

extern "C" void OS__SleepTicks_HLE_801aaca8(CpuContext* ctx);

namespace SoundCore {
namespace {

// OSThread: stack base (high end) and stack end (low end).
constexpr uint32_t kThreadStackBaseOffset = 0x304u;
constexpr uint32_t kThreadStackEndOffset = 0x308u;
constexpr uint32_t kMessageBlockReady = 1u;
constexpr uint32_t kMessageShutdown = 2u;
constexpr size_t kQueueCapacity = 4;

thread_local bool t_onSoundThread = false;
thread_local bool t_interruptsEnabled = true;
thread_local bool t_processingMessage = false;

std::mutex g_stateMutex;
std::condition_variable g_messageReady;
std::condition_variable g_idle;
std::array<uint32_t, kQueueCapacity> g_queue{};
size_t g_queueHead = 0;
size_t g_queueCount = 0;
// Messages queued plus the one being processed.
uint32_t g_outstanding = 0;
// Messages sent from inside the audio interrupt are released when it returns, as on a console
// where the woken sound thread cannot run until the handler is done.
bool g_staging = false;
std::array<uint32_t, kQueueCapacity> g_staged{};
size_t g_stagedCount = 0;
std::thread g_thread;
bool g_threadRunning = false;
uint64_t g_droppedMessages = 0;

// What the split costs and saves, reported every 20 s in the log.
struct Stats {
    std::atomic<uint64_t> blocks{0};
    std::atomic<uint64_t> soundBusyMicros{0};
    std::atomic<uint64_t> idleWaits{0};
    std::atomic<uint64_t> idleWaitMicros{0};
    std::atomic<uint64_t> mutexWaits{0};
    std::atomic<uint64_t> mutexWaitMicros{0};
    std::atomic<uint64_t> mainSectionWaits{0};
    std::atomic<uint64_t> soundSectionBackoffs{0};
    // Part of soundBusyMicros: the sound thread waiting for the emulation thread's sections.
    std::atomic<uint64_t> soundSectionWaitMicros{0};
    std::atomic<uint64_t> worldStops{0};
};
Stats g_stats;
using StatsClock = std::chrono::steady_clock;
thread_local StatsClock::time_point t_blockStart;

uint64_t MicrosSince(StatsClock::time_point start)
{
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(StatsClock::now() - start).count());
}

// Sound thread, between blocks.
void ReportStats()
{
    static StatsClock::time_point lastReport = StatsClock::now();
    const double seconds = std::chrono::duration<double>(StatsClock::now() - lastReport).count();
    if (seconds < 20.0) {
        return;
    }
    lastReport = StatsClock::now();
    const auto take = [](std::atomic<uint64_t>& value) { return value.exchange(0, std::memory_order_relaxed); };
    const uint64_t blocks = take(g_stats.blocks);
    std::printf("[sound_core] per second: blocks %.0f, sound thread busy %.1f ms (of it waiting for main's sections "
                "%.1f ms), main waits: idle %.1f ms (%.0f), "
                "mutex %.2f ms (%.0f), section %.0f; sound backoffs %.0f, world stops %llu; dropped total %llu\n",
                blocks / seconds, take(g_stats.soundBusyMicros) / 1000.0 / seconds,
                take(g_stats.soundSectionWaitMicros) / 1000.0 / seconds,
                take(g_stats.idleWaitMicros) / 1000.0 / seconds, take(g_stats.idleWaits) / seconds,
                take(g_stats.mutexWaitMicros) / 1000.0 / seconds, take(g_stats.mutexWaits) / seconds,
                take(g_stats.mainSectionWaits) / seconds, take(g_stats.soundSectionBackoffs) / seconds,
                static_cast<unsigned long long>(take(g_stats.worldStops)),
                static_cast<unsigned long long>(g_droppedMessages));
    std::fflush(stdout);
}

// The sound thread's mutex. The main thread takes the guest mutex first (exclusion between guest
// threads sharing the emulation thread), then this one (exclusion against the sound host thread).
//
// A recursive mutex that knows its owner, instead of std::recursive_mutex: an unlock by a thread
// that does not hold it is undefined there. glibc refuses it; libnx's RMutex decrements the count
// anyway, wraps it, and the next lock leaves the mutex held by that thread for good - on Switch the
// sound thread kept running while the game waited forever at the Retro WFC login (2026-10-21; the
// PC never froze). Here such an unlock is ignored and reported.
std::atomic<std::thread::id> g_soundThreadId{};

class SoundMutex {
public:
    bool try_lock()
    {
        std::lock_guard<std::mutex> lock(m_);
        return TakeLocked();
    }
    void lock()
    {
        std::unique_lock<std::mutex> lock(m_);
        cv_.wait(lock, [this] { return TakeLocked(); });
    }
    void unlock()
    {
        {
            std::lock_guard<std::mutex> lock(m_);
            if (depth_ == 0 || owner_ != std::this_thread::get_id()) {
                static std::atomic<uint32_t> reports{0};
                if (reports.fetch_add(1, std::memory_order_relaxed) < 10) {
                    std::printf("[sound_core] ignored an unlock of the sound mutex by a thread that does not hold it "
                                "(%s)\n",
                                t_onSoundThread ? "sound thread" : "emulation thread");
                    std::fflush(stdout);
                }
                return;
            }
            if (--depth_ != 0) {
                return;
            }
            owner_ = std::thread::id();
        }
        cv_.notify_all();
    }

private:
    // m_ held.
    bool TakeLocked()
    {
        const std::thread::id self = std::this_thread::get_id();
        if (depth_ != 0 && owner_ != self) {
            return false;
        }
        owner_ = self;
        ++depth_;
        return true;
    }

    std::mutex m_;
    std::condition_variable cv_;
    std::thread::id owner_;
    uint32_t depth_ = 0;
};
SoundMutex g_soundMutex;

// Interrupt sections, asymmetric Dekker: each side raises its flag and then looks at the other;
// the sound thread is the one that backs off.
std::atomic<bool> g_soundInSection{false};
// Main thread is blocked waiting for the sound thread (mutex or idle wait). On a console that is
// the main thread asleep with the sound thread running, so its interrupt section does not count.
std::atomic<bool> g_mainYielded{false};

// Every wait between the two threads lasts until it is over. They used to give up after 250 ms
// and go on unprotected, so both threads ran the same section at once: Retro Rewind rooms and
// race starts on Switch crashed right after "gave up" lines (2026-10-22: Mii heads built from
// garbage, then EGG's heap list corrupted), and never with the sound core off. A long wait is
// reported instead, so a freeze still says where it is.
constexpr auto kFirstReport = std::chrono::milliseconds(100);
constexpr auto kReportEvery = std::chrono::seconds(1);

// Where the emulation thread last turned interrupts off (host return address, os_interrupt.cpp).
// It names the main-thread section a waiting sound thread is stuck behind.
} // namespace
std::atomic<const void*> g_mainSectionSite{nullptr};
namespace {

void ReportLongWait(const char* what, StatsClock::duration waited)
{
    static std::atomic<uint32_t> reports{0};
    if (reports.fetch_add(1, std::memory_order_relaxed) >= 60) {
        return;
    }
    // Module-relative through a known function: <site> = <address of SoundCore::Enabled in the
    // ELF> + offset, for addr2line.
    const auto site = reinterpret_cast<uintptr_t>(g_mainSectionSite.load(std::memory_order_relaxed));
    const auto anchor = reinterpret_cast<uintptr_t>(&Enabled);
    std::printf("[sound_core] still waiting after %.0f ms: %s (emulation thread's last interrupts-off site: "
                "SoundCore::Enabled %+lld)\n",
                std::chrono::duration<double, std::milli>(waited).count(), what,
                static_cast<long long>(site - anchor));
    std::fflush(stdout);
}

class LongWait {
public:
    explicit LongWait(const char* what) : what_(what), start_(StatsClock::now()), next_(start_ + kFirstReport) {}
    // Call between attempts.
    void Check()
    {
        const auto now = StatsClock::now();
        if (now < next_) {
            return;
        }
        next_ = now + kReportEvery;
        ReportLongWait(what_, now - start_);
    }

private:
    const char* what_;
    StatsClock::time_point start_;
    StatsClock::time_point next_;
};

// The guest thread that owns a mutex the sound thread is waiting for (0: none). The emulation
// thread must let that guest thread run instead of blocking on the sound thread.
std::atomic<uint32_t> g_soundWaitsForGuestThread{0};

// Main thread inside the AI interrupt callback: on a console a handler runs with interrupts off,
// so the sound thread cannot be in a section meanwhile.
std::atomic<bool> g_mainInHandler{false};
// Main thread parked for a world stop. Stopped, like yielded.
std::atomic<bool> g_mainParked{false};

bool MainHoldsSection()
{
    return (!OsHleInternal::g_interrupts_enabled.load(std::memory_order_seq_cst) ||
            g_mainInHandler.load(std::memory_order_seq_cst)) &&
           !g_mainYielded.load(std::memory_order_seq_cst) && !g_mainParked.load(std::memory_order_seq_cst);
}

void SoundEnterSection()
{
    g_soundInSection.store(true, std::memory_order_seq_cst);
    while (MainHoldsSection()) {
        g_stats.soundSectionBackoffs.fetch_add(1, std::memory_order_relaxed);
        g_soundInSection.store(false, std::memory_order_seq_cst);
        const auto start = StatsClock::now();
        LongWait wait("sound thread for the emulation thread's interrupt section");
        while (MainHoldsSection()) {
            std::this_thread::yield();
            wait.Check();
        }
        g_stats.soundSectionWaitMicros.fetch_add(MicrosSince(start), std::memory_order_relaxed);
        g_soundInSection.store(true, std::memory_order_seq_cst);
    }
}

void SoundLeaveSection()
{
    g_soundInSection.store(false, std::memory_order_seq_cst);
}

// Stop-the-world for the sound thread's calls into other subsystems.
constexpr uint32_t kRunningThreadAddr = 0x800000E4u;
constexpr uint32_t kCurrentContextAddr = 0x800000D4u;
std::atomic<bool> g_worldStopRequested{false};
std::mutex g_worldMutex;
std::condition_variable g_worldChanged;
thread_local int t_worldStopDepth = 0;
thread_local uint32_t t_savedRunningThread = 0;
thread_local uint32_t t_savedCurrentContext = 0;

void MainParkIfAsked()
{
    if (!g_worldStopRequested.load(std::memory_order_seq_cst)) {
        return;
    }
    std::unique_lock<std::mutex> lock(g_worldMutex);
    g_mainParked.store(true, std::memory_order_seq_cst);
    g_worldChanged.notify_all();
    LongWait wait("emulation thread parked for the sound thread");
    while (g_worldStopRequested.load(std::memory_order_seq_cst)) {
        g_worldChanged.wait_for(lock, std::chrono::milliseconds(20));
        wait.Check();
    }
    g_mainParked.store(false, std::memory_order_seq_cst);
}

// Main thread with interrupts just turned off: wait until the sound thread is out of its section.
// A world stop asked meanwhile is served here, since the sound thread may ask for one from inside
// its section.
void MainWaitForSoundSection()
{
    std::atomic_thread_fence(std::memory_order_seq_cst);
    if (!g_soundInSection.load(std::memory_order_seq_cst)) {
        return;
    }
    g_stats.mainSectionWaits.fetch_add(1, std::memory_order_relaxed);
    LongWait wait("emulation thread for the sound thread's interrupt section");
    while (true) {
        MainParkIfAsked();
        if (!g_soundInSection.load(std::memory_order_seq_cst)) {
            return;
        }
        std::this_thread::yield();
        wait.Check();
    }
}

// Main thread about to block on the sound thread. While it is blocked it counts as stopped; on
// the way out it parks if the sound thread has the world stopped.
struct MainYieldScope {
    MainYieldScope() { g_mainYielded.store(true, std::memory_order_seq_cst); }
    ~MainYieldScope()
    {
        g_mainYielded.store(false, std::memory_order_seq_cst);
        MainParkIfAsked();
        if (!OsHleInternal::g_interrupts_enabled.load(std::memory_order_relaxed)) {
            MainWaitForSoundSection();
        }
    }
};

void SoundThreadMain()
{
    t_onSoundThread = true;
    t_interruptsEnabled = true;
    g_soundThreadId.store(std::this_thread::get_id());
    if (SwitchConfigureSoundThread != nullptr) {
        SwitchConfigureSoundThread();
    }
    // This thread's current context for its whole life. Set directly rather than through
    // CpuContextScope, which would also rewrite the emulation thread's NI mirror.
    CpuContext& cpu = g_mkwSoundCpuContext;
    g_currentCpuContext = &cpu;
    MkwApplyHostNiModeRegisterOnly(cpu.fpscr);
    // The guest thread already registered the AX callback when it started (and unregisters it
    // when it exits), so only the per-block half of its loop runs here.
    const uint32_t stackTop = cpu.gpr[1];
    do {
        cpu.gpr[1] = stackTop;
        cpu.gpr[3] = kSoundThreadObject;
        InvokeIndirectCpu(kSoundThreadFrameProcess, &cpu);
    } while ((cpu.gpr[3] & 0xFFu) != 0);

    std::lock_guard<std::mutex> lock(g_stateMutex);
    g_outstanding = 0;
    g_queueCount = 0;
    g_idle.notify_all();
}

// g_stateMutex held.
void StartThreadLocked(const CpuContext& mainCpu)
{
    if (g_threadRunning) {
        return;
    }
    CpuContext& cpu = g_mkwSoundCpuContext;
    cpu = mainCpu;
    // The guest sound thread is parked in its own receive call near the top of its stack. Off the
    // race it runs blocks from there again, about 1 KB deep (TEMP-SNDSTACK measured), so the
    // bottom three quarters of the stack are free for this thread.
    const uint32_t stackBase = Memory::Read32(kSoundThreadObject + kThreadStackBaseOffset);
    const uint32_t stackEnd = Memory::Read32(kSoundThreadObject + kThreadStackEndOffset);
    const uint32_t stackSize = stackBase - stackEnd;
    const uint32_t stackTop = (stackEnd + (stackSize / 4u) * 3u) & ~0xFu;
    Memory::Write32(stackTop, 0);
    cpu.gpr[1] = stackTop;
    cpu.lr = 0;
    std::printf("[sound_core] starting: guest stack %08X..%08X, using top %08X\n", stackEnd, stackBase,
                stackTop);
    std::fflush(stdout);
    g_threadRunning = true;
    g_thread = std::thread(SoundThreadMain);
}

// g_stateMutex held.
void EnqueueLocked(uint32_t message)
{
    if (g_queueCount == kQueueCapacity) {
        ++g_droppedMessages;
        return;
    }
    g_queue[(g_queueHead + g_queueCount) % kQueueCapacity] = message;
    ++g_queueCount;
    ++g_outstanding;
}

// Races only. Retro Rewind rooms crashed with the sound core on, five Switch runs in a row
// (2026-10-22: a room's Mii head built with garbage model holders in MiiHeadsModel::Init, once
// EGG's heap list), and never with it off. The core is there for the race frame rate, so blocks go
// to the host thread from the countdown to the finish line; before and after that (menus, rooms,
// race loading, results) the guest sound thread gets them and runs on the emulation thread as it
// does with the core off. `sound_core_everywhere.flag` (Switch) or MKW_SOUND_CORE_EVERYWHERE=1
// keeps the host thread on everywhere, for hunting that crash.
constexpr uint32_t kRaceInstancePointer = 0x809BD730u; // RaceManager; stage at +0x28
constexpr uint32_t kRaceStageOffset = 0x28u;
constexpr uint32_t kRaceStageCountdown = 1u;
constexpr uint32_t kRaceStageRacing = 2u;
constexpr uint32_t kThreadStateOffset = 0x2C8u;
constexpr uint32_t kThreadQueueOffset = 0x2DCu;
constexpr uint16_t kThreadStateWaiting = 4u;
constexpr uint32_t kMsgQueueRecvOffset = 0x08u;
constexpr uint32_t kMsgQueueUsedOffset = 0x1Cu;

bool Everywhere()
{
#if defined(__SWITCH__)
    static const bool everywhere = [] {
        std::FILE* file = std::fopen("sdmc:/switch/WiiCompiled/sound_core_everywhere.flag", "r");
        if (file == nullptr) {
            return false;
        }
        std::fclose(file);
        std::printf("[sound_core] on everywhere (sound_core_everywhere.flag), not only in races\n");
        return true;
    }();
#else
    static const bool everywhere = [] {
        const char* value = std::getenv("MKW_SOUND_CORE_EVERYWHERE");
        return value != nullptr && value[0] == '1';
    }();
#endif
    return everywhere;
}

bool RaceUnderWay()
{
    uint32_t race = 0;
    uint32_t stage = 0;
    return Memory::TryRead32(kRaceInstancePointer, race) && race != 0 &&
           Memory::TryRead32(race + kRaceStageOffset, stage) &&
           (stage == kRaceStageCountdown || stage == kRaceStageRacing);
}

// The guest sound thread asleep in its own receive with nothing queued for it: between two blocks,
// so the host thread can take over without the two running one block's code at once.
bool GuestSoundThreadParked()
{
    return Memory::Read16(kSoundThreadObject + kThreadStateOffset) == kThreadStateWaiting &&
           Memory::Read32(kSoundThreadObject + kThreadQueueOffset) == kSoundThreadQueue + kMsgQueueRecvOffset &&
           Memory::Read32(kSoundThreadQueue + kMsgQueueUsedOffset) == 0;
}

bool g_routedToHost = false;

// g_stateMutex held, emulation thread. Whether this block goes to the host thread. A switch waits
// for a block boundary on the side that has the blocks now: the host thread idle, or the guest
// thread parked.
bool RouteToHostLocked()
{
    const bool want = Everywhere() || RaceUnderWay();
    if (want == g_routedToHost) {
        return g_routedToHost;
    }
    if (want ? !GuestSoundThreadParked() : (g_outstanding != 0 || g_stagedCount != 0)) {
        return g_routedToHost;
    }
    g_routedToHost = want;
    std::printf("[sound_core] %s\n", want ? "race: sound on its own core" : "off the race: sound on the emulation thread");
    std::fflush(stdout);
    return want;
}

} // namespace

bool Enabled()
{
#if defined(MKW_PGO_GENERATE)
    // A recording build counts executions in plain shared counters; two threads running the same
    // code would lose counts and can leave a profile the compiler rejects.
    return false;
#elif defined(__SWITCH__)
    // Off unless `sdmc:/switch/WiiCompiled/sound_core.flag` exists. It is faster, but Retro Rewind
    // online is not safe with it: test32 froze at the Retro WFC login (fixed: SoundMutex), and
    // after that fix rooms and race starts still crashed with it on (2026-10-22: a Mii head built
    // with garbage model holders, then EGG's heap list found corrupted) and never with it off.
    // `no_sound_core.flag` wins over it.
    static const bool enabled = [] {
        if (std::FILE* off = std::fopen("sdmc:/switch/WiiCompiled/no_sound_core.flag", "r")) {
            std::fclose(off);
            std::printf("[sound_core] turned off by no_sound_core.flag\n");
            return false;
        }
        if (std::FILE* on = std::fopen("sdmc:/switch/WiiCompiled/sound_core.flag", "r")) {
            std::fclose(on);
            std::printf("[sound_core] turned on by sound_core.flag\n");
            return true;
        }
        return false;
    }();
    return enabled;
#elif defined(__ANDROID__)
    return false;
#else
    static const bool enabled = [] {
        const char* value = std::getenv("MKW_SOUND_CORE");
        return value != nullptr && value[0] == '1';
    }();
    return enabled;
#endif
}

bool OnSoundThread()
{
    return t_onSoundThread;
}

bool SoundThreadHasWorldStopped()
{
    return t_onSoundThread && t_worldStopDepth > 0;
}

bool HandleSend(CpuContext* cpu, uint32_t queue, uint32_t message)
{
    if (queue != kSoundThreadQueue || !Enabled() || t_onSoundThread) {
        return false;
    }
    if (message == kMessageShutdown) {
        // The guest thread still has to receive this one to exit and be joined.
        Shutdown();
        return false;
    }
    if (message != kMessageBlockReady) {
        return false;
    }

    std::lock_guard<std::mutex> lock(g_stateMutex);
    if (!RouteToHostLocked()) {
        return false;
    }
    StartThreadLocked(*cpu);
    if (g_staging) {
        if (g_stagedCount < g_staged.size()) {
            g_staged[g_stagedCount++] = message;
        } else {
            ++g_droppedMessages;
        }
    } else {
        EnqueueLocked(message);
        g_messageReady.notify_one();
    }
    cpu->gpr[3] = 1;
    return true;
}

bool HandleReceive(CpuContext* cpu, uint32_t queue, uint32_t outMessagePtr, bool block)
{
    if (!t_onSoundThread || queue != kSoundThreadQueue) {
        return false;
    }
    std::unique_lock<std::mutex> lock(g_stateMutex);
    if (t_processingMessage) {
        t_processingMessage = false;
        g_stats.blocks.fetch_add(1, std::memory_order_relaxed);
        g_stats.soundBusyMicros.fetch_add(MicrosSince(t_blockStart), std::memory_order_relaxed);
        if (g_outstanding > 0) {
            --g_outstanding;
        }
        if (g_outstanding == 0) {
            g_idle.notify_all();
        }
    }
    if (g_queueCount == 0 && !block) {
        cpu->gpr[3] = 0;
        return true;
    }
    if (g_queueCount == 0) {
        lock.unlock();
        ReportStats();
        lock.lock();
    }
    g_messageReady.wait(lock, [] { return g_queueCount != 0; });
    t_blockStart = StatsClock::now();
    const uint32_t message = g_queue[g_queueHead];
    g_queueHead = (g_queueHead + 1) % kQueueCapacity;
    --g_queueCount;
    t_processingMessage = true;
    lock.unlock();

    if (outMessagePtr != 0) {
        Memory::Write32(outMessagePtr, message);
    }
    cpu->gpr[3] = 1;
    return true;
}

bool HandleLockMutex(uint32_t mutex)
{
    if (mutex != kSoundThreadMutex || !Enabled()) {
        return false;
    }
    if (g_soundMutex.try_lock()) {
        return true;
    }
    if (t_onSoundThread) {
        // Blocked with interrupts off: let the main thread's sections through meanwhile.
        const bool inSection = !t_interruptsEnabled;
        if (inSection) {
            SoundLeaveSection();
        }
        g_soundMutex.lock();
        if (inSection) {
            SoundEnterSection();
        }
    } else {
        const auto start = StatsClock::now();
        MainYieldScope yielded;
        g_soundMutex.lock();
        g_stats.mutexWaits.fetch_add(1, std::memory_order_relaxed);
        g_stats.mutexWaitMicros.fetch_add(MicrosSince(start), std::memory_order_relaxed);
    }
    return true;
}

bool TryLockMutexFromGuest(uint32_t mutex, bool& soundWaitsForGuestThread)
{
    soundWaitsForGuestThread = false;
    if (mutex != kSoundThreadMutex || !Enabled() || t_onSoundThread) {
        return true;
    }
    if (g_soundMutex.try_lock()) {
        return true;
    }
    const auto start = StatsClock::now();
    MainYieldScope yielded;
    LongWait wait("emulation thread for the sound mutex");
    while (!g_soundMutex.try_lock()) {
        if (g_soundWaitsForGuestThread.load(std::memory_order_seq_cst) != 0) {
            soundWaitsForGuestThread = true;
            return false;
        }
        std::this_thread::yield();
        wait.Check();
    }
    g_stats.mutexWaits.fetch_add(1, std::memory_order_relaxed);
    g_stats.mutexWaitMicros.fetch_add(MicrosSince(start), std::memory_order_relaxed);
    return true;
}

// The emulation thread sleeping in the scheduler's idle wait, every guest thread asleep. Guest
// threads go to sleep with interrupts off, so the flag stays "disabled" for the whole wait; on a
// console that is the idle state with interrupts on, and the sound thread runs freely. Without
// this the sound thread spun in SoundEnterSection for as long as the game waited on the network
// (250 ms and more during the Retro WFC login).
void MainIdleBegin()
{
    if (!t_onSoundThread) {
        g_mainYielded.store(true, std::memory_order_seq_cst);
    }
}

void MainIdleEnd()
{
    if (t_onSoundThread) {
        return;
    }
    g_mainYielded.store(false, std::memory_order_seq_cst);
    MainParkIfAsked();
    if (!OsHleInternal::g_interrupts_enabled.load(std::memory_order_relaxed)) {
        MainWaitForSoundSection();
    }
}

void LockFromGuest(CpuContext* cpu, uint32_t mutex)
{
    LongWait wait("emulation thread for a guest thread the sound thread waits on");
    while (true) {
        bool soundWaitsForGuestThread = false;
        if (TryLockMutexFromGuest(mutex, soundWaitsForGuestThread)) {
            return;
        }
        const uint32_t savedR3 = cpu->gpr[3];
        const uint32_t savedR4 = cpu->gpr[4];
        cpu->gpr[3] = 0;
        cpu->gpr[4] = 60750u;  // OS ticks in one millisecond
        OS__SleepTicks_HLE_801aaca8(cpu);
        cpu->gpr[3] = savedR3;
        cpu->gpr[4] = savedR4;
        wait.Check();
    }
}

void ResumeWorldWaitingFor(uint32_t ownerThread)
{
    g_soundWaitsForGuestThread.store(ownerThread, std::memory_order_seq_cst);
    ResumeWorld();
}

bool HandleUnlockMutex(uint32_t mutex)
{
    if (mutex != kSoundThreadMutex || !Enabled()) {
        return false;
    }
    g_soundMutex.unlock();
    return true;
}

int32_t SoundThreadSetInterrupts(bool enabled)
{
    const bool previous = t_interruptsEnabled;
    if (previous && !enabled) {
        SoundEnterSection();
    } else if (!previous && enabled) {
        SoundLeaveSection();
    }
    t_interruptsEnabled = enabled;
    return previous ? 1 : 0;
}

bool SoundThreadInterruptsEnabled()
{
    return t_interruptsEnabled;
}

void MainThreadInterruptsDisabled()
{
    if (g_mainYielded.load(std::memory_order_relaxed)) {
        return;
    }
    MainWaitForSoundSection();
}

void MainThreadSafePoint()
{
    if (g_worldStopRequested.load(std::memory_order_relaxed) && !t_onSoundThread) {
        MainParkIfAsked();
    }
}

void StopWorld()
{
    if (!t_onSoundThread) {
        return;
    }
    if (t_worldStopDepth++ != 0) {
        return;
    }
    g_soundWaitsForGuestThread.store(0, std::memory_order_seq_cst);
    g_worldStopRequested.store(true, std::memory_order_seq_cst);
    {
        std::unique_lock<std::mutex> lock(g_worldMutex);
        LongWait wait("sound thread for the emulation thread to stop");
        while (!g_mainParked.load(std::memory_order_seq_cst) && !g_mainYielded.load(std::memory_order_seq_cst)) {
            g_worldChanged.wait_for(lock, std::chrono::microseconds(200));
            wait.Check();
        }
    }
    t_savedRunningThread = Memory::Read32(kRunningThreadAddr);
    t_savedCurrentContext = Memory::Read32(kCurrentContextAddr);
    Memory::Write32(kRunningThreadAddr, kSoundThreadObject);
    Memory::Write32(kCurrentContextAddr, kSoundThreadObject);
    g_stats.worldStops.fetch_add(1, std::memory_order_relaxed);
}

void ResumeWorld()
{
    if (!t_onSoundThread || t_worldStopDepth == 0) {
        return;
    }
    if (--t_worldStopDepth != 0) {
        return;
    }
    Memory::Write32(kRunningThreadAddr, t_savedRunningThread);
    Memory::Write32(kCurrentContextAddr, t_savedCurrentContext);
    {
        std::lock_guard<std::mutex> lock(g_worldMutex);
        g_worldStopRequested.store(false, std::memory_order_seq_cst);
    }
    g_worldChanged.notify_all();
}

void WaitUntilIdle()
{
    if (!Enabled() || t_onSoundThread) {
        return;
    }
    std::unique_lock<std::mutex> lock(g_stateMutex);
    if (g_outstanding == 0) {
        return;
    }
    const auto start = StatsClock::now();
    MainYieldScope yielded;
    const bool idle = g_idle.wait_for(lock, std::chrono::milliseconds(200), [] { return g_outstanding == 0; });
    g_stats.idleWaits.fetch_add(1, std::memory_order_relaxed);
    g_stats.idleWaitMicros.fetch_add(MicrosSince(start), std::memory_order_relaxed);
    if (!idle) {
        std::printf("[sound_core] sound thread did not go idle within 200 ms (outstanding=%u)\n",
                    g_outstanding);
        std::fflush(stdout);
    }
}

void BeginAudioInterrupt()
{
    if (!Enabled()) {
        return;
    }
    WaitUntilIdle();
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        g_staging = true;
    }
    // Usually idle by now, but the wait above is bounded (it cannot tell a sound thread blocked on
    // a mutex this thread holds from a slow one).
    g_mainInHandler.store(true, std::memory_order_seq_cst);
    MainWaitForSoundSection();
}

void EndAudioInterrupt()
{
    if (!Enabled()) {
        return;
    }
    g_mainInHandler.store(false, std::memory_order_seq_cst);
    std::lock_guard<std::mutex> lock(g_stateMutex);
    g_staging = false;
    for (size_t i = 0; i < g_stagedCount; ++i) {
        EnqueueLocked(g_staged[i]);
    }
    if (g_stagedCount != 0) {
        g_stagedCount = 0;
        g_messageReady.notify_one();
    }
}

void Shutdown()
{
    std::thread thread;
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        if (!g_threadRunning) {
            return;
        }
        // Pending blocks are not worth finishing; the shutdown message must fit.
        g_queueCount = 0;
        g_outstanding = 0;
        EnqueueLocked(kMessageShutdown);
        g_messageReady.notify_one();
        thread = std::move(g_thread);
        g_threadRunning = false;
    }
    {
        MainYieldScope yielded;
        thread.join();
    }
    std::printf("[sound_core] stopped (dropped block messages: %llu)\n",
                static_cast<unsigned long long>(g_droppedMessages));
    std::fflush(stdout);
}

} // namespace SoundCore
