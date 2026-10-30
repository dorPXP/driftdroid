#pragma once

#include <cstdint>

struct CpuContext;

// Runs the game's sound thread (nw4r::snd::detail::SoundThread) on its own host thread instead of
// as a guest fiber on the emulation thread. The guest's own SoundThreadFunc loop is executed
// there; only the three things it shares with the rest of the game are redirected:
//   - its message queue (the "one audio block is ready" message sent from the AI interrupt),
//   - its mutex (taken by every sound call the game makes),
//   - interrupt-disable sections (the game's only other exclusion between the two threads).
// On a console the sound thread preempts the main thread and runs each block to completion. Here
// the main thread keeps going meanwhile, and waits for the sound thread only where the console
// ordering matters: before the next audio block is generated.
namespace SoundCore {

inline constexpr uint32_t kSoundThreadObject = 0x802ECEB0u;
inline constexpr uint32_t kSoundThreadQueue = kSoundThreadObject + 800u;
inline constexpr uint32_t kSoundThreadMutex = kSoundThreadObject + 852u;
// bool SoundThread::FrameProcess(): receive one message, run one block; false on shutdown.
inline constexpr uint32_t kSoundThreadFrameProcess = 0x800A4AF0u;

// Fixed at first use (config / environment); false on platforms that are not ready for it.
bool Enabled();
bool OnSoundThread();

// OSSendMessage / OSReceiveMessage on the sound thread's queue. Return true when handled here.
bool HandleSend(CpuContext* cpu, uint32_t queue, uint32_t message);
bool HandleReceive(CpuContext* cpu, uint32_t queue, uint32_t outMessagePtr, bool block);

// OSLockMutex / OSUnlockMutex on the sound thread's mutex. Return true when handled here.
bool HandleLockMutex(uint32_t mutex);
bool HandleUnlockMutex(uint32_t mutex);
// Emulation thread, after it took the guest mutex: takes the host half. The sound thread may hold
// it while waiting for a mutex that another guest thread owns. On a console this thread would
// sleep and that owner would run; here every guest thread shares the emulation thread, so
// blocking would keep the owner from ever releasing it. In that case this sleeps the guest way
// (1 ms at a time) and tries again.
void LockFromGuest(CpuContext* cpu, uint32_t mutex);
// Sound thread, between attempts at a mutex that guest thread `ownerThread` holds.
void ResumeWorldWaitingFor(uint32_t ownerThread);

// Interrupt sections. The sound thread keeps its own enable flag; the main thread reports its
// transitions so a section on one thread excludes sections on the other.
int32_t SoundThreadSetInterrupts(bool enabled);
bool SoundThreadInterruptsEnabled();
void MainThreadInterruptsDisabled();

// Around the emulation thread's host sleep in the scheduler's idle wait (no guest thread running).
void MainIdleBegin();
void MainIdleEnd();

// Blocks until the sound thread has finished every block it was sent.
void WaitUntilIdle();

// The sound thread calling into another subsystem (it closes DVD streams when music ends) is, on
// a console, the sound thread preempting the main thread at a point where interrupts are on. So
// there the emulation thread is stopped at such a point for the duration, and the guest's
// running-thread pointers name the sound thread. StopWorld / ResumeWorld nest; the sound thread
// brackets every lock of a mutex other than its own with them (os_scheduler.cpp).
void StopWorld();
void ResumeWorld();
// Emulation thread: a guest-level return to "interrupts on". Cheap when nothing is asked.
void MainThreadSafePoint();

// Around the AI interrupt callback on the main thread. Begin waits for the sound thread, which
// keeps one sound update per audio block; block messages sent inside are delivered at End.
void BeginAudioInterrupt();
void EndAudioInterrupt();

// Sends the shutdown message's host half: stops the host thread before the guest thread is told.
void Shutdown();

} // namespace SoundCore
