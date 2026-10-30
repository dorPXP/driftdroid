#include "hle_stubs.h"
#include "memory.h"
#include "hle/controller_status_contract.h"
#include "wii_remote_input.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include <dolphin/pad.h>

namespace {

void WritePadStatus(uint32_t base, const PADStatus& status) {
    const auto guestStatus = PadStatusContract::Encode({
        status.button,
        status.stickX,
        status.stickY,
        status.substickX,
        status.substickY,
        status.triggerL,
        status.triggerR,
        status.analogA,
        status.analogB,
        status.err,
    });
    uint8_t* dst = Memory::GetPointer(base, guestStatus.size());
    std::memcpy(dst, guestStatus.data(), guestStatus.size());
}

} // namespace

extern "C" uint32_t PAD__Init_HLE()
{
    return PADInit() ? 1u : 0u;
}
PPC_NATIVE_OVERRIDE(801AF2F0, PAD__Init_HLE, uint32_t, (), ());

// RaceManager instance (PAL); non-zero while a race is loaded. Its stage is the word at +0x28
// (Raceinfo::IsAtLeastStage, 0x80536230): 0 intro, 1 countdown, 2 racing, then finished.
constexpr uint32_t kRaceInstancePointer = 0x809BD730;
constexpr uint32_t kRaceStageOffset = 0x28;
constexpr uint32_t kRaceStageRacing = 2;
// RaceScene::isPaused (0x80554E14): the scene's own flag (byte +0x4C of the scene at 0x809BD6F8),
// or the current UI section's (byte +0x38B of *SectionManager, SectionManager at 0x809C1E38), which
// is the one the pause menu sets.
constexpr uint32_t kGameScenePointer = 0x809BD6F8;
constexpr uint32_t kGameScenePausedOffset = 0x4C;
constexpr uint32_t kSectionManagerPointer = 0x809C1E38;
constexpr uint32_t kSectionPausedWord = 0x388; // the flag is this word's last byte (+0x38B)

// The player is driving: a race is under way and not paused. The pause menu, its quit
// confirmation and the menus after the finish need the stick and D-pad as a cursor instead.
#if defined(__SWITCH__)
extern "C" void AuroraSwitchSetDriving(int driving);
#endif
#if defined(__ANDROID__)
extern "C" bool AndroidMotionSteer(float* value);  // android_touch_controls.cpp
#endif

// Last answer of PlayerIsDriving(), for the Android app's tilt steering (android_touch_controls.cpp).
static std::atomic<bool> g_playerDriving{false};
extern "C" bool RuntimePlayerIsDriving() { return g_playerDriving.load(std::memory_order_relaxed); }

static bool PlayerIsDriving()
{
    uint32_t race = 0;
    uint32_t stage = 0;
    if (!Memory::TryRead32(kRaceInstancePointer, race) || race == 0 ||
        !Memory::TryRead32(race + kRaceStageOffset, stage) || stage != kRaceStageRacing) {
        return false;
    }
    uint32_t scene = 0;
    uint32_t word = 0;
    if (Memory::TryRead32(kGameScenePointer, scene) && scene != 0 &&
        Memory::TryRead32(scene + kGameScenePausedOffset, word) && (word >> 24) != 0) {
        return false;
    }
    uint32_t sections = 0;
    uint32_t section = 0;
    if (Memory::TryRead32(kSectionManagerPointer, sections) && sections != 0 &&
        Memory::TryRead32(sections, section) && section != 0 &&
        Memory::TryRead32(section + kSectionPausedWord, word) && (word & 0xFF) != 0) {
        return false;
    }
    return true;
}

