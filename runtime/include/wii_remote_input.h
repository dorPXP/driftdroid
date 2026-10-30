#pragma once

#include <cstdint>

// Host-side state of the emulated Wii Remote on channel 0 ("Wii Wheel mode"). While it is active
// the WPAD/KPAD natives (hle/input/wpad.cpp, kpad.cpp) report a connected core remote fed from
// here and PAD__Read reports every GameCube port as empty, so the game's own Wii Wheel code does
// the steering, tricks and wheelies from the motion values.
//
// The mode is chosen at start-up from WiiCompiled/wii_wheel.cfg ("1" = tilt steering, "2" = stick
// steering with shake to trick only), which the Android app writes; the game picks its controller
// in the menus, so it is not switched mid-session.
namespace WiiRemoteInput {

// WPAD_BUTTON_* as the game sees them.
inline constexpr uint32_t kButtonLeft = 0x0001;
inline constexpr uint32_t kButtonRight = 0x0002;
inline constexpr uint32_t kButtonDown = 0x0004;
inline constexpr uint32_t kButtonUp = 0x0008;
inline constexpr uint32_t kButtonPlus = 0x0010;
inline constexpr uint32_t kButton2 = 0x0100;
inline constexpr uint32_t kButton1 = 0x0200;
inline constexpr uint32_t kButtonB = 0x0400;
inline constexpr uint32_t kButtonA = 0x0800;
inline constexpr uint32_t kButtonMinus = 0x1000;
inline constexpr uint32_t kButtonHome = 0x8000;

struct Sample {
    uint32_t buttons = 0;
    // In g, in the remote's own axes (+X left side, +Y pointer end, +Z button face).
    float acc[3] = {0.0f, 0.0f, 0.0f};
};

bool Active();
void SetActive(bool active);
// Motion from the phone's accelerometer (Android JNI), in remote axes.
void PublishMotion(float x, float y, float z);
// A shake detected by the phone: sends the same 100 ms trick pulse a D-pad press does, so the game
// sees it for several frames instead of a few milliseconds of raw sensor samples.
void TriggerTrickPulse();
// The player's buttons as the GameCube layout reports them (touch controls and controllers keep
// their usual mapping); translated to the sideways remote's buttons.
// driving: a race is under way and not paused. Then the stick and D-pad press no remote D-pad (it
// uses items in a race) and, in "shake to trick only" mode (wii_wheel.cfg "2"), left/right steer
// the wheel; otherwise they move the menu cursor.
void PublishGameCubeButtons(uint16_t buttons, uint8_t triggerL, uint8_t triggerR, int8_t stickX, int8_t stickY,
                            bool driving);
// One sample per call; a desktop test source (MKW_TEST_WHEEL) replaces the published one.
Sample Read();

} // namespace WiiRemoteInput
