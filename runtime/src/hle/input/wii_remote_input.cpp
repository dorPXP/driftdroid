#include "wii_remote_input.h"

#include "runtime_config.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <string>


#if defined(__ANDROID__)
extern "C" bool AndroidRealControllerConnected(); // android_jni_bridge.cpp
#endif

namespace WiiRemoteInput {
namespace {

std::atomic<bool> g_active{false};
std::atomic<bool> g_stickSteering{false};
std::once_flag g_settingOnce;
std::mutex g_sampleMutex;
Sample g_sample{};
float g_steer = 0.0f; // -1 full left .. 1 full right, stick-steering mode only
float g_stickOverride = 0.0f; // tilt mode: the stick pushed sideways, or 0

// With a real controller connected (phone only), the Wii Wheel and shake modes step aside and it
// plays like a normal controller: its stick steers, its D-pad tricks, the phone's motion is not
// used (user, 2026-10-25: D-pad tricks did not work with a controller in motion mode).
bool ControllerPlaysNormally()
{
#if defined(__ANDROID__)
    return AndroidRealControllerConnected();
#else
    return false;
#endif
}
bool g_trickHeld = false;
std::chrono::steady_clock::time_point g_trickPulseEnd{};

// GameCube pad bits (dolphin/pad.h), restated so this file stays free of aurora headers.
constexpr uint16_t kPadLeft = 0x0001;
constexpr uint16_t kPadRight = 0x0002;
constexpr uint16_t kPadDown = 0x0004;
constexpr uint16_t kPadUp = 0x0008;
constexpr uint16_t kPadTriggerR = 0x0020;
constexpr uint16_t kPadTriggerL = 0x0040;
constexpr uint16_t kPadA = 0x0100;
constexpr uint16_t kPadB = 0x0200;
constexpr uint16_t kPadX = 0x0400;
constexpr uint16_t kPadY = 0x0800;
constexpr uint16_t kPadStart = 0x1000;
constexpr uint8_t kAnalogTriggerPressed = 0x80;
constexpr int8_t kStickPressed = 48;
// Tilt mode: how far sideways the stick must be pushed to take the steering from the phone.
constexpr int8_t kStickOverride = 40;
constexpr float kStickFull = 100.0f;
// How far the stick tilts the virtual wheel at full lock (the game reaches full lock near 30 deg
// and calibrates its own range).
constexpr float kStickSteerRadians = 0.6f;
constexpr float kDiagonal = 0.70710677f;
// A D-pad press in a race, the GameCube trick input, sends one shake of this size (a firm
// 1 g shake after WiiWheelMode.kt's gain), for players who forget to shake mid-jump.
constexpr float kTrickPulseBoost = 2.5f;
constexpr auto kTrickPulse = std::chrono::milliseconds(100);
void LoadSetting()
{
#if defined(__SWITCH__)
    // The Switch has no Wii Wheel any more: its Shake to trick presses the D-pad on each player's
    // own controller (aurora lib/switch/input_switch.cpp), so an old wii_wheel.cfg is ignored.
    return;
#endif
    std::ifstream file(RuntimeConfigFile::ApplicationDataDirectory() / "wii_wheel.cfg");
    std::string value;
    if (file >> value && (value == "1" || value == "2")) {
        g_active.store(true, std::memory_order_relaxed);
        g_stickSteering.store(value == "2", std::memory_order_relaxed);
    }
}

#if !defined(__SWITCH__) && !defined(__ANDROID__)
struct TestVector {
    float v[3] = {0.0f, 0.0f, 0.0f};
};

TestVector ParseTestVector(const char* name, TestVector fallback)
{
    const char* value = std::getenv(name);
    if (value == nullptr) {
        return fallback;
    }
    TestVector parsed{};
    if (std::sscanf(value, "%f,%f,%f", &parsed.v[0], &parsed.v[1], &parsed.v[2]) != 3) {
        return fallback;
    }
    return parsed;
}

// Desktop test source. MKW_TEST_WHEEL_MOTION=1 rocks the motion between MKW_TEST_WHEEL_REST and
// MKW_TEST_WHEEL_TILT ("x,y,z" in g) every 8 s and adds MKW_TEST_WHEEL_JOLT for 50 ms every 4 s;
// buttons still come from the pad (MKW_TEST_PAD_AUTOPRESS drives them unattended).
bool TestMotionEnabled()
{
    static const bool enabled = std::getenv("MKW_TEST_WHEEL_MOTION") != nullptr;
    return enabled;
}

void ApplyTestMotion(Sample& sample)
{
    using Clock = std::chrono::steady_clock;
    static const Clock::time_point start = Clock::now();
    static const TestVector rest = ParseTestVector("MKW_TEST_WHEEL_REST", {{0.7071f, -0.7071f, 0.0f}});
    static const TestVector tilt = ParseTestVector("MKW_TEST_WHEEL_TILT", {{0.0f, 0.0f, 1.0f}});
    static const TestVector jolt = ParseTestVector("MKW_TEST_WHEEL_JOLT", {{0.0f, 0.0f, 0.0f}});
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
    const float angle = 0.6f * std::sin(static_cast<float>(elapsed) * (6.2831853f / 8000.0f));
    const bool jolting = (elapsed % 4000) < 50;
    for (int i = 0; i < 3; ++i) {
        sample.acc[i] = rest.v[i] * std::cos(angle) + tilt.v[i] * std::sin(angle) + (jolting ? jolt.v[i] : 0.0f);
    }
    // TEMP-WHEEL phone probe: MKW_TEST_WHEEL_PHONE=<g> replays WiiWheelMode.kt (session mode from
    // MKW_TEST_WHEEL_SIMMODE, 1 or 2) on a phone held upright in landscape, sampled at 100 Hz:
    // every 4 s an up-down shake of <g> (60 ms up, 60 ms down), and in between a slow +-35 degree
    // steering tilt over 8 s (mode 1) or the same sweep on the stick (mode 2).
    static const char* phoneEnv = std::getenv("MKW_TEST_WHEEL_PHONE");
    if (phoneEnv != nullptr) {
        static const int simMode = std::getenv("MKW_TEST_WHEEL_SIMMODE") ? std::atoi(std::getenv("MKW_TEST_WHEEL_SIMMODE")) : 1;
        const float g = static_cast<float>(std::atof(phoneEnv));
        const long long held = (elapsed / 10) * 10;
        const float sweep = std::sin(static_cast<float>(held) * (6.2831853f / 8000.0f));
        const float tiltAngle = simMode == 1 ? 0.61f * sweep : 0.0f;
        const long long phase = held % 4000;
        const float pulse = phase < 60 ? g : (phase < 120 ? -g : 0.0f);
        const float reading[3] = {std::cos(tiltAngle) + pulse, 0.0f, -std::sin(tiltAngle)};
        static float gravity[3] = {0.0f, 0.0f, 0.0f};
        static long long lastHeld = -1;
        static float shake = 0.0f;
        if (held != lastHeld) {
            const float dt = lastHeld < 0 ? 0.0f : static_cast<float>(held - lastHeld) / 1000.0f;
            lastHeld = held;
            const float alpha = (dt <= 0.0f || dt > 0.5f) ? 1.0f : dt / (0.25f + dt);
            shake = 0.0f;
            for (int i = 0; i < 3; ++i) {
                gravity[i] += alpha * (reading[i] - gravity[i]);
                if (simMode == 2 || i != 2) {
                    shake = std::fmax(shake, std::fabs(reading[i] - gravity[i]));
                }
            }
        }
        const float boost = shake >= 0.25f ? shake * 2.5f : 0.0f;
        if (simMode == 2) {
            sample.acc[0] = 0.70710677f * (1.0f + boost);
            sample.acc[1] = -0.70710677f * (1.0f + boost);
            sample.acc[2] = 0.0f;
            std::lock_guard lock(g_sampleMutex);
            g_steer = sweep;
        } else {
            const float upAxis = gravity[0] + boost;
            sample.acc[0] = 0.70710677f * (upAxis + gravity[1]);
            sample.acc[1] = 0.70710677f * (gravity[1] - upAxis);
            sample.acc[2] = reading[2];
        }
        static int lastPulse = -1;
        if (lastPulse != static_cast<int>(elapsed / 4000)) {
            lastPulse = static_cast<int>(elapsed / 4000);
            std::fprintf(stderr, "[wheel-probe] t=%lld phone shake %.2f g\n", elapsed, g);
        }
        return;
    }
    // TEMP-WHEEL axis probe: MKW_TEST_WHEEL_AXES=<g> shakes +X,-X,+Y,-Y,+Z,-Z in turn, one every
    // 4 s, as a push of <g> for 60 ms then the pull back for 60 ms, held in 20 ms steps (50 Hz).
    static const char* axesEnv = std::getenv("MKW_TEST_WHEEL_AXES");
    if (axesEnv != nullptr) {
        const float g = static_cast<float>(std::atof(axesEnv));
        const long long phase = elapsed % 4000;
        const int pulse = static_cast<int>((elapsed / 4000) % 6);
        const long long held = (phase / 20) * 20;
        if (held < 120) {
            const float sign = ((pulse & 1) ? -1.0f : 1.0f) * (held < 60 ? 1.0f : -1.0f);
            sample.acc[pulse / 2] += sign * g;
            static int lastPulse = -1;
            if (lastPulse != static_cast<int>(elapsed / 4000)) {
                lastPulse = static_cast<int>(elapsed / 4000);
                std::fprintf(stderr, "[wheel-probe] t=%lld shake %c%c\n", elapsed, (pulse & 1) ? '-' : '+', "XYZ"[pulse / 2]);
            }
        }
    }
}
#endif

} // namespace

bool Active()
{
    std::call_once(g_settingOnce, LoadSetting);
    return g_active.load(std::memory_order_relaxed);
}

void SetActive(bool active)
{
    std::call_once(g_settingOnce, LoadSetting);
    g_active.store(active, std::memory_order_relaxed);
}

void PublishMotion(float x, float y, float z)
{
    std::lock_guard lock(g_sampleMutex);
    g_sample.acc[0] = x;
    g_sample.acc[1] = y;
    g_sample.acc[2] = z;
}

void TriggerTrickPulse()
{
    std::lock_guard lock(g_sampleMutex);
    g_trickPulseEnd = std::chrono::steady_clock::now() + kTrickPulse;
}

void PublishGameCubeButtons(uint16_t buttons, uint8_t triggerL, uint8_t triggerR, int8_t stickX, int8_t stickY,
                            bool driving)
{
    // Held sideways in a Wii Wheel, D-pad on the left: the player's up is the remote's Right,
    // right is Down, down is Left and left is Up. 2 accelerates and confirms, 1 brakes and goes
    // back, B drifts, the D-pad uses items, A looks behind, + pauses.
    const bool up = (buttons & kPadUp) != 0 || stickY >= kStickPressed;
    const bool down = (buttons & kPadDown) != 0 || stickY <= -kStickPressed;
    // While driving with stick steering, the stick steers the wheel (the D-pad tricks, below).
    // A connected controller always steers with its stick (see ControllerPlaysNormally).
    const bool stickMode = g_stickSteering.load(std::memory_order_relaxed) || ControllerPlaysNormally();
    const bool steerWithStick = driving && stickMode;
    float steer = 0.0f;
    if (steerWithStick) {
        steer = std::fmax(-1.0f, std::fmin(1.0f, static_cast<float>(stickX) / kStickFull));
    }
    // With tilt steering the stick takes over while it is pushed sideways, as plain tilt steering
    // does (pad.cpp): before, a stick or controller did nothing in a race in this mode.
    float stickOverride = 0.0f;
    if (driving && !stickMode && std::abs(stickX) >= kStickOverride) {
        stickOverride = std::fmax(-1.0f, std::fmin(1.0f, static_cast<float>(stickX) / kStickFull));
    }
    const bool left = (buttons & kPadLeft) != 0 || stickX <= -kStickPressed;
    const bool right = (buttons & kPadRight) != 0 || stickX >= kStickPressed;
    const bool item = (buttons & kPadTriggerL) != 0 || triggerL >= kAnalogTriggerPressed;
    const bool drift = (buttons & kPadTriggerR) != 0 || triggerR >= kAnalogTriggerPressed;
    // Any D-pad direction, as on a GameCube pad. Only the D-pad itself, not the stick: a thumb
    // resting on the stick would trick on every ramp.
    const bool trick = driving && (buttons & (kPadUp | kPadDown | kPadLeft | kPadRight)) != 0;

    uint32_t remote = 0;
    if (buttons & kPadA) remote |= kButton2;
    if (buttons & kPadB) remote |= kButton1;
    if (buttons & kPadStart) remote |= kButtonPlus;
    if (drift) remote |= kButtonB;
    if (driving) {
        // In a race every remote D-pad direction uses the item, so the stick and D-pad must not
        // press any (a thumb resting slightly up on the stick fired stars). Only the item button
        // does. The game throws at once on the player's up/down (remote Right/Left) and keeps the
        // item trailing while the player's left/right (remote Up/Down) is held (PC probe,
        // 2026-10-24: the race item bit lasted one frame on Right, the whole hold on Up). So L
        // held trails the item like L on a GameCube pad, released throws it; with the stick back
        // it is thrown backward at once.
        if (item) remote |= down ? kButtonLeft : kButtonUp;
        // X or Y looks behind (X on a GameCube pad).
        if ((buttons & (kPadX | kPadY)) != 0) remote |= kButtonA;
    } else {
        // In the menus (the pause menu too) the D-pad moves the cursor, and L does nothing there on a
        // GameCube pad.
        if (up) remote |= kButtonRight;
        if (down) remote |= kButtonLeft;
        if (left) remote |= kButtonUp;
        if (right) remote |= kButtonDown;
    }

    std::lock_guard lock(g_sampleMutex);
    g_sample.buttons = remote;
    g_steer = steer;
    g_stickOverride = stickOverride;
    if (trick && !g_trickHeld) {
        g_trickPulseEnd = std::chrono::steady_clock::now() + kTrickPulse;
    }
    g_trickHeld = trick;
}

Sample Read()
{
    Sample sample;
    bool trickPulse = false;
    {
        std::lock_guard lock(g_sampleMutex);
        sample = g_sample;
        trickPulse = std::chrono::steady_clock::now() < g_trickPulseEnd;
    }
#if defined(__ANDROID__)
    if (ControllerPlaysNormally()) {
        // A level wheel with no shakes: the phone may sit in a stand or a clip, and its motion
        // must not steer or trick. The stick steers it and the D-pad tricks, below.
        sample.acc[0] = kDiagonal;
        sample.acc[1] = -kDiagonal;
        sample.acc[2] = 0.0f;
    }
#endif
#if !defined(__SWITCH__) && !defined(__ANDROID__)
    if (TestMotionEnabled()) {
        ApplyTestMotion(sample);
    }
#endif
    if (trickPulse) {
        // Lift the wheel along its resting up axis, as a real shake does.
        sample.acc[0] += kDiagonal * kTrickPulseBoost;
        sample.acc[1] -= kDiagonal * kTrickPulseBoost;
    }
    float stickOverride = 0.0f;
    {
        std::lock_guard lock(g_sampleMutex);
        stickOverride = g_stickOverride;
    }
    if (stickOverride != 0.0f) {
        // Replace the phone's sideways tilt with the stick's, keeping its up/toward reading.
        sample.acc[2] = -std::sin(stickOverride * kStickSteerRadians);
    }
    if (g_stickSteering.load(std::memory_order_relaxed) || ControllerPlaysNormally()) {
        float steer = 0.0f;
        {
            std::lock_guard lock(g_sampleMutex);
            steer = g_steer;
        }
        // The phone sends the resting wheel plus its shakes; the stick tilts it (negative Z steers
        // right).
        const float angle = steer * kStickSteerRadians;
        sample.acc[0] *= std::cos(angle);
        sample.acc[1] *= std::cos(angle);
        sample.acc[2] -= std::sin(angle);
    }
    return sample;
}

} // namespace WiiRemoteInput