extern "C" uint32_t PAD__Read_HLE(uint32_t statusPtr)
{
    if (statusPtr == 0) {
        return 0;
    }

    PADStatus statuses[PAD_CHANMAX]{};
    uint32_t rumbleMask = PADRead(statuses);
    const bool driving = PlayerIsDriving();
    g_playerDriving.store(driving, std::memory_order_relaxed);
#if defined(__ANDROID__)
    // Tilt steering also steers a real controller that is player 1 (a Pro Controller with the phone
    // in a clip): MotionSteering.kt's value otherwise only reaches the touch gamepad. As on Switch,
    // the stick pushed sideways wins.
    {
        float tilt = 0.0f;
        if (AndroidMotionSteer(&tilt) && statuses[0].err == PAD_ERR_NONE && std::abs(statuses[0].stickX) < 40) {
            statuses[0].stickX = static_cast<s8>(std::clamp(tilt * 127.0f, -127.0f, 127.0f));
        }
    }
#endif
#if !defined(__SWITCH__) && !defined(__ANDROID__)
    // TEMP-WHEEL: MKW_TEST_PAD_PROBE=1 prints player 1's race buttons whenever they change
    // (the same word kpad.cpp's probe prints in Wii Remote mode).
    if (std::getenv("MKW_TEST_PAD_PROBE") != nullptr) {
        static uint32_t lastButtons = 0xFFFFFFFFu;
        uint32_t manager = 0;
        uint32_t buttons = 0;
        if (Memory::TryRead32(0x809BD70C, manager) && manager != 0 &&
            Memory::TryRead32(manager + 4 + 0x28 + 0x04, buttons) && buttons != lastButtons) {
            lastButtons = buttons;
            std::fprintf(stderr, "[pad-probe] race buttons %08X pad %04X\n", buttons, statuses[0].button);
        }
    }
#endif
#if defined(__SWITCH__)
    AuroraSwitchSetDriving(driving ? 1 : 0); // tilt steering only while driving
#endif
    if (WiiRemoteInput::Active()) {
        // Wii Wheel mode: player 1's buttons drive the remote, and player 1's pad is reported
        // unplugged so the game hands that player to the remote. Players 2-4 stay normal pads
        // (unplugging all four left local multiplayer with one player, 2026-10-26).
        const PADStatus& player = statuses[0];
#if !defined(__SWITCH__) && !defined(__ANDROID__)
        // TEMP-WHEEL: MKW_TEST_RACE_STATE=1 prints when "driving" changes.
        if (std::getenv("MKW_TEST_RACE_STATE") != nullptr) {
            static int last = -1;
            if (static_cast<int>(driving) != last) {
                last = driving;
                uint32_t race = 0, stage = 0xFFFFFFFF;
                if (Memory::TryRead32(kRaceInstancePointer, race) && race != 0) {
                    Memory::TryRead32(race + kRaceStageOffset, stage);
                }
                static const auto t0 = std::chrono::steady_clock::now();
                const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                         std::chrono::steady_clock::now() - t0).count();
                std::printf("[TEMP-WHEEL] %lld ms driving=%d race=%08x stage=%u buttons=%04x\n", ms, driving ? 1 : 0,
                            race, stage, player.button);
                std::fflush(stdout);
            }
        }
#endif
        WiiRemoteInput::PublishGameCubeButtons(player.button, player.triggerL, player.triggerR, player.stickX,
                                               player.stickY, driving);
        statuses[0] = PADStatus{};
        statuses[0].err = PAD_ERR_NO_CONTROLLER;
        rumbleMask &= ~PAD_CHAN0_BIT;
    }

    try {
        for (uint32_t i = 0; i < PAD_CHANMAX; ++i) {
            WritePadStatus(statusPtr + static_cast<uint32_t>(i * PadStatusContract::kGuestStatusSize),
                           statuses[i]);
        }
    } catch (const Memory::AccessViolation&) {
        return 0;
    }

    return rumbleMask;
}
PPC_NATIVE_OVERRIDE(801AF44C, PAD__Read_HLE, uint32_t, (uint32_t statusPtr), (statusPtr));

extern "C" uint32_t PAD__Reset_HLE(uint32_t mask)
{
    return PADReset(mask) ? 1u : 0u;
}
PPC_NATIVE_OVERRIDE(801AF0DC, PAD__Reset_HLE, uint32_t, (uint32_t mask), (mask));

extern "C" uint32_t PAD__Recalibrate_HLE(uint32_t mask)
{
    return PADRecalibrate(mask) ? 1u : 0u;
}
PPC_NATIVE_OVERRIDE(801AF1E4, PAD__Recalibrate_HLE, uint32_t, (uint32_t mask), (mask));

extern "C" void PAD__ControlMotor_HLE(int32_t chan, uint32_t command)
{
    PADControlMotor(chan, command);
}
PPC_NATIVE_OVERRIDE_VOID(801AF908, PAD__ControlMotor_HLE, (int32_t chan, uint32_t command), (chan, command));
