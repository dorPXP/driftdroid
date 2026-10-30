#include "hle_stubs.h"
#include "memory.h"
#include "wii_remote_input.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {

// KPADStatus (0x84 bytes). Only the fields the game's core-remote path reads are named.
constexpr uint32_t kKpadStatusSize = 0x84;
constexpr uint32_t kKpadHold = 0x00;
constexpr uint32_t kKpadTrig = 0x04;
constexpr uint32_t kKpadRelease = 0x08;
constexpr uint32_t kKpadAcc = 0x0C;
constexpr uint32_t kKpadAccValue = 0x18;
constexpr uint32_t kKpadAccSpeed = 0x1C;
constexpr uint32_t kKpadAccVertical = 0x54;
constexpr uint32_t kKpadDevType = 0x5C;
constexpr uint32_t kKpadWpadErr = 0x5D;
constexpr uint32_t kKpadDataFormat = 0x5F;

// WPADStatus for a core remote: button, acc[3] (100 counts per g), dpd objects, dev, err.
constexpr uint32_t kWpadStatusSize = 0x2A;
constexpr uint32_t kWpadStatusDev = 0x28;
constexpr uint32_t kWpadStatusErr = 0x29;
constexpr float kWpadAccCountsPerG = 100.0f;

// The game reads the remote from more than one buffer per frame; each reader gets its own
// trigger/release edges and its own acceleration delta.
struct ReaderHistory {
    uint32_t statusPtr = 0;
    uint32_t hold = 0;
    float acc[3] = {0.0f, 0.0f, 0.0f};
};
std::array<ReaderHistory, 4> g_readers{};
uint32_t g_nextReader = 0;

ReaderHistory& ReaderFor(uint32_t statusPtr)
{
    for (auto& reader : g_readers) {
        if (reader.statusPtr == statusPtr) {
            return reader;
        }
    }
    ReaderHistory& reader = g_readers[g_nextReader];
    g_nextReader = (g_nextReader + 1) % g_readers.size();
    reader = ReaderHistory{};
    reader.statusPtr = statusPtr;
    return reader;
}

} // namespace

extern "C" int32_t KPAD__Read_HLE(uint32_t chan, uint32_t statusPtr, uint32_t count)
{
    if (chan != 0 || statusPtr == 0 || count == 0 || !WiiRemoteInput::Active()) {
        return 0;
    }

    const WiiRemoteInput::Sample sample = WiiRemoteInput::Read();
    ReaderHistory& reader = ReaderFor(statusPtr);

    uint8_t* status = Memory::GetPointer(statusPtr, kKpadStatusSize);
    std::memset(status, 0, kKpadStatusSize);
    Memory::Write32(statusPtr + kKpadHold, sample.buttons);
    Memory::Write32(statusPtr + kKpadTrig, sample.buttons & ~reader.hold);
    Memory::Write32(statusPtr + kKpadRelease, reader.hold & ~sample.buttons);

    float speedSquared = 0.0f;
    float valueSquared = 0.0f;
    for (uint32_t i = 0; i < 3; ++i) {
        Memory::WriteFloat32(statusPtr + kKpadAcc + i * 4, sample.acc[i]);
        const float delta = sample.acc[i] - reader.acc[i];
        speedSquared += delta * delta;
        valueSquared += sample.acc[i] * sample.acc[i];
    }
    Memory::WriteFloat32(statusPtr + kKpadAccValue, std::sqrt(valueSquared));
    Memory::WriteFloat32(statusPtr + kKpadAccSpeed, std::sqrt(speedSquared));
    Memory::WriteFloat32(statusPtr + kKpadAccVertical, 1.0f);
    Memory::Write8(statusPtr + kKpadDevType, 0);
    Memory::Write8(statusPtr + kKpadWpadErr, 0);
    Memory::Write8(statusPtr + kKpadDataFormat, 0);

#if !defined(__SWITCH__) && !defined(__ANDROID__)
    // TEMP-WHEEL: print player 1's trick flick whenever it changes.
    if (std::getenv("MKW_TEST_WHEEL_AXES") != nullptr || std::getenv("MKW_TEST_WHEEL_PHONE") != nullptr ||
        std::getenv("MKW_TEST_WHEEL_PROBE") != nullptr) {
        static uint32_t lastFlick = 0xFFFFFFFFu;
        uint32_t manager = 0;
        uint32_t holder = 0;
        if (Memory::TryRead32(0x809BD70C, manager) && manager != 0 && (holder = manager + 4) != 0) {
            uint32_t word = 0;
            if (Memory::TryRead32(holder + 0x28 + 0x10, word) && word != lastFlick) {
                lastFlick = word;
                std::fprintf(stderr, "[wheel-probe] state+0x10 = %08X (flick byte %u)\n", word, (word >> 8) & 0xFF);
            }
            static uint32_t lastButtons = 0xFFFFFFFFu;
            uint32_t buttons = 0;
            if (Memory::TryRead32(holder + 0x28 + 0x04, buttons) && buttons != lastButtons) {
                lastButtons = buttons;
                std::fprintf(stderr, "[wheel-probe] race buttons %08X remote %04X\n", buttons, sample.buttons);
            }
        }
    }
#endif
    reader.hold = sample.buttons;
    for (uint32_t i = 0; i < 3; ++i) {
        reader.acc[i] = sample.acc[i];
    }

    return 1;
}
PPC_NATIVE_OVERRIDE(80197380, KPAD__Read_HLE, int32_t, (uint32_t chan, uint32_t statusPtr, uint32_t count),
         (chan, statusPtr, count));

extern "C" int32_t KPAD__GetUnifiedWpadStatus_HLE(uint32_t chan, uint32_t statusPtr, uint32_t count)
{
    if (chan != 0 || statusPtr == 0 || count == 0 || !WiiRemoteInput::Active()) {
        return 0;
    }

    const WiiRemoteInput::Sample sample = WiiRemoteInput::Read();
    uint8_t* status = Memory::GetPointer(statusPtr, kWpadStatusSize);
    std::memset(status, 0, kWpadStatusSize);
    Memory::Write16(statusPtr, static_cast<uint16_t>(sample.buttons));
    for (uint32_t i = 0; i < 3; ++i) {
        const auto counts = static_cast<int16_t>(std::lround(sample.acc[i] * kWpadAccCountsPerG));
        Memory::Write16(statusPtr + 2 + i * 2, static_cast<uint16_t>(counts));
    }
    Memory::Write8(statusPtr + kWpadStatusDev, 0);
    Memory::Write8(statusPtr + kWpadStatusErr, 0);
    return 1;
}
PPC_NATIVE_OVERRIDE(8019812C, KPAD__GetUnifiedWpadStatus_HLE, int32_t,
         (uint32_t chan, uint32_t statusPtr, uint32_t count), (chan, statusPtr, count));
